package com.bearinmind.equalizer314.audio

import kotlin.math.log10

/**
 * Pure-math utilities for channel offset and auto-gain computation,
 * extracted from [DynamicsProcessingManager] so they can be unit-tested
 * without Android dependencies.
 */
object ChannelMath {

    /**
     * Compute the flat dB offset to apply to each channel via the
     * input-gain stage, combining per-channel preamp gain with balance
     * attenuation.
     *
     * Balance semantics: the side being panned TOWARD stays at 0 dB
     * relative to preamp; the opposite side is attenuated. Pan wins
     * over preamp, so a fully-left pan mutes the right channel
     * regardless of right preamp.
     *
     * @param balancePercent -100..100, 0 = center.
     * @param leftGainDb per-channel preamp gain in dB.
     * @param rightGainDb per-channel preamp gain in dB.
     * @return (leftDbOffset, rightDbOffset) each clamped to [-60, +24].
     */
    fun computeChannelOffsets(
        balancePercent: Int,
        leftGainDb: Float,
        rightGainDb: Float,
    ): Pair<Float, Float> {
        val pct = balancePercent.coerceIn(-100, 100)
        val leftBalanceDb = if (pct > 0) {
            val ratio = ((100 - pct) / 100f).coerceAtLeast(1e-4f)
            20f * log10(ratio)
        } else 0f
        val rightBalanceDb = if (pct < 0) {
            val ratio = ((100 + pct) / 100f).coerceAtLeast(1e-4f)
            20f * log10(ratio)
        } else 0f
        val left = (leftGainDb + leftBalanceDb).coerceIn(-60f, 24f)
        val right = (rightGainDb + rightBalanceDb).coerceIn(-60f, 24f)
        return Pair(left, right)
    }

    /**
     * Compute the auto-gain offset: the amount by which to shift all
     * band gains so the loudest band is ≤ 0 dB.
     *
     * @return negative offset (or 0 if no band exceeds 0 dB).
     */
    fun computeAutoGainOffset(leftGains: FloatArray, rightGains: FloatArray): Float {
        // Защита от ArrayIndexOutOfBounds при пустых массивах.
        // Проходим по каждому массиву независимо, чтобы избежать AIOOBE
        // при несовпадающих размерах (см. ревью кода B-1).
        if (leftGains.isEmpty() && rightGains.isEmpty()) return 0f
        var peak = Float.NEGATIVE_INFINITY
        for (i in leftGains.indices) {
            if (leftGains[i] > peak) peak = leftGains[i]
        }
        for (i in rightGains.indices) {
            if (rightGains[i] > peak) peak = rightGains[i]
        }
        return if (peak > 0f) -peak else 0f
    }
}
