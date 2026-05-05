package net.swlr.vpnmaster.vpn.wireguard

import net.swlr.vpnmaster.logging.AppLog as Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val goBackend: GoBackend
) : VpnBackend {

    companion object {
        private const val TAG = "WireGuardBackend"
        // Carriers typically expire UDP NAT mappings at ~30s. 25s keeps them alive.
        private const val DEFAULT_PERSISTENT_KEEPALIVE = 25
        // Max time to wait for the first handshake after setState UP. WireGuard
        // retries handshake init every ~5s, so 15s gives 3 attempts — enough on
        // a working link, short enough to fail fast on a dead one.
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val HANDSHAKE_POLL_MS = 500L
    }

    private var activeTunnel: WgTunnel? = null
    private var connectedSince: Long = 0

    override suspend fun connect(profile: VpnProfile): Unit = withContext(Dispatchers.IO) {
        val wgConfig = profile.wireGuardConfig
            ?: throw IllegalArgumentException("WireGuard config is required")

        Log.i(TAG, "Building WireGuard config for ${profile.name}")
        val config = buildConfig(wgConfig, profile)
        val tunnel = WgTunnel(profile.name)

        // Hold the prior reference so we can clean up on a failed UP. If we
        // overwrote activeTunnel with the new object before setState(UP) and
        // setState then threw, the orchestrator's safeBackendDisconnect would
        // DOWN the *new* tunnel — a no-op, since the new tunnel was never up
        // — while the previously-running tunnel could survive untracked
        // depending on how far GoBackend got internally before failing.
        val previousTunnel = activeTunnel

        Log.i(TAG, "Calling GoBackend.setState UP")
        try {
            goBackend.setState(tunnel, Tunnel.State.UP, config)
        } catch (e: Exception) {
            // setState(UP) threw. GoBackend may or may not have torn down the
            // previous tunnel before failing; do it explicitly so no stale
            // tunnel survives this reconnect attempt. Best-effort: if it's
            // already down, this is a no-op.
            if (previousTunnel != null && previousTunnel !== tunnel) {
                try {
                    Log.i(TAG, "setState UP failed — taking previous tunnel DOWN as cleanup")
                    goBackend.setState(previousTunnel, Tunnel.State.DOWN, null)
                } catch (_: Exception) {
                    // Best-effort cleanup; the failure path is already in flight.
                }
            }
            // Keep activeTunnel pointing at previousTunnel (or null if there
            // wasn't one) so subsequent disconnect() calls have something
            // useful to target if state diverged. The next successful
            // connect() replaces it.
            throw e
        }
        // setState(UP) succeeded — now we own the new tunnel. GoBackend tore
        // down any prior tunnel internally as part of this call.
        activeTunnel = tunnel
        connectedSince = System.currentTimeMillis()
        Log.i(TAG, "WireGuard tunnel UP for ${profile.name}, waiting for handshake")

        // setState UP returns as soon as the TUN is up — it does NOT wait for a
        // handshake. On marginal links, the tunnel can stay "UP but silent"
        // indefinitely. Require a real handshake before calling connect()
        // successful; otherwise the orchestrator's backoff thinks every phantom
        // connect is a real one and thrashes.
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (getLatestHandshakeMillis() >= connectedSince) {
                Log.i(TAG, "Handshake confirmed for ${profile.name}")
                return@withContext
            }
            delay(HANDSHAKE_POLL_MS)
        }
        Log.w(TAG, "No handshake within ${HANDSHAKE_TIMEOUT_MS}ms — tearing down")
        try { goBackend.setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) {}
        activeTunnel = null
        connectedSince = 0
        throw HandshakeTimeoutException("No WireGuard handshake within ${HANDSHAKE_TIMEOUT_MS / 1000}s")
    }

    class HandshakeTimeoutException(message: String) : Exception(message)

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: return@withContext
        try {
            Log.i(TAG, "Calling GoBackend.setState DOWN")
            goBackend.setState(tunnel, Tunnel.State.DOWN, null)
        } finally {
            activeTunnel = null
            connectedSince = 0
        }
    }

    override suspend fun getState(): VpnState = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: return@withContext VpnState.DISCONNECTED
        // Tunnel.State is a Java enum so Kotlin can't statically prove the
        // returned value is non-null; the else branch covers that and any
        // future enum additions in the wireguard library.
        when (goBackend.getState(tunnel)) {
            Tunnel.State.UP -> VpnState.CONNECTED
            Tunnel.State.DOWN -> VpnState.DISCONNECTED
            Tunnel.State.TOGGLE -> VpnState.CONNECTING
            else -> VpnState.DISCONNECTED
        }
    }

    override suspend fun getStatistics(): VpnStatistics = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: return@withContext VpnStatistics()
        val stats = goBackend.getStatistics(tunnel)
        var totalRx = 0L
        var totalTx = 0L
        for (key in stats.peers()) {
            val peer = stats.peer(key) ?: continue
            totalRx += peer.rxBytes
            totalTx += peer.txBytes
        }
        VpnStatistics(
            bytesReceived = totalRx,
            bytesSent = totalTx,
            connectedSince = connectedSince
        )
    }

    /**
     * Most recent successful handshake across all peers, in epoch millis.
     * Returns 0 if no handshake has occurred yet or the tunnel is down.
     * Used to detect "hung" tunnels where the state says UP but no traffic flows.
     */
    suspend fun getLatestHandshakeMillis(): Long = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: return@withContext 0L
        try {
            val stats = goBackend.getStatistics(tunnel)
            var latest = 0L
            for (key in stats.peers()) {
                val peer = stats.peer(key) ?: continue
                if (peer.latestHandshakeEpochMillis > latest) {
                    latest = peer.latestHandshakeEpochMillis
                }
            }
            latest
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read handshake: ${e.message}")
            0L
        }
    }

    private fun buildConfig(wgConfig: WireGuardConfig, profile: VpnProfile): Config {
        val splitConfig = profile.splitTunnelConfig
        val excludedCidrs = parseCidrList(splitConfig.excludedRoutes, "excludedRoutes")
        val includedCidrs = parseCidrList(splitConfig.includedRoutes, "includedRoutes")

        val interfaceBuilder = Interface.Builder().apply {
            parsePrivateKey(wgConfig.privateKey)
            wgConfig.addresses.forEach { addr ->
                addAddress(InetNetwork.parse(addr.trim()))
            }
            wgConfig.dnsServers.forEach { dns ->
                // Reject hostnames — InetAddress.getByName would trigger a blocking
                // DNS lookup here (often before the tunnel is even up), which can
                // stall connect() or fail outright. DNS entries in a WG config must
                // be numeric IP literals.
                val trimmed = dns.trim()
                require(android.net.InetAddresses.isNumericAddress(trimmed)) {
                    "DNS server must be a numeric IP literal, got: $trimmed"
                }
                addDnsServer(java.net.InetAddress.getByName(trimmed))
            }
            wgConfig.listenPort?.let { setListenPort(it) }
            wgConfig.mtu?.let { setMtu(it) }

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

        val peers = wgConfig.peers.mapIndexed { index, peer ->
            Peer.Builder().apply {
                parsePublicKey(peer.publicKey)
                peer.preSharedKey?.let { parsePreSharedKey(it) }
                if (peer.endpoint.isNotBlank()) {
                    setEndpoint(InetEndpoint.parse(peer.endpoint))
                }

                val finalCidrs = computePeerAllowedCidrs(
                    rawAllowedIPs = peer.allowedIPs,
                    isFirstPeer = index == 0,
                    includedCidrs = includedCidrs,
                    excludedCidrs = excludedCidrs,
                    peerLabel = peer.publicKey.take(8)
                )
                finalCidrs.forEach { addAllowedIp(InetNetwork.parse(it.toCanonicalString())) }

                // Default keepalive keeps NAT mappings on cellular alive;
                // without it, tunnels often hang after signal drops.
                setPersistentKeepalive(peer.persistentKeepalive ?: DEFAULT_PERSISTENT_KEEPALIVE)
            }.build()
        }

        return Config.Builder()
            .setInterface(interfaceBuilder.build())
            .addPeers(peers)
            .build()
    }

    private fun parseCidrList(raw: List<String>, source: String): List<CidrMath.Cidr> =
        raw.mapNotNull { entry ->
            try {
                CidrMath.Cidr.parse(entry)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed $source entry '$entry': ${e.message}")
                null
            }
        }

    /**
     * Final AllowedIPs for a peer, with split-tunnel routes and the IPv6 leak
     * guard applied. Order matters:
     *   1. Start from the peer's AllowedIPs.
     *   2. Leak guard: if the peer catches all of one IP family via a default
     *      route (0.0.0.0/0 or ::/0) but not the other, Android will route
     *      the captured family through the TUN and let the other family exit
     *      the physical interface unencrypted. Add the missing default so the
     *      VpnService captures that family too; the peer black-holing packets
     *      for a family it can't forward is the desired failure mode over a
     *      silent plaintext leak.
     *   3. Included routes append to the first peer only. Duplicating them
     *      across multiple peers would give wg-android conflicting routes.
     *   4. Excluded routes subtract last so that user-specified exclusions
     *      always win, even over included routes.
     */
    private fun computePeerAllowedCidrs(
        rawAllowedIPs: List<String>,
        isFirstPeer: Boolean,
        includedCidrs: List<CidrMath.Cidr>,
        excludedCidrs: List<CidrMath.Cidr>,
        peerLabel: String
    ): List<CidrMath.Cidr> {
        val original = rawAllowedIPs.mapNotNull { entry ->
            try {
                CidrMath.Cidr.parse(entry.trim())
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed peer AllowedIPs entry '$entry': ${e.message}")
                null
            }
        }

        val guarded = original.toMutableList()
        val hasV4Default = original.any { !it.isV6 && it.prefix == 0 }
        val hasV6Default = original.any { it.isV6 && it.prefix == 0 }
        if (hasV4Default && !hasV6Default) {
            Log.w(TAG, "Peer $peerLabel: 0.0.0.0/0 without ::/0, adding ::/0 to prevent IPv6 leak")
            guarded.add(CidrMath.Cidr.parse("::/0"))
        }
        if (hasV6Default && !hasV4Default) {
            Log.w(TAG, "Peer $peerLabel: ::/0 without 0.0.0.0/0, adding 0.0.0.0/0 to prevent IPv4 leak")
            guarded.add(CidrMath.Cidr.parse("0.0.0.0/0"))
        }

        val augmented = if (isFirstPeer && includedCidrs.isNotEmpty()) {
            guarded + includedCidrs
        } else {
            guarded.toList()
        }

        return CidrMath.subtractFromList(augmented, excludedCidrs)
    }

    private class WgTunnel(private val profileName: String) : Tunnel {
        override fun getName(): String = profileName.replace(Regex("[^a-zA-Z0-9_=+.-]"), "_")
        override fun onStateChange(newState: Tunnel.State) {}
    }
}
