package net.swlr.vpnmaster.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import net.swlr.vpnmaster.logging.AppLog as Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.swlr.vpnmaster.data.model.SplitTunnelMode
import net.swlr.vpnmaster.data.repository.SettingsRepository
import net.swlr.vpnmaster.vpn.VpnOrchestrator
import net.swlr.vpnmaster.vpn.VpnState
import java.util.concurrent.atomic.AtomicBoolean
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchdogManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: VpnOrchestrator,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "VpnWatchdog"
        private const val WORK_NAME = "vpn_watchdog"
        // If the latest handshake is older than this while the tunnel claims UP,
        // the tunnel is stalled. Handshakes normally happen every ~2 minutes with
        // traffic, and at most 3 min after keepalive fails.
        private const val MAX_HANDSHAKE_AGE_MS = 180_000L
        // Per-probe socket connect timeout. Short enough to keep the burst snappy,
        // long enough that a slow-but-alive cellular link still completes.
        private const val PROBE_TIMEOUT_MS = 2_000
        // Spacing between probe attempts in a burst.
        private const val PROBE_INTERVAL_MS = 2_000L
        // Outer ceiling on a single sendProbe call. sendProbe does two back-to-back
        // socket ops with PROBE_TIMEOUT_MS each, so a well-behaved call takes up to
        // ~2*PROBE_TIMEOUT_MS. The extra slack absorbs scheduling jitter. If the
        // call exceeds this, the underlying JNI socket op is misbehaving (kernel
        // didn't honor the timeout — happens on flaky cellular during interface
        // teardown or after a doze suspend) and the watchdog orphans it.
        private const val PROBE_HANG_CEILING_MS = 5_000L
        // After a probe-triggered reconnect, suppress further probe-triggered
        // reconnects for this long. Falls back to the slower handshake-age path
        // during the cooldown so a misbehaving probe target (or a genuinely down
        // server) can't drive a tight reconnect loop.
        private const val PROBE_RECONNECT_COOLDOWN_MS = 5 * 60_000L
        // After this many consecutive ticks where refreshStatistics() fails,
        // treat the stats path as broken: rx-stuck heuristics are useless, so
        // fall back to handshake age, and if that's also unreadable declare
        // the tunnel hung. Without this ceiling, a persistent native-bridge
        // failure would have isTrafficFlowing() return true forever and the
        // watchdog would silently stop monitoring. 5 ticks ≈ 2.5min at default.
        private const val STATS_FAIL_HANG_THRESHOLD = 5
    }

    private var watchdogJob: Job? = null
    // Scope for the in-memory tick loop. Created on start() and cancelled on
    // stop() so the watchdog has no long-lived process-scoped state when idle.
    private var scope: CoroutineScope? = null

    // Process-lifetime scope for the orchestrator-state observer. Distinct from
    // [scope] (which holds the per-session tick loop) so calling stop() never
    // tears down the observer itself — the observer is what re-arms the
    // watchdog the next time the orchestrator transitions back to CONNECTED.
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initialized = AtomicBoolean(false)

    // Set false while no non-VPN network has validated internet access.
    // Reconnect attempts during an outage are futile and waste battery.
    //
    // Sourced from a process-scoped NetworkCallback owned by this class (see
    // registerInternetObserver), NOT from VpnMasterService — having the service
    // be the source of truth meant the watchdog's view went stale whenever the
    // service was killed during an outage, and stayed stale until the periodic
    // backstop revived it ~15min later. Defaulting to true on cold start is
    // intentional: the first capability event from the callback corrects it,
    // and starting suppressed-by-default would block the very first reconnect
    // attempt on a process restart.
    @Volatile private var hasInternet: Boolean = true
    // Per-network VALIDATED state. hasInternet derives from "any value true".
    // ConcurrentHashMap because callbacks fire on an arbitrary binder thread
    // and can interleave with the watchdog tick reading hasInternet.
    private val internetByNetwork = ConcurrentHashMap<Network, Boolean>()
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Traffic-liveness tracking
    private var lastRxBytes: Long = -1
    private var lastRxChangeAt: Long = 0
    // Counts back-to-back refreshStatistics() failures. Reset on any successful
    // read and on start(). When it crosses STATS_FAIL_HANG_THRESHOLD the tick
    // stops trusting the rx-stuck heuristic and forces a handshake-age check.
    private var consecutiveStatsFailures: Int = 0
    // Wall-clock of the last probe-driven reconnect, for cooldown. Deliberately
    // not reset by start() — the goal is to throttle decisions made by *this*
    // watchdog instance across reconnect cycles, not per-tunnel-session.
    @Volatile private var lastProbeReconnectAt: Long = 0

    /**
     * Begin observing orchestrator state. Idempotent — safe to call from
     * Application.onCreate without guarding against duplicate observers.
     *
     * Watchdog liveness is tied to orchestrator state from this point forward,
     * not to VpnMasterService lifecycle. That decouples it from the service
     * destroy/recreate cycles that happen during a watchdog-driven reconnect:
     * previously, the second onDestroy in a setState UP→DOWN cycle would call
     * stop() and the periodic backstop would also be cancelled, so when the
     * orchestrator's reconnect loop later succeeded with no service running,
     * nothing rearmed the watchdog. Now the observer drives start() on every
     * fresh CONNECTED transition regardless of whether a service is alive.
     */
    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        Log.i(TAG, "Observer initialized — watchdog liveness now tied to orchestrator state")
        registerInternetObserver()
        observerScope.launch {
            var prevState: VpnState? = null
            orchestrator.state.collect { state ->
                when (state) {
                    VpnState.CONNECTED -> {
                        if (prevState != VpnState.CONNECTED) {
                            val enabled = settingsRepository.watchdogEnabled.first()
                            if (enabled) {
                                val interval = settingsRepository.watchdogIntervalSeconds.first()
                                val probeMax = settingsRepository.watchdogProbeMaxFailures.first()
                                Log.i(TAG, "State CONNECTED (from $prevState) — starting watchdog (interval=${interval}s, probeMax=$probeMax)")
                                start(interval.toLong(), probeMax)
                            } else {
                                Log.i(TAG, "State CONNECTED (from $prevState) but watchdog disabled in settings — not starting")
                            }
                        }
                    }
                    VpnState.DISCONNECTED -> {
                        // Skip on cold-boot initial emission (prevState == null) so
                        // any periodic backstop scheduled by a previous app-process
                        // session survives — that's the recovery path for "process
                        // died while VPN was up". Only treat a real transition out
                        // of an active state as a user-initiated disconnect.
                        if (prevState != null && prevState != VpnState.DISCONNECTED) {
                            Log.i(TAG, "State DISCONNECTED (from $prevState) — stopping watchdog")
                            stop()
                        }
                    }
                    else -> {
                        // CONNECTING / RECONNECTING / DISCONNECTING / ERROR — keep
                        // the watchdog in its current state. In particular, don't
                        // stop on RECONNECTING; the in-memory loop's checkAndReconnect
                        // already gates on isReconnecting and we want it ticking
                        // again the moment the cycle finishes.
                    }
                }
                prevState = state
            }
        }

        // React to live setting changes while CONNECTED: starting/stopping or
        // restarting the in-memory loop with the new values. Without this,
        // toggling watchdog off or moving the interval slider has no effect
        // until the next disconnect/reconnect — confusing, and disables the
        // ostensible purpose of the toggle. drop(1) skips the initial
        // emission of each flow on subscribe; the state observer above is
        // already responsible for the initial start.
        observerScope.launch {
            combine(
                settingsRepository.watchdogEnabled,
                settingsRepository.watchdogIntervalSeconds,
                settingsRepository.watchdogProbeMaxFailures
            ) { enabled, interval, probeMax -> Triple(enabled, interval, probeMax) }
                .drop(1)
                .collect { (enabled, interval, probeMax) ->
                    if (orchestrator.state.value != VpnState.CONNECTED) return@collect
                    if (enabled) {
                        Log.i(TAG, "Settings changed while CONNECTED — restarting (interval=${interval}s, probeMax=$probeMax)")
                        start(interval.toLong(), probeMax)
                    } else {
                        Log.i(TAG, "Watchdog disabled while CONNECTED — stopping")
                        stop()
                    }
                }
        }
    }

    fun start(intervalSeconds: Long, probeMaxFailures: Int) {
        // Cancel only the in-memory tick loop; deliberately do NOT cancel the
        // periodic backstop here. WorkManager's cancel is asynchronous, so a
        // cancel-then-enqueue(KEEP) sequence can race: the KEEP enqueue may
        // observe the about-to-be-cancelled work and skip, leaving us with no
        // periodic backstop at all. Keeping start() additive — only stop()
        // cancels periodic work — eliminates the window.
        scope?.cancel()
        scope = null
        watchdogJob = null

        lastRxBytes = -1
        lastRxChangeAt = System.currentTimeMillis()
        consecutiveStatsFailures = 0

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        watchdogJob = s.launch {
            while (isActive) {
                delay(intervalSeconds * 1000)
                checkAndReconnect(intervalSeconds, probeMaxFailures)
            }
        }

        schedulePeriodicWork()
        // Fast process-death recovery. The periodic work above can't fire below a
        // 15-min floor, so an OEM/low-memory kill can leave the tunnel down for up
        // to ~14min. A self-rescheduling alarm (also process-death-surviving) closes
        // that to a couple of minutes. Idempotent like schedulePeriodicWork(): a
        // re-arm just replaces the pending alarm.
        HeartbeatReceiver.schedule(context)
    }

    fun stop() {
        scope?.cancel()
        scope = null
        watchdogJob = null
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        HeartbeatReceiver.cancel(context)
    }

    /**
     * Registers a process-scoped callback for non-VPN networks with INTERNET
     * capability and tracks each one's VALIDATED bit. [hasInternet] is true if
     * any tracked network is currently validated.
     *
     * From an Application context (not a VpnService), `registerDefaultNetworkCallback`
     * returns the *VPN-routed* default while a VPN is up — useless here, since
     * that always reports VALIDATED. A NetworkRequest with the default
     * NOT_VPN capability gives us the underlying physical networks instead;
     * tracking per-network in a map handles the dual-stack (wifi + cell)
     * case where multiple non-VPN networks are available simultaneously.
     */
    private fun registerInternetObserver() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val prior = internetByNetwork.put(network, validated)
                if (prior != validated) recomputeHasInternet("$network ${if (validated) "validated" else "unvalidated"}")
            }
            override fun onLost(network: Network) {
                if (internetByNetwork.remove(network) != null) recomputeHasInternet("$network lost")
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.i(TAG, "Registered process-scoped internet observer")
        } catch (e: Exception) {
            // Callback registration can fail in low-resource conditions or on
            // restricted devices. Leave hasInternet at its default (true) so
            // we don't suppress reconnects forever on a missing observer.
            Log.w(TAG, "Failed to register internet observer: ${e.message} — defaulting hasInternet=true")
        }
    }

    private fun recomputeHasInternet(reason: String) {
        val anyValidated = internetByNetwork.values.any { it }
        if (hasInternet != anyValidated) {
            hasInternet = anyValidated
            Log.i(TAG, "hasInternet=$anyValidated ($reason; tracked networks=${internetByNetwork.size})")
        }
    }

    private suspend fun checkAndReconnect(intervalSeconds: Long, probeMaxFailures: Int) {
        // Don't stomp on an in-flight reconnect schedule — that restarts
        // backoff from 1s and converts exponential delay into rapid retries.
        if (orchestrator.isReconnecting) return

        // Note: the !hasInternet check is intentionally NOT a top-level early
        // return. We still evaluate rx-stuck and handshake-age so logs show
        // the watchdog is alive during an outage — particularly the case
        // where Android marks the underlying wifi as unvalidated (captive-
        // portal-style AP with no real upstream) but the user expects the
        // watchdog to be visibly watching. We do skip the active probe burst
        // (see isTrafficFlowing) and the reconnect call itself; both are
        // wasted work when the device says there's no upstream. The internet
        // observer flips hasInternet back to true the moment a non-VPN
        // network reports VALIDATED and the next tick will reconnect on its
        // own.

        val state = orchestrator.state.value
        if (state == VpnState.ERROR) {
            if (hasInternet) {
                Log.w(TAG, "VPN in error state, attempting reconnect")
                orchestrator.reconnect()
            } else {
                Log.w(TAG, "VPN in error state but no validated internet — holding off reconnect")
            }
            return
        }
        if (state != VpnState.CONNECTED) return

        val healthy = orchestrator.checkHealth()
        if (!healthy) {
            if (hasInternet) {
                Log.w(TAG, "Backend state unhealthy, reconnecting")
                orchestrator.reconnect()
            } else {
                Log.w(TAG, "Backend state unhealthy but no validated internet — holding off reconnect")
            }
            return
        }

        if (!isTrafficFlowing(intervalSeconds, probeMaxFailures)) {
            if (hasInternet) {
                Log.w(TAG, "Tunnel appears hung, reconnecting")
                lastProbeReconnectAt = System.currentTimeMillis()
                orchestrator.reconnect()
            } else {
                // Don't update lastProbeReconnectAt — cooldown only throttles
                // *actual* reconnects, so probes can resume immediately once
                // internet returns rather than sitting in a stale cooldown.
                Log.w(TAG, "Tunnel appears hung but no validated internet — holding off reconnect")
            }
        }
    }

    /**
     * Returns true if the tunnel looks alive. Layered detection:
     *   1. rx advancing → alive
     *   2. rx stuck < grace → assume alive (idle tunnels legitimately see no rx)
     *   3. rx stuck ≥ grace + probe enabled + outside cooldown → run active
     *      probe burst; result is decisive
     *   4. fallback: handshake age (slow but probe-target-independent)
     *
     * The cooldown in (3) is what prevents an infinite reconnect loop: if a
     * probe-driven reconnect already happened within PROBE_RECONNECT_COOLDOWN_MS
     * we skip step 3 and let the conservative handshake-age check decide. So the
     * tightest possible reconnect cadence under sustained probe failure is
     * cooldown + handshake_age = ~5min + ~3min, not "every 30s".
     */
    private suspend fun isTrafficFlowing(intervalSeconds: Long, probeMaxFailures: Int): Boolean {
        // If the stats read itself failed, the cached rx is stale — it would look
        // "stuck" even if traffic is actually flowing. Treat the first few
        // failures as transient (alive) and wait for stats to recover. Past
        // STATS_FAIL_HANG_THRESHOLD the stats path is presumed broken: skip
        // the rx-stuck heuristic entirely and decide on handshake age, which
        // is read via a separate path. If THAT also returns nothing, we have
        // no signal at all and the safe move is to declare hung — better a
        // noisy reconnect than silent monitoring death.
        if (!orchestrator.refreshStatistics()) {
            consecutiveStatsFailures++
            if (consecutiveStatsFailures < STATS_FAIL_HANG_THRESHOLD) return true
            val handshake = orchestrator.latestHandshakeMillis()
            val handshakeAge = if (handshake > 0) System.currentTimeMillis() - handshake else Long.MAX_VALUE
            if (handshakeAge < MAX_HANDSHAKE_AGE_MS) {
                Log.w(TAG, "Stats unreadable for $consecutiveStatsFailures ticks; handshake age ${handshakeAge / 1000}s — assuming alive")
                return true
            }
            Log.w(TAG, "Stats unreadable for $consecutiveStatsFailures ticks AND handshake stale/missing — declaring tunnel hung")
            return false
        }
        consecutiveStatsFailures = 0
        val rx = orchestrator.statistics.value.bytesReceived
        val now = System.currentTimeMillis()

        if (rx != lastRxBytes) {
            lastRxBytes = rx
            lastRxChangeAt = now
            return true
        }

        // rx stuck. With active probing enabled we can declare a hang sooner
        // (~one interval), since the probe burst distinguishes "idle alive"
        // from "silently dead". Without probing, keep the conservative grace.
        val probeEnabled = probeMaxFailures > 0
        val rxStuckFor = now - lastRxChangeAt
        val rxStuckGrace = if (probeEnabled) {
            maxOf(intervalSeconds * 1000, 30_000L)
        } else {
            maxOf(intervalSeconds * 3 * 1000, 90_000L)
        }
        if (rxStuckFor < rxStuckGrace) return true

        // Probe phase. Skip during cooldown so a misbehaving probe target or a
        // genuinely down server can't drive tight reconnect cycles. Skip when
        // !hasInternet — the device already says there's no upstream, so probes
        // can't produce a meaningful rx delta and would just burn battery on
        // every tick of a sustained outage. Skip when this app is split-tunneled
        // out of the VPN — probes from this process would route via the
        // physical interface, never reach the peer, and produce a guaranteed
        // false-positive "tunnel hung" verdict (see appRoutedThroughVpn()).
        val routedThroughVpn = appRoutedThroughVpn()
        if (probeEnabled && hasInternet && routedThroughVpn && (now - lastProbeReconnectAt) > PROBE_RECONNECT_COOLDOWN_MS) {
            val target = derivePeerTunnelIp()
            if (target != null) {
                Log.i(TAG, "Probe burst start: rx stuck ${rxStuckFor / 1000}s, target=${target.hostAddress}, max=$probeMaxFailures")
                val alive = runProbeBurst(target, probeMaxFailures)
                if (alive) return true
                Log.w(TAG, "Probe burst exhausted: $probeMaxFailures attempts, no rx — declaring tunnel hung")
                return false
            }
            Log.i(TAG, "rx stuck ${rxStuckFor / 1000}s but no probe target derivable; using handshake-age fallback")
        } else if (probeEnabled && !hasInternet) {
            Log.i(TAG, "rx stuck ${rxStuckFor / 1000}s but no validated internet; skipping probe burst, using handshake-age fallback")
        } else if (probeEnabled && !routedThroughVpn) {
            Log.i(TAG, "rx stuck ${rxStuckFor / 1000}s but app is split-tunneled out of VPN; skipping probe burst, using handshake-age fallback")
        } else if (probeEnabled) {
            val cooldownLeft = (PROBE_RECONNECT_COOLDOWN_MS - (now - lastProbeReconnectAt)) / 1000
            Log.i(TAG, "rx stuck ${rxStuckFor / 1000}s; probe in cooldown ${cooldownLeft}s, using handshake-age fallback")
        }

        // Handshake-age fallback. A recent handshake means the tunnel is alive
        // (keepalive is working) even if no user traffic is flowing.
        val handshake = orchestrator.latestHandshakeMillis()
        val handshakeAge = if (handshake > 0) now - handshake else Long.MAX_VALUE
        return handshakeAge < MAX_HANDSHAKE_AGE_MS
    }

    /**
     * Sends up to [maxFailures] probes to [target] with PROBE_INTERVAL_MS between
     * attempts, watching the WireGuard rx counter for any movement. Returns true
     * (alive) as soon as rx advances; returns false (dead) only after every
     * attempt has elapsed with rx still flat.
     *
     * The probe target should be reachable only through the tunnel — that way a
     * response (SYN-ACK, RST, ICMP echo reply, anything) round-trips through
     * WireGuard and bumps the encrypted rx counter. We don't care whether the
     * probe technically "succeeded" at the application layer; rx delta is the
     * only signal that matters.
     *
     * Aborts early on any state transition out of CONNECTED so we don't fight
     * an in-flight disconnect or reconnect.
     */
    private suspend fun runProbeBurst(target: InetAddress, maxFailures: Int): Boolean {
        for (attempt in 1..maxFailures) {
            if (orchestrator.state.value != VpnState.CONNECTED) {
                Log.i(TAG, "Probe burst aborted at attempt $attempt: state changed mid-burst")
                return true
            }
            if (orchestrator.isReconnecting) {
                Log.i(TAG, "Probe burst aborted at attempt $attempt: reconnect in flight")
                return true
            }
            // No mid-burst !hasInternet abort. The caller already gates burst
            // start on hasInternet, but it can flip false during the ~Ns burst
            // window; bailing then would mask a genuinely dead tunnel as
            // "alive". Let the burst finish and let the caller's hasInternet
            // check decide whether to reconnect on the result.

            val rxBefore = orchestrator.statistics.value.bytesReceived
            sendProbeBounded(target)
            // Wait for any response to round-trip and update the counter.
            delay(PROBE_INTERVAL_MS)

            if (orchestrator.refreshStatistics()) {
                val rxAfter = orchestrator.statistics.value.bytesReceived
                val delta = rxAfter - rxBefore
                if (delta > 0) {
                    lastRxBytes = rxAfter
                    lastRxChangeAt = System.currentTimeMillis()
                    Log.i(TAG, "Probe attempt $attempt/$maxFailures: alive (rx +$delta bytes)")
                    return true
                }
                Log.i(TAG, "Probe attempt $attempt/$maxFailures: no rx delta")
            } else {
                Log.i(TAG, "Probe attempt $attempt/$maxFailures: stats read failed (treating as no delta)")
            }
        }
        return false
    }

    private suspend fun sendProbe(target: InetAddress) = withContext(Dispatchers.IO) {
        // Two cheap stimuli: ICMP echo (often filtered, but free when it works)
        // and a TCP SYN. A SYN to a closed port still elicits a RST that gets
        // encrypted by the peer and shows up as rx — exactly what we want.
        try { target.isReachable(PROBE_TIMEOUT_MS) } catch (_: Exception) {}
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(target, 80), PROBE_TIMEOUT_MS)
            }
        } catch (_: Exception) {
            // Connection failures (RST, timeout, unreachable) are expected and fine.
        }
    }

    /**
     * Runs [sendProbe] with a hard ceiling on how long the watchdog tick will
     * wait. The underlying isReachable / Socket.connect calls in [sendProbe]
     * each take a timeout parameter, but the Android kernel doesn't always
     * honor it on flaky cellular — radio interface teardown, cell handoff, or
     * a doze suspend mid-connect can leave the syscall blocked well past its
     * nominal timeout. When that happens the tick coroutine (single-threaded)
     * is pinned, no further ticks fire, and the watchdog goes silent. The
     * symptom in logs is "Probe burst start" with no subsequent attempt line.
     *
     * Fix: launch the probe as a sibling job on the watchdog scope, race it
     * against a coroutine-level timeout, and if the timer wins, cancel the
     * job and continue. Coroutine cancellation is cooperative — the stuck JNI
     * call will still hold the IO thread until the kernel finally returns —
     * but the tick coroutine itself moves on. The orphaned job completes on
     * its own and is GC'd; Dispatchers.IO has a generous thread pool, so one
     * parked thread per hang is tolerable.
     */
    private suspend fun sendProbeBounded(target: InetAddress) {
        val scopeRef = scope ?: return
        val probeJob = scopeRef.launch(Dispatchers.IO) { sendProbe(target) }
        val finished = withTimeoutOrNull(PROBE_HANG_CEILING_MS) { probeJob.join() }
        if (finished == null) {
            probeJob.cancel()
            Log.w(TAG, "Probe to ${target.hostAddress} exceeded ${PROBE_HANG_CEILING_MS}ms — orphaning IO and treating as no rx (kernel likely didn't honor socket timeout)")
        }
    }

    /**
     * True when sockets opened from this app's process traverse the VPN
     * tunnel — i.e., this package is in the VPN's routing scope per the
     * profile's split-tunnel config. The watchdog's probe sockets only
     * generate rx-on-tunnel deltas when this is true. False forces the
     * caller to fall back to handshake-age detection because probes would
     * silently bypass the tunnel, never reach the peer, and produce a
     * guaranteed false "tunnel hung" verdict.
     *
     * Returns true conservatively if there's no active profile (we'd
     * already have bigger problems if probing fires without one).
     */
    private fun appRoutedThroughVpn(): Boolean {
        val split = orchestrator.activeProfile.value?.splitTunnelConfig ?: return true
        val pkg = context.packageName
        return when (split.mode) {
            SplitTunnelMode.DISABLED -> true
            SplitTunnelMode.EXCLUDE_APPS -> pkg !in split.appPackages
            SplitTunnelMode.INCLUDE_APPS -> pkg in split.appPackages
        }
    }

    /**
     * Resolve the active profile's probe target. Prefers an explicit
     * `probeTarget` configured on the profile so peers that use a non-.1
     * gateway (e.g. .254 setups, /31 point-to-point links) can opt out of the
     * convention-based derivation. Falls back to deriving the gateway as the
     * .1 of the first IPv4 interface address, which matches the common
     * WireGuard client convention. Returns null when nothing usable is
     * available; the caller must fall back to the handshake-age check rather
     * than guessing.
     */
    private fun derivePeerTunnelIp(): InetAddress? {
        val wg = orchestrator.activeProfile.value?.wireGuardConfig ?: return null

        wg.probeTarget?.trim()?.takeIf { it.isNotEmpty() }?.let { override ->
            if (android.net.InetAddresses.isNumericAddress(override)) {
                try {
                    return InetAddress.getByName(override)
                } catch (e: Exception) {
                    Log.w(TAG, "Configured probeTarget '$override' failed to parse: ${e.message} — falling back to derivation")
                }
            } else {
                Log.w(TAG, "Configured probeTarget '$override' is not a numeric IP — falling back to derivation")
            }
        }

        val addresses = wg.addresses
        for (raw in addresses) {
            val parts = raw.trim().split("/")
            if (parts.size != 2) continue
            val literal = parts[0]
            val prefix = parts[1].toIntOrNull() ?: continue
            if (!android.net.InetAddresses.isNumericAddress(literal)) continue
            val ip = try { InetAddress.getByName(literal) } catch (_: Exception) { continue }
            if (ip !is Inet4Address) continue
            // /32 has no network range to derive a gateway from.
            if (prefix !in 0..31) continue

            val addrBytes = ip.address
            val netBytes = ByteArray(4)
            for (i in 0 until 4) {
                val bitsInByte = (prefix - i * 8).coerceIn(0, 8)
                val mask = ((0xFF shl (8 - bitsInByte)) and 0xFF).toByte()
                netBytes[i] = (addrBytes[i].toInt() and mask.toInt()).toByte()
            }
            // gateway = network base + 1
            var carry = 1
            for (i in 3 downTo 0) {
                val v = (netBytes[i].toInt() and 0xFF) + carry
                netBytes[i] = (v and 0xFF).toByte()
                carry = v ushr 8
                if (carry == 0) break
            }
            return try { InetAddress.getByAddress(netBytes) } catch (_: Exception) { null }
        }
        return null
    }

    private fun schedulePeriodicWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<WatchdogWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}

