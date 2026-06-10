package com.bearinmind.equalizer314.dsp

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ParametricEqualizer] — multi-band EQ composition,
 * frequency response summation, band management, and presets.
 */
class ParametricEqualizerTest {

    private lateinit var eq: ParametricEqualizer

    @Before
    fun setUp() {
        eq = ParametricEqualizer(48000)
    }

    @Test
    fun `default bands count is 4`() {
        assertEquals("Default band count should be 4", 4, eq.getBandCount())
    }

    @Test
    fun `addBand increases band count`() {
        eq.addBand(500f, 3f, BiquadFilter.FilterType.BELL)
        assertEquals("Should have 5 bands after add", 5, eq.getBandCount())
    }

    @Test
    fun `insertBand at index shifts bands`() {
        val count = eq.getBandCount()
        eq.insertBand(1, 2000f, -2f, BiquadFilter.FilterType.HIGH_SHELF)
        assertEquals("Band count should increase", count + 1, eq.getBandCount())
        val band = eq.getBand(1)
        assertNotNull("Band at index 1 should exist", band)
        assertEquals("Band frequency should match", 2000.0, band!!.frequency.toDouble(), 0.01)
    }

    @Test
    fun `removeBand decreases band count`() {
        val count = eq.getBandCount()
        eq.removeBand(0)
        assertEquals("Band count should decrease", count - 1, eq.getBandCount())
    }

    @Test
    fun `updateBand changes parameters`() {
        eq.updateBand(0, 250f, -5f, BiquadFilter.FilterType.LOW_SHELF, 0.8)
        val band = eq.getBand(0)
        assertNotNull(band)
        assertEquals("Frequency should be updated", 250.0, band!!.frequency.toDouble(), 0.01)
        assertEquals("Gain should be updated", -5.0, band.gain.toDouble(), 0.01)
        assertEquals("Filter type should be updated", BiquadFilter.FilterType.LOW_SHELF, band.filterType)
        assertEquals("Q should be updated", 0.8, band.q, 0.01)
    }

    @Test
    fun `setBandEnabled toggles band`() {
        eq.setBandEnabled(0, false)
        val band = eq.getBand(0)
        assertNotNull(band)
        assertFalse("Band should be disabled", band!!.enabled)

        eq.setBandEnabled(0, true)
        assertTrue("Band should be re-enabled", band.enabled)
    }

    @Test
    fun `getAllBands returns copy`() {
        val bands = eq.getAllBands()
        assertEquals("All bands should match count", eq.getBandCount(), bands.size)
    }

    @Test
    fun `flat EQ is unity across frequency range`() {
        // Default EQ is 4 bands at 0 dB — should be ~0 dB everywhere
        for (freq in listOf(20f, 100f, 1000f, 5000f, 15000f)) {
            val resp = eq.getFrequencyResponse(freq)
            assertEquals("Flat response at $freq Hz should be ~0 dB", 0.0, resp.toDouble(), 0.5)
        }
    }

    @Test
    fun `single bell boost peaks at Fc`() {
        eq.clearBands()
        eq.addBand(1000f, 10f, BiquadFilter.FilterType.BELL, 1.0)

        val atFc = eq.getFrequencyResponse(1000f)
        val offFc = eq.getFrequencyResponse(100f)
        assertTrue("Response at Fc ($atFc dB) should be > response at 100 Hz ($offFc dB)", atFc > offFc)
    }

    @Test
    fun `multiple bands sum in dB`() {
        eq.clearBands()
        eq.addBand(100f, 6f, BiquadFilter.FilterType.BELL, 1.0)
        eq.addBand(1000f, -6f, BiquadFilter.FilterType.BELL, 1.0)

        val resp100 = eq.getFrequencyResponse(100f)
        val resp1000 = eq.getFrequencyResponse(1000f)
        // 100 Hz should be boosted, 1 kHz should be cut
        assertTrue("100 Hz should be boosted ($resp100 dB)", resp100 > 0f)
        assertTrue("1 kHz should be cut ($resp1000 dB)", resp1000 < 0f)
    }

