package net.swlr.vpnmaster.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.swlr.vpnmaster.BuildConfig
import net.swlr.vpnmaster.data.repository.ProfileRepository
import net.swlr.vpnmaster.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

sealed class BackupResult {
    object Success : BackupResult()
    data class Error(val message: String) : BackupResult()
}

sealed class RestoreResult {
    data class Success(val profilesImported: Int) : RestoreResult()
    object InvalidPassword : RestoreResult()
    data class Error(val message: String) : RestoreResult()
}

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val json: Json
) {

    suspend fun export(uri: Uri, password: CharArray): BackupResult {
        return try {
            val payload = BackupPayload(
                profiles = profileRepository.getAllProfilesSnapshot(),
                settings = currentSettingsSnapshot()
            )
            val plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)

            val salt = BackupCrypto.randomSalt()
            val iv = BackupCrypto.randomIv()
            val key = BackupCrypto.deriveKey(password, salt)
            val ciphertext = BackupCrypto.encrypt(plaintext, key, iv)

            val envelope = BackupEnvelope(
                appVersionName = BuildConfig.VERSION_NAME,
                createdAtEpochMillis = System.currentTimeMillis(),
                kdf = KdfParams(saltBase64 = base64(salt)),
                cipher = CipherParams(ivBase64 = base64(iv)),
                ciphertextBase64 = base64(ciphertext)
            )
            val envelopeJson = json.encodeToString(envelope)

            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(envelopeJson.toByteArray(Charsets.UTF_8))
            } ?: return BackupResult.Error("Could not open file for writing")

            BackupResult.Success
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Export failed", e)
            BackupResult.Error(e.message ?: "Export failed")
        }
    }

    suspend fun restore(uri: Uri, password: CharArray): RestoreResult {
        val envelope: BackupEnvelope = try {
            val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return RestoreResult.Error("Could not open file")
            json.decodeFromString(BackupEnvelope.serializer(), raw.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Envelope parse failed", e)
            return RestoreResult.Error("Not a valid VPN Master backup file")
        }

        if (envelope.magic != BackupEnvelope.MAGIC) {
            return RestoreResult.Error("Not a valid VPN Master backup file")
        }
        if (envelope.formatVersion > BackupEnvelope.CURRENT_FORMAT_VERSION) {
            return RestoreResult.Error("Backup was created by a newer app version. Update VPN Master to import.")
        }

        val payload: BackupPayload = try {
            val salt = decodeBase64(envelope.kdf.saltBase64)
            val iv = decodeBase64(envelope.cipher.ivBase64)
            val ciphertext = decodeBase64(envelope.ciphertextBase64)
            val key = BackupCrypto.deriveKey(password, salt, envelope.kdf.iterations)
            val plaintext = BackupCrypto.decrypt(ciphertext, key, iv)
            json.decodeFromString(BackupPayload.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (e: BackupCrypto.InvalidPasswordException) {
            return RestoreResult.InvalidPassword
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Decrypt/parse failed", e)
            return RestoreResult.Error("Backup file is corrupted")
        }

        return try {
            profileRepository.replaceAllProfiles(payload.profiles)
            applySettings(payload.settings)
            RestoreResult.Success(profilesImported = payload.profiles.size)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Apply failed", e)
            RestoreResult.Error("Could not save imported data")
        }
    }

    private suspend fun currentSettingsSnapshot(): BackupSettings {
        return BackupSettings(
            watchdogEnabled = settingsRepository.watchdogEnabled.first(),
            watchdogIntervalSeconds = settingsRepository.watchdogIntervalSeconds.first(),
            watchdogProbeMaxFailures = settingsRepository.watchdogProbeMaxFailures.first(),
            autoConnectOnBoot = settingsRepository.autoConnectOnBoot.first(),
            diagnosticLoggingEnabled = settingsRepository.diagnosticLoggingEnabled.first()
        )
    }

    // Settings absent from the backup (null) are left untouched so newer keys
    // added after the backup was taken keep their current values.
    private suspend fun applySettings(s: BackupSettings) {
        s.watchdogEnabled?.let { settingsRepository.setWatchdogEnabled(it) }
        s.watchdogIntervalSeconds?.let { settingsRepository.setWatchdogIntervalSeconds(it) }
        s.watchdogProbeMaxFailures?.let { settingsRepository.setWatchdogProbeMaxFailures(it) }
        s.autoConnectOnBoot?.let { settingsRepository.setAutoConnectOnBoot(it) }
        s.diagnosticLoggingEnabled?.let { settingsRepository.setDiagnosticLoggingEnabled(it) }
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decodeBase64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val TAG = "BackupRepository"
        const val FILE_EXTENSION = "vmbackup"
        const val MIME_TYPE = "application/octet-stream"
    }
}
