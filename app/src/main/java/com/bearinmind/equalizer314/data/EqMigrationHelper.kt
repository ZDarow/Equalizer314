package com.bearinmind.equalizer314.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-time migration helper that reads legacy data from SharedPreferences
 * and writes it into the Room database.
 *
 * Safe to call multiple times — each upsert is idempotent.
 */
object EqMigrationHelper {

    /**
     * Migrate custom presets and simple-EQ presets from SharedPreferences
     * into [PresetRepository].
     *
     * Custom parametric presets are stored under keys `preset_<name>` as
     * JSON in the `eq_settings` file. Simple-EQ presets are stored under
     * `simple_preset_<name>` as JSON gain arrays.
     *
     * @param eqPrefs the `eq_settings` SharedPreferences file.
     * @param repo the target Room-backed repository.
     */
    suspend fun migratePresets(eqPrefs: SharedPreferences, repo: PresetRepository) {
        migrateCustomParametricPresets(eqPrefs, repo)
        migrateSimpleEqPresets(eqPrefs, repo)
    }

    /**
     * Migrate device bindings from the `device_bindings` SharedPreferences
     * file into [DeviceBindingDao].
     */
    suspend fun migrateDeviceBindings(
        bindingsPrefs: SharedPreferences,
        dao: DeviceBindingDao,
    ) {
        for ((key, value) in bindingsPrefs.all) {
            if (!key.startsWith("binding_")) continue
            val str = value as? String ?: continue
            runCatching {
                val obj = JSONObject(str)
                val binding = DeviceBindingEntity(
                    deviceKey = obj.getString("key"),
                    label = obj.getString("label"),
                    presetName = obj.getString("presetName"),
                )
                dao.upsert(binding)
            }
        }
    }

    /**
     * Migrate seen devices from the `device_bindings` SharedPreferences
     * file into [SeenDeviceDao].
     */
    suspend fun migrateSeenDevices(
        bindingsPrefs: SharedPreferences,
        dao: SeenDeviceDao,
    ) {
        for ((key, value) in bindingsPrefs.all) {
            if (!key.startsWith("seen_")) continue
            val label = value as? String ?: continue
            val device = SeenDeviceEntity(
                deviceKey = key.removePrefix("seen_"),
                label = label,
            )
            dao.upsert(device)
        }
    }

    // ---- Private helpers ----

    private suspend fun migrateCustomParametricPresets(
        prefs: SharedPreferences,
        repo: PresetRepository,
    ) {
        // Named custom parametric presets: keys `preset_<name>`
        for ((key, value) in prefs.all) {
            if (!key.startsWith("preset_")) continue
            val name = key.removePrefix("preset_")
            val jsonStr = value as? String ?: continue
            if (jsonStr.isBlank()) continue
            val entity = PresetConverter.fromLegacyJson(name, jsonStr)
            repo.save(entity)
        }
    }

    private suspend fun migrateSimpleEqPresets(
        prefs: SharedPreferences,
        repo: PresetRepository,
    ) {
        val names = prefs.getStringSet("simple_preset_names", emptySet()) ?: return
        for (name in names) {
            val gainsStr = prefs.getString("simple_preset_$name", null) ?: continue
            val gainsArr = try {
                JSONArray(gainsStr)
            } catch (_: Exception) { continue }

            // Convert simple EQ (10 fixed-band gain floats) to
            // the parametric JSON format so downstream consumers
            // treat it uniformly.
            val bands = JSONArray()
            for (i in 0 until gainsArr.length()) {
                bands.put(JSONObject().apply {
                    put("frequency", simpleEqFrequencies.getOrElse(i) { 0.0 })
                    put("gain", gainsArr.optDouble(i, 0.0))
                    put("filterType", "BELL")
                    put("q", 0.71)
                    put("enabled", true)
                })
            }
            val json = JSONObject().apply {
                put("bands", bands)
                put("preamp", 0.0)
            }
            val entity = PresetConverter.fromLegacyJson("Simple: $name", json.toString())
            repo.save(entity)
        }
    }

    /** Standard 10-band simple-EQ frequencies matching the app UI. */
    private val simpleEqFrequencies = listOf(
        31.0, 62.0, 125.0, 250.0, 500.0,
        1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
    )
}
