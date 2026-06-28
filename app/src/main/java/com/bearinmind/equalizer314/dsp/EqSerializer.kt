package com.bearinmind.equalizer314.dsp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified serializer for [ParametricEqualizer] ↔ JSON.
 *
 * Consolidates the (de)serialization logic that was duplicated across
 * [EqPreferencesManager], [RouteSwitchCoordinator], [MainActivity],
 * and [PresetConverter]. All band state is represented as a portable
 * JSON array of band objects, which can be embedded in presets, prefs,
 * or APO export.
 *
 * ## Band JSON shape
 * ```json
 * {"frequency": 1000.0, "gain": 3.0, "filterType": "BELL", "q": 0.71, "enabled": true}
 * ```
 *
 * ## Preset JSON shape
 * ```json
 * {"preamp": -3.5, "bands": [...], "channelSideEqEnabled": false}
 * ```
 */
object EqSerializer {

    /**
     * Константы ключей JSON. Используются во всём проекте для сериализации
     * пресетов. Вынесены, чтобы избежать опечаток в строковых литералах.
     */
    object Keys {
        const val FREQUENCY = "frequency"
        const val GAIN = "gain"
        const val FILTER_TYPE = "filterType"
        const val Q = "q"
        const val ENABLED = "enabled"
        const val SLOT = "slot"
        const val BANDS = "bands"
        const val PREAMP = "preamp"
        const val CHANNEL_SIDE_EQ_ENABLED = "channelSideEqEnabled"
        const val LEFT_BANDS = "leftBands"
        const val RIGHT_BANDS = "rightBands"
    }

    // ── Band ↔ JSON ──────────────────────────────────────────────────────

    /** Serialize all bands of [eq] into a [JSONArray]. */
    fun bandsToJson(eq: ParametricEqualizer): JSONArray {
        val arr = JSONArray()
        for (i in 0 until eq.getBandCount()) {
            val band = eq.getBand(i) ?: continue
            arr.put(bandToJson(band))
        }
        return arr
    }

    /** Serialize bands with optional [slot] indices. When provided, each
     *  band JSON includes a [Keys.SLOT] key. Keeps the index/slot association
     *  across save/restore cycles for per-channel EQ layouts. */
    fun bandsToJson(eq: ParametricEqualizer, slots: List<Int>): JSONArray {
        val arr = JSONArray()
        for (i in 0 until eq.getBandCount()) {
            val band = eq.getBand(i) ?: continue
            val json = bandToJson(band)
            if (i < slots.size) json.put(Keys.SLOT, slots[i])
            arr.put(json)
        }
        return arr
    }

    /** Serialize a single [EqualizerBand] into a [JSONObject]. */
    fun bandToJson(band: ParametricEqualizer.EqualizerBand): JSONObject = JSONObject().apply {
        put(Keys.FREQUENCY, band.frequency.toDouble())
        put(Keys.GAIN, band.gain.toDouble())
        put(Keys.FILTER_TYPE, band.filterType.name)
        put(Keys.Q, band.q)
        put(Keys.ENABLED, band.enabled)
    }

    /** Deserialize [bandsJson] into [eq], clearing any existing bands first. */
    fun loadBandsTo(eq: ParametricEqualizer, bandsJson: JSONArray) {
        eq.clearBands()
        for (i in 0 until bandsJson.length()) {
            val obj = bandsJson.getJSONObject(i)
            val ft = runCatching {
                BiquadFilter.FilterType.valueOf(obj.getString(Keys.FILTER_TYPE))
            }.getOrDefault(BiquadFilter.FilterType.BELL)
            eq.addBand(
                obj.getDouble(Keys.FREQUENCY).toFloat(),
                obj.getDouble(Keys.GAIN).toFloat(),
                ft,
                obj.getDouble(Keys.Q),
            )
            if (obj.has(Keys.ENABLED)) {
                eq.setBandEnabled(i, obj.getBoolean(Keys.ENABLED))
            }
        }
    }

    /** Deserialize a JSON string into [eq]. Returns false on parse failure. */
    fun loadBandsTo(eq: ParametricEqualizer, jsonStr: String): Boolean = runCatching {
        loadBandsTo(eq, JSONArray(jsonStr))
        true
    }.getOrDefault(false)

    /** Convenience: parse JSON string, return new [ParametricEqualizer] or null. */
    fun parseBands(jsonStr: String): ParametricEqualizer? = runCatching {
        val arr = JSONArray(jsonStr)
        val eq = ParametricEqualizer()
        loadBandsTo(eq, arr)
        eq
    }.getOrNull()

    /** Convenience: parse [JSONArray], return new [ParametricEqualizer]. */
    fun parseBands(arr: JSONArray): ParametricEqualizer {
        val eq = ParametricEqualizer()
        loadBandsTo(eq, arr)
        eq.isEnabled = true
        return eq
    }

    /** Legacy preset JSON — returns [ParametricEqualizer] or null. */
    fun parsePresetJson(jsonStr: String): ParametricEqualizer? = runCatching {
        val obj = JSONObject(jsonStr)
        val bands = obj.optJSONArray(Keys.BANDS) ?: return@runCatching null
        parseBands(bands)
    }.getOrNull()

    // ── Preset JSON (preamp + bands + optional CSE) ─────────────────────

    /** Serialize full preset state (preamp, bands, optional CSE) to JSON string. */
    fun presetToJson(
        preampDb: Float,
        bands: JSONArray,
        channelSideEqEnabled: Boolean = false,
        leftBands: JSONArray? = null,
        rightBands: JSONArray? = null,
    ): String = JSONObject().apply {
        put(Keys.PREAMP, preampDb.toDouble())
        put(Keys.BANDS, bands)
        put(Keys.CHANNEL_SIDE_EQ_ENABLED, channelSideEqEnabled)
        if (channelSideEqEnabled && leftBands != null && rightBands != null) {
            put(Keys.LEFT_BANDS, leftBands)
            put(Keys.RIGHT_BANDS, rightBands)
        }
    }.toString()

    /** Serialize a complete [ParametricEqualizer] + preamp into preset JSON. */
    fun eqToPresetJson(eq: ParametricEqualizer, preampDb: Float): String =
        presetToJson(preampDb, bandsToJson(eq))
}
