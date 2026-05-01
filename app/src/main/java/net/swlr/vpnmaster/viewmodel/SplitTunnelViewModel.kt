package net.swlr.vpnmaster.viewmodel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.swlr.vpnmaster.data.model.SplitTunnelConfig
import net.swlr.vpnmaster.data.model.SplitTunnelMode
import net.swlr.vpnmaster.data.repository.ProfileRepository
import javax.inject.Inject

/**
 * Lightweight handle to an installed app. Drawables are NOT held here — loading
 * one drawable per installed app eagerly held tens of MB of bitmap data and
 * pinned PackageManager Resources references. Icons are now loaded lazily by
 * the composable as items scroll into view.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isSelected: Boolean = false
)

@HiltViewModel
class SplitTunnelViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository
) : ViewModel() {

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _splitConfig = MutableStateFlow(SplitTunnelConfig())
    val splitConfig: StateFlow<SplitTunnelConfig> = _splitConfig.asStateFlow()

    private val _showSystemApps = MutableStateFlow(false)
    val showSystemApps: StateFlow<Boolean> = _showSystemApps.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    // True when the in-memory _splitConfig differs from what's on disk. The
    // composable observes this to put up a "Discard changes?" guard on back-press.
    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    private var profileId: String = ""
    private var savedConfig: SplitTunnelConfig = SplitTunnelConfig()
    private var allApps: List<AppInfo> = emptyList()

    fun loadProfile(profileId: String) {
        this.profileId = profileId
        viewModelScope.launch {
            val profile = profileRepository.getProfileById(profileId) ?: return@launch
            _splitConfig.value = profile.splitTunnelConfig
            savedConfig = profile.splitTunnelConfig
            _isDirty.value = false

            loadInstalledApps(profile.splitTunnelConfig.appPackages)
        }
    }

    private suspend fun loadInstalledApps(selectedPackages: Set<String>) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (_: Exception) {
                emptyList()
            }

            allApps = packages.map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(pm).toString(),
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isSelected = appInfo.packageName in selectedPackages
                )
            }.sortedWith(compareBy({ !it.isSelected }, { it.label.lowercase() }))

            filterApps()
        }
    }

    fun setMode(mode: SplitTunnelMode) {
        _splitConfig.value = _splitConfig.value.copy(mode = mode)
        markDirty()
    }

    fun toggleApp(packageName: String) {
        val current = _splitConfig.value
        val newPackages = current.appPackages.toMutableSet()
        if (packageName in newPackages) {
            newPackages.remove(packageName)
        } else {
            newPackages.add(packageName)
        }
        _splitConfig.value = current.copy(appPackages = newPackages)

        allApps = allApps.map {
            if (it.packageName == packageName) it.copy(isSelected = !it.isSelected) else it
        }
        filterApps()
        markDirty()
    }

    fun setShowSystemApps(show: Boolean) {
        _showSystemApps.value = show
        filterApps()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        filterApps()
    }

    fun updateExcludedRoutes(routes: String) {
        val routeList = routes.lines().map { it.trim() }.filter { it.isNotBlank() }
        _splitConfig.value = _splitConfig.value.copy(excludedRoutes = routeList)
        markDirty()
    }

    fun updateIncludedRoutes(routes: String) {
        val routeList = routes.lines().map { it.trim() }.filter { it.isNotBlank() }
        _splitConfig.value = _splitConfig.value.copy(includedRoutes = routeList)
        markDirty()
    }

    fun save() {
        viewModelScope.launch {
            val profile = profileRepository.getProfileById(profileId) ?: return@launch
            val updated = profile.copy(splitTunnelConfig = _splitConfig.value)
            profileRepository.updateProfile(updated)
            savedConfig = _splitConfig.value
            _isDirty.value = false
            _uiMessage.value = "Split tunnel settings saved"
        }
    }

    fun clearMessage() {
        _uiMessage.value = null
    }

    private fun markDirty() {
        _isDirty.value = _splitConfig.value != savedConfig
    }

    private fun filterApps() {
        val query = _searchQuery.value.lowercase()
        val showSystem = _showSystemApps.value

        _apps.value = allApps.filter { app ->
            (showSystem || !app.isSystem) &&
                    (query.isBlank() || app.label.lowercase().contains(query) ||
                            app.packageName.lowercase().contains(query))
        }
    }
}
