package net.swlr.vpnmaster.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.swlr.vpnmaster.config.ConfigImporter
import net.swlr.vpnmaster.config.ImportResult
import net.swlr.vpnmaster.data.model.IkeV2Config
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.data.model.VpnType
import net.swlr.vpnmaster.data.model.WireGuardConfig
import net.swlr.vpnmaster.data.repository.ProfileRepository
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val configImporter: ConfigImporter
) : ViewModel() {

    val profiles = profileRepository.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _editingProfile = MutableStateFlow<VpnProfile?>(null)
    val editingProfile: StateFlow<VpnProfile?> = _editingProfile.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    private val _profileSaved = MutableSharedFlow<Unit>()
    val profileSaved: SharedFlow<Unit> = _profileSaved.asSharedFlow()

    fun loadProfile(profileId: String?) {
        if (profileId == null) {
            _editingProfile.value = VpnProfile(
                wireGuardConfig = WireGuardConfig()
            )
            return
        }
        viewModelScope.launch {
            _editingProfile.value = profileRepository.getProfileById(profileId)
        }
    }

    fun updateEditingProfile(profile: VpnProfile) {
        _editingProfile.value = profile
    }

    fun saveProfile() {
        val profile = _editingProfile.value ?: return
        viewModelScope.launch {
            if (profile.name.isBlank()) {
                _uiMessage.value = "Profile name is required"
                return@launch
            }
            if (profile.serverAddress.isBlank()) {
                _uiMessage.value = "Server address is required"
                return@launch
            }

            when (profile.type) {
                VpnType.WIREGUARD -> {
                    val config = profile.wireGuardConfig
                    if (config == null || config.privateKey.isBlank()) {
                        _uiMessage.value = "WireGuard private key is required"
                        return@launch
                    }
                    if (config.peers.isEmpty() || config.peers.first().publicKey.isBlank()) {
                        _uiMessage.value = "At least one peer with a public key is required"
                        return@launch
                    }
                }
                VpnType.IKEV2 -> {
                    val config = profile.ikeV2Config
                    if (config == null) {
                        _uiMessage.value = "IKEv2 configuration is required"
                        return@launch
                    }
                }
            }

            try {
                val existing = profileRepository.getProfileById(profile.id)
                if (existing != null) {
                    profileRepository.updateProfile(profile)
                } else {
                    profileRepository.saveProfile(profile)
                }
                _uiMessage.value = "Profile saved"
                _profileSaved.emit(Unit)
            } catch (e: Exception) {
                _uiMessage.value = "Failed to save: ${e.message}"
            }
        }
    }

    fun deleteProfile(profile: VpnProfile) {
        viewModelScope.launch {
            profileRepository.deleteProfile(profile)
            _uiMessage.value = "Profile deleted"
        }
    }

    fun setDefault(profileId: String) {
        viewModelScope.launch {
            profileRepository.setDefaultProfile(profileId)
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            when (val result = configImporter.importFromUri(uri)) {
                is ImportResult.Success -> {
                    _editingProfile.value = result.profile
                    _uiMessage.value = "Configuration imported"
                }
                is ImportResult.Error -> {
                    _uiMessage.value = result.message
                }
            }
        }
    }

    fun importFromQrCode(data: String) {
        when (val result = configImporter.parseQrCode(data)) {
            is ImportResult.Success -> {
                _editingProfile.value = result.profile
                _uiMessage.value = "QR code imported"
            }
            is ImportResult.Error -> {
                _uiMessage.value = result.message
            }
        }
    }

    fun changeVpnType(type: VpnType) {
        val current = _editingProfile.value ?: return
        _editingProfile.value = current.copy(
            type = type,
            wireGuardConfig = if (type == VpnType.WIREGUARD) current.wireGuardConfig ?: WireGuardConfig() else current.wireGuardConfig,
            ikeV2Config = if (type == VpnType.IKEV2) current.ikeV2Config ?: IkeV2Config() else current.ikeV2Config
        )
    }

    fun clearMessage() {
        _uiMessage.value = null
    }
}
