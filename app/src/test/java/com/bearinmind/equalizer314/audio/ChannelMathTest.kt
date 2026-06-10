package com.bearinmind.equalizer314.audio

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-Kotlin tests for [ChannelMath] — channel offset and auto-gain
 * computation. Requires no Android dependencies.
 */
class ChannelMathTest {

    // ---- computeAutoGainOffset ----

    @Test
    fun `autoGain no peak returns 0`() {
        val gains = floatArrayOf(-3f, -6f, -2f)
        assertEquals(0f, ChannelMath.computeAutoGainOffset(gains, gains), 0.001f)
    }

    @Test
    fun `autoGain with positive peak returns negative offset`() {
        val left = floatArrayOf(-3f, 4f, -2f)
        val right = floatArrayOf(-3f, 6f, -2f)
        assertEquals(-6f, ChannelMath.computeAutoGainOffset(left, right), 0.001f)
    }

    @Test
    fun `autoGain zero values in one array`() {
        val left = floatArrayOf(0f, 0f, 0f)
        val right = floatArrayOf(-1f, -2f, -3f)
        assertEquals(0f, ChannelMath.computeAutoGainOffset(left, right), 0.001f)
    }

    @Test
    fun `autoGain all negative no offset`() {
        val left = floatArrayOf(-1f, -2f, -3f)
        val right = floatArrayOf(-4f, -5f, -6f)
        assertEquals(0f, ChannelMath.computeAutoGainOffset(left, right), 0.001f)
    }

    @Test
    fun `autoGain single element`() {
        assertEquals(-5f, ChannelMath.computeAutoGainOffset(floatArrayOf(5f), floatArrayOf(-1f)), 0.001f)
    }

    @Test
    fun `autoGain empty arrays`() {
        // Float.NEGATIVE_INFINITY → peak > 0f is false → return 0
        assertEquals(0f, ChannelMath.computeAutoGainOffset(floatArrayOf(), floatArrayOf()), 0.001f)
    }

    // ---- computeChannelOffsets ----

    @Test
    fun `channelOffset center balance`() {
        val (left, right) = ChannelMath.computeChannelOffsets(0, 0f, 0f)
        assertEquals(0f, left, 0.001f)
        assertEquals(0f, right, 0.001f)
    }

    @Test
    fun `channelOffset fully left`() {
        val (left, right) = ChannelMath.computeChannelOffsets(-100, 0f, 0f)
        assertEquals(0f, left, 0.001f)   // left channel: balance is -100 → rightBalanceDb = 0, leftBalanceDb = 0
        // Wait — pct = -100, so pct < 0 → rightBalanceDb applied
        // pct = -100 < 0 → rightBalanceDb = 20*log10((100 + (-100))/100) = 20*log10(0) = -inf
        // But coerceAtLeast(1e-4f) gives 1e-4 → 20*log10(1e-4) = 20*(-4) = -80
        // Then coerceIn(-60, 24) → -60
        // So left stays at 0, right goes to -60
        assertEquals(0f, left, 0.001f)
        assertEquals(-60f, right, 0.001f)
    }

    @Test
    fun `channelOffset fully right`() {
        val (left, right) = ChannelMath.computeChannelOffsets(100, 0f, 0f)
        // pct = 100 > 0
        // leftBalanceDb = 20*log10((100-100)/100) = 20*log10(0) = -inf → coerceAtLeast 1e-4 → -80 → clamp to -60
        // rightBalanceDb = 0
        assertEquals(-60f, left, 0.001f)
        assertEquals(0f, right, 0.001f)
    }

    @Test
    fun `channelOffset partial pan`() {
        val (left, right) = ChannelMath.computeChannelOffsets(30, 0f, 0f)
        // pct = 30 > 0
        // leftBalanceDb = 20*log10((100-30)/100) = 20*log10(0.7) ≈ 20*(-0.1549) = -3.098
        // rightBalanceDb = 0
        assertEquals(-3.098f, left, 0.01f)
        assertEquals(0f, right, 0.01f)
    }

    @Test
    fun `channelOffset with perChannel gain`() {
        val (left, right) = ChannelMath.computeChannelOffsets(0, 3f, -2f)
        // pct = 0 → no balance attenuation
        // left = 3 + 0 = 3
        // right = -2 + 0 = -2
        assertEquals(3f, left, 0.001f)
        assertEquals(-2f, right, 0.001f)
    }

    @Test
    fun `channelOffset with gain and pan`() {
        val (left, right) = ChannelMath.computeChannelOffsets(50, 2f, 1f)
        // pct = 50 > 0
        // leftBalanceDb = 20*log10((100-50)/100) = 20*log10(0.5) = -6.0206
        // left = 2 + (-6.0206) = -4.0206
        // right = 1 + 0 = 1
        assertEquals(-4.0206f, left, 0.01f)
        assertEquals(1f, right, 0.01f)
    }

    @Test
    fun `channelOffset clamp above max`() {
        val (left, right) = ChannelMath.computeChannelOffsets(0, 50f, 0f)
        // left = 50 → clamped to 24
        assertEquals(24f, left, 0.001f)
        assertEquals(0f, right, 0.001f)
    }

    @Test
    fun `channelOffset clamp below min`() {
        val (left, right) = ChannelMath.computeChannelOffsets(0, -70f, 0f)
        // left = -70 → clamped to -60
        assertEquals(-60f, left, 0.001f)
        assertEquals(0f, right, 0.001f)
    }

    @Test
    fun `channelOffset balancePercent out of bounds`() {
        val (left, right) = ChannelMath.computeChannelOffsets(200, 0f, 0f)
        // pct coerced to 100 → same as fully right
        assertEquals(-60f, left, 0.001f)
        assertEquals(0f, right, 0.001f)
    }

    @Test
    fun `channelOffset negative balance`() {
        val (left, right) = ChannelMath.computeChannelOffsets(-50, 0f, 0f)
        // pct = -50 < 0 → rightBalanceDb applied
        // rightBalanceDb = 20*log10((100+(-50))/100) = 20*log10(0.5) = -6.0206
        // leftBalanceDb = 0
        assertEquals(0f, left, 0.01f)
        assertEquals(-6.0206f, right, 0.01f)
    }
}
