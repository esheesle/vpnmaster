package net.swlr.vpnmaster.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class VpnType {
    WIREGUARD
}

enum class SplitTunnelMode {
    DISABLED,
    EXCLUDE_APPS,
    INCLUDE_APPS
}

@Serializable
data class WireGuardConfig(
    val privateKey: String = "",
    val addresses: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val listenPort: Int? = null,
    val mtu: Int? = null,
    val peers: List<WireGuardPeer> = listOf(WireGuardPeer()),
    // Optional override for the watchdog probe target. When null, the watchdog
    // derives the gateway as the .1 of the client's subnet, which matches the
    // common WireGuard convention but fails on peers that use a different
    // gateway (e.g. .254, /31 point-to-point). Any IPv4/IPv6 reachable only
    // through the tunnel works — the watchdog only cares about rx delta.
    val probeTarget: String? = null
)

@Serializable
data class WireGuardPeer(
    val publicKey: String = "",
    val preSharedKey: String? = null,
    val endpoint: String = "",
    val allowedIPs: List<String> = listOf("0.0.0.0/0", "::/0"),
    val persistentKeepalive: Int? = null
)

@Serializable
data class SplitTunnelConfig(
    val mode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val appPackages: Set<String> = emptySet(),
    val excludedRoutes: List<String> = emptyList(),
    val includedRoutes: List<String> = emptyList()
)

@Serializable
data class VpnProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: VpnType = VpnType.WIREGUARD,
    val serverAddress: String = "",
    val wireGuardConfig: WireGuardConfig? = null,
    val splitTunnelConfig: SplitTunnelConfig = SplitTunnelConfig(),
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val displayType: String
        get() = when (type) {
            VpnType.WIREGUARD -> "WireGuard"
        }
}
