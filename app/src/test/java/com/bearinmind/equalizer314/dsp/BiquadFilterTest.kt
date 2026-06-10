package com.bearinmind.equalizer314.dsp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [BiquadFilter] — verifies coefficient calculation,
 * frequency response magnitude, and stereo in-place processing.
 */
class BiquadFilterTest {

    private companion object {
        const val SAMPLE_RATE = 48000
        const val TOLERANCE = 1e-10
        const val TOLERANCE_GAIN = 1e-6
    }

    private fun assertResponse(expected: Double, actual: Float, delta: Double) {
        assertEquals(expected, actual.toDouble(), delta)
    }

    private fun assertResponseTrue(actual: Float, delta: Double) {
        assertEquals(1.0, actual.toDouble(), delta)
    }

    @Test
    fun `bell filter at 0 dB is passthrough`() {
        val filter = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.BELL, SAMPLE_RATE, 0.707)
        val response = filter.getFrequencyResponse(1000f)
        assertResponseTrue(response, TOLERANCE_GAIN)
    }

    @Test
    fun `bell filter resonance at Fc matches expected gain`() {
        val gainDb = 6f
        val filter = BiquadFilter(1000f, gainDb, BiquadFilter.FilterType.BELL, SAMPLE_RATE, 1.0)
        val responseLinear = filter.getFrequencyResponse(1000f)
        val responseDb = 20f * kotlin.math.log10(responseLinear.coerceAtLeast(1e-10f))
        assertResponse(gainDb.toDouble(), responseDb, 0.5)
    }

    @Test
    fun `bell filter far from Fc is unity`() {
        val filter = BiquadFilter(1000f, 12f, BiquadFilter.FilterType.BELL, SAMPLE_RATE, 0.707)
        val response = filter.getFrequencyResponse(20f)
        assertEquals("BELL response at 20 Hz (far from 1 kHz) should be ~1.0", 1.0, response.toDouble(), 0.05)
    }

    @Test
    fun `low shelf boost at low frequency`() {
        val gainDb = 8f
        val filter = BiquadFilter(200f, gainDb, BiquadFilter.FilterType.LOW_SHELF, SAMPLE_RATE, 1.0)
        val responseLinear = filter.getFrequencyResponse(50f)
        val responseDb = 20f * kotlin.math.log10(responseLinear.coerceAtLeast(1e-10f))
        assertTrue("LOW_SHELF +8 dB should boost low freqs (got $responseDb dB)", responseDb > 4f)
    }

    @Test
    fun `high shelf boost at high frequency`() {
        val filter = BiquadFilter(5000f, 6f, BiquadFilter.FilterType.HIGH_SHELF, SAMPLE_RATE, 1.0)
        val responseLinear = filter.getFrequencyResponse(15000f)
        val responseDb = 20f * kotlin.math.log10(responseLinear.coerceAtLeast(1e-10f))
        assertTrue("HIGH_SHELF +6 dB should boost high freqs (got $responseDb dB)", responseDb > 3f)
    }

    @Test
    fun `low pass attenuates above cutoff`() {
        val filter = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.LOW_PASS, SAMPLE_RATE, 0.707)
        val below = filter.getFrequencyResponse(100f)
        val above = filter.getFrequencyResponse(8000f)
        assertTrue("LPF passband should pass ($below), stopband should attenuate ($above)",
            above < below)
    }

    @Test
    fun `high pass attenuates below cutoff`() {
        val filter = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.HIGH_PASS, SAMPLE_RATE, 0.707)
        val below = filter.getFrequencyResponse(100f)
        val above = filter.getFrequencyResponse(8000f)
        assertTrue("HPF passband ($above) should be > stopband ($below)", above > below)
    }

    @Test
    fun `band pass peaks at Fc`() {
        val filter = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.BAND_PASS, SAMPLE_RATE, 2.0)
        val atFc = filter.getFrequencyResponse(1000f)
        val far = filter.getFrequencyResponse(50f)
        assertTrue("BPF at Fc ($atFc) should be > far from Fc ($far)", atFc > far)
    }

    @Test
    fun `notch nulls at Fc`() {
        val filter = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.NOTCH, SAMPLE_RATE, 10.0)
        val atFc = filter.getFrequencyResponse(1000f)
        val offFc = filter.getFrequencyResponse(500f)
        assertTrue("NOTCH at Fc ($atFc) should be < off Fc ($offFc)", atFc < offFc)
    }

    @Test
    fun `all pass is unity magnitude`() {
        val filter = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.ALL_PASS, SAMPLE_RATE, 0.707)
        val r1 = filter.getFrequencyResponse(100f)
        val r2 = filter.getFrequencyResponse(1000f)
        val r3 = filter.getFrequencyResponse(10000f)
        assertEquals("ALL_PASS at 100 Hz", 1.0, r1.toDouble(), TOLERANCE_GAIN)
        assertEquals("ALL_PASS at 1 kHz", 1.0, r2.toDouble(), TOLERANCE_GAIN)
        assertEquals("ALL_PASS at 10 kHz", 1.0, r3.toDouble(), TOLERANCE_GAIN)
    }

    @Test
    fun `1st order low pass attenuates above cutoff`() {
        val filter = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.LOW_PASS_1, SAMPLE_RATE)
        val above = filter.getFrequencyResponse(8000f)
        val below = filter.getFrequencyResponse(100f)
        assertTrue("LPF-1 should attenuate above cutoff ($above < $below)", above < below)
    }

    @Test
    fun `1st order high shelf boosts at high frequency`() {
        val filter = BiquadFilter(1000f, 6f, BiquadFilter.FilterType.HIGH_SHELF_1, SAMPLE_RATE)
        val response = filter.getFrequencyResponse(10000f)
        assertTrue("HIGH_SHELF_1 +6 dB should boost ($response > 1.0)", response > 1.0)
    }

    @Test
    fun `processStereoInPlace preserves stereo balance`() {
        val filter = BiquadFilter(1000f, 0f, BiquadFilter.FilterType.BELL, SAMPLE_RATE, 0.707)
        val input = floatArrayOf(0.5f, -0.3f) // L, R
        filter.processStereoInPlace(input, 0)
        // At 0 dB gain, output should approximately equal input
        assertEquals("Left channel", 0.5, input[0].toDouble(), 0.01)
        assertEquals("Right channel", -0.3, input[1].toDouble(), 0.01)
    }

    @Test
    fun `reset clears filter state`() {
        val filter = BiquadFilter(1000f, 6f, BiquadFilter.FilterType.BELL, SAMPLE_RATE, 1.0)
        val input = FloatArray(2) { 1.0f }
        filter.processStereoInPlace(input, 0)
        filter.reset()
        // After reset, next sample should not depend on previous state
        val input2 = floatArrayOf(0.5f, 0.5f)
        filter.processStereoInPlace(input2, 0)
        assertFalse("Output should be finite", input2[0].isInfinite())
        assertFalse("Output should be finite", input2[1].isInfinite())
    }

    @Test
    fun `vicanek bell matches standard bell for moderate Q`() {
        val stdFilter = BiquadFilter(1000f, 6f, BiquadFilter.FilterType.BELL, SAMPLE_RATE, 0.707)
        stdFilter.useVicanekMethod = false
        val vicanekFilter = BiquadFilter(1000f, 6f, BiquadFilter.FilterType.BELL, SAMPLE_RATE, 0.707)
        vicanekFilter.useVicanekMethod = true

        // At moderate gain and Q, both methods should produce similar Fc magnitude
        val stdResp = stdFilter.getFrequencyResponse(1000f)
        val vicResp = vicanekFilter.getFrequencyResponse(1000f)
        val stdDb = 20.0 * kotlin.math.log10(stdResp.coerceAtLeast(1e-10f).toDouble())
        val vicDb = 20.0 * kotlin.math.log10(vicResp.coerceAtLeast(1e-10f).toDouble())
        assertEquals("Vicanek and standard should agree within 0.1 dB at Fc for moderate Q",
            stdDb, vicDb, 0.1)
    }

    @Test
    fun `all 12 filter types produce finite coefficients`() {
        for (type in BiquadFilter.FilterType.entries) {
            val filter = BiquadFilter(1000f, 3f, type, SAMPLE_RATE, 1.0)
            val r = filter.getFrequencyResponse(1000f)
            assertFalse("$type response must not be NaN", r.isNaN())
            assertFalse("$type response must not be infinite", r.isInfinite())
            assertTrue("$type response must be positive (got $r)", r > 0f)
        }
    }
}
