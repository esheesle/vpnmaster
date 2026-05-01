package net.swlr.vpnmaster.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with PBKDF2-HMAC-SHA256 key derivation, JDK only.
 *
 * Tag failure (wrong password, tampered ciphertext, truncated file) surfaces as
 * [InvalidPasswordException] so callers can show a single user-facing error.
 */
object BackupCrypto {

    const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val PBKDF2_ITERATIONS = 600_000
    const val KEY_LENGTH_BITS = 256
    const val SALT_LENGTH_BYTES = 16
    const val IV_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128

    class InvalidPasswordException : Exception("Incorrect password or corrupted backup file")

    fun randomSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    fun randomIv(): ByteArray = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plaintext: ByteArray, key: SecretKeySpec, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(plaintext)
    }

    fun decrypt(ciphertext: ByteArray, key: SecretKeySpec, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw InvalidPasswordException()
        } catch (e: javax.crypto.BadPaddingException) {
            throw InvalidPasswordException()
        }
    }
}
