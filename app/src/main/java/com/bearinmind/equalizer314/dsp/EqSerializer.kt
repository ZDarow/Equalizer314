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

    /** Serialize a single [EqualizerBand] into a [JSONObject]. */
    fun bandToJson(band: ParametricEqualizer.EqualizerBand): JSONObject = JSONObject().apply {
        put("frequency", band.frequency.toDouble())
        put("gain", band.gain.toDouble())
        put("filterType", band.filterType.name)
        put("q", band.q)
        put("enabled", band.enabled)
    }

    /** Deserialize [bandsJson] into [eq], clearing any existing bands first. */
    fun loadBandsTo(eq: ParametricEqualizer, bandsJson: JSONArray) {
        eq.clearBands()
        for (i in 0 until bandsJson.length()) {
            val obj = bandsJson.getJSONObject(i)
            val ft = try {
                BiquadFilter.FilterType.valueOf(obj.getString("filterType"))
            } catch (_: Exception) {
                BiquadFilter.FilterType.BELL
            }
            eq.addBand(
                obj.getDouble("frequency").toFloat(),
                obj.getDouble("gain").toFloat(),
                ft,
                obj.getDouble("q"),
            )
            if (obj.has("enabled")) {
                eq.setBandEnabled(i, obj.getBoolean("enabled"))
            }
        }
    }

    /** Deserialize a JSON string into [eq]. Returns false on parse failure. */
    fun loadBandsTo(eq: ParametricEqualizer, jsonStr: String): Boolean = try {
        loadBandsTo(eq, JSONArray(jsonStr))
        true
    } catch (_: Exception) {
        false
    }

    /** Convenience: parse JSON string, return new [ParametricEqualizer] or null. */
    fun parseBands(jsonStr: String): ParametricEqualizer? = try {
        val arr = JSONArray(jsonStr)
        val eq = ParametricEqualizer()
        loadBandsTo(eq, arr)
        eq
    } catch (_: Exception) {
        null
    }

    /** Convenience: parse [JSONArray], return new [ParametricEqualizer]. */
    fun parseBands(arr: JSONArray): ParametricEqualizer {
        val eq = ParametricEqualizer()
        loadBandsTo(eq, arr)
        eq.isEnabled = true
        return eq
    }

    /** Legacy preset JSON — returns [ParametricEqualizer] or null. */
    fun parsePresetJson(jsonStr: String): ParametricEqualizer? = try {
        val obj = JSONObject(jsonStr)
        val bands = obj.optJSONArray("bands") ?: return null
        parseBands(bands)
    } catch (_: Exception) {
        null
    }

    // ── Preset JSON (preamp + bands + optional CSE) ─────────────────────

    /** Serialize full preset state (preamp, bands, optional CSE) to JSON string. */
    fun presetToJson(
        preampDb: Float,
        bands: JSONArray,
        channelSideEqEnabled: Boolean = false,
        leftBands: JSONArray? = null,
        rightBands: JSONArray? = null,
    ): String = JSONObject().apply {
        put("preamp", preampDb.toDouble())
        put("bands", bands)
        put("channelSideEqEnabled", channelSideEqEnabled)
        if (channelSideEqEnabled && leftBands != null && rightBands != null) {
            put("leftBands", leftBands)
            put("rightBands", rightBands)
        }
    }.toString()

    /** Serialize a complete [ParametricEqualizer] + preamp into preset JSON. */
    fun eqToPresetJson(eq: ParametricEqualizer, preampDb: Float): String =
        presetToJson(preampDb, bandsToJson(eq))
}
