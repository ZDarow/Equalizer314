package com.bearinmind.equalizer314.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a saved equalizer preset.
 *
 * Bands are stored as JSON arrays so the schema stays flat and
 * compatible with the legacy SharedPreferences format.
 * [leftBandsJson] / [rightBandsJson] are non-null only when
 * the preset was saved with Channel Side EQ enabled.
 */
@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey
    @ColumnInfo(name = "name")
    val name: String,

    /** JSON array of band objects (frequency, gain, q, filterType, enabled). */
    @ColumnInfo(name = "bands_json")
    val bandsJson: String,

    /** Pre-amp gain in dB. */
    @ColumnInfo(name = "preamp")
    val preamp: Double = 0.0,

    /** Whether this preset stores separate left/right bands. */
    @ColumnInfo(name = "is_channel_side_eq")
    val isChannelSideEq: Boolean = false,

    /** JSON array of left-channel bands (null when [isChannelSideEq] is false). */
    @ColumnInfo(name = "left_bands_json")
    val leftBandsJson: String? = null,

    /** JSON array of right-channel bands (null when [isChannelSideEq] is false). */
    @ColumnInfo(name = "right_bands_json")
    val rightBandsJson: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
