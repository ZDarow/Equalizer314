package com.bearinmind.equalizer314.state

import android.content.SharedPreferences
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.EqSerializer
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages custom EQ presets stored in SharedPreferences.
 *
 * Each preset is stored as a JSON string under `"preset_$name"` and the
 * set of all preset names under `"preset_names"`. Presets can be single-EQ
 * or per-channel (CSE) with separate left/right configurations.
 *
 * This class handles data persistence only — UI rendering and thumbnail
 * generation remain in the calling code.
 */
class PresetManager(private val prefs: SharedPreferences) {

    /** All stored custom preset names. */
    val names: Set<String>
        get() = prefs.getStringSet("preset_names", emptySet()).orEmpty()

    /** Retrieve the raw JSON for a preset. */
    fun getJson(name: String): String? = prefs.getString("preset_$name", null)

    /** Save a new or updated preset. */
    fun save(name: String, json: String) {
        prefs.edit()
            .putString("preset_$name", json)
            .putStringSet("preset_names", names + name)
            .apply()
    }

    /** Delete a preset by name. */
    fun delete(name: String) {
        prefs.edit()
            .remove("preset_$name")
            .putStringSet("preset_names", names - name)
            .apply()
    }

    /** Generate the next available name with the given [prefix] (e.g. "Custom #").
     *  Use a localized prefix via [android.content.Context.getString]. */
    fun nextCustomName(prefix: String): String {
        var next = 1
        val pattern = Regex("""${Regex.escape(prefix)}(\d+)""")
        for (n in names) {
            val m = pattern.find(n)
            if (m != null) next = maxOf(next, m.groupValues[1].toInt() + 1)
        }
        return "$prefix$next"
    }

    /**
     * Parse a stored preset JSON into components usable by [EqViewModel.applyPresetEqs].
     *
     * @return A [ParsedPreset] with CSE flag and band specs, or null on parse failure.
     */
    fun parse(name: String): ParsedPreset? {
        val json = getJson(name) ?: return null
        return try {
            val obj = JSONObject(json)
            val cseOn = obj.optBoolean("channelSideEqEnabled", false)
            val bothBands = if (obj.has("bands")) parseBandSpecs(obj.getJSONArray("bands")) else emptyList()
            val leftBands = if (obj.has("leftBands")) parseBandSpecs(obj.getJSONArray("leftBands")) else bothBands
            val rightBands = if (obj.has("rightBands")) parseBandSpecs(obj.getJSONArray("rightBands")) else bothBands
            PrefsPreset(cseOn, bothBands, leftBands, rightBands)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build a [ParametricEqualizer] from a stored preset's bands for thumbnail rendering.
     */
    fun buildThumbnailEq(name: String): ParametricEqualizer? {
        val json = getJson(name) ?: return null
        return try {
            val obj = JSONObject(json)
            val bands = obj.optJSONArray("bands") ?: return null
            EqSerializer.parseBands(bands)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build a per-channel [ParametricEqualizer] from a stored preset.
     * Returns null if the preset is not a CSE preset.
     */
    fun buildChannelThumbnailEq(name: String, isLeft: Boolean): ParametricEqualizer? {
        val json = getJson(name) ?: return null
        return try {
            val obj = JSONObject(json)
            if (!obj.optBoolean("channelSideEqEnabled", false)) return null
            val key = if (isLeft) "leftBands" else "rightBands"
            val bands = obj.optJSONArray(key) ?: return null
            EqSerializer.parseBands(bands)
        } catch (_: Exception) {
            null
        }
    }

    /** Convert a preset's JSON to APO-format text for export. */
    fun toApoText(name: String): String? {
        val json = getJson(name) ?: return null
        return try {
            val obj = JSONObject(json)
            val sb = StringBuilder()
            val preamp = obj.optDouble("preamp", 0.0)
            sb.append("Preamp: ${String.format("%.1f", preamp)} dB\n")

            fun appendFilters(bands: JSONArray, indexOffset: Int = 0) {
                for (i in 0 until bands.length()) {
                    val b = bands.getJSONObject(i)
                    val ft = b.getString("filterType")
                    val (apoType, hasGain, hasQ) = when (ft) {
                        "BELL"         -> Triple("PK",  true, true)
                        "LOW_SHELF"    -> Triple("LSC", true, true)
                        "HIGH_SHELF"   -> Triple("HSC", true, true)
                        "LOW_PASS"     -> Triple("LPQ", false, true)
                        "HIGH_PASS"    -> Triple("HPQ", false, true)
                        "LOW_SHELF_1"  -> Triple("LS",  true, false)
                        "HIGH_SHELF_1" -> Triple("HS",  true, false)
                        "LOW_PASS_1"   -> Triple("LP",  false, false)
                        "HIGH_PASS_1"  -> Triple("HP",  false, false)
                        "BAND_PASS"    -> Triple("BP",  false, true)
                        "NOTCH"        -> Triple("NO",  false, true)
                        "ALL_PASS"     -> Triple("AP",  false, true)
                        else           -> Triple("PK",  true, true)
                    }
                    val fc = b.getDouble("frequency").toInt()
                    val line = StringBuilder("Filter ${i + 1 + indexOffset}: ON $apoType Fc $fc Hz")
                    if (hasGain) line.append(" Gain ${String.format("%.1f", b.getDouble("gain"))} dB")
                    if (hasQ) line.append(" Q ${String.format("%.2f", b.getDouble("q"))}")
                    sb.append(line).append('\n')
                }
            }

            val cseOn = obj.optBoolean("channelSideEqEnabled", false)
            if (cseOn && obj.has("leftBands") && obj.has("rightBands")) {
                val leftArr = obj.getJSONArray("leftBands")
                val rightArr = obj.getJSONArray("rightBands")
                sb.append("Channel: L\n")
                appendFilters(leftArr)
                sb.append("Channel: R\n")
                appendFilters(rightArr, indexOffset = leftArr.length())
            } else {
                appendFilters(obj.getJSONArray("bands"))
            }
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    // ── internal ────────────────────────────────────────────────────────

    private data class PrefsPreset(
        override val cseOn: Boolean,
        override val bothBands: List<EqStateManager.BandSpec>,
        override val leftBands: List<EqStateManager.BandSpec>,
        override val rightBands: List<EqStateManager.BandSpec>,
    ) : ParsedPreset

    private fun parseBandSpecs(arr: JSONArray): List<EqStateManager.BandSpec> {
        val out = mutableListOf<EqStateManager.BandSpec>()
        for (i in 0 until arr.length()) {
            val bj = arr.getJSONObject(i)
            val ft = try {
                BiquadFilter.FilterType.valueOf(bj.getString("filterType"))
            } catch (_: Exception) {
                BiquadFilter.FilterType.BELL
            }
            out += EqStateManager.BandSpec(
                frequency = bj.getDouble("frequency").toFloat(),
                gain = bj.getDouble("gain").toFloat(),
                q = bj.getDouble("q"),
                filterType = ft,
                enabled = if (bj.has("enabled")) bj.getBoolean("enabled") else true,
            )
        }
        return out
    }
}

/**
 * Result of parsing a stored preset.
 */
interface ParsedPreset {
    val cseOn: Boolean
    val bothBands: List<EqStateManager.BandSpec>
    val leftBands: List<EqStateManager.BandSpec>
    val rightBands: List<EqStateManager.BandSpec>
}
