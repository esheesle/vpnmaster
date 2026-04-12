package net.swlr.vpnmaster.vpn.wireguard

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.data.model.WireGuardConfig
import net.swlr.vpnmaster.vpn.VpnBackend
import net.swlr.vpnmaster.vpn.VpnState
import net.swlr.vpnmaster.vpn.VpnStatistics
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WireGuardBackend @Inject constructor(
    @ApplicationContext context: Context
) : VpnBackend {

    private val goBackend = GoBackend(context)
    private var activeTunnel: WgTunnel? = null
    private var connectedSince: Long = 0

    override suspend fun connect(profile: VpnProfile) = withContext(Dispatchers.IO) {
        val wgConfig = profile.wireGuardConfig
            ?: throw IllegalArgumentException("WireGuard config is required")

        val config = buildConfig(wgConfig, profile)
        val tunnel = WgTunnel(profile.name)
        activeTunnel = tunnel

        goBackend.setState(tunnel, Tunnel.State.UP, config)
        connectedSince = System.currentTimeMillis()
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: return@withContext
        try {
            goBackend.setState(tunnel, Tunnel.State.DOWN, null)
        } finally {
            activeTunnel = null
            connectedSince = 0
        }
    }

    override suspend fun getState(): VpnState = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: return@withContext VpnState.DISCONNECTED
        when (goBackend.getState(tunnel)) {
            Tunnel.State.UP -> VpnState.CONNECTED
            Tunnel.State.DOWN -> VpnState.DISCONNECTED
            Tunnel.State.TOGGLE -> VpnState.CONNECTING
        }
    }

    override suspend fun getStatistics(): VpnStatistics = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: return@withContext VpnStatistics()
        val stats = goBackend.getStatistics(tunnel)
        var totalRx = 0L
        var totalTx = 0L
        for (key in stats.peers()) {
            totalRx += stats.peerRx(key)
            totalTx += stats.peerTx(key)
        }
        VpnStatistics(
            bytesReceived = totalRx,
            bytesSent = totalTx,
            connectedSince = connectedSince
        )
    }

    private fun buildConfig(wgConfig: WireGuardConfig, profile: VpnProfile): Config {
        val interfaceBuilder = Interface.Builder().apply {
            parsePrivateKey(wgConfig.privateKey)
            wgConfig.addresses.forEach { addr ->
                addAddress(InetNetwork.parse(addr.trim()))
            }
            wgConfig.dnsServers.forEach { dns ->
                val parsed = java.net.InetAddress.getByName(dns.trim())
                addDnsServer(parsed)
            }
            wgConfig.listenPort?.let { setListenPort(it) }
            wgConfig.mtu?.let { setMtu(it) }

            val splitConfig = profile.splitTunnelConfig
            when (splitConfig.mode) {
                net.swlr.vpnmaster.data.model.SplitTunnelMode.EXCLUDE_APPS -> {
                    splitConfig.appPackages.forEach { excludeApplication(it) }
                }
                net.swlr.vpnmaster.data.model.SplitTunnelMode.INCLUDE_APPS -> {
                    splitConfig.appPackages.forEach { includeApplication(it) }
                }
                net.swlr.vpnmaster.data.model.SplitTunnelMode.DISABLED -> {}
            }
        }

        val peers = wgConfig.peers.map { peer ->
            Peer.Builder().apply {
                parsePublicKey(peer.publicKey)
                peer.preSharedKey?.let { parsePreSharedKey(it) }
                if (peer.endpoint.isNotBlank()) {
                    setEndpoint(InetEndpoint.parse(peer.endpoint))
                }
                peer.allowedIPs.forEach { ip ->
                    addAllowedIp(InetNetwork.parse(ip.trim()))
                }
                peer.persistentKeepalive?.let { setPersistentKeepalive(it) }
            }.build()
        }

        return Config.Builder()
            .setInterface(interfaceBuilder.build())
            .addPeers(peers)
            .build()
    }

    private class WgTunnel(private val profileName: String) : Tunnel {
        override fun getName(): String = profileName.replace(Regex("[^a-zA-Z0-9_=+.-]"), "_")
        override fun onStateChange(newState: Tunnel.State) {}
    }
}
