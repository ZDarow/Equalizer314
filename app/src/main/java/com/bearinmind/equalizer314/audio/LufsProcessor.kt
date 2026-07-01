package com.bearinmind.equalizer314.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow

/**
 * LUFS = Loudness Units relative to Full Scale
 *
 * K-weighted LUFS Momentary loudness processor.
 * Implements ITU-R BS.1770-4 K-weighting + 400ms sliding window RMS.
 *
 * LUFS is the international standard for measuring perceived loudness.
 * Used by streaming platforms (Spotify: -14 LUFS, YouTube: -13, Apple Music: -16)
 * and broadcast standard EBU R128.
 *
 * K-weighting = two cascaded biquad filters:
 * 1. Pre-filter (high shelf): +4 dB above ~1.5 kHz — models head/ear canal resonance
 * 2. RLB filter (highpass): rolls off below ~100 Hz — de-emphasizes bass (ears less sensitive)
 *
 * Then: RMS over a 400ms sliding window → LUFS = -0.691 + 10 * log10(meanSquare)
 *
 * Коэффициенты вычисляются через bilinear transform из аналоговых прототипов
 * ITU-R BS.1770-4, что позволяет работать на любой частоте дискретизации.
 * Ранее коэффициенты были захардкожены для 48 кГц (баг C-6).
 *
 * Documentation & references used:
 * - ITU-R BS.1770-4 (the actual standard defining K-weighting and LUFS measurement)
 * - pyloudnorm (github.com/csteinmetz1/pyloudnorm) — MIT, Python reference implementation
 *   Source of the exact biquad coefficients used here
 * - libebur128 (github.com/jiixyj/libebur128) — MIT, C implementation of EBU R128
 *   Cross-referenced filter coefficients and sliding window approach
 */
class LufsProcessor(sampleRate: Int = 48000) {

    // ── K-weighting filter coefficients (var, пересчитываются при [recalculate]) ──

    // Pre-filter (high shelf)
    private var preB0 = 0.0
    private var preB1 = 0.0
    private var preB2 = 0.0
    private var preA1 = 0.0
    private var preA2 = 0.0

    // RLB (revised low-frequency B-curve) highpass
    private var rlbB0 = 0.0
    private var rlbB1 = 0.0
    private var rlbB2 = 0.0
    private var rlbA1 = 0.0
    private var rlbA2 = 0.0

    // Pre-filter state
    private var preX1 = 0.0
    private var preX2 = 0.0
    private var preY1 = 0.0
    private var preY2 = 0.0

    // RLB filter state
    private var rlbX1 = 0.0
    private var rlbX2 = 0.0
    private var rlbY1 = 0.0
    private var rlbY2 = 0.0

    // ── 400ms sliding window for Momentary LUFS ──
    // At ~20 captures/sec from Visualizer, 400ms ≈ 8 captures
    // But we compute per-capture RMS and smooth, so we use a ring buffer of squared values
    private val windowSize = 12  // ~400ms at 30fps timer
    private val windowBuffer = FloatArray(windowSize)
    private var windowIdx = 0
    private var windowFilled = false

    init {
        recalculate(sampleRate)
    }

