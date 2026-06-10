package com.bearinmind.equalizer314.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure-Kotlin utility for converting between the legacy SharedPreferences
 * JSON format and [PresetEntity].
 *
 * Separated from [PresetRepository] so callers and tests never need
 * an Android Context or Room dependency for format conversion.
 */
object PresetConverter {

    /**
     * Build a [PresetEntity] from the legacy SharedPreferences JSON format.
     * The JSON object is the same structure stored by
     * [com.bearinmind.equalizer314.state.EqPreferencesManager].
     */
    fun fromLegacyJson(name: String, json: String): PresetEntity {
        val obj = JSONObject(json)
        val bandsJson = obj.optJSONArray("bands")?.toString() ?: JSONArray().toString()
        val preamp = obj.optDouble("preamp", 0.0)
        val isCse = obj.optBoolean("channelSideEqEnabled", false)
        val leftJson = if (obj.has("leftBands")) obj.getJSONArray("leftBands").toString() else null
        val rightJson = if (obj.has("rightBands")) obj.getJSONArray("rightBands").toString() else null
        val now = System.currentTimeMillis()
        return PresetEntity(
            name = name,
            bandsJson = bandsJson,
            preamp = preamp,
            isChannelSideEq = isCse,
            leftBandsJson = leftJson,
            rightBandsJson = rightJson,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Convert a [PresetEntity] back to the legacy SharedPreferences JSON format
     * so the rest of the app can load it without changes.
     */
    fun toLegacyJson(preset: PresetEntity): String {
        return JSONObject().apply {
            put("bands", JSONArray(preset.bandsJson))
            put("preamp", preset.preamp)
            put("channelSideEqEnabled", preset.isChannelSideEq)
            if (preset.isChannelSideEq) {
                preset.leftBandsJson?.let { put("leftBands", JSONArray(it)) }
                preset.rightBandsJson?.let { put("rightBands", JSONArray(it)) }
            }
        }.toString()
    }
}
