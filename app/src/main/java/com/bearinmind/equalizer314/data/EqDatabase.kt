@file:Suppress("ForbiddenComment")

package com.bearinmind.equalizer314.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for the Equalizer314 app.
 *
 * Schema version history:
 * - v1: initial release — `presets`, `device_bindings`, `seen_devices`
 */
@Database(
    entities = [
        PresetEntity::class,
        DeviceBindingEntity::class,
        SeenDeviceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class EqDatabase : RoomDatabase() {

    abstract fun presetDao(): PresetDao
    abstract fun deviceBindingDao(): DeviceBindingDao
    abstract fun seenDeviceDao(): SeenDeviceDao

    companion object {
        private const val DB_NAME = "equalizer314.db"

        @Volatile
        private var instance: EqDatabase? = null

        // NOTE: replace .fallbackToDestructiveMigration() with proper
        // Migration(1, 2) before shipping to production. Schema exports
        // to app/schemas/ via room.schemaLocation — use the v1.json as
        // the reference for writing MIGRATION_1_2.

        fun getInstance(context: Context): EqDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EqDatabase::class.java,
                    DB_NAME,
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
