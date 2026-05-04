package net.swlr.vpnmaster.vpn

import net.swlr.vpnmaster.BuildConfig
import net.swlr.vpnmaster.logging.AppLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.data.model.VpnType
import net.swlr.vpnmaster.data.repository.SettingsRepository
import net.swlr.vpnmaster.vpn.wireguard.WireGuardBackend
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnOrchestrator @Inject constructor(
    private val wireGuardBackend: WireGuardBackend,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "VpnOrchestrator"
        // Backoff schedule in seconds. Caps at 180s (3 min) and stays there.
        private val BACKOFF_SCHEDULE_SEC = longArrayOf(1, 2, 5, 15, 30, 60, 120, 180)
        // Reset backoff only if the previous tunnel stayed up this long.
        // Shorter = flapping thrashes. Longer = legitimate reconnects pay old penalty.
        private const val BACKOFF_RESET_WINDOW_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        // If a new reconnect fires within this window after the prior tunnel
        // reached CONNECTED, treat it as anomalous churn — the kind of pattern
        // that would surface a regression in GoBackend.setState(UP)'s internal
        // teardown of an existing tunnel (stale native state left behind).
        // Threshold high enough to not fire on legitimate single hand-off
        // events (network change, brief hang) yet low enough to flag the
        // pathological back-to-back UP-over-UP that would indicate a leak.
        private const val RECONNECT_CHURN_WINDOW_MS = 20_000L
        // Retries for an explicit user-initiated connect (UI, Tasker, boot,
        // null-intent service restart). The watchdog and network-change
        // listener are only armed after the tunnel reaches CONNECTED at least
        // once, so without these retries a single handshake glitch on the
        // first attempt drops to ERROR with no recovery path. Kept distinct
        // from reconnect()'s backoff schedule — that one is for stale-tunnel
        // self-healing and runs forever; this one is bounded so a genuinely
        // unreachable server fails the user's request in a reasonable time.
        private const val INITIAL_CONNECT_MAX_ATTEMPTS = 4
        private const val INITIAL_CONNECT_RETRY_DELAY_MS = 3_000L
    }

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _statistics = MutableStateFlow(VpnStatistics())
    val statistics: StateFlow<VpnStatistics> = _statistics.asStateFlow()

    private val _activeProfile = MutableStateFlow<VpnProfile?>(null)
    val activeProfile: StateFlow<VpnProfile?> = _activeProfile.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // One-shot alert events for user-visible notifications. SharedFlow with no
    // replay so a late observer (service recreated) doesn't re-fire a stale
    // alert. extraBufferCapacity is generous so tryEmit() never drops; we emit
    // infrequently (only on terminal failures), but the buffer covers the
    // pathological case where the service is mid-restart when an alert fires.
    private val _alerts = MutableSharedFlow<VpnAlert>(extraBufferCapacity = 8)
    val alerts: SharedFlow<VpnAlert> = _alerts.asSharedFlow()

    private var activeBackend: VpnBackend? = null
    private val connectionMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var reconnectJob: Job? = null
    // Backoff continuity: only reset attempt counter if the last success held
    // for at least BACKOFF_RESET_WINDOW_MS. Otherwise keep climbing so a
    // flapping condition eventually lands on the 180s cap instead of
    // thrashing at 1s. lastConnectedAt is only stamped after the backend
    // reports a verified handshake (see WireGuardBackend.connect), so a
    // non-zero value genuinely means "we were working, not just UP".
    @Volatile private var reconnectAttempt: Int = 0
    @Volatile private var lastConnectedAt: Long = 0
    // Survives connect() failure paths that clear _activeProfile. Without this,
    // reconnect() finds no profile and silently exits — the watchdog loops forever
    // calling reconnect() which does nothing, and recovery never happens.
    @Volatile private var lastKnownProfile: VpnProfile? = null

    /** True while a reconnect loop is actively attempting. Watchdog uses this
     *  to avoid cancelling an in-flight backoff schedule. */
    val isReconnecting: Boolean
        get() = reconnectJob?.isActive == true

    private fun getBackend(type: VpnType): VpnBackend = when (type) {
        VpnType.WIREGUARD -> wireGuardBackend
    }

    suspend fun connect(profile: VpnProfile) {
        // Idempotence: if we're already up on the exact profile the caller
        // asked for, do nothing. Tasker rules often fire START_TUNNEL on a
        // timer against the already-connected profile, and churning the
        // tunnel down/up on every trigger breaks live connections.
        if (_state.value == VpnState.CONNECTED && _activeProfile.value?.id == profile.id) {
            Log.i(TAG, "Already connected to ${profile.name} — ignoring redundant connect")
            return
        }
        // Cancel any in-flight retry/reconnect and launch a fresh initial-connect
        // retry job on the orchestrator's app-scoped SupervisorJob. Running on
        // `scope` (not the caller's scope) is what makes retries survive the
        // VpnService getting torn down mid-handshake — the service's serviceScope
        // can die during the inter-attempt delay (Android sometimes stops the
        // VpnService after setState(DOWN)), and a delay() running on serviceScope
        // would silently throw CancellationException past the in-loop catches and
        // strand the user in CONNECTING with no further log output. Reusing
        // reconnectJob means a subsequent connect()/disconnect()/reconnect() still
        // cancels and supersedes us cleanly, and isReconnecting still gates the
        // watchdog and network-change handlers.
        reconnectJob?.cancel()
        val job = scope.launch { runInitialConnectWithRetries(profile) }
        reconnectJob = job
        // Suspend until the launched job finishes. If our caller is cancelled
        // (service tear-down), join() throws CancellationException out of
        // connect() — but the launched job itself keeps running on `scope` and
        // will land the state correctly on its own.
        job.join()
    }

    private suspend fun runInitialConnectWithRetries(profile: VpnProfile) {
        connectionMutex.withLock {
            // Re-check under the lock in case another caller landed first.
            if (_state.value == VpnState.CONNECTED && _activeProfile.value?.id == profile.id) {
                Log.i(TAG, "Already connected to ${profile.name} — ignoring redundant connect")
                return@withLock
            }
            if (_state.value == VpnState.CONNECTED || _state.value == VpnState.CONNECTING) {
                disconnectInternal()
            }

            _state.value = VpnState.CONNECTING
            _activeProfile.value = profile
            lastKnownProfile = profile
            _errorMessage.value = null

            val backend = getBackend(profile.type)
            activeBackend = backend

            Log.i(TAG, "Connecting to ${profile.name} (${profile.displayType})")

            var lastError: Throwable? = null
            for (attempt in 1..INITIAL_CONNECT_MAX_ATTEMPTS) {
                try {
                    withTimeout(CONNECT_TIMEOUT_MS) {
                        backend.connect(profile)
                    }
                    _state.value = VpnState.CONNECTED
                    lastConnectedAt = System.currentTimeMillis()
                    reconnectAttempt = 0
                    settingsRepository.setActiveProfileId(profile.id)
                    settingsRepository.setLastConnectedProfileId(profile.id)
                    if (attempt == 1) {
                        Log.i(TAG, "Connected to ${profile.name}")
                    } else {
                        Log.i(TAG, "Connected to ${profile.name} on attempt $attempt/$INITIAL_CONNECT_MAX_ATTEMPTS")
                    }
                    return@withLock
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Connect attempt $attempt/$INITIAL_CONNECT_MAX_ATTEMPTS timed out for ${profile.name}")
                    safeBackendDisconnect(backend)
                    lastError = e
                } catch (e: CancellationException) {
                    // The retry job itself was cancelled (a superseding
                    // connect/disconnect/reconnect). Don't surface as a
                    // user-visible error; rethrow so the launched job exits
                    // cleanly and the replacement flow runs.
                    Log.i(TAG, "Connect for ${profile.name} was cancelled on attempt $attempt")
                    safeBackendDisconnect(backend)
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Connect attempt $attempt/$INITIAL_CONNECT_MAX_ATTEMPTS failed for ${profile.name}: ${e.message}")
                    safeBackendDisconnect(backend)
                    lastError = e
                }

                // Wait between attempts (skipped after the final attempt).
                // Fixed delay rather than exponential — keeps the total bounded
                // and doesn't drift into reconnect()'s territory. We're on the
                // orchestrator's scope here, so delay() is only cancelled by an
                // explicit reconnectJob.cancel() (i.e., a superseding
                // connect/disconnect), not by the caller going away.
                if (attempt < INITIAL_CONNECT_MAX_ATTEMPTS) {
                    Log.i(TAG, "Retrying connect in ${INITIAL_CONNECT_RETRY_DELAY_MS / 1000}s (next attempt ${attempt + 1}/$INITIAL_CONNECT_MAX_ATTEMPTS)")
                    delay(INITIAL_CONNECT_RETRY_DELAY_MS)
                }
            }

            // All attempts exhausted — surface the last failure.
            val errMsg = lastError?.message
            Log.e(TAG, "Connection failed for ${profile.name} after $INITIAL_CONNECT_MAX_ATTEMPTS attempts: $errMsg", lastError)
            val finalMessage = if (lastError is TimeoutCancellationException) {
                "Connection timed out (after $INITIAL_CONNECT_MAX_ATTEMPTS attempts)"
            } else {
                "Connection failed after $INITIAL_CONNECT_MAX_ATTEMPTS attempts: ${errMsg ?: "unknown error"}"
            }
            _state.value = VpnState.ERROR
            _errorMessage.value = finalMessage
            _activeProfile.value = null
            activeBackend = null
            lastConnectedAt = 0
            _alerts.tryEmit(
                VpnAlert.InitialConnectFailed(
                    profileName = profile.name,
                    attempts = INITIAL_CONNECT_MAX_ATTEMPTS,
                    cause = errMsg ?: "unknown error"
                )
            )
        }
    }

    suspend fun disconnect() {
        // Stack trace of the caller is useful for chasing accidental disconnects
        // during wifi↔cell handoffs, but allocating + formatting + persisting it on
        // every call is wasteful in release. Keep the trace only on debug builds.
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "disconnect() called", Throwable("disconnect caller"))
        } else {
            Log.i(TAG, "disconnect() called")
        }
        reconnectJob?.cancel()
        reconnectJob = null
        connectionMutex.withLock { disconnectInternal() }
    }

    private suspend fun disconnectInternal() {
        _state.value = VpnState.DISCONNECTING
        try {
            activeBackend?.disconnect()
        } catch (e: CancellationException) {
            // Expected during tear-down supersession — not a real error.
            // Must rethrow so the outer coroutine unwinds cleanly instead
            // of silently swallowing cancellation and continuing with the
            // finally block as if the disconnect was routine.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect: ${e.message}", e)
        } finally {
            _state.value = VpnState.DISCONNECTED
            _activeProfile.value = null
            lastKnownProfile = null
            activeBackend = null
            _statistics.value = VpnStatistics()
            lastConnectedAt = 0
            reconnectAttempt = 0
            settingsRepository.setActiveProfileId(null)
        }
    }

    /**
     * Reconnect with exponential backoff capped at 180s. Keeps retrying forever
     * at the cap until it succeeds, is cancelled by [disconnect]/[connect], or
     * another [reconnect] call supersedes it. Backoff resets to start only if
     * the previous connection was stable for [BACKOFF_RESET_WINDOW_MS] — this
     * prevents flapping networks from restarting backoff at 1s on every retry.
     *
     * @Synchronized makes the cancel-then-launch pair atomic. Without it, two
     * concurrent callers (e.g., the watchdog tick and the network-change
     * debounce firing in the same moment) could both observe the same prior
     * job, both cancel it, and both launch — producing two parallel reconnect
     * loops on the orchestrator scope, each running its own backoff schedule.
     */
    @Synchronized
    fun reconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val now = System.currentTimeMillis()
            // Anomaly signal for the no-explicit-DOWN-before-UP design choice:
            // a fresh reconnect arriving very soon after the prior tunnel was
            // verified up suggests stale native state surviving the implicit
            // teardown inside GoBackend.setState(UP). Quiet on healthy paths
            // (network change, single hang) — fires only on rapid back-to-back
            // churn, so it's safe to leave on in release.
            if (lastConnectedAt > 0) {
                val sinceConnected = now - lastConnectedAt
                if (sinceConnected in 0 until RECONNECT_CHURN_WINDOW_MS) {
                    Log.w(TAG, "Reconnect churn: new reconnect ${sinceConnected}ms after prior CONNECTED (state=${_state.value})")
                }
            }
            val wasStable = lastConnectedAt > 0 && (now - lastConnectedAt) >= BACKOFF_RESET_WINDOW_MS
            if (wasStable) reconnectAttempt = 0
            // Snapshot at the start of this cycle so that a teardown that
            // resets lastConnectedAt mid-cycle doesn't suppress the alert
            // that should have fired for *this* cycle's first failure.
            val wasConnectedBefore = lastConnectedAt > 0
            // Per-cycle latch: only the first failure in this launched job
            // emits an alert. The user has been notified by then; further
            // failures fall under "the user is already aware" and stay quiet.
            var firstFailureNotified = false

            while (true) {
                val profile = _activeProfile.value ?: lastKnownProfile ?: return@launch
                val success = attemptReconnect(profile)
                if (success) {
                    Log.i(TAG, "Reconnected successfully after $reconnectAttempt retries")
                    return@launch
                }
                if (!firstFailureNotified && wasConnectedBefore) {
                    _alerts.tryEmit(
                        VpnAlert.RecoveryFailed(
                            profileName = profile.name,
                            cause = _errorMessage.value ?: "unknown error"
                        )
                    )
                    firstFailureNotified = true
                }
                // coerceIn (not minOf) so a hypothetical Int overflow that wraps
                // reconnectAttempt to a negative value still indexes safely.
                val delaySec = BACKOFF_SCHEDULE_SEC[
                    reconnectAttempt.coerceIn(0, BACKOFF_SCHEDULE_SEC.lastIndex)
                ]
                reconnectAttempt++
                Log.w(TAG, "Reconnect failed (attempt $reconnectAttempt), backing off ${delaySec}s")
                delay(delaySec * 1000)
            }
        }
    }

    private suspend fun attemptReconnect(profile: VpnProfile): Boolean = connectionMutex.withLock {
        _state.value = VpnState.RECONNECTING
        // Restore _activeProfile in case a prior connect() failure cleared it.
        // Without this, the UI shows no profile during reconnect, and connect()
        // idempotence checks can't detect a redundant in-flight connection.
        _activeProfile.value = profile
        lastKnownProfile = profile

        // Deliberately no explicit disconnect() before connect(). GoBackend.setState(UP)
        // tears down any currently-UP tunnel internally, back-to-back with the new
        // setStateInternal(UP), which collapses the Kotlin-level DOWN→UP gap to a
        // single JNI call. That shrinks the window where the TUN is absent and apps
        // can route over the physical interface.
        //
        // Hang-recovery is still intact: the internal teardown closes the stuck UDP
        // socket before the new one is rebound to the current underlying network, so
        // a watchdog-triggered reconnect on a dead link rebuilds exactly as before.
        val backend = getBackend(profile.type)
        activeBackend = backend
        return@withLock try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                backend.connect(profile)
            }
            _state.value = VpnState.CONNECTED
            _errorMessage.value = null
            lastConnectedAt = System.currentTimeMillis()
            // Symmetric with connect()'s success path. Without this, a recovery
            // that lands on attempt N leaves reconnectAttempt=N, so a fresh
            // hang within BACKOFF_RESET_WINDOW_MS resumes backoff at the cap
            // instead of restarting cleanly.
            reconnectAttempt = 0
            true
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Reconnect attempt timed out")
            safeBackendDisconnect(backend)
            _state.value = VpnState.ERROR
            _errorMessage.value = "Reconnection timed out"
            lastConnectedAt = 0
            false
        } catch (e: CancellationException) {
            // Another reconnect/connect/disconnect superseded us. Must rethrow
            // so the enclosing loop exits and the new flow proceeds — and must
            // NOT set errorMessage, otherwise a "Job was cancelled" string
            // leaks to the UI and the state gets wedged in ERROR.
            Log.i(TAG, "Reconnect attempt cancelled")
            safeBackendDisconnect(backend)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Reconnection attempt failed: ${e.message}", e)
            safeBackendDisconnect(backend)
            _state.value = VpnState.ERROR
            _errorMessage.value = e.message ?: "Reconnection failed"
            lastConnectedAt = 0
            false
        }
    }

    private suspend fun safeBackendDisconnect(backend: VpnBackend) {
        try { backend.disconnect() } catch (_: Exception) {}
    }

    /**
     * Refreshes the cached statistics. Returns true only on successful read.
     * On failure the cached value is unchanged — callers doing liveness checks
     * must treat false as "no new data" rather than "traffic stopped".
     */
    suspend fun refreshStatistics(): Boolean {
        val backend = activeBackend ?: return false
        if (_state.value != VpnState.CONNECTED) return false
        return try {
            _statistics.value = backend.getStatistics()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh statistics: ${e.message}")
            false
        }
    }

    suspend fun checkHealth(): Boolean {
        val backend = activeBackend ?: return false
        return try {
            val backendState = backend.getState()
            if (backendState != VpnState.CONNECTED) {
                // Do NOT overwrite orchestrator state here. Flipping directly to
                // DISCONNECTED from a health check skips the RECONNECTING flow
                // and the service observer then tears down the watchdog +
                // network callback — so wifi→cell handoffs end up permanently
                // disconnected with no recovery. Just report unhealthy; the
                // watchdog will call reconnect() which drives the transition
                // through RECONNECTING properly.
                Log.d(TAG, "Backend reports $backendState while orchestrator=${_state.value}")
            }
            backendState == VpnState.CONNECTED
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed: ${e.message}")
            false
        }
    }

    suspend fun latestHandshakeMillis(): Long {
        val backend = activeBackend as? WireGuardBackend ?: return 0L
        return backend.getLatestHandshakeMillis()
    }

    fun reportError(message: String) {
        _state.value = VpnState.ERROR
        _errorMessage.value = message
        _activeProfile.value = null
        activeBackend = null
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
