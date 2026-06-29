package com.bearinmind.equalizer314.ui

import kotlin.math.log10
import kotlin.math.pow

/**
 * Pure math functions для MBC (Multiband Compressor) отрисовки.
 *
 * Содержит:
 * - Linkwitz-Riley 4th-order crossover math (LR4)
 * - Компрессорную статическую gain-кривую (Giannoulis/Massberg/Reiss 2012)
 * - Экспандер/noise-gate gain
 *
 * Все функции чисты (pure), не имеют состояния и Android-зависимостей.
 */
object EqGraphMbcMath {

    // ── Linkwitz-Riley 4th-order crossover ─────────────────────
    // LR4 = two cascaded 2nd-order Butterworth filters
    // Lowpass amplitude:  |H_LP(f)| = 1 / (1 + (f/fc)^4)
    // Highpass amplitude: |H_HP(f)| = (f/fc)^4 / (1 + (f/fc)^4)
    // LP + HP = 1 (flat sum at all frequencies), -6 dB each at crossover

    /**
     * LR4 lowpass amplitude на частоте [f] с частотой среза [fc].
     */
    fun lr4LowpassAmplitude(f: Float, fc: Float): Float {
        val ratio = f / fc
        val r4 = ratio * ratio * ratio * ratio
        return 1f / (1f + r4)
    }

    /**
     * LR4 highpass amplitude на частоте [f] с частотой среза [fc].
     */
    fun lr4HighpassAmplitude(f: Float, fc: Float): Float {
        val ratio = f / fc
        val r4 = ratio * ratio * ratio * ratio
        return r4 / (1f + r4)
    }

    /**
     * Амплитуда кроссовер-фильтра для полосы [bandIndex] на частоте [freq].
     *
     * Band 0 (lowest):  LP at crossover[0]
     * Band i (middle):  HP at crossover[i-1] × LP at crossover[i]
     * Band N-1 (highest): HP at crossover[N-2]
     */
    fun mbcBandAmplitude(bandIndex: Int, freq: Float, crossovers: FloatArray): Float {
        val bandCount = crossovers.size + 1
        var amplitude = 1f
        if (bandIndex > 0) {
            amplitude *= lr4HighpassAmplitude(freq, crossovers[bandIndex - 1])
        }
        if (bandIndex < bandCount - 1) {
            amplitude *= lr4LowpassAmplitude(freq, crossovers[bandIndex])
        }
        return amplitude
    }

    /**
     * Суммарная MBC gain в dB на частоте [freq].
     * Каждая полоса вносит: crossover_amplitude × band_gain_linear.
     * Суммирование в linear domain → конвертация в dB.
     */
    fun mbcGainAtFreq(freq: Float, crossovers: FloatArray, gains: FloatArray): Float {
        val bandCount = crossovers.size + 1
        var totalLinear = 0f
        for (i in 0 until bandCount) {
            val amplitude = mbcBandAmplitude(i, freq, crossovers)
            val gain = gains.getOrElse(i) { 0f }
            val bandLinear = 10f.pow(gain / 20f)
            totalLinear += amplitude * bandLinear
        }
        return 20f * log10(totalLinear.coerceAtLeast(0.00001f))
    }

    /**
     * Находит индекс MBC-полосы, которой принадлежит частота [freq],
     * по массиву кроссоверов [crossovers].
     */
    fun getMbcBandForFreq(freq: Float, crossovers: FloatArray): Int {
        for (i in crossovers.indices) {
            if (freq < crossovers[i]) return i
        }
        return crossovers.size
    }

    // ── Compressor static gain curve ────────────────────────────
    // Soft knee gain computer (Giannoulis/Massberg/Reiss 2012):
    //   if x < (T - W/2):   gc = 0
    //   if (T-W/2) ≤ x ≤ (T+W/2):  gc = (1/R - 1) × (x - T + W/2)² / (2W)
    //   if x > (T + W/2):   gc = (1/R - 1) × (x - T)
    // T = threshold, R = ratio, W = knee width, x = input dB, gc = gain change dB

    /**
     * Gain reduction (dB) компрессора с soft-knee.
     * Всегда ≤ 0 (или 0, если ratio ≤ 1).
     */
    fun compressorGainDb(inputDb: Float, threshold: Float, ratio: Float, kneeWidth: Float): Float {
        if (ratio <= 1f) return 0f
        return when {
            inputDb < (threshold - kneeWidth / 2f) -> 0f
            inputDb > (threshold + kneeWidth / 2f) -> (1f / ratio - 1f) * (inputDb - threshold)
            else -> {
                val diff = inputDb - threshold + kneeWidth / 2f
                (1f / ratio - 1f) * diff * diff / (2f * kneeWidth)
            }
        }
    }

    /**
     * Gain reduction (dB) экспандера/noise-gate.
     * Для сигналов ниже [noiseGateThreshold]: gain = (expanderRatio-1) × (input - noiseGateThreshold)
     * Результат ≤ 0.
     */
    fun expanderGainDb(inputDb: Float, noiseGateThreshold: Float, expanderRatio: Float): Float {
        if (inputDb >= noiseGateThreshold || expanderRatio <= 1f) return 0f
        return (expanderRatio - 1f) * (inputDb - noiseGateThreshold)
    }
}
