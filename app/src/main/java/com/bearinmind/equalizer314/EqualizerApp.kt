package com.bearinmind.equalizer314

import android.app.Application
import com.bearinmind.equalizer314.data.EqDatabase
import com.bearinmind.equalizer314.data.EqMigrationHelper
import com.bearinmind.equalizer314.data.PresetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application subclass that initialises the Room database and runs
 * the one-time migration from SharedPreferences.
 */
class EqualizerApp : Application() {

    /** Shared application-wide scope for background tasks. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        migrateLegacyData()
    }

    /**
     * One-time migration from SharedPreferences to Room.
     *
     * Reads [EqMigrationHelper.migratedPref] — once set to true the
     * migration is skipped on all subsequent launches.
     */
    private fun migrateLegacyData() {
        val prefs = getSharedPreferences(EQ_PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ROOM_MIGRATED, false)) return

        appScope.launch {
            try {
                val repo = PresetRepository.getInstance(this@EqualizerApp)
                val database = EqDatabase.getInstance(this@EqualizerApp)
                val bindingsPrefs = getSharedPreferences(DEVICE_BINDINGS_NAME, MODE_PRIVATE)

                EqMigrationHelper.migratePresets(prefs, repo)
                EqMigrationHelper.migrateDeviceBindings(bindingsPrefs, database.deviceBindingDao())
                EqMigrationHelper.migrateSeenDevices(bindingsPrefs, database.seenDeviceDao())

                prefs.edit().putBoolean(KEY_ROOM_MIGRATED, true).apply()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Migration is best-effort — SharedPreferences still works
                android.util.Log.w(TAG, "Room migration failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "EqualizerApp"

        internal const val EQ_PREFS_NAME = "eq_settings"
        internal const val DEVICE_BINDINGS_NAME = "device_bindings"
        private const val KEY_ROOM_MIGRATED = "room_migrated"

        /**
         * Convenience accessor for existing code that constructs
         * [com.bearinmind.equalizer314.state.EqPreferencesManager].
         */
        fun eqPrefsName(): String = EQ_PREFS_NAME
    }
}
