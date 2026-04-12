package net.swlr.vpnmaster.config

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.swlr.vpnmaster.data.model.IkeV2AuthMethod
import net.swlr.vpnmaster.data.model.IkeV2Config
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

            val fileName = uri.lastPathSegment ?: ""
            when {
                fileName.endsWith(".conf") || isWireGuardConfig(content) -> {
                    parseWireGuardConfig(content)
                }
                fileName.endsWith(".sswan") -> {
                    parseStrongSwanProfile(content)
                }
                else -> {
                    // Try WireGuard first, then strongSwan
                    val wgResult = parseWireGuardConfig(content)
                    if (wgResult is ImportResult.Success) wgResult
                    else parseStrongSwanProfile(content)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ConfigImporter", "Import failed", e)
            ImportResult.Error("Could not import this file. Check the format and try again.")
        }
    }

    fun parseQrCode(data: String): ImportResult {
        return parseWireGuardConfig(data)
    }

    private fun isWireGuardConfig(content: String): Boolean {
        return content.contains("[Interface]", ignoreCase = true) &&
                content.contains("[Peer]", ignoreCase = true)
    }

    fun parseWireGuardConfig(content: String): ImportResult {
        return try {
            val lines = content.lines()
            var section = ""
            var privateKey = ""
            val addresses = mutableListOf<String>()
            val dnsServers = mutableListOf<String>()
            var listenPort: Int? = null
            var mtu: Int? = null
            val peers = mutableListOf<WireGuardPeer>()

            // Current peer being built
            var peerPublicKey = ""
            var peerPsk: String? = null
            var peerEndpoint = ""
            var peerAllowedIPs = mutableListOf<String>()
            var peerKeepalive: Int? = null

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                when {
                    trimmed.equals("[Interface]", ignoreCase = true) -> {
                        if (section == "Peer" && peerPublicKey.isNotBlank()) {
                            peers.add(WireGuardPeer(peerPublicKey, peerPsk, peerEndpoint, peerAllowedIPs.toList(), peerKeepalive))
                            peerPublicKey = ""; peerPsk = null; peerEndpoint = ""; peerAllowedIPs = mutableListOf(); peerKeepalive = null
                        }
                        section = "Interface"
                    }
                    trimmed.equals("[Peer]", ignoreCase = true) -> {
                        if (section == "Peer" && peerPublicKey.isNotBlank()) {
                            peers.add(WireGuardPeer(peerPublicKey, peerPsk, peerEndpoint, peerAllowedIPs.toList(), peerKeepalive))
                            peerPublicKey = ""; peerPsk = null; peerEndpoint = ""; peerAllowedIPs = mutableListOf(); peerKeepalive = null
                        }
                        section = "Peer"
                    }
                    else -> {
                        val eqIdx = trimmed.indexOf('=')
                        if (eqIdx < 0) continue
                        val key = trimmed.substring(0, eqIdx).trim()
                        val value = trimmed.substring(eqIdx + 1).trim()

                        when (section) {
                            "Interface" -> when (key.lowercase()) {
                                "privatekey" -> privateKey = value
                                "address" -> addresses.addAll(value.split(",").map { it.trim() })
                                "dns" -> dnsServers.addAll(value.split(",").map { it.trim() })
                                "listenport" -> listenPort = value.toIntOrNull()
                                "mtu" -> mtu = value.toIntOrNull()
                            }
                            "Peer" -> when (key.lowercase()) {
                                "publickey" -> peerPublicKey = value
                                "presharedkey" -> peerPsk = value
                                "endpoint" -> peerEndpoint = value
                                "allowedips" -> peerAllowedIPs.addAll(value.split(",").map { it.trim() })
                                "persistentkeepalive" -> peerKeepalive = value.toIntOrNull()
                            }
                        }
                    }
                }
            }
            // Add last peer
            if (peerPublicKey.isNotBlank()) {
                peers.add(WireGuardPeer(peerPublicKey, peerPsk, peerEndpoint, peerAllowedIPs.toList(), peerKeepalive))
            }

            if (privateKey.isBlank()) {
                return ImportResult.Error("No private key found in WireGuard config")
            }

            val serverAddress = peers.firstOrNull()?.endpoint?.substringBeforeLast(":") ?: ""
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
        } catch (e: Exception) {
            android.util.Log.e("ConfigImporter", "WireGuard parse failed", e)
            ImportResult.Error("Invalid WireGuard configuration file")
        }
    }

    private fun parseStrongSwanProfile(content: String): ImportResult {
        return try {
            val root = json.parseToJsonElement(content).jsonObject
            val remoteObj = root["remote"]?.jsonObject
            val localObj = root["local"]?.jsonObject

            val gateway = remoteObj?.get("addr")?.jsonPrimitive?.content
                ?: return ImportResult.Error("No gateway address in .sswan profile")

            val remoteId = remoteObj["id"]?.jsonPrimitive?.content
            val localId = localObj?.get("id")?.jsonPrimitive?.content

            val authMethod = when (localObj?.get("type")?.jsonPrimitive?.content) {
                "eap" -> IkeV2AuthMethod.EAP
                "pubkey", "cert" -> IkeV2AuthMethod.CERTIFICATE
                else -> IkeV2AuthMethod.EAP
            }

            val eapId = localObj?.get("eap_id")?.jsonPrimitive?.content

            val config = IkeV2Config(
                remoteId = remoteId,
                localId = localId,
                authMethod = authMethod,
                username = eapId
            )

            ImportResult.Success(
                VpnProfile(
                    name = "Imported IKEv2",
                    type = VpnType.IKEV2,
                    serverAddress = gateway,
                    ikeV2Config = config
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("ConfigImporter", "strongSwan parse failed", e)
            ImportResult.Error("Invalid strongSwan profile file")
        }
    }
}
