package com.bearinmind.equalizer314.dsp

import kotlin.math.pow
// tanh больше не используется — заменён на softClip (без toDouble конверсии)

/**
 * Parametric Equalizer - Custom DSP implementation
 * Allows full control over frequency, gain, and filter type for each band
 */
// sampleRate defaults to 48000 (the Android device-output rate on
// virtually every modern device). EqStateManager overrides this with
// the actual rate from AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE for
// the audio-path instances; non-audio callers (preset import, target
// curve, auto-EQ) accept the default since their use is for response-
// curve computation only.
class ParametricEqualizer(private val sampleRate: Int = 48000) {

    /**
     * A single parametric EQ band.
     *
     * @property frequency centre frequency in Hz (20–20000)
     * @property gain gain in dB (-20 to +20)
     * @property filterType filter topology — see [BiquadFilter.FilterType]
     * @property q quality factor (0.1–10.0, default 0.707)
     * @property enabled whether this band participates in processing
     */
    data class EqualizerBand(
        var frequency: Float,
        var gain: Float,
        var filterType: BiquadFilter.FilterType,
        var q: Double = 0.707,
        var enabled: Boolean = true
    )

    private val bands = mutableListOf<EqualizerBand>()
    private val filters = mutableListOf<BiquadFilter>()

    var isEnabled = false
        set(value) {
            field = value
            if (!value) {
                filters.forEach { it.reset() }
            }
        }

    init {
        addDefaultBands()
    }

    private fun addDefaultBands() {
        // Start with 4 bands at slots 0,1,2,3 using those exact default frequencies
        // so initBandSlots() assigns them to slots 0-3 (displayed as 1,2,3,4)
        val allFreqs = logSpacedFrequencies(16)
        for (i in 0..3) {
            addBand(allFreqs[i], 0f, BiquadFilter.FilterType.BELL)
        }
    }

    companion object {
        /** Compute n log-spaced frequencies across 10–22000 Hz */
        fun logSpacedFrequencies(n: Int): FloatArray {
            val logMin = kotlin.math.log10(10f)
            val logMax = kotlin.math.log10(22000f)
            return FloatArray(n) { i -> 10f.pow(logMin + i * (logMax - logMin) / (n - 1)) }
        }

        /**
         * Быстрая soft-clip функция. Заменяет tanh для предотвращения клиппирования
         * буфера: работает только с Float (без конверсии в Double и обратно),
         * что даёт ~2x прирост производительности на ARM64.
         *
         * Алгоритм: кубический soft-clamp: f(x) = x - x³/3 для |x| ≤ 1,
         *         sign(x) * 2/3 для |x| > 1.
         * Производная непрерывна в x = ±1, гармоники ниже чем у hard-clip.
         */
        @JvmStatic
        fun softClip(x: Float): Float {
            val ax = kotlin.math.abs(x)
            return if (ax >= 1f) {
                kotlin.math.sign(x) * (2f / 3f)
            } else {
                x - (x * x * x) / 3f
            }
        }
    }

    /** Remove all bands and their corresponding filters. */
    fun clearBands() {
        bands.clear()
        filters.clear()
    }

    /** Append a new band with the given parameters.
     *  @param frequency centre frequency in Hz
     *  @param gain gain in dB
     *  @param filterType filter topology
     *  @param q quality factor (default 0.707) */
    fun addBand(frequency: Float, gain: Float, filterType: BiquadFilter.FilterType, q: Double = 0.707) {
        val band = EqualizerBand(frequency, gain, filterType, q, true)
        bands.add(band)

        val filter = BiquadFilter(frequency, gain, filterType, sampleRate, q).apply {
            // RBJ bell (not Vicanek). RBJ's +G and -G peaking filters are
            // exact inverses (numerator↔denominator swap via A and 1/A),
            // so opposite bells cancel perfectly — in the graph AND the
            // audio (the DP converter samples this same response). Vicanek
            // matched only DC/center/Nyquist, leaving ripple between them
            // that broke cancellation (issue #41).
            useVicanekMethod = false
        }
        filters.add(filter)
    }

    /** Insert a band at [index], shifting subsequent bands down.
     *  @param index insertion position
     *  @param frequency centre frequency in Hz
     *  @param gain gain in dB
     *  @param filterType filter topology
     *  @param q quality factor (default 0.707) */
    fun insertBand(index: Int, frequency: Float, gain: Float, filterType: BiquadFilter.FilterType, q: Double = 0.707) {
        val band = EqualizerBand(frequency, gain, filterType, q, true)
        bands.add(index, band)

        val filter = BiquadFilter(frequency, gain, filterType, sampleRate, q).apply {
            // RBJ bell (not Vicanek). RBJ's +G and -G peaking filters are
            // exact inverses (numerator↔denominator swap via A and 1/A),
            // so opposite bells cancel perfectly — in the graph AND the
            // audio (the DP converter samples this same response). Vicanek
            // matched only DC/center/Nyquist, leaving ripple between them
            // that broke cancellation (issue #41).
            useVicanekMethod = false
        }
        filters.add(index, filter)
    }

    /** Remove the band at [index]. No-op if [index] is out of range. */
    fun removeBand(index: Int) {
        if (index in bands.indices) {
            bands.removeAt(index)
            filters.removeAt(index)
        }
    }

