package net.swlr.vpnmaster.service

import android.content.Context
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.swlr.vpnmaster.data.repository.SettingsRepository
import net.swlr.vpnmaster.vpn.VpnOrchestrator
import net.swlr.vpnmaster.vpn.VpnState
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchdogManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: VpnOrchestrator
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
        // After a probe-triggered reconnect, suppress further probe-triggered
        // reconnects for this long. Falls back to the slower handshake-age path
        // during the cooldown so a misbehaving probe target (or a genuinely down
        // server) can't drive a tight reconnect loop.
        private const val PROBE_RECONNECT_COOLDOWN_MS = 5 * 60_000L
    }

    private var watchdogJob: Job? = null
    // Scope is created on start() and cancelled on stop() so the watchdog has no
    // long-lived process-scoped state when idle. Previously a single Singleton
    // scope held a SupervisorJob for the lifetime of the process — never a leak,
    // but unnecessary state.
    private var scope: CoroutineScope? = null

    // Set false while the underlying network has no validated internet access.
    // Reconnect attempts during an outage are futile and waste battery; when
    // internet returns VpnMasterService.onInternetRestored() triggers recovery.
    @Volatile private var hasInternet: Boolean = true

    // Traffic-liveness tracking
    private var lastRxBytes: Long = -1
    private var lastRxChangeAt: Long = 0
    // Wall-clock of the last probe-driven reconnect, for cooldown. Persists
    // across notifyConnected() resets — the goal is to throttle decisions made
    // by *this* watchdog instance, not per-tunnel-session.
    @Volatile private var lastProbeReconnectAt: Long = 0

    fun start(intervalSeconds: Long, probeMaxFailures: Int) {
        stop()

        lastRxBytes = -1
        lastRxChangeAt = System.currentTimeMillis()

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        watchdogJob = s.launch {
            while (isActive) {
                delay(intervalSeconds * 1000)
                checkAndReconnect(intervalSeconds, probeMaxFailures)
            }
        }

        schedulePeriodicWork()
    }

    fun stop() {
        scope?.cancel()
        scope = null
        watchdogJob = null
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun setInternetAvailable(available: Boolean) {
        hasInternet = available
    }

    /**
     * Reset the traffic-liveness counters. Must be called on every transition
     * into CONNECTED (including reconnects), otherwise stale rx/handshake
     * timestamps carry over and instantly re-trip the hang detector — the
     * main cause of flap cycles in poor-signal areas.
     */
    fun notifyConnected() {
        lastRxBytes = -1
        lastRxChangeAt = System.currentTimeMillis()
    }

    private suspend fun checkAndReconnect(intervalSeconds: Long, probeMaxFailures: Int) {
        // Don't stomp on an in-flight reconnect schedule — that restarts
        // backoff from 1s and converts exponential delay into rapid retries.
        if (orchestrator.isReconnecting) return
        // Skip while offline — reconnect attempts fail immediately with
        // BackendException and add noise. onInternetRestored() triggers recovery.
        if (!hasInternet) return

        val state = orchestrator.state.value
        if (state == VpnState.ERROR) {
            Log.w(TAG, "VPN in error state, attempting reconnect")
            orchestrator.reconnect()
            return
        }
        if (state != VpnState.CONNECTED) return

        val healthy = orchestrator.checkHealth()
        if (!healthy) {
            Log.w(TAG, "Backend state unhealthy, reconnecting")
            orchestrator.reconnect()
            return
        }

        if (!isTrafficFlowing(intervalSeconds, probeMaxFailures)) {
            Log.w(TAG, "Tunnel appears hung, reconnecting")
            lastProbeReconnectAt = System.currentTimeMillis()
            orchestrator.reconnect()
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
        // "stuck" even if traffic is actually flowing. Treat that as inconclusive
        // (alive) and wait for the next tick. The handshake-age fallback will
        // catch genuine hangs on subsequent checks once stats recover.
        if (!orchestrator.refreshStatistics()) return true
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
        // genuinely down server can't drive tight reconnect cycles.
        if (probeEnabled && (now - lastProbeReconnectAt) > PROBE_RECONNECT_COOLDOWN_MS) {
            val target = derivePeerTunnelIp()
            if (target != null) {
                Log.i(TAG, "Probe burst start: rx stuck ${rxStuckFor / 1000}s, target=${target.hostAddress}, max=$probeMaxFailures")
                val alive = runProbeBurst(target, probeMaxFailures)
                if (alive) return true
                Log.w(TAG, "Probe burst exhausted: $probeMaxFailures attempts, no rx — declaring tunnel hung")
                return false
            }
            Log.i(TAG, "rx stuck ${rxStuckFor / 1000}s but no probe target derivable; using handshake-age fallback")
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
            if (!hasInternet) {
                Log.i(TAG, "Probe burst aborted at attempt $attempt: internet lost mid-burst")
                return true
            }

            val rxBefore = orchestrator.statistics.value.bytesReceived
            sendProbe(target)
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
     * Derive the peer's tunnel-side IP (gateway) from the active profile's first
     * IPv4 interface address. Convention: peer is the .1 of the user's subnet.
     * Returns null when not derivable (no IPv4 address, /32 prefix, etc.) — the
     * caller must fall back to the handshake-age check rather than guessing.
     */
    private fun derivePeerTunnelIp(): InetAddress? {
        val addresses = orchestrator.activeProfile.value?.wireGuardConfig?.addresses ?: return null
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
}
