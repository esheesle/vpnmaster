package net.swlr.vpnmaster.viewmodel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.data.repository.ProfileRepository
import net.swlr.vpnmaster.service.VpnMasterService
import net.swlr.vpnmaster.vpn.VpnOrchestrator
import net.swlr.vpnmaster.vpn.VpnState
import net.swlr.vpnmaster.vpn.VpnStatistics
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: VpnOrchestrator,
    private val profileRepository: ProfileRepository
) : ViewModel() {

    val vpnState: StateFlow<VpnState> = orchestrator.state
    val activeProfile: StateFlow<VpnProfile?> = orchestrator.activeProfile
    val errorMessage: StateFlow<String?> = orchestrator.errorMessage

    // Stats poll only fires while a UI subscriber is collecting AND the tunnel is up.
    // Without WhileSubscribed, the previous init{} loop polled GoBackend.getStatistics
    // every 2s for the entire activity lifetime — including with the screen off — which
    // was the dominant battery cost when connected.
    val statistics: StateFlow<VpnStatistics> = orchestrator.state
        .flatMapLatest { state ->
            if (state == VpnState.CONNECTED) {
                flow {
                    while (true) {
                        orchestrator.refreshStatistics()
                        emit(orchestrator.statistics.value)
                        delay(2_000)
                    }
                }
            } else {
                flowOf(VpnStatistics())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VpnStatistics())

    val profiles = profileRepository.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val defaultProfile = profileRepository.observeDefaultProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedProfile = MutableStateFlow<VpnProfile?>(null)
    val selectedProfile: StateFlow<VpnProfile?> = _selectedProfile.asStateFlow()

    fun selectProfile(profile: VpnProfile) {
        _selectedProfile.value = profile
    }

    fun connect(profile: VpnProfile? = null) {
        val targetProfile = profile ?: _selectedProfile.value ?: defaultProfile.value ?: return

        val intent = Intent(context, VpnMasterService::class.java).apply {
            action = VpnMasterService.ACTION_CONNECT
            putExtra(VpnMasterService.EXTRA_PROFILE_ID, targetProfile.id)
        }
        context.startForegroundService(intent)
    }

    fun disconnect() {
        val intent = Intent(context, VpnMasterService::class.java).apply {
            action = VpnMasterService.ACTION_DISCONNECT
        }
        context.startForegroundService(intent)
    }

    fun clearError() {
        orchestrator.clearError()
    }

    fun getVpnPermissionIntent(): Intent? {
        return VpnService.prepare(context)
    }
}
