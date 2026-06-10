package com.bearinmind.equalizer314.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for [SeenDeviceEntity] — record of every audio output device
 * the app has encountered.
 */
@Dao
interface SeenDeviceDao {

    /** All seen devices sorted by label. */
    @Query("SELECT * FROM seen_devices ORDER BY label ASC")
    suspend fun getAll(): List<SeenDeviceEntity>

    /** Insert or update a device record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: SeenDeviceEntity)

    /** Delete a device record. */
    @Query("DELETE FROM seen_devices WHERE device_key = :deviceKey")
    suspend fun deleteByDeviceKey(deviceKey: String)

    /** Count of seen devices. */
    @Query("SELECT COUNT(*) FROM seen_devices")
    suspend fun count(): Int
}
