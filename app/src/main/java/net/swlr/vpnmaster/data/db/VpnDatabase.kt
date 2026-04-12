package net.swlr.vpnmaster.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vpn_profiles")
data class VpnProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    @ColumnInfo(name = "server_address") val serverAddress: String,
    @ColumnInfo(name = "encrypted_config") val encryptedConfig: String,
    @ColumnInfo(name = "split_tunnel_config") val splitTunnelConfig: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Dao
abstract class VpnProfileDao {
    @Query("SELECT * FROM vpn_profiles ORDER BY name ASC")
    abstract fun getAllProfiles(): Flow<List<VpnProfileEntity>>

    @Query("SELECT * FROM vpn_profiles WHERE id = :id")
    abstract suspend fun getProfileById(id: String): VpnProfileEntity?

    @Query("SELECT * FROM vpn_profiles WHERE is_default = 1 LIMIT 1")
    abstract suspend fun getDefaultProfile(): VpnProfileEntity?

    @Query("SELECT * FROM vpn_profiles WHERE is_default = 1 LIMIT 1")
    abstract fun observeDefaultProfile(): Flow<VpnProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertProfile(profile: VpnProfileEntity)

    @Update
    abstract suspend fun updateProfile(profile: VpnProfileEntity)

    @Delete
    abstract suspend fun deleteProfile(profile: VpnProfileEntity)

    @Query("UPDATE vpn_profiles SET is_default = 0")
    abstract suspend fun clearDefaultFlags()

    @Query("UPDATE vpn_profiles SET is_default = 1 WHERE id = :profileId")
    abstract suspend fun markAsDefault(profileId: String)

    @Transaction
    open suspend fun setDefault(profileId: String) {
        clearDefaultFlags()
        markAsDefault(profileId)
    }

    @Query("SELECT COUNT(*) FROM vpn_profiles")
    abstract suspend fun getProfileCount(): Int
}

@Database(entities = [VpnProfileEntity::class], version = 1, exportSchema = false)
abstract class VpnDatabase : RoomDatabase() {
    abstract fun profileDao(): VpnProfileDao
}