@androidx.hilt.work.HiltWorker
class WatchdogWorker @dagger.assisted.AssistedInject constructor(
    @dagger.assisted.Assisted appContext: Context,
    @dagger.assisted.Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val orchestrator: VpnOrchestrator
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val currentState = orchestrator.state.value
        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            // Re-post the foreground status notification. Some OEM launchers or
            // an accidental swipe on Android versions that allow dismissing FGS
            // notifications can leave the slot empty while the service is still
            // alive; calling notify() with the same id restores it without
            // disturbing the FGS state.
            if (currentState == VpnState.CONNECTED) {
                repostConnectedNotification()
            }
            Log.d("WatchdogWorker", "VPN already connected, nothing to do")
            return Result.success()
        }

        // Read activeProfileId, not lastConnectedProfileId. activeProfileId is cleared
        // on user-initiated disconnect; lastConnectedProfileId persists. Using the
        // latter would have us trying to restart the service after the user explicitly
        // turned the VPN off (the service's null-intent branch would then refuse, so
        // it's defense-in-depth, but we shouldn't be doing the wasted work in the
        // first place).
        val activeProfileId = settingsRepository.activeProfileId.first()
        if (activeProfileId == null) {
            Log.d("WatchdogWorker", "No active profile (user disconnected), nothing to do")
            return Result.success()
        }

        Log.i("WatchdogWorker", "VPN not connected but should be, restarting service")
        val intent = android.content.Intent(applicationContext, VpnMasterService::class.java)
        try {
            applicationContext.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e("WatchdogWorker", "Failed to restart VPN service", e)
            return Result.retry()
        }
        return Result.success()
    }

    private fun repostConnectedNotification() {
        val ctx = applicationContext
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)

        // Skip only when the slot already holds the Connected variant. Checking
        // the id alone isn't enough: the post-service Disconnected notification
        // shares NOTIFICATION_ID, so a stale Disconnected would otherwise look
        // "already posted" and we'd skip the repair the user actually needs.
        // Re-issuing notify() with the same id is otherwise harmless, but it
        // can re-trigger heads-up presentation on some OEMs and resets the
        // user's swipe-collapse state on others — both visible to the user
        // even though we're "just refreshing".
        val existing = nm?.activeNotifications?.firstOrNull { it.id == VpnMasterService.NOTIFICATION_ID }
        val existingKind = existing?.notification?.extras?.getString(VpnMasterService.EXTRA_NOTIFICATION_KIND)
        if (existingKind == VpnMasterService.KIND_CONNECTED) return

        val profileName = orchestrator.activeProfile.value?.name ?: ""
        val text = ctx.getString(net.swlr.vpnmaster.R.string.notification_connected, profileName)

        val contentIntent = android.app.PendingIntent.getActivity(
            ctx, 0,
            android.content.Intent(ctx, net.swlr.vpnmaster.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = android.app.PendingIntent.getService(
            ctx, 1,
            android.content.Intent(ctx, VpnMasterService::class.java).apply {
                action = VpnMasterService.ACTION_DISCONNECT
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(ctx, VpnMasterService.CHANNEL_ID)
            .setContentTitle(ctx.getString(net.swlr.vpnmaster.R.string.app_name))
            .setContentText(text)
            .setSmallIcon(net.swlr.vpnmaster.R.drawable.ic_vpn_key)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .addExtras(android.os.Bundle().apply {
                putString(VpnMasterService.EXTRA_NOTIFICATION_KIND, VpnMasterService.KIND_CONNECTED)
            })
            .addAction(
                0,
                ctx.getString(net.swlr.vpnmaster.R.string.notification_action_disconnect),
                disconnectIntent
            )
            .build()

        try {
            nm?.notify(VpnMasterService.NOTIFICATION_ID, notification)
            val replaced = when {
                existing == null -> "slot was empty"
                existingKind == null -> "displaced untagged notification"
                else -> "displaced kind=$existingKind"
            }
            Log.i("WatchdogWorker", "Re-posted connected status notification ($replaced)")
        } catch (e: Exception) {
            Log.w("WatchdogWorker", "Failed to re-post connected notification: ${e.message}")
        }
    }
}
