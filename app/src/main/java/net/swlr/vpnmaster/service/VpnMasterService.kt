package net.swlr.vpnmaster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import net.swlr.vpnmaster.logging.AppLog as Log
import androidx.core.app.NotificationCompat
import com.wireguard.android.backend.GoBackend
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.swlr.vpnmaster.MainActivity
import net.swlr.vpnmaster.R
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.data.repository.ProfileRepository
import net.swlr.vpnmaster.data.repository.SettingsRepository
import net.swlr.vpnmaster.vpn.VpnOrchestrator
import net.swlr.vpnmaster.vpn.VpnState
import javax.inject.Inject

/**
 * VPN service for WireGuard.
 *
 * Extends GoBackend.VpnService so GoBackend can use this service's VpnService.Builder
 * to create the TUN interface.
 */
@AndroidEntryPoint
class VpnMasterService : GoBackend.VpnService() {

    companion object {
        const val ACTION_CONNECT = "net.swlr.vpnmaster.CONNECT"
        const val ACTION_DISCONNECT = "net.swlr.vpnmaster.DISCONNECT"
        // "Connect to whatever the user last used (or the default profile)" — used
        // by the QS tile and other entry points that don't know which profile to
        // pick. The service does the lookup internally so callers don't have to
        // block on the DB before invoking startForegroundService.
        const val ACTION_RESUME = "net.swlr.vpnmaster.RESUME"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val NOTIFICATION_ID = 1
        // Distinct alert IDs so the two alert types don't collapse onto each
        // other if both fire. Channels are also distinct so users can configure
        // sound/importance per type in Android system settings.
        private const val NOTIFICATION_ID_ALERT_CONNECT = 2
        private const val NOTIFICATION_ID_ALERT_RECOVERY = 3
        private const val CHANNEL_ID = "vpn_status"
        private const val CHANNEL_ID_ALERT_CONNECT = "vpn_alert_connect"
        private const val CHANNEL_ID_ALERT_RECOVERY = "vpn_alert_recovery"
        private const val TAG = "VpnMasterService"
        // Debounce for network-change reconnects. A single wifi→cell handoff
        // often fires several onAvailable callbacks in <1s; reconnecting on
        // each one would thrash. Wait for the churn to settle.
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 1_500L
    }

    @Inject lateinit var orchestrator: VpnOrchestrator
    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var watchdogManager: WatchdogManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var currentUnderlyingNetwork: Network? = null
    @Volatile private var networkChangeJob: Job? = null
    @Volatile private var internetRestoredJob: Job? = null
    // True when the underlying network has confirmed internet reachability
    // (NET_CAPABILITY_VALIDATED). Debounced rebirds and watchdog reconnects
    // are suppressed when false — no point hammering GoBackend while offline.
    @Volatile private var hasInternet: Boolean = true

