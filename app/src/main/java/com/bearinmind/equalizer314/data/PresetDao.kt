package com.bearinmind.equalizer314.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for [PresetEntity].
 */
@Dao
interface PresetDao {

    /** Observe all presets sorted by last-updated time (newest first). */
    @Query("SELECT * FROM presets ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<PresetEntity>>

    /** All preset names (for populating the preset picker). */
    @Query("SELECT name FROM presets ORDER BY updated_at DESC")
    suspend fun getAllNames(): List<String>

    /** Load a single preset by name. Returns null when not found. */
    @Query("SELECT * FROM presets WHERE name = :name")
    suspend fun getByName(name: String): PresetEntity?

    /** Insert or replace a preset. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: PresetEntity)

    /** Update only the updated_at timestamp and bands/preamp/CSE data. */
    @Query("""
        UPDATE presets SET
            bands_json = :bandsJson,
            preamp = :preamp,
            is_channel_side_eq = :isCse,
            left_bands_json = :leftBandsJson,
            right_bands_json = :rightBandsJson,
            updated_at = :updatedAt
        WHERE name = :name
    """)
    suspend fun update(
        name: String,
        bandsJson: String,
        preamp: Double,
        isCse: Boolean,
        leftBandsJson: String?,
        rightBandsJson: String?,
        updatedAt: Long = System.currentTimeMillis(),
    )

    /** Delete a preset by name. */
    @Query("DELETE FROM presets WHERE name = :name")
    suspend fun deleteByName(name: String)

    /** Delete all presets. */
    @Query("DELETE FROM presets")
    suspend fun deleteAll()

    /** Total number of stored presets. */
    @Query("SELECT COUNT(*) FROM presets")
    suspend fun count(): Int
}
