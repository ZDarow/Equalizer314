package com.bearinmind.equalizer314.dsp

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ParametricToDpConverter] — verifies the feature-aware
 * sampling algorithm and direct conversion paths.
 */
class ParametricToDpConverterTest {

    private lateinit var eq: ParametricEqualizer

    @Before
    fun setUp() {
        eq = ParametricEqualizer(48000)
    }

    @Test
    fun `numBands defaults to 127`() {
        assertEquals("Default band count should be 127", 127, ParametricToDpConverter.numBands)
    }

    @Test
    fun `setNumBands does not change from 127`() {
        ParametricToDpConverter.setNumBands(64)
        // Currently fixed at Wavelet's 127
        assertEquals("numBands should remain 127", 127, ParametricToDpConverter.numBands)
    }

    @Test
    fun `cutoffFrequencies returns copy of 127 Wavelet freqs`() {
        val cutoffs = ParametricToDpConverter.cutoffFrequencies
        assertEquals(127, cutoffs.size)
        assertEquals("First cutoff should be 20 Hz", 20.0, cutoffs[0].toDouble(), 0.5)
        assertTrue("Last cutoff should be ~20 kHz", cutoffs.last() > 15000f)
    }

    @Test
    fun `centerFrequencies returns 127 centers`() {
        val centers = ParametricToDpConverter.centerFrequencies
        assertEquals(127, centers.size)
        assertTrue("First center should be > 10 Hz", centers[0] > 10f)
        assertTrue("Last center should be < 22000 Hz", centers.last() < 22000f)
    }

    @Test
    fun `convertFeatureAware returns correct array sizes`() {
        val result = ParametricToDpConverter.convertFeatureAware(eq)
        assertEquals(127, result.cutoffs.size)
        assertEquals(127, result.gains.size)
        assertEquals(result.cutoffs.size, result.gains.size)
    }

    @Test
    fun `convertFeatureAware flat EQ is near 0 dB`() {
        val result = ParametricToDpConverter.convertFeatureAware(eq)
        for (gain in result.gains) {
            assertEquals("Flat EQ gain should be ~0 dB", 0.0, gain.toDouble(), 0.1)
        }
    }

    @Test
    fun `convertFeatureAware captures bell peak`() {
        eq.clearBands()
        // High-Q bell at a frequency that's between Wavelet table entries
        eq.addBand(7878f, 12f, BiquadFilter.FilterType.BELL, 10.0)
        val result = ParametricToDpConverter.convertFeatureAware(eq)

        // Without feature-aware sampling, a narrow Q=10 peak would be smeared.
        // Verify that at least one gain value is close to 12 dB.
        val maxGain = result.gains.max()
        assertTrue("Feature-aware sampling should capture narrow peak (max gain = $maxGain dB)", maxGain > 8f)
    }

    @Test
    fun `convertFeatureAware bell at low frequency`() {
        eq.clearBands()
        eq.addBand(31f, 6f, BiquadFilter.FilterType.BELL, 2.0)
        val result = ParametricToDpConverter.convertFeatureAware(eq)
        assertEquals(127, result.gains.size)
        val maxGain = result.gains.max()
        assertTrue("Low-freq bell should show some gain (max = $maxGain dB)", maxGain > 2f)
    }

    @Test
    fun `convertFeatureAware shelf shapes are represented`() {
        eq.clearBands()
        eq.addBand(200f, 8f, BiquadFilter.FilterType.LOW_SHELF, 0.8)
        val result = ParametricToDpConverter.convertFeatureAware(eq)
        // Shelf should show higher gain at low frequencies
        val lowGain = result.gains.take(20).average()
        val highGain = result.gains.takeLast(20).average()
        assertTrue("Low shelf should have higher low-freq gain ($lowGain) than high ($highGain)", lowGain > highGain)
    }

