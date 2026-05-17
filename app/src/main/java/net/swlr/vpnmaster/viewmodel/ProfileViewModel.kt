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
import net.swlr.vpnmaster.data.model.VpnProfile
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

            val config = profile.wireGuardConfig
            if (config == null || config.privateKey.isBlank()) {
                _uiMessage.value = "WireGuard private key is required"
                return@launch
            }
            if (config.peers.isEmpty() || config.peers.first().publicKey.isBlank()) {
                _uiMessage.value = "At least one peer with a public key is required"
                return@launch
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

    fun clearDefault() {
        viewModelScope.launch {
            profileRepository.clearDefaultProfile()
            _uiMessage.value = "Default cleared"
        }
    }

    fun duplicateProfile(profile: VpnProfile) {
        viewModelScope.launch {
            // New random id + isDefault=false so the duplicate doesn't usurp the
            // original. Name suffix avoids collision in the list.
            val copy = profile.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = profile.name + " (copy)",
                isDefault = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            profileRepository.saveProfile(copy)
            _uiMessage.value = "Profile duplicated"
        }
    }

    /** Returns the profile rendered as wg-quick `.conf` text, or null if absent. */
    fun exportProfileText(profile: VpnProfile): String =
        configImporter.toWgQuickString(profile)

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            when (val result = configImporter.importFromUri(uri)) {
                is ImportResult.Success -> {
                    val imported = result.profile
                    val current = _editingProfile.value
                    _editingProfile.value = if (current != null) {
                        current.copy(
                            name = if (current.name.isBlank()) imported.name else current.name,
                            serverAddress = imported.serverAddress,
                            wireGuardConfig = imported.wireGuardConfig
                        )
                    } else {
                        imported
                    }
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
                val imported = result.profile
                val current = _editingProfile.value
                _editingProfile.value = if (current != null) {
                    current.copy(
                        name = if (current.name.isBlank()) imported.name else current.name,
                        serverAddress = imported.serverAddress,
                        wireGuardConfig = imported.wireGuardConfig
                    )
                } else {
                    imported
                }
                _uiMessage.value = "QR code imported"
            }
            is ImportResult.Error -> {
                _uiMessage.value = result.message
            }
        }
    }

    fun clearMessage() {
        _uiMessage.value = null
    }

    fun postUiMessage(message: String) {
        _uiMessage.value = message
    }
}