    /** Update an existing band's parameters and recalculate its biquad coefficients.
     *  No-op if [index] is out of range. Retains the previous Q when not specified. */
    fun updateBand(index: Int, frequency: Float, gain: Float, filterType: BiquadFilter.FilterType, q: Double = bands.getOrNull(index)?.q ?: 0.707) {
        if (index in bands.indices) {
            bands[index].frequency = frequency
            bands[index].gain = gain
            bands[index].filterType = filterType
            bands[index].q = q

            filters[index].updateParameters(frequency, gain, filterType, q)
        }
    }

    /** Enable or disable the band at [index]. Disabled bands are skipped during [process]. */
    fun setBandEnabled(index: Int, enabled: Boolean) {
        if (index in bands.indices) {
            bands[index].enabled = enabled
        }
    }

    /** Return the band at [index], or null if out of range. */
    fun getBand(index: Int): EqualizerBand? = bands.getOrNull(index)

    /** Return the total number of bands. */
    fun getBandCount(): Int = bands.size

    /** Return a snapshot copy of all bands. */
    fun getAllBands(): List<EqualizerBand> = bands.toList()

    /**
     * Process stereo audio buffer
     * Input/output format: interleaved stereo (L, R, L, R, ...)
     */
    fun process(buffer: FloatArray) {
        if (!isEnabled) return

        var i = 0
        while (i < buffer.size - 1) {
            for (j in filters.indices) {
                if (bands[j].enabled) {
                    filters[j].processStereoInPlace(buffer, i)
                }
            }

            // softClip заменяет tanh — быстрее (без toDouble) и даёт
            // аналогичную мягкую сатурацию без перегрузки буфера
            buffer[i] = softClip(buffer[i])
            buffer[i + 1] = softClip(buffer[i + 1])

            i += 2
        }
    }

    /** Reset all filter states (history buffers). */
    fun reset() {
        filters.forEach { it.reset() }
    }

    /** Evaluate the composite magnitude response at [frequency] Hz across all enabled bands.
     *  @return summed response in dB. */
    fun getFrequencyResponse(frequency: Float): Float {
        var totalMagnitude = 1f

        for (i in filters.indices) {
            if (bands[i].enabled) {
                val magnitude = filters[i].getFrequencyResponse(frequency)
                totalMagnitude *= magnitude
            }
        }

        return 20f * kotlin.math.log10(totalMagnitude.coerceAtLeast(0.0001f))
    }

    /** Response of a single band in dB at [frequency]. Used by the
     *  graph's per-band curve overlay (issue #40) to draw each
     *  filter's individual contribution under the summed white curve.
     *  Returns 0 dB for an out-of-range index or a disabled band so
     *  callers can skip drawing flat lines. */
    fun getBandFrequencyResponse(index: Int, frequency: Float): Float {
        val filter = filters.getOrNull(index) ?: return 0f
        if (bands.getOrNull(index)?.enabled != true) return 0f
        val magnitude = filter.getFrequencyResponse(frequency)
        return 20f * kotlin.math.log10(magnitude.coerceAtLeast(0.0001f))
    }

    /**
     * Returns the effective frequency response after soft-clip saturation,
     * assuming a 0 dBFS reference input. Normalized so flat EQ = 0 dB.
     */
    fun getFrequencyResponseWithSaturation(frequency: Float): Float {
        var totalMagnitude = 1f

        for (i in filters.indices) {
            if (bands[i].enabled) {
                totalMagnitude *= filters[i].getFrequencyResponse(frequency)
            }
        }

        // softClip baseline: clipping applied to flat signal (totalMagnitude = 1)
        val clipRef = softClip(1f)
        val saturated = softClip(totalMagnitude) / clipRef
        return 20f * kotlin.math.log10(saturated.coerceAtLeast(0.0001f).toDouble()).toFloat()
    }

    /** Apply a built-in preset by name. Presets: Flat, Bass Boost, Treble Boost, Vocal Enhance. */
    fun loadPreset(presetName: String) {
        when (presetName) {
            "Flat" -> {
                bands.forEachIndexed { index, _ ->
                    updateBand(index, bands[index].frequency, 0f, bands[index].filterType, bands[index].q)
                }
            }
            "Bass Boost" -> {
                bands.forEachIndexed { index, _ ->
                    // Boost low bands, leave rest flat
                    val ratio = 1f - (index.toFloat() / (bands.size - 1).coerceAtLeast(1))
                    val gain = (ratio * 8f).coerceAtLeast(0f)
                    updateBand(index, bands[index].frequency, gain, bands[index].filterType, bands[index].q)
                }
            }
            "Treble Boost" -> {
                bands.forEachIndexed { index, _ ->
                    val ratio = index.toFloat() / (bands.size - 1).coerceAtLeast(1)
                    val gain = (ratio * 8f).coerceAtLeast(0f)
                    updateBand(index, bands[index].frequency, gain, bands[index].filterType, bands[index].q)
                }
            }
            "Vocal Enhance" -> {
                bands.forEachIndexed { index, _ ->
                    // Mid-focused boost
                    val center = (bands.size - 1) / 2f
                    val dist = kotlin.math.abs(index - center) / center.coerceAtLeast(1f)
                    val gain = (1f - dist) * 4f - 1f
                    updateBand(index, bands[index].frequency, gain, bands[index].filterType, bands[index].q)
                }
            }
        }
    }
}