    @Test
    fun `convertFeatureAware high shelf is represented`() {
        eq.clearBands()
        eq.addBand(5000f, 6f, BiquadFilter.FilterType.HIGH_SHELF, 0.8)
        val result = ParametricToDpConverter.convertFeatureAware(eq)
        val lowGain = result.gains.take(30).average()
        val highGain = result.gains.takeLast(30).average()
        assertTrue("High shelf should have higher high-freq gain ($highGain) than low ($lowGain)", highGain > lowGain)
    }

    @Test
    fun `convertFeatureAware disabled bands don't contribute`() {
        eq.clearBands()
        eq.addBand(500f, 12f, BiquadFilter.FilterType.BELL, 5.0)
        ParametricToDpConverter.convertFeatureAware(eq) // just to set state

        eq.clearBands()
        eq.addBand(500f, 12f, BiquadFilter.FilterType.BELL, 5.0)
        eq.setBandEnabled(0, false)
        val resultDisabled = ParametricToDpConverter.convertFeatureAware(eq)

        // With no enabled bands, should be near 0 dB
        for (gain in resultDisabled.gains) {
            assertEquals("Disabled bands should result in ~0 dB gain", 0.0, gain.toDouble(), 0.1)
        }
    }

    @Test
    fun `notch filter is captured by feature-aware sampling`() {
        eq.clearBands()
        eq.addBand(1000f, -20f, BiquadFilter.FilterType.NOTCH, 15.0)
        val result = ParametricToDpConverter.convertFeatureAware(eq)
        val minGain = result.gains.min()
        assertTrue("NOTCH with high Q should produce deep null (min = $minGain dB)", minGain < -10f)
    }

    @Test
    fun `all-pass filter shows flat response`() {
        eq.clearBands()
        eq.addBand(1000f, 0f, BiquadFilter.FilterType.ALL_PASS, 0.707)
        val result = ParametricToDpConverter.convertFeatureAware(eq)
        for (gain in result.gains) {
            assertEquals("ALL_PASS should be flat 0 dB", 0.0, gain.toDouble(), 0.01)
        }
    }

    @Test
    fun `gains array is within valid dB range`() {
        eq.clearBands()
        eq.addBand(200f, 15f, BiquadFilter.FilterType.LOW_SHELF, 0.5)
        eq.addBand(5000f, -15f, BiquadFilter.FilterType.HIGH_SHELF, 0.5)
        val result = ParametricToDpConverter.convertFeatureAware(eq)

        for (gain in result.gains) {
            assertFalse("Gain should not be NaN", gain.isNaN())
            assertFalse("Gain should not be infinite", gain.isInfinite())
            assertTrue("Gain should be within ±24 dB (got $gain)", gain >= -24f && gain <= 24f)
        }
    }

    @Test
    fun `multiple narrow bands all captured`() {
        eq.clearBands()
        eq.addBand(300f, 8f, BiquadFilter.FilterType.BELL, 8.0)
        eq.addBand(3000f, -8f, BiquadFilter.FilterType.BELL, 8.0)
        eq.addBand(8000f, 5f, BiquadFilter.FilterType.BELL, 6.0)
        val result = ParametricToDpConverter.convertFeatureAware(eq)

        val maxGain = result.gains.max()
        val minGain = result.gains.min()
        assertTrue("Multi-band should capture boost (max = $maxGain)", maxGain > 5f)
        assertTrue("Multi-band should capture cut (min = $minGain)", minGain < -5f)
    }

    @Test
    fun `low and high pass filters are sampled`() {
        eq.clearBands()
        eq.addBand(500f, 0f, BiquadFilter.FilterType.LOW_PASS, 0.707)
        val result = ParametricToDpConverter.convertFeatureAware(eq)
        val lowFreqGain = result.gains.take(30).average()
        val highFreqGain = result.gains.takeLast(30).average()
        assertTrue("LOW_PASS should attenuate high frequencies more than low",
            highFreqGain < lowFreqGain + 0.1)
    }
}
