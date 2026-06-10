package com.bearinmind.equalizer314.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a device that the app has seen at least once.
 *
 * Records are created lazily when a new audio routing callback arrives.
 * Used to populate the "devices seen" list in Audio Output settings.
 */
@Entity(tableName = "seen_devices")
data class SeenDeviceEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_key")
    val deviceKey: String,

    @ColumnInfo(name = "label")
    val label: String,
)
