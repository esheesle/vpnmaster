package net.swlr.vpnmaster.viewmodel

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.data.repository.ProfileRepository
import net.swlr.vpnmaster.data.repository.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository
) : ViewModel() {

    val profiles: StateFlow<List<VpnProfile>> = profileRepository.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchdogEnabled: StateFlow<Boolean> = settingsRepository.watchdogEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val watchdogIntervalSeconds: StateFlow<Int> = settingsRepository.watchdogIntervalSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 30)

    val watchdogProbeMaxFailures: StateFlow<Int> = settingsRepository.watchdogProbeMaxFailures
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    val autoConnectOnBoot: StateFlow<Boolean> = settingsRepository.autoConnectOnBoot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val diagnosticLoggingEnabled: StateFlow<Boolean> = settingsRepository.diagnosticLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setWatchdogEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWatchdogEnabled(enabled) }
    }

    fun setWatchdogInterval(seconds: Int) {
        viewModelScope.launch { settingsRepository.setWatchdogIntervalSeconds(seconds) }
    }

    fun setWatchdogProbeMaxFailures(count: Int) {
        viewModelScope.launch { settingsRepository.setWatchdogProbeMaxFailures(count) }
    }

    fun setAutoConnectOnBoot(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoConnectOnBoot(enabled) }
    }

    fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDiagnosticLoggingEnabled(enabled) }
    }

    fun openVpnSettings() {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