    /**
     * Пересчитывает коэффициенты K-weighting фильтров для новой [sampleRate].
     * Использует DeMan-вариант high_shelf/high_pass (bilinear transform с prewarping
     * через K = tan(π·fc/fs)), который даёт точное совпадение с pyloudnorm/ITU-R BS.1770-4.
     * Безопасно вызывать в любое время; сбрасывает состояние фильтров.
     */
    fun recalculate(sampleRate: Int) {
        val fs = sampleRate.toDouble()

        // ── Pre-filter (high shelf DeMan) ──
        // Параметры из pyloudnorm: G=3.99984385397 dB, Q=0.7071752369554193, fc=1681.9744509555319 Hz
        val preFc = 1681.9744509555319
        val preG = 3.99984385397
        val preQ = 0.7071752369554193

        val preK = kotlin.math.tan(PI * preFc / fs)
        val preVh = 10.0.pow(preG / 20.0)
        val preVb = preVh.pow(0.499666774155)
        val preK2 = preK * preK
        val preKOverQ = preK / preQ
        val preDen = 1.0 + preKOverQ + preK2

        preB0 = (preVh + preVb * preKOverQ + preK2) / preDen
        preB1 = 2.0 * (preK2 - preVh) / preDen
        preB2 = (preVh - preVb * preKOverQ + preK2) / preDen
        preA1 = 2.0 * (preK2 - 1.0) / preDen
        preA2 = (1.0 - preKOverQ + preK2) / preDen

        // ── RLB (high pass DeMan) ──
        // Параметры из pyloudnorm: Q=0.5003270373253953, fc=38.13547087613982 Hz
        val rlbFc = 38.13547087613982
        val rlbQ = 0.5003270373253953

        val rlbK = kotlin.math.tan(PI * rlbFc / fs)
        val rlbK2 = rlbK * rlbK
        val rlbKOverQ = rlbK / rlbQ
        val rlbDen = 1.0 + rlbKOverQ + rlbK2

        // DeMan high_pass: b = [1, -2, 1] (не зависит от fs)
        rlbB0 = 1.0
        rlbB1 = -2.0
        rlbB2 = 1.0
        rlbA1 = 2.0 * (rlbK2 - 1.0) / rlbDen
        rlbA2 = (1.0 - rlbKOverQ + rlbK2) / rlbDen

        reset()
    }

    /**
     * Process a waveform capture from the Visualizer.
     * Applies K-weighting, computes mean square, stores in window.
     *
     * @param waveform Raw unsigned 8-bit bytes from Visualizer
     * @return Momentary LUFS value in dB (typically -60 to 0)
     */
    fun processWaveform(waveform: ByteArray): Float {
        var sumSquared = 0.0
        var count = 0

        for (b in waveform) {
            val sample = ((b.toInt() and 0xFF) - 128) / 128.0

            // Stage 1: Pre-filter (high shelf)
            val preOut = preB0 * sample + preB1 * preX1 + preB2 * preX2 - preA1 * preY1 - preA2 * preY2
            preX2 = preX1; preX1 = sample
            preY2 = preY1; preY1 = preOut

            // Stage 2: RLB highpass
            val rlbOut = rlbB0 * preOut + rlbB1 * rlbX1 + rlbB2 * rlbX2 - rlbA1 * rlbY1 - rlbA2 * rlbY2
            rlbX2 = rlbX1; rlbX1 = preOut
            rlbY2 = rlbY1; rlbY1 = rlbOut

            // Accumulate squared K-weighted output
            sumSquared += rlbOut * rlbOut
            count++
        }

        // Mean square for this capture
        val meanSquare = if (count > 0) (sumSquared / count).toFloat() else 0f

        // Store in sliding window
        windowBuffer[windowIdx % windowSize] = meanSquare
        windowIdx++
        if (windowIdx >= windowSize) windowFilled = true

        // Compute Momentary LUFS from window
        val windowLen = if (windowFilled) windowSize else windowIdx
        var windowSum = 0f
        for (i in 0 until windowLen) {
            windowSum += windowBuffer[i]
        }
        val windowMeanSquare = windowSum / windowLen

        // Convert to LUFS: -0.691 + 10 * log10(meanSquare)
        // The -0.691 offset is from ITU-R BS.1770 (adjusts to match subjective loudness scale)
        return if (windowMeanSquare > 1e-10f) {
            (-0.691f + 10f * log10(windowMeanSquare)).coerceIn(-80f, 10f)
        } else {
            -80f
        }
    }

    fun reset() {
        preX1 = 0.0; preX2 = 0.0; preY1 = 0.0; preY2 = 0.0
        rlbX1 = 0.0; rlbX2 = 0.0; rlbY1 = 0.0; rlbY2 = 0.0
        windowBuffer.fill(0f)
        windowIdx = 0
        windowFilled = false
    }
}
