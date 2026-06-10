package com.bearinmind.equalizer314.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Repository for user-defined EQ presets.
 *
 * Wraps [PresetDao] and delegates JSON format conversion to [PresetConverter].
 * This is the single source of truth for custom presets going forward.
 */
class PresetRepository private constructor(
    private val dao: PresetDao,
) {
    /** Observe all presets sorted by last-updated (newest first). */
    val allPresets: Flow<List<PresetEntity>> = dao.observeAll()

    /** All preset names. */
    suspend fun getAllNames(): List<String> = dao.getAllNames()

    /** Get a single preset. */
    suspend fun getByName(name: String): PresetEntity? = dao.getByName(name)

    /** Save (insert or replace) a preset. */
    suspend fun save(preset: PresetEntity) = dao.upsert(preset)

    /** Delete a preset. */
    suspend fun deleteByName(name: String) = dao.deleteByName(name)

    /** Count of stored presets. */
    suspend fun count(): Int = dao.count()

    /** Build a [PresetEntity] from legacy SharedPreferences JSON. */
    fun fromLegacyJson(name: String, json: String): PresetEntity =
        PresetConverter.fromLegacyJson(name, json)

    /** Convert back to legacy SharedPreferences JSON. */
    fun toLegacyJson(preset: PresetEntity): String =
        PresetConverter.toLegacyJson(preset)

    companion object {
        @Volatile
        private var instance: PresetRepository? = null

        fun getInstance(context: Context): PresetRepository {
            return instance ?: synchronized(this) {
                instance ?: PresetRepository(
                    EqDatabase.getInstance(context).presetDao()
                ).also { instance = it }
            }
        }
    }
}
