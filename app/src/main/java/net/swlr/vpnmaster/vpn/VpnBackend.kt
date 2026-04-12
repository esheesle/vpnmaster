package net.swlr.vpnmaster.vpn

import net.swlr.vpnmaster.data.model.VpnProfile

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR,
    RECONNECTING
}

data class VpnStatistics(
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0,
    val connectedSince: Long = 0
) {
    val durationMillis: Long
        get() = if (connectedSince > 0) System.currentTimeMillis() - connectedSince else 0
}

interface VpnBackend {
    suspend fun connect(profile: VpnProfile)
    suspend fun disconnect()
    suspend fun getState(): VpnState
    suspend fun getStatistics(): VpnStatistics
}
