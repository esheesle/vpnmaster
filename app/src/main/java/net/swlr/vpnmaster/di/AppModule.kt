package net.swlr.vpnmaster.di

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import net.sqlcipher.database.SupportFactory
import net.swlr.vpnmaster.data.db.VpnDatabase
import net.swlr.vpnmaster.data.db.VpnProfileDao
import java.security.SecureRandom
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val TAG = "AppModule"
    private const val SECURE_PREFS_NAME = "vpnmaster_secure_prefs"
    private const val DB_KEY_PREF = "db_key_v2"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VpnDatabase {
        val passphrase = getOrCreateDatabaseKey(context)
        val factory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            VpnDatabase::class.java,
            "vpnmaster.db"
        )
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideProfileDao(database: VpnDatabase): VpnProfileDao {
        return database.profileDao()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    /**
     * Generate or retrieve a 32-byte database encryption key.
     *
     * The key is stored as a Base64 string inside EncryptedSharedPreferences,
     * which encrypts the value at rest using AES-256-GCM backed by AndroidKeyStore.
     * We decode the Base64 back to raw bytes before passing to SQLCipher.
     */
    private fun getOrCreateDatabaseKey(context: Context): ByteArray {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

        val prefs = EncryptedSharedPreferences.create(
            SECURE_PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val existingKey = prefs.getString(DB_KEY_PREF, null)
        if (existingKey != null) {
            return Base64.decode(existingKey, Base64.NO_WRAP)
        }

        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded = Base64.encodeToString(key, Base64.NO_WRAP)
        prefs.edit().putString(DB_KEY_PREF, encoded).apply()
        Log.i(TAG, "Generated new database encryption key")
        return key
    }
}
