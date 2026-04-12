package net.swlr.vpnmaster.vpn

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.data.model.VpnType
import net.swlr.vpnmaster.data.repository.SettingsRepository
import net.swlr.vpnmaster.vpn.ikev2.StrongSwanBackend
import net.swlr.vpnmaster.vpn.wireguard.WireGuardBackend
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnOrchestrator @Inject constructor(
    private val wireGuardBackend: WireGuardBackend,
    private val strongSwanBackend: StrongSwanBackend,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "VpnOrchestrator"
    }

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _statistics = MutableStateFlow(VpnStatistics())
    val statistics: StateFlow<VpnStatistics> = _statistics.asStateFlow()

    private val _activeProfile = MutableStateFlow<VpnProfile?>(null)
    val activeProfile: StateFlow<VpnProfile?> = _activeProfile.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var activeBackend: VpnBackend? = null
    private val connectionMutex = Mutex()

    private fun getBackend(type: VpnType): VpnBackend = when (type) {
        VpnType.WIREGUARD -> wireGuardBackend
        VpnType.IKEV2 -> strongSwanBackend
    }

    suspend fun connect(profile: VpnProfile) = connectionMutex.withLock {
        // Tear down any existing connection first
        if (_state.value == VpnState.CONNECTED || _state.value == VpnState.CONNECTING) {
            disconnectInternal()
        }

        _state.value = VpnState.CONNECTING
        _activeProfile.value = profile
        _errorMessage.value = null

        val backend = getBackend(profile.type)
        activeBackend = backend

        try {
            backend.connect(profile)
            _state.value = VpnState.CONNECTED
            settingsRepository.setActiveProfileId(profile.id)
            settingsRepository.setLastConnectedProfileId(profile.id)
            Log.i(TAG, "Connected to ${profile.name} (${profile.displayType})")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            _state.value = VpnState.ERROR
            _errorMessage.value = e.message ?: "Connection failed"
            _activeProfile.value = null
            activeBackend = null
        }
    }

    suspend fun disconnect() = connectionMutex.withLock {
        disconnectInternal()
    }

    private suspend fun disconnectInternal() {
        _state.value = VpnState.DISCONNECTING
        try {
            activeBackend?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect: ${e.message}", e)
        } finally {
            _state.value = VpnState.DISCONNECTED
            _activeProfile.value = null
            activeBackend = null
            _statistics.value = VpnStatistics()
            settingsRepository.setActiveProfileId(null)
        }
    }

    suspend fun reconnect() = connectionMutex.withLock {
        val profile = _activeProfile.value ?: return@withLock
        Log.i(TAG, "Reconnecting to ${profile.name}")
        _state.value = VpnState.RECONNECTING

        try {
            activeBackend?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error during reconnect teardown: ${e.message}")
        }

        try {
            val backend = getBackend(profile.type)
            activeBackend = backend
            backend.connect(profile)
            _state.value = VpnState.CONNECTED
            Log.i(TAG, "Reconnected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Reconnection failed: ${e.message}", e)
            _state.value = VpnState.ERROR
            _errorMessage.value = e.message ?: "Reconnection failed"
        }
    }

    suspend fun refreshStatistics() {
        val backend = activeBackend ?: return
        if (_state.value == VpnState.CONNECTED) {
            try {
                _statistics.value = backend.getStatistics()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh statistics: ${e.message}")
            }
        }
    }

    suspend fun checkHealth(): Boolean {
        val backend = activeBackend ?: return false
        return try {
            val backendState = backend.getState()
            if (backendState != _state.value) {
                Log.d(TAG, "State mismatch: orchestrator=${_state.value}, backend=$backendState")
                _state.value = backendState
            }
            backendState == VpnState.CONNECTED
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed: ${e.message}")
            false
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
