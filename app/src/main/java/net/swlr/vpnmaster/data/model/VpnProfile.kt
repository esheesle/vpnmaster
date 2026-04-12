package net.swlr.vpnmaster.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class VpnType {
    WIREGUARD,
    IKEV2
}

enum class SplitTunnelMode {
    DISABLED,
    EXCLUDE_APPS,
    INCLUDE_APPS
}

enum class IkeV2AuthMethod {
    CERTIFICATE,
    EAP,
    CERTIFICATE_AND_EAP
}

@Serializable
data class WireGuardConfig(
    val privateKey: String = "",
    val addresses: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val listenPort: Int? = null,
    val mtu: Int? = null,
    val peers: List<WireGuardPeer> = listOf(WireGuardPeer())
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
data class IkeV2Config(
    val remoteId: String? = null,
    val localId: String? = null,
    val authMethod: IkeV2AuthMethod = IkeV2AuthMethod.EAP,
    val username: String? = null,
    val password: String? = null,
    val certificateAlias: String? = null,
    val caCertificateAlias: String? = null,
    val ikeProposal: String? = null,
    val espProposal: String? = null,
    val mtu: Int? = null,
    val dpdDelaySeconds: Int? = null,
    val rekeyIkeMinutes: Int? = null,
    val rekeyEspMinutes: Int? = null
)

@Serializable
data class SplitTunnelConfig(
    val mode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val appPackages: Set<String> = emptySet(),
    val excludedRoutes: List<String> = emptyList(),
    val includedRoutes: List<String> = emptyList()
)

data class VpnProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: VpnType = VpnType.WIREGUARD,
    val serverAddress: String = "",
    val wireGuardConfig: WireGuardConfig? = null,
    val ikeV2Config: IkeV2Config? = null,
    val splitTunnelConfig: SplitTunnelConfig = SplitTunnelConfig(),
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val displayType: String
        get() = when (type) {
            VpnType.WIREGUARD -> "WireGuard"
            VpnType.IKEV2 -> "IKEv2/IPsec"
        }
}