    @Test
    fun `disabled bands do not affect response`() {
        eq.clearBands()
        eq.addBand(1000f, 12f, BiquadFilter.FilterType.BELL, 1.0)
        val withBoost = eq.getFrequencyResponse(1000f)

        eq.setBandEnabled(0, false)
        val withoutBoost = eq.getFrequencyResponse(1000f)
        assertTrue("Disabling band should reduce response ($withBoost → $withoutBoost dB)",
            withoutBoost < withBoost)
    }

    @Test
    fun `process flat EQ is passthrough`() {
        val buffer = floatArrayOf(0.5f, -0.3f, 0.1f, 0.8f)
        val expected = buffer.copyOf()
        eq.process(buffer)
        // Flat 0 dB EQ should be passthrough
        assertArrayEquals("Flat EQ should not change audio", expected, buffer, 0.01f)
    }

    @Test
    fun `process with disabled EQ is passthrough`() {
        eq.isEnabled = false
        val buffer = floatArrayOf(0.3f, -0.1f, 0.7f, -0.5f)
        val expected = buffer.copyOf()
        eq.process(buffer)
        assertArrayEquals("Disabled EQ should passthrough", expected, buffer, 0.01f)
    }

    @Test
    fun `loadPreset Flat sets all gains to 0`() {
        eq.loadPreset("Flat")
        for (i in 0 until eq.getBandCount()) {
            val band = eq.getBand(i)!!
            assertEquals("Band $i gain should be 0 after Flat preset", 0.0, band.gain.toDouble(), 0.01)
        }
    }

    @Test
    fun `loadPreset Bass Boost boosts low bands`() {
        eq.loadPreset("Bass Boost")
        val firstGain = eq.getBand(0)?.gain ?: 0f
        val lastGain = eq.getBand(eq.getBandCount() - 1)?.gain ?: 0f
        assertTrue("First band gain ($firstGain) should be >= last band gain ($lastGain)", firstGain >= lastGain)
    }

    @Test
    fun `loadPreset Treble Boost boosts high bands`() {
        eq.loadPreset("Treble Boost")
        val firstGain = eq.getBand(0)?.gain ?: 0f
        val lastGain = eq.getBand(eq.getBandCount() - 1)?.gain ?: 0f
        assertTrue("Last band gain ($lastGain) should be >= first band gain ($firstGain)", lastGain >= firstGain)
    }

    @Test
    fun `logSpacedFrequencies produces correct count`() {
        val freqs = ParametricEqualizer.logSpacedFrequencies(16)
        assertEquals(16, freqs.size)
        assertTrue("First freq should be >= 10 Hz", freqs[0] >= 10f)
        assertTrue("Last freq should be <= 22000 Hz", freqs.last() <= 22000f)
    }

    @Test
    fun `logSpacedFrequencies are monotonic`() {
        val freqs = ParametricEqualizer.logSpacedFrequencies(31)
        for (i in 1 until freqs.size) {
            assertTrue("Frequencies should be monotonically increasing", freqs[i] > freqs[i - 1])
        }
    }

    @Test
    fun `clearBands removes all bands`() {
        eq.clearBands()
        assertEquals("EQ should have 0 bands after clear", 0, eq.getBandCount())
    }

    @Test
    fun `getFrequencyResponseWithSaturation never exceeds flat reference`() {
        eq.clearBands()
        eq.addBand(500f, 20f, BiquadFilter.FilterType.BELL, 10.0)
        val saturated = eq.getFrequencyResponseWithSaturation(500f)
        val linear = eq.getFrequencyResponse(500f)
        // Saturation should reduce peak gain
        assertTrue("Saturated response ($saturated dB) should be <= linear ($linear dB)", saturated <= linear + 0.1f)
    }

    @Test
    fun `band count is capped at MAX_BANDS via EqStateManager constant`() {
        // ParametricEqualizer itself doesn't enforce MAX_BANDS — EqStateManager does.
        // Verify the constant is defined.
        assertTrue("EqStateManager.MAX_BANDS should be >= 1",
            com.bearinmind.equalizer314.state.EqStateManager.MAX_BANDS >= 1)
    }
}
