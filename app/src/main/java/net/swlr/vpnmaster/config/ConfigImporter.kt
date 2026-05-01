package net.swlr.vpnmaster.config

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.data.model.VpnType
import net.swlr.vpnmaster.data.model.WireGuardConfig
import net.swlr.vpnmaster.data.model.WireGuardPeer
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

sealed class ImportResult {
    data class Success(val profile: VpnProfile) : ImportResult()
    data class Error(val message: String) : ImportResult()
}

@Singleton
class ConfigImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    fun importFromUri(uri: Uri): ImportResult {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            } ?: return ImportResult.Error("Could not read file")

            parseWireGuardConfig(content)
        } catch (e: Exception) {
            android.util.Log.e("ConfigImporter", "Import failed", e)
            ImportResult.Error("Could not import this file. Check the format and try again.")
        }
    }

    fun parseQrCode(data: String): ImportResult {
        return parseWireGuardWithLibrary(data)
    }

    private fun parseWireGuardWithLibrary(content: String): ImportResult {
        return try {
            val parsed = Config.parse(content.byteInputStream())

            val iface = parsed.`interface`
            val privateKey = iface.keyPair.privateKey.toBase64()
            val addresses = iface.addresses.map { it.toString() }
            val dnsServers = iface.dnsServers.map { it.hostAddress ?: it.toString() }
            val listenPort = iface.listenPort.orElse(null)?.toInt()
            val mtu = iface.mtu.orElse(null)?.toInt()

            val peers = parsed.peers.map { peer ->
                WireGuardPeer(
                    publicKey = peer.publicKey.toBase64(),
                    preSharedKey = peer.preSharedKey.orElse(null)?.toBase64(),
                    endpoint = peer.endpoint.orElse(null)?.let { "${it.host}:${it.port}" } ?: "",
                    allowedIPs = peer.allowedIps.map { it.toString() },
                    persistentKeepalive = peer.persistentKeepalive.orElse(null)?.toInt()
                )
            }

            // Use the parsed host directly. substringBeforeLast(":") on the
            // composed "host:port" string is brittle for IPv6 — e.g. brackets
            // survive into the result, or a bare-IPv6 host gets its last hextet
            // chopped off if the port was already glued on.
            val serverAddress = parsed.peers.firstOrNull()?.endpoint?.orElse(null)?.host ?: ""
            val config = WireGuardConfig(
                privateKey = privateKey,
                addresses = addresses,
                dnsServers = dnsServers,
                listenPort = listenPort,
                mtu = mtu,
                peers = peers
            )

            ImportResult.Success(
                VpnProfile(
                    name = "Imported WireGuard",
                    type = VpnType.WIREGUARD,
                    serverAddress = serverAddress,
                    wireGuardConfig = config
                )
            )
        } catch (e: BadConfigException) {
            android.util.Log.e("ConfigImporter", "WireGuard library parse failed", e)
            ImportResult.Error("Invalid WireGuard config: ${e.reason.name}")
        } catch (e: Exception) {
            android.util.Log.e("ConfigImporter", "WireGuard parse failed", e)
            ImportResult.Error("Invalid WireGuard configuration")
        }
    }

    fun parseWireGuardConfig(content: String): ImportResult {
        return parseWireGuardWithLibrary(content)
    }

    /**
     * Render a profile back into wg-quick `.conf` format for share/export.
     * We hand-build the text rather than going through Config.toWgQuickString()
     * because the library form requires a fully validated config (failing on,
     * e.g., missing peer endpoints), and we want export to succeed for any
     * profile the user has saved — even partial ones.
     */
    fun toWgQuickString(profile: VpnProfile): String {
        val cfg = profile.wireGuardConfig ?: return ""
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${cfg.privateKey}")
            if (cfg.addresses.isNotEmpty()) appendLine("Address = ${cfg.addresses.joinToString(", ")}")
            if (cfg.dnsServers.isNotEmpty()) appendLine("DNS = ${cfg.dnsServers.joinToString(", ")}")
            cfg.listenPort?.let { appendLine("ListenPort = $it") }
            cfg.mtu?.let { appendLine("MTU = $it") }

            cfg.peers.forEach { peer ->
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = ${peer.publicKey}")
                peer.preSharedKey?.let { appendLine("PresharedKey = $it") }
                if (peer.endpoint.isNotBlank()) appendLine("Endpoint = ${peer.endpoint}")
                if (peer.allowedIPs.isNotEmpty()) {
                    appendLine("AllowedIPs = ${peer.allowedIPs.joinToString(", ")}")
                }
                peer.persistentKeepalive?.let { appendLine("PersistentKeepalive = $it") }
            }
        }
    }
}