    override fun onCreate() {
        super.onCreate()
        // Log every onCreate with the orchestrator's current state. Lets us
        // confirm from logs alone whether Android destroyed and recreated the
        // service mid-session (e.g., during a GoBackend.setState UP cycle).
        Log.d(TAG, "onCreate, orchestrator.state=${orchestrator.state.value}")
        createNotificationChannel()
        startForegroundCompat(buildNotification(getString(R.string.vpn_disconnected)))

        // If Android killed the service and START_STICKY is restarting it,
        // the orchestrator (app-scoped singleton) may still report an active
        // state while the watchdog and network callback are torn down. This
        // happens during watchdog-driven reconnects: GoBackend.setState(UP)
        // does an internal DOWN→UP cycle that closes the TUN; Android often
        // stops the VpnService on the close, then restarts it mid-reconnect
        // with state still RECONNECTING. Rehydrate for any state where the
        // orchestrator is actively trying to maintain a connection — anything
        // but a clean DISCONNECTED/DISCONNECTING — otherwise we end up with
        // state=CONNECTED and no monitoring once the in-flight reconnect
        // lands. (The state collector below registers the network callback
        // on the CONNECTED transition but does NOT start the watchdog, so
        // missing this rehydrate means the watchdog stays dead until the
        // next user-initiated connect.)
        val currentState = orchestrator.state.value
        val needsRehydrate = currentState == VpnState.CONNECTED ||
            currentState == VpnState.CONNECTING ||
            currentState == VpnState.RECONNECTING ||
            currentState == VpnState.ERROR
        if (needsRehydrate) {
            Log.i(TAG, "onCreate with state=$currentState — service likely restarted; rehydrating monitors")
            serviceScope.launch {
                val watchdogEnabled = settingsRepository.watchdogEnabled.first()
                if (watchdogEnabled) {
                    val interval = settingsRepository.watchdogIntervalSeconds.first()
                    val probeMaxFailures = settingsRepository.watchdogProbeMaxFailures.first()
                    watchdogManager.start(interval.toLong(), probeMaxFailures)
                }
            }
        }

        serviceScope.launch {
            var prevState: VpnState? = null
            orchestrator.state.collect { state ->
                // Reset watchdog traffic counters on every fresh entry into
                // CONNECTED — including reconnects, which never pass through
                // DISCONNECTED so start() wouldn't fire. Without this, stale
                // rx timestamps instantly re-trip the hang detector.
                if (state == VpnState.CONNECTED && prevState != VpnState.CONNECTED) {
                    watchdogManager.notifyConnected()
                    // Cancel any pending debounce/internet-restored jobs queued while
                    // the reconnect was in flight — we're up now, no need to act on them.
                    networkChangeJob?.cancel()
                    networkChangeJob = null
                    internetRestoredJob?.cancel()
                    internetRestoredJob = null
                }
                prevState = state

                val text = when (state) {
                    VpnState.DISCONNECTED -> getString(R.string.vpn_disconnected)
                    VpnState.CONNECTING -> {
                        val name = orchestrator.activeProfile.value?.name ?: ""
                        getString(R.string.notification_connecting, name)
                    }
                    VpnState.CONNECTED -> {
                        val name = orchestrator.activeProfile.value?.name ?: ""
                        getString(R.string.notification_connected, name)
                    }
                    VpnState.DISCONNECTING -> getString(R.string.vpn_disconnecting)
                    VpnState.ERROR -> getString(R.string.vpn_error)
                    VpnState.RECONNECTING -> getString(R.string.vpn_reconnecting)
                }
                updateNotification(text, state == VpnState.CONNECTED)

                when (state) {
                    VpnState.CONNECTED -> registerNetworkCallback()
                    VpnState.DISCONNECTED -> {
                        watchdogManager.stop()
                        unregisterNetworkCallback()
                    }
                    else -> {}
                }
            }
        }

        // One-shot alerts (initial-connect failure, recovery failure). Distinct
        // collector so a slow notification post never backs up the state stream
        // — and so a future test can mock the orchestrator's alerts flow without
        // entangling it with state.
        serviceScope.launch {
            orchestrator.alerts.collect { alert ->
                when (alert) {
                    is net.swlr.vpnmaster.vpn.VpnAlert.InitialConnectFailed -> {
                        showAlertNotification(
                            notificationId = NOTIFICATION_ID_ALERT_CONNECT,
                            channelId = CHANNEL_ID_ALERT_CONNECT,
                            title = getString(R.string.alert_connect_failed_title),
                            body = getString(
                                R.string.alert_connect_failed_body,
                                alert.profileName,
                                alert.attempts,
                                alert.cause
                            )
                        )
                    }
                    is net.swlr.vpnmaster.vpn.VpnAlert.RecoveryFailed -> {
                        showAlertNotification(
                            notificationId = NOTIFICATION_ID_ALERT_RECOVERY,
                            channelId = CHANNEL_ID_ALERT_RECOVERY,
                            title = getString(R.string.alert_recovery_failed_title),
                            body = getString(
                                R.string.alert_recovery_failed_body,
                                alert.profileName,
                                alert.cause
                            )
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return START_STICKY
                serviceScope.launch {
                    val profile = profileRepository.getProfileById(profileId) ?: return@launch
                    handleConnect(profile)
                }
            }
            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    orchestrator.disconnect()
                }
            }
            ACTION_RESUME -> {
                serviceScope.launch {
                    val currentState = orchestrator.state.value
                    if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) return@launch
                    val lastId = settingsRepository.lastConnectedProfileId.first()
                    val profile = (lastId?.let { profileRepository.getProfileById(it) })
                        ?: profileRepository.getDefaultProfile()
                        ?: return@launch
                    handleConnect(profile)
                }
            }
            else -> {
                // Null-intent path: Android restarted us via START_STICKY after killing the
                // process mid-session. Only reconnect if the user actually wants the tunnel
                // up — `activeProfileId` is cleared on user-initiated disconnect, so reading
                // *that* (not lastConnectedProfileId) keeps us from auto-reconnecting after
                // the user explicitly turned the VPN off.
                serviceScope.launch {
                    val currentState = orchestrator.state.value
                    if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
                        Log.d(TAG, "Already connected/connecting, ignoring restart intent")
                        return@launch
                    }
                    val profileId = settingsRepository.activeProfileId.first()
                    if (profileId == null) {
                        Log.i(TAG, "Null-intent restart with no activeProfileId — user disconnected, staying off")
                        return@launch
                    }
                    val profile = profileRepository.getProfileById(profileId) ?: return@launch
                    handleConnect(profile)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun handleConnect(profile: VpnProfile) {
        try {
            orchestrator.connect(profile)

            val watchdogEnabled = settingsRepository.watchdogEnabled.first()
            if (watchdogEnabled) {
                val interval = settingsRepository.watchdogIntervalSeconds.first()
                val probeMaxFailures = settingsRepository.watchdogProbeMaxFailures.first()
                watchdogManager.start(interval.toLong(), probeMaxFailures)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Superseded by another connect/disconnect — don't report as error.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect: ${e.message}", e)
            orchestrator.reportError("Connection failed: ${e.message}")
        }
    }

    /**
     * Watch the default (underlying) network. When it changes — cell tower handoff,
     * cellular→wifi, brief signal loss and return — the Go tunnel's UDP socket is
     * bound to the stale network handle and silently stops moving traffic. Trigger
     * a reconnect so GoBackend rebinds onto the new network.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return

        // Let Android track whatever the default is. Previously we passed
        // arrayOf(specificNetwork), which *pins* the VPN to that network —
        // when wifi dies, Android sees the VPN's pinned underlying is dead
        // and can decide the VpnService should be torn down, which manifests
        // as an out-of-nowhere DISCONNECTED after a handoff. Null means
        // "track default", so 5g transparently takes over for wifi.
        try {
            setUnderlyingNetworks(null)
        } catch (e: Exception) {
            Log.w(TAG, "setUnderlyingNetworks(null) failed: ${e.message}")
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val prev = currentUnderlyingNetwork
                if (prev == network) return
                currentUnderlyingNetwork = network

                if (prev != null) {
                    Log.i(TAG, "Underlying network changed ($prev -> $network), debouncing rebind")
                    scheduleDebouncedReconnect()
                }
            }

            override fun onLost(network: Network) {
                // Don't clear currentUnderlyingNetwork here — if the replacement
                // is the same network re-surfacing, we want onAvailable's equality
                // check to suppress a spurious reconnect. Let onAvailable overwrite
                // it on the real next network.
                Log.i(TAG, "Underlying network lost ($network), waiting for replacement")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val nowHasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val hadInternet = hasInternet
                hasInternet = nowHasInternet
                watchdogManager.setInternetAvailable(nowHasInternet)
                if (!hadInternet && nowHasInternet) {
                    Log.i(TAG, "Internet connectivity restored on $network")
                    onInternetRestored()
                } else if (hadInternet && !nowHasInternet) {
                    Log.i(TAG, "Internet connectivity lost on $network")
                }
            }
        }

        try {
            // registerDefaultNetworkCallback from inside a VpnService returns the
            // underlying (non-VPN) default network, which is exactly what we need
            // to detect wifi↔cell handoffs. Using a NetworkRequest with NOT_VPN
            // would match multiple simultaneous networks (both wifi and cell) and
            // flip onAvailable back and forth.
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            Log.d(TAG, "Registered underlying-network callback")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun scheduleDebouncedReconnect() {
        networkChangeJob?.cancel()
        networkChangeJob = serviceScope.launch {
            delay(NETWORK_CHANGE_DEBOUNCE_MS)
            // If a reconnect loop is already in flight, don't cancel/restart it
            // — Android fires multiple onAvailable callbacks during wifi↔cell
            // handoff as the radio settles, and each restart would blow away
            // the current attempt plus its backoff delay. The in-flight loop
            // will pick up the new underlying network on its next attempt
            // (setUnderlyingNetworks was already updated in onAvailable).
            if (orchestrator.isReconnecting) {
                Log.i(TAG, "Debounce elapsed but reconnect already in flight, skipping")
                return@launch
            }
            if (!hasInternet) {
                Log.i(TAG, "Debounce elapsed but no internet connectivity, skipping rebind")
                return@launch
            }
            Log.i(TAG, "Debounce elapsed, triggering tunnel rebind")
            orchestrator.reconnect()
        }
    }

    /**
     * Called when the underlying network regains validated internet access.
     * Triggers an immediate reconnect attempt if the VPN is not already up —
     * this recovers the tunnel faster than waiting for the next watchdog tick
     * or WorkManager job after a multi-minute outage.
     *
     * Debounced: onCapabilitiesChanged can fire multiple VALIDATED transitions
     * in rapid succession during cellular recovery. Without debouncing, two
     * concurrent handleConnect calls can race — the second aborts the first
     * mid-handshake by calling disconnectInternal() when state is CONNECTING.
     */
    private fun onInternetRestored() {
        internetRestoredJob?.cancel()
        internetRestoredJob = serviceScope.launch {
            delay(NETWORK_CHANGE_DEBOUNCE_MS)
            val state = orchestrator.state.value
            if (state == VpnState.CONNECTED || state == VpnState.CONNECTING) return@launch
            // The reconnect loop's own retries run in RECONNECTING/ERROR — don't
            // call connect() on top of it. connect() cancels reconnectJob, which
            // tears down an in-flight backend.connect (mid-handshake) and brings
            // the TUN down, then right back up. That TUN bounce re-triggers a
            // VALIDATED capability event on the underlying network, which fires
            // onInternetRestored again, producing a flap every ~1.5–2s. The
            // reconnect loop is already trying forever (capped at 180s) and the
            // watchdog covers the case where it somehow exits — no need to
            // re-drive it here. Mirrors the skip in scheduleDebouncedReconnect.
            if (orchestrator.isReconnecting) {
                Log.i(TAG, "Internet restored but reconnect already in flight, skipping")
                return@launch
            }
            val profileId = settingsRepository.lastConnectedProfileId.first()
            val profile = if (profileId != null) profileRepository.getProfileById(profileId) else null
            if (profile != null) {
                Log.i(TAG, "Internet restored, reconnecting to ${profile.name}")
                handleConnect(profile)
            }
        }
    }

    private fun unregisterNetworkCallback() {
        networkChangeJob?.cancel()
        networkChangeJob = null
        internetRestoredJob?.cancel()
        internetRestoredJob = null
        hasInternet = true  // reset so next registration starts with a clean assumption
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
        currentUnderlyingNetwork = null
    }

    override fun onRevoke() {
        Log.w(TAG, "onRevoke() fired — VPN permission was withdrawn by the system")
        serviceScope.launch {
            orchestrator.disconnect()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy, orchestrator.state=${orchestrator.state.value}")
        serviceScope.cancel()
        watchdogManager.stop()
        unregisterNetworkCallback()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        val statusChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_vpn),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_vpn_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(statusChannel)

        // Separate channels (DEFAULT importance, badge enabled) so users can
        // give these alerts their own sound/vibration in Android settings,
        // independently of the always-on status notification.
        val connectAlertChannel = NotificationChannel(
            CHANNEL_ID_ALERT_CONNECT,
            getString(R.string.notification_channel_alert_connect),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_alert_connect_desc)
        }
        manager.createNotificationChannel(connectAlertChannel)

        val recoveryAlertChannel = NotificationChannel(
            CHANNEL_ID_ALERT_RECOVERY,
            getString(R.string.notification_channel_alert_recovery),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_alert_recovery_desc)
        }
        manager.createNotificationChannel(recoveryAlertChannel)
    }

    private fun showAlertNotification(
        notificationId: Int,
        channelId: String,
        title: String,
        body: String
    ) {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun buildNotification(text: String, showDisconnect: Boolean = false): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (showDisconnect) {
            val disconnectIntent = PendingIntent.getService(
                this, 1,
                Intent(this, VpnMasterService::class.java).apply {
                    action = ACTION_DISCONNECT
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                0,
                getString(R.string.notification_action_disconnect),
                disconnectIntent
            )
        }

        return builder.build()
    }

    private fun updateNotification(text: String, showDisconnect: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, showDisconnect))
    }

    // Android 14+ requires the foregroundServiceType bitmask to be passed to
    // startForeground when the manifest declares a non-default type. Manifest
    // has foregroundServiceType="specialUse"; pass the matching constant so
    // the FGS start doesn't fail with MissingForegroundServiceTypeException.
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
