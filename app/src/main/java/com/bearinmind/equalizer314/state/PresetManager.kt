package com.bearinmind.equalizer314.state

import android.content.SharedPreferences
import com.bearinmind.equalizer314.dsp.BiquadFilter
import java.util.Locale
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
        return runCatching {
            val obj = JSONObject(json)
            val cseOn = obj.optBoolean("channelSideEqEnabled", false)
            val bothBands = if (obj.has("bands")) parseBandSpecs(obj.getJSONArray("bands")) else emptyList()
            val leftBands = if (obj.has("leftBands")) parseBandSpecs(obj.getJSONArray("leftBands")) else bothBands
            val rightBands = if (obj.has("rightBands")) parseBandSpecs(obj.getJSONArray("rightBands")) else bothBands
            PrefsPreset(cseOn, bothBands, leftBands, rightBands)
        }.getOrNull()
    }

    /**
     * Build a [ParametricEqualizer] from a stored preset's bands for thumbnail rendering.
     */
    fun buildThumbnailEq(name: String): ParametricEqualizer? {
        val json = getJson(name) ?: return null
        return runCatching {
            JSONObject(json).optJSONArray("bands")?.let { EqSerializer.parseBands(it) }
        }.getOrNull()
    }

    /**
     * Build a per-channel [ParametricEqualizer] from a stored preset.
     * Returns null if the preset is not a CSE preset.
     */
    fun buildChannelThumbnailEq(name: String, isLeft: Boolean): ParametricEqualizer? {
        val json = getJson(name) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            if (!obj.optBoolean("channelSideEqEnabled", false)) null
            else {
                val key = if (isLeft) "leftBands" else "rightBands"
                obj.optJSONArray(key)?.let { EqSerializer.parseBands(it) }
            }
        }.getOrNull()
    }

    /** Convert a preset's JSON to APO-format text for export. */
    fun toApoText(name: String): String? {
        val json = getJson(name) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            val sb = StringBuilder()
            val preamp = obj.optDouble("preamp", 0.0)
            sb.append("Preamp: ${"%.1f".format(Locale.US, preamp)} dB\n")

            val cseOn = obj.optBoolean("channelSideEqEnabled", false)
            if (cseOn && obj.has("leftBands") && obj.has("rightBands")) {
                val leftArr = obj.getJSONArray("leftBands")
                val rightArr = obj.getJSONArray("rightBands")
                sb.append("Channel: L\n")
                sb.append(apoFilterLines(leftArr))
                sb.append("Channel: R\n")
                sb.append(apoFilterLines(rightArr, indexOffset = leftArr.length()))
            } else {
                sb.append(apoFilterLines(obj.getJSONArray("bands")))
            }
            sb.toString()
        }.getOrNull()
    }

    /** Format a JSONArray of band objects into APO "Filter N: ON ..." lines. */
    private fun apoFilterLines(bands: JSONArray, indexOffset: Int = 0): String {
        val sb = StringBuilder()
        for (i in 0 until bands.length()) {
            val b = bands.getJSONObject(i)
            val ft = b.getString("filterType")
            val (apoType, hasGain, hasQ) = apoFilterType(ft)
            val fc = b.getDouble("frequency").toInt()
            val line = StringBuilder("Filter ${i + 1 + indexOffset}: ON $apoType Fc $fc Hz")
            if (hasGain) line.append(" Gain ${"%.1f".format(Locale.US, b.getDouble("gain"))} dB")
            if (hasQ) line.append(" Q ${"%.2f".format(Locale.US, b.getDouble("q"))}")
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    /** Map internal filter type to APO token + flags. */
    private fun apoFilterType(ft: String): Triple<String, Boolean, Boolean> = when (ft) {
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
            val ft = runCatching {
                BiquadFilter.FilterType.valueOf(bj.getString("filterType"))
            }.getOrDefault(BiquadFilter.FilterType.BELL)
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
 *
 * @property cseOn Whether the preset stores separate left/right band configurations.
 * @property bothBands Bands for the shared channel (used when CSE is off).
 * @property leftBands Bands for the left channel (used when CSE is on).
 * @property rightBands Bands for the right channel (used when CSE is on).
 */
interface ParsedPreset {
    val cseOn: Boolean
    val bothBands: List<EqStateManager.BandSpec>
    val leftBands: List<EqStateManager.BandSpec>
    val rightBands: List<EqStateManager.BandSpec>
}
