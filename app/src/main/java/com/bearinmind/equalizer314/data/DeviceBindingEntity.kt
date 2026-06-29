package com.bearinmind.equalizer314.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a device → preset binding.
 *
 * Maps a stable device identity (MAC or routable identifier) to
 * a named EQ preset that should auto-load when that device is
 * the active audio output.
 */
@Entity(tableName = "device_bindings")
data class DeviceBindingEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_key")
    val deviceKey: String,

    /** Human-friendly label shown in the UI (e.g. "Sony WH-1000XM5"). */
    @ColumnInfo(name = "label")
    val label: String,

    /** Name of the preset to auto-load — matches [PresetEntity.name]. */
    @ColumnInfo(name = "preset_name")
    val presetName: String,

    /** Timestamp when this binding was first created (epoch millis). */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,
)
