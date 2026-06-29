package com.bearinmind.equalizer314.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for the Equalizer314 app.
 *
 * Schema version history:
 * - v1: initial release — `presets`, `device_bindings`, `seen_devices`
 * - v2: add `created_at` to `device_bindings` for tracking creation time
 */
@Database(
    entities = [
        PresetEntity::class,
        DeviceBindingEntity::class,
        SeenDeviceEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class EqDatabase : RoomDatabase() {

    /** Room DAO for preset entities. */
    abstract fun presetDao(): PresetDao

    /** Room DAO for device-binding entities. */
    abstract fun deviceBindingDao(): DeviceBindingDao

    /** Room DAO for seen-device entities. */
    abstract fun seenDeviceDao(): SeenDeviceDao

    companion object {
        private const val DB_NAME = "equalizer314.db"

        @Volatile
        private var instance: EqDatabase? = null

        /**
         * Migration from v1 to v2.
         *
         * Changes:
         * - `device_bindings`: add `created_at INTEGER NOT NULL DEFAULT 0`
         */
        @JvmField
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE device_bindings
                    ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
            }
        }

        /**
         * Thread-safe singleton accessor for the Room database.
         * @param context  application context (used only on first call)
         */
        fun getInstance(context: Context): EqDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EqDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
