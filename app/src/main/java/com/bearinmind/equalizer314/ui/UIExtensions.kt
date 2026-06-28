package com.bearinmind.equalizer314.ui

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

// ── Frequency slider helpers ──────────────────────────────────────
// Used by MainActivity to map between Hz values and slider positions.

private val hzLogMin = log10(10f)
private val hzLogMax = log10(20000f)

/**
 * Map an Hz value (10–20000) to a normalised slider position (0–1000).
 * Uses logarithmic scaling to match human pitch perception.
 * @param hz  frequency in Hz, coerced to [10, 20000]
 */
fun hzToSlider(hz: Float): Float {
    val logHz = log10(hz.coerceIn(10f, 20000f))
    return ((logHz - hzLogMin) / (hzLogMax - hzLogMin) * 1000f)
}

/**
 * Map a slider position (0–1000) back to an Hz value.
 * The inverse of [hzToSlider].
 * @param pos  slider position in [0, 1000]
 * @return frequency in Hz, in range [10, 20000]
 */
fun sliderToHz(pos: Float): Float {
    val logHz = hzLogMin + (pos / 1000f) * (hzLogMax - hzLogMin)
    return (10.0).pow(logHz.toDouble()).toFloat()
}

/**
 * Format an Hz value for display.
 * Values ≥ 1000 are shown as integer (e.g. "1000"),
 * values < 1000 as "xxx.x" (e.g. "63.5").
 * @param hz  frequency in Hz
 */
fun formatHzValue(hz: Float): String {
    return if (hz >= 1000) String.format(Locale.US, "%.0f", hz) else String.format(Locale.US, "%.1f", hz)
}

// ── Color utilities ──────────────────────────────────────────────

/**
 * Linearly interpolate between two ARGB colours.
 * Each channel (alpha, red, green, blue) is blended independently.
 * @param from  start colour (ARGB int)
 * @param to    end colour (ARGB int)
 * @param ratio blend factor (0 = all `from`, 1 = all `to`)
 */
fun blendColor(from: Int, to: Int, ratio: Float): Int {
    val inv = 1f - ratio
    val a = ((from shr 24 and 0xFF) * inv + (to shr 24 and 0xFF) * ratio).toInt()
    val r = ((from shr 16 and 0xFF) * inv + (to shr 16 and 0xFF) * ratio).toInt()
    val g = ((from shr 8 and 0xFF) * inv + (to shr 8 and 0xFF) * ratio).toInt()
    val b = ((from and 0xFF) * inv + (to and 0xFF) * ratio).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
