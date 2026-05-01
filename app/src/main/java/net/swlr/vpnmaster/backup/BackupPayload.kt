package net.swlr.vpnmaster.backup

import kotlinx.serialization.Serializable
import net.swlr.vpnmaster.data.model.VpnProfile

/**
 * Outer file format. Header fields are plaintext so we can pick the right KDF/cipher
 * before asking for the password; the actual data lives inside [ciphertextBase64].
 *
 * `formatVersion` lets future builds reject or migrate older envelopes.
 */
@Serializable
data class BackupEnvelope(
    val magic: String = MAGIC,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val appVersionName: String,
    val createdAtEpochMillis: Long,
    val kdf: KdfParams,
    val cipher: CipherParams,
    val ciphertextBase64: String
) {
    companion object {
        const val MAGIC = "VPNMASTER_BACKUP"
        const val CURRENT_FORMAT_VERSION = 1
    }
}

@Serializable
data class KdfParams(
    val algorithm: String = BackupCrypto.PBKDF2_ALGORITHM,
    val iterations: Int = BackupCrypto.PBKDF2_ITERATIONS,
    val saltBase64: String,
    val keyLengthBits: Int = BackupCrypto.KEY_LENGTH_BITS
)

@Serializable
data class CipherParams(
    val algorithm: String = "AES/GCM/NoPadding",
    val ivBase64: String,
    val tagLengthBits: Int = BackupCrypto.GCM_TAG_LENGTH_BITS
)

/**
 * Decrypted payload. All fields are nullable on settings so import can leave a
 * key untouched when the backup didn't carry it (lets older backups restore
 * cleanly into newer builds with added settings).
 */
@Serializable
data class BackupPayload(
    val profiles: List<VpnProfile>,
    val settings: BackupSettings
)

@Serializable
data class BackupSettings(
    val watchdogEnabled: Boolean? = null,
    val watchdogIntervalSeconds: Int? = null,
    val watchdogProbeMaxFailures: Int? = null,
    val autoConnectOnBoot: Boolean? = null,
    val diagnosticLoggingEnabled: Boolean? = null
)
