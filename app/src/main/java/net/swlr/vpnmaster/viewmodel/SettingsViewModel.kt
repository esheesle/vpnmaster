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
import net.swlr.vpnmaster.data.repository.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val watchdogEnabled: StateFlow<Boolean> = settingsRepository.watchdogEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val watchdogIntervalSeconds: StateFlow<Int> = settingsRepository.watchdogIntervalSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 30)

    val autoConnectOnBoot: StateFlow<Boolean> = settingsRepository.autoConnectOnBoot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setWatchdogEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWatchdogEnabled(enabled) }
    }

    fun setWatchdogInterval(seconds: Int) {
        viewModelScope.launch { settingsRepository.setWatchdogIntervalSeconds(seconds) }
    }

    fun setAutoConnectOnBoot(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoConnectOnBoot(enabled) }
    }

    fun openVpnSettings() {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
