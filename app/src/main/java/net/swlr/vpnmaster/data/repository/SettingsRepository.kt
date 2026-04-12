package net.swlr.vpnmaster.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vpnmaster_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val WATCHDOG_ENABLED = booleanPreferencesKey("watchdog_enabled")
        val WATCHDOG_INTERVAL_SECONDS = intPreferencesKey("watchdog_interval_seconds")
        val AUTO_CONNECT_ON_BOOT = booleanPreferencesKey("auto_connect_on_boot")
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val LAST_CONNECTED_PROFILE_ID = stringPreferencesKey("last_connected_profile_id")
    }

    val watchdogEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.WATCHDOG_ENABLED] ?: true
    }

    val watchdogIntervalSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.WATCHDOG_INTERVAL_SECONDS] ?: 30
    }

    val autoConnectOnBoot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_CONNECT_ON_BOOT] ?: false
    }

    val activeProfileId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_PROFILE_ID]
    }

    val lastConnectedProfileId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_CONNECTED_PROFILE_ID]
    }

    suspend fun setWatchdogEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WATCHDOG_ENABLED] = enabled }
    }

    suspend fun setWatchdogIntervalSeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.WATCHDOG_INTERVAL_SECONDS] = seconds }
    }

    suspend fun setAutoConnectOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_CONNECT_ON_BOOT] = enabled }
    }

    suspend fun setActiveProfileId(profileId: String?) {
        context.dataStore.edit {
            if (profileId != null) {
                it[Keys.ACTIVE_PROFILE_ID] = profileId
            } else {
                it.remove(Keys.ACTIVE_PROFILE_ID)
            }
        }
    }

    suspend fun setLastConnectedProfileId(profileId: String?) {
        context.dataStore.edit {
            if (profileId != null) {
                it[Keys.LAST_CONNECTED_PROFILE_ID] = profileId
            } else {
                it.remove(Keys.LAST_CONNECTED_PROFILE_ID)
            }
        }
    }
}
