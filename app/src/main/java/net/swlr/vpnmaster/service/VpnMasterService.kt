package net.swlr.vpnmaster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.wireguard.android.backend.GoBackend
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.swlr.vpnmaster.MainActivity
import net.swlr.vpnmaster.R
import net.swlr.vpnmaster.data.model.SplitTunnelMode
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.data.model.VpnType
import net.swlr.vpnmaster.data.repository.ProfileRepository
import net.swlr.vpnmaster.data.repository.SettingsRepository
import net.swlr.vpnmaster.vpn.VpnOrchestrator
import net.swlr.vpnmaster.vpn.VpnState
import net.swlr.vpnmaster.vpn.ikev2.CharonBridge
import net.swlr.vpnmaster.vpn.ikev2.StrongSwanBackend
import javax.inject.Inject

/**
 * Unified VPN service for both WireGuard and IKEv2/strongSwan.
 *
 * Extends GoBackend.VpnService so GoBackend can use this service's VPN tunnel
 * builder when in WireGuard mode. When in IKEv2 mode, we manage the tunnel
 * directly via strongSwan's charon daemon.
 */
@AndroidEntryPoint
class VpnMasterService : GoBackend.VpnService() {

    companion object {
        const val ACTION_CONNECT = "net.swlr.vpnmaster.CONNECT"
        const val ACTION_DISCONNECT = "net.swlr.vpnmaster.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vpn_status"
    }

    @Inject lateinit var orchestrator: VpnOrchestrator
    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var watchdogManager: WatchdogManager
    @Inject lateinit var strongSwanBackend: StrongSwanBackend

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.vpn_disconnected)))

        serviceScope.launch {
            orchestrator.state.collect { state ->
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

                if (state == VpnState.DISCONNECTED) {
                    watchdogManager.stop()
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
                    closeVpnInterface()
                }
            }
            else -> {
                // Always-on VPN or system restart — connect to last/default profile
                serviceScope.launch {
                    val profileId = settingsRepository.lastConnectedProfileId.first()
                    val profile = if (profileId != null) {
                        profileRepository.getProfileById(profileId)
                    } else {
                        profileRepository.getDefaultProfile()
                    }
                    if (profile != null) {
                        handleConnect(profile)
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun handleConnect(profile: VpnProfile) {
        if (profile.type == VpnType.IKEV2) {
            initializeStrongSwan(profile)
        }
        orchestrator.connect(profile)

        val watchdogEnabled = settingsRepository.watchdogEnabled.first()
        if (watchdogEnabled) {
            val interval = settingsRepository.watchdogIntervalSeconds.first()
            watchdogManager.start(interval.toLong())
        }
    }

    /**
     * Initialize strongSwan's charon daemon with a BuilderAdapter that
     * accumulates VPN configuration and creates the TUN interface.
     *
     * strongSwan's native code calls the adapter methods sequentially:
     * addAddress, addDnsServer, addRoute, etc., then finally establish().
     */
    private fun initializeStrongSwan(profile: VpnProfile) {
        val service = this

        val adapter = object : CharonBridge.BuilderAdapter {
            private var pendingAddresses = mutableListOf<Pair<String, Int>>()
            private var pendingDns = mutableListOf<String>()
            private var pendingRoutes = mutableListOf<Pair<String, Int>>()
            private var pendingDomains = mutableListOf<String>()
            private var pendingMtu: Int? = null

            override fun addAddress(address: String, prefixLength: Int): Boolean {
                pendingAddresses.add(address to prefixLength)
                return true
            }

            override fun addDnsServer(address: String): Boolean {
                pendingDns.add(address)
                return true
            }

            override fun addRoute(address: String, prefixLength: Int): Boolean {
                pendingRoutes.add(address to prefixLength)
                return true
            }

            override fun addSearchDomain(domain: String): Boolean {
                pendingDomains.add(domain)
                return true
            }

            override fun setMtu(mtu: Int): Boolean {
                pendingMtu = mtu
                return true
            }

            override fun establish(): Int {
                try {
                    val builder = service.Builder()
                    builder.setSession(profile.name)

                    pendingAddresses.forEach { (addr, prefix) ->
                        builder.addAddress(addr, prefix)
                    }
                    pendingDns.forEach { dns ->
                        builder.addDnsServer(dns)
                    }
                    pendingRoutes.forEach { (addr, prefix) ->
                        builder.addRoute(addr, prefix)
                    }
                    pendingDomains.forEach { domain ->
                        builder.addSearchDomain(domain)
                    }
                    // Use MTU from native code if provided, otherwise fall back
                    // to the user's configured MTU from the profile
                    val mtu = pendingMtu ?: profile.ikeV2Config?.mtu
                    mtu?.let { builder.setMtu(it) }

                    // Apply split tunneling
                    val splitConfig = profile.splitTunnelConfig
                    when (splitConfig.mode) {
                        SplitTunnelMode.EXCLUDE_APPS -> {
                            splitConfig.appPackages.forEach { pkg ->
                                try { builder.addDisallowedApplication(pkg) }
                                catch (_: Exception) {}
                            }
                        }
                        SplitTunnelMode.INCLUDE_APPS -> {
                            splitConfig.appPackages.forEach { pkg ->
                                try { builder.addAllowedApplication(pkg) }
                                catch (_: Exception) {}
                            }
                        }
                        SplitTunnelMode.DISABLED -> {}
                    }

                    closeVpnInterface()
                    vpnInterface = builder.establish()

                    // Reset for next connection
                    pendingAddresses.clear()
                    pendingDns.clear()
                    pendingRoutes.clear()
                    pendingDomains.clear()
                    pendingMtu = null

                    return vpnInterface?.fd ?: -1
                } catch (e: Exception) {
                    return -1
                }
            }
        }

        strongSwanBackend.initializeNative(adapter)
    }

    override fun onRevoke() {
        serviceScope.launch {
            orchestrator.disconnect()
            closeVpnInterface()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        closeVpnInterface()
        watchdogManager.stop()
        strongSwanBackend.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    private fun closeVpnInterface() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_vpn),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_vpn_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
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
}
