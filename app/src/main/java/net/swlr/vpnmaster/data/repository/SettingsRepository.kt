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
    companion object {
        // Watchdog interval bounds. Lower bound prevents a tight reconnect loop
        // if a malformed backup or future caller writes 0 / negative / huge values;
        // upper bound matches the Settings slider's max so detection latency stays
        // bounded. Coerced on both read and write so legacy/restored values are
        // sanitized even if they bypass the setter.
        const val WATCHDOG_INTERVAL_MIN = 10
        const val WATCHDOG_INTERVAL_MAX = 120
        const val WATCHDOG_INTERVAL_DEFAULT = 30
    }

    private object Keys {
        val WATCHDOG_ENABLED = booleanPreferencesKey("watchdog_enabled")
        val WATCHDOG_INTERVAL_SECONDS = intPreferencesKey("watchdog_interval_seconds")
        val WATCHDOG_PROBE_MAX_FAILURES = intPreferencesKey("watchdog_probe_max_failures")
        val AUTO_CONNECT_ON_BOOT = booleanPreferencesKey("auto_connect_on_boot")
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val LAST_CONNECTED_PROFILE_ID = stringPreferencesKey("last_connected_profile_id")
        val DIAGNOSTIC_LOGGING_ENABLED = booleanPreferencesKey("diagnostic_logging_enabled")
        val TASKER_AUTH_REQUIRED = booleanPreferencesKey("tasker_auth_required")
        val TASKER_AUTH_TOKEN = stringPreferencesKey("tasker_auth_token")
    }

    val watchdogEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.WATCHDOG_ENABLED] ?: true
    }

    val watchdogIntervalSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[Keys.WATCHDOG_INTERVAL_SECONDS] ?: WATCHDOG_INTERVAL_DEFAULT)
            .coerceIn(WATCHDOG_INTERVAL_MIN, WATCHDOG_INTERVAL_MAX)
    }

    val watchdogProbeMaxFailures: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[Keys.WATCHDOG_PROBE_MAX_FAILURES] ?: 3).coerceIn(1, 10)
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

    val diagnosticLoggingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DIAGNOSTIC_LOGGING_ENABLED] ?: true
    }

    // Default off so existing Tasker setups keep working after upgrade. The
    // Settings UI surfaces a recommendation to enable. When enabled, the
    // exported TaskerReceiver requires a matching token extra on every command.
    val taskerAuthRequired: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.TASKER_AUTH_REQUIRED] ?: false
    }

    // Lazily generate on first read so a freshly installed app already has a
    // token to display, even if the user never visits the Tasker section.
    // Token is opaque to the user (copy-paste); regeneration is a setter.
    val taskerAuthToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.TASKER_AUTH_TOKEN] ?: run {
            val generated = generateTaskerToken()
            context.dataStore.edit { it[Keys.TASKER_AUTH_TOKEN] = generated }
            generated
        }
    }

    suspend fun setWatchdogEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WATCHDOG_ENABLED] = enabled }
    }

    suspend fun setWatchdogIntervalSeconds(seconds: Int) {
        context.dataStore.edit {
            it[Keys.WATCHDOG_INTERVAL_SECONDS] =
                seconds.coerceIn(WATCHDOG_INTERVAL_MIN, WATCHDOG_INTERVAL_MAX)
        }
    }

    suspend fun setWatchdogProbeMaxFailures(count: Int) {
        context.dataStore.edit { it[Keys.WATCHDOG_PROBE_MAX_FAILURES] = count.coerceIn(1, 10) }
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

    suspend fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DIAGNOSTIC_LOGGING_ENABLED] = enabled }
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

    suspend fun setTaskerAuthRequired(required: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TASKER_AUTH_REQUIRED] = required
            // Materialize the token on first enable so the Settings UI can
            // display it immediately rather than briefly showing an empty
            // field while the lazy reader populates it.
            if (required && prefs[Keys.TASKER_AUTH_TOKEN] == null) {
                prefs[Keys.TASKER_AUTH_TOKEN] = generateTaskerToken()
            }
        }
    }

    suspend fun regenerateTaskerToken(): String {
        val fresh = generateTaskerToken()
        context.dataStore.edit { it[Keys.TASKER_AUTH_TOKEN] = fresh }
        return fresh
    }

    private fun generateTaskerToken(): String {
        // 128 bits of entropy is far more than necessary against a local
        // attacker who has to send broadcasts and read whatever response is
        // exposed (none of the responses are exported). Encoded URL-safe and
        // padding-stripped so users can paste into Tasker without escaping.
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }
}
