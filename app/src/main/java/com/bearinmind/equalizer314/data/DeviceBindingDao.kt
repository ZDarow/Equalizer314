package com.bearinmind.equalizer314.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [DeviceBindingEntity] — per-device EQ preset bindings.
 */
@Dao
interface DeviceBindingDao {

    /** Observe all bindings sorted by label. */
    @Query("SELECT * FROM device_bindings ORDER BY label ASC")
    fun observeAll(): Flow<List<DeviceBindingEntity>>

    /** All bindings as a snapshot list. */
    @Query("SELECT * FROM device_bindings ORDER BY label ASC")
    suspend fun getAll(): List<DeviceBindingEntity>

    /** Look up a binding by device key. */
    @Query("SELECT * FROM device_bindings WHERE device_key = :deviceKey")
    suspend fun getByDeviceKey(deviceKey: String): DeviceBindingEntity?

    /** Insert or replace a binding. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: DeviceBindingEntity)

    /** Delete a binding. */
    @Query("DELETE FROM device_bindings WHERE device_key = :deviceKey")
    suspend fun deleteByDeviceKey(deviceKey: String)

    /** Delete all bindings. */
    @Query("DELETE FROM device_bindings")
    suspend fun deleteAll()

    /** Count of stored bindings. */
    @Query("SELECT COUNT(*) FROM device_bindings")
    suspend fun count(): Int
}
