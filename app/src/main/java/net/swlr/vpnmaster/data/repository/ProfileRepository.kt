package net.swlr.vpnmaster.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.swlr.vpnmaster.data.db.VpnProfileDao
import net.swlr.vpnmaster.data.db.VpnProfileEntity
import net.swlr.vpnmaster.data.model.IkeV2Config
import net.swlr.vpnmaster.data.model.SplitTunnelConfig
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.data.model.VpnType
import net.swlr.vpnmaster.data.model.WireGuardConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: VpnProfileDao,
    private val json: Json
) {
    companion object {
        private const val TAG = "ProfileRepository"
    }

    fun getAllProfiles(): Flow<List<VpnProfile>> {
        return profileDao.getAllProfiles().map { entities ->
            entities.mapNotNull { entity ->
                try {
                    entity.toDomain()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deserialize profile ${entity.id}: ${e.message}", e)
                    null
                }
            }
        }
    }

    fun observeDefaultProfile(): Flow<VpnProfile?> {
        return profileDao.observeDefaultProfile().map { entity ->
            try {
                entity?.toDomain()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize default profile: ${e.message}", e)
                null
            }
        }
    }

    suspend fun getProfileById(id: String): VpnProfile? {
        return try {
            profileDao.getProfileById(id)?.toDomain()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load profile $id: ${e.message}", e)
            null
        }
    }

    suspend fun getDefaultProfile(): VpnProfile? {
        return try {
            profileDao.getDefaultProfile()?.toDomain()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load default profile: ${e.message}", e)
            null
        }
    }

    suspend fun saveProfile(profile: VpnProfile) {
        profileDao.insertProfile(profile.toEntity())
    }

    suspend fun updateProfile(profile: VpnProfile) {
        profileDao.updateProfile(profile.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    suspend fun deleteProfile(profile: VpnProfile) {
        profileDao.deleteProfile(profile.toEntity())
    }

    suspend fun setDefaultProfile(profileId: String) {
        profileDao.setDefault(profileId)
    }

    private fun VpnProfileEntity.toDomain(): VpnProfile {
        val vpnType = VpnType.valueOf(type)
        val configStr = encryptedConfig

        val wgConfig = if (vpnType == VpnType.WIREGUARD && configStr.isNotBlank()) {
            try {
                json.decodeFromString<WireGuardConfig>(configStr)
            } catch (e: Exception) {
                Log.w(TAG, "Corrupt WireGuard config in profile $id, will require reconfiguration", e)
                null
            }
        } else null

        val ikeConfig = if (vpnType == VpnType.IKEV2 && configStr.isNotBlank()) {
            try {
                json.decodeFromString<IkeV2Config>(configStr)
            } catch (e: Exception) {
                Log.w(TAG, "Corrupt IKEv2 config in profile $id, will require reconfiguration", e)
                null
            }
        } else null

        val splitConfig = try {
            json.decodeFromString<SplitTunnelConfig>(splitTunnelConfig)
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt split tunnel config in profile $id, using defaults", e)
            SplitTunnelConfig()
        }

        return VpnProfile(
            id = id,
            name = name,
            type = vpnType,
            serverAddress = serverAddress,
            wireGuardConfig = wgConfig,
            ikeV2Config = ikeConfig,
            splitTunnelConfig = splitConfig,
            isDefault = isDefault,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun VpnProfile.toEntity(): VpnProfileEntity {
        val configStr = when (type) {
            VpnType.WIREGUARD -> wireGuardConfig?.let { json.encodeToString(it) } ?: ""
            VpnType.IKEV2 -> ikeV2Config?.let { json.encodeToString(it) } ?: ""
        }

        return VpnProfileEntity(
            id = id,
            name = name,
            type = type.name,
            serverAddress = serverAddress,
            encryptedConfig = configStr,
            splitTunnelConfig = json.encodeToString(splitTunnelConfig),
            isDefault = isDefault,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
