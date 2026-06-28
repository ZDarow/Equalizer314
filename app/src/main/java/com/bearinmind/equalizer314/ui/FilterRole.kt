package com.bearinmind.equalizer314.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import com.bearinmind.equalizer314.R
import com.bearinmind.equalizer314.dsp.BiquadFilter

/**
 * Filter-type classification for the filter button rows on the EQ graph.
 *
 * Each filter type displayed in the UI belongs to one of three "roles"
 * that determines which button row it appears in.
 */
enum class FilterRole { PEAK, SHELF_PASS, BYPASS }

/** Primary label text for the PEAK button.
 *
 * When the band is on BP or NO the label swaps to "B. PASS" / "NOTCH"
 * so the sub-type reads like its own filter category rather than a
 * variant badge. "B. PASS" keeps the label short so the text stays
 * at full size without shrinking.
 */
fun peakButtonLabel(
    current: BiquadFilter.FilterType,
    bandEnabled: Boolean,
    context: android.content.Context,
): String = when {
    !bandEnabled -> context.getString(R.string.filter_peak)
    current == BiquadFilter.FilterType.BAND_PASS -> "B. PASS"
    current == BiquadFilter.FilterType.NOTCH -> "NOTCH"
    else -> context.getString(R.string.filter_peak)
}

/** True when the filter type belongs to the PEAK button's dropdown:
 *  the plain bell plus the two gainless Fc+Q specials (BP / NO).
 *  ALL_PASS lives on the BYPASS button, not here. */
fun isPeakFamily(t: BiquadFilter.FilterType): Boolean = when (t) {
    BiquadFilter.FilterType.BELL,
    BiquadFilter.FilterType.BAND_PASS,
    BiquadFilter.FilterType.NOTCH -> true
    else -> false
}

/** Collapse 1st- and 2nd-order variants into a single "family" key.
 *
 * Used so the filter-type button highlighting treats LShelf / LShelf (6 dB)
 * as the same button.
 */
fun filterTypeFamily(t: BiquadFilter.FilterType): BiquadFilter.FilterType = when (t) {
    BiquadFilter.FilterType.LOW_SHELF_1 -> BiquadFilter.FilterType.LOW_SHELF
    BiquadFilter.FilterType.HIGH_SHELF_1 -> BiquadFilter.FilterType.HIGH_SHELF
    BiquadFilter.FilterType.LOW_PASS_1 -> BiquadFilter.FilterType.LOW_PASS
    BiquadFilter.FilterType.HIGH_PASS_1 -> BiquadFilter.FilterType.HIGH_PASS
    else -> t
}

/** Given a 2nd-order filter family button (LOW_SHELF / HIGH_SHELF /
 *  LOW_PASS / HIGH_PASS), return the matching 1st-order type. */
fun oneOrderVariant(family: BiquadFilter.FilterType): BiquadFilter.FilterType? = when (family) {
    BiquadFilter.FilterType.LOW_SHELF -> BiquadFilter.FilterType.LOW_SHELF_1
    BiquadFilter.FilterType.HIGH_SHELF -> BiquadFilter.FilterType.HIGH_SHELF_1
    BiquadFilter.FilterType.LOW_PASS -> BiquadFilter.FilterType.LOW_PASS_1
    BiquadFilter.FilterType.HIGH_PASS -> BiquadFilter.FilterType.HIGH_PASS_1
    else -> null
}

/** Build the filter-button label.
 *
 * Two-line form when a subtitle is supplied ("LSHELF\n12 dB");
 * single-line form when there's no subtitle (PEAK, B. PASS, NOTCH, BYPASS)
 * so the visible text sits at the true vertical center of the button.
 * Row / button minHeight keeps every cell the same size despite the
 * line-count difference. The primary label is proportionally shrunk
 * when it's long enough to overflow (8+ chars → 70% of the button's textSize).
 */
fun buildFilterButtonText(primary: String, subtitle: String): CharSequence {
    val full = if (subtitle.isEmpty()) primary else "$primary\n$subtitle"
    val span = SpannableString(full)
    val shrink = when {
        primary.length >= 8 -> 0.7f
        else -> 1f
    }
    if (shrink < 1f) {
        span.setSpan(
            RelativeSizeSpan(shrink),
            0, primary.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return span
}
