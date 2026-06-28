package com.bearinmind.equalizer314.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FFT] — validates FFT computation, windowing,
 * and frequency conversion utilities.
 */
class FFTTest {

    @Test
    fun `fft of silence is all zeros`() {
        val fft = FFT(1024)
        val silence = DoubleArray(1024)
        val spectrum = fft.computePowerSpectrum(silence)

        // All bins should be very close to zero
        for (i in spectrum.indices) {
            assertEquals("bin $i should be 0", 0.0, spectrum[i], 1e-15)
        }
    }

    @Test
    fun `fft of DC signal has peak at bin 0`() {
        val fft = FFT(1024)
        val dc = DoubleArray(1024) { 1.0 }
        val spectrum = fft.computePowerSpectrum(dc)

        // Bin 0 (DC) should be non-zero, all others near zero
        assertTrue("DC bin should be > 0", spectrum[0] > 0)
        for (i in 1 until spectrum.size) {
            assertEquals("bin $i should be near 0", 0.0, spectrum[i], 1e-15)
        }
    }

    @Test
    fun `fft of sine wave has peak at expected bin`() {
        val fftSize = 1024
        val sampleRate = 44100f
        val freq = 1000f
        val fft = FFT(fftSize)

        val signal = DoubleArray(fftSize) { i ->
            sin(2.0 * PI * freq / sampleRate * i)
        }

        val spectrum = fft.computePowerSpectrum(signal)

        // Expected peak bin = frequency * fftSize / sampleRate
        val expectedBin = (freq * fftSize / sampleRate).toInt()
        assertTrue("Expected bin $expectedBin should have energy", spectrum[expectedBin] > 0.01)

        // Peak should be at expected bin
        val maxBin = spectrum.indices.maxByOrNull { spectrum[it] } ?: -1
        assertEquals("Peak bin should match expected", expectedBin, maxBin)
    }

    @Test
    fun `computePowerSpectrumDB returns dB values`() {
        val fft = FFT(512)
        val signal = DoubleArray(512)
        val spectrum = fft.computePowerSpectrumDB(signal)

        // dB of silence should be very negative (10*log10(0) ≈ -inf → log10(1e-18) = -180)
        for (i in spectrum.indices) {
            assertTrue("dB value should be <= -150", spectrum[i] <= -150)
        }
    }

    @Test
    fun `binToFrequency converts correctly`() {
        val fft = FFT(1024)
        assertEquals(0f, fft.binToFrequency(0, 44100), 0.001f)
        assertEquals(44100f / 1024, fft.binToFrequency(1, 44100), 0.001f)
        assertEquals(44100f / 2, fft.binToFrequency(512, 44100), 0.001f)
    }

    @Test
    fun `frequencyToBin converts correctly`() {
        val fft = FFT(1024)
        assertEquals(0, fft.frequencyToBin(0f, 44100))
        assertEquals(23, fft.frequencyToBin(1000f, 44100))
        assertEquals(512, fft.frequencyToBin(50000f, 44100)) // clamped to Nyquist
    }

    @Test
    fun `applyWindow returns same length as input`() {
        val fft = FFT(1024)
        val input = FloatArray(512) { 1f }
        val windowed = fft.applyWindow(input)

        assertEquals(input.size, windowed.size)
    }

    @Test
    fun `applyWindow produces weighted output`() {
        val fft = FFT(1024)
        val input = FloatArray(256) { 1f }
        val windowed = fft.applyWindow(input)

        // Hann window: first and last values should be near 0
        assertTrue("first windowed value should be near 0", windowed.first() < 0.01)
        assertTrue("last windowed value should be near 0", windowed.last() < 0.01)

        // Center values should be near 1 (hann * 2 normalization ≈ 1 at center)
        val center = windowed[windowed.size / 2]
        assertTrue("center value should be > 0.5", center > 0.5)
    }

    @Test
    fun `getWindowEnergyFactor is positive`() {
        val fft = FFT(1024)
        val factor = fft.getWindowEnergyFactor()
        assertTrue("energy factor should be > 0", factor > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fft rejects non-power-of-2 size`() {
        FFT(100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fft rejects zero size`() {
        FFT(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `computePowerSpectrum rejects wrong input size`() {
        val fft = FFT(1024)
        fft.computePowerSpectrum(DoubleArray(512))
    }

    @Test
    fun `fft size 2 works`() {
        val fft = FFT(2)
        val signal = DoubleArray(2) { 1.0 }
        val spectrum = fft.computePowerSpectrum(signal)

        assertEquals(2, spectrum.size) // size/2 + 1 = 2
        assertTrue("bin 0 should be > 0", spectrum[0] > 0)
    }

    @Test
    fun `fft of single sinusoid has two peaks when real input`() {
        val fftSize = 2048
        val sampleRate = 44100f
        val freq = 440f
        val fft = FFT(fftSize)

        val signal = DoubleArray(fftSize) { i ->
            sin(2.0 * PI * freq / sampleRate * i)
        }

        val spectrum = fft.computePowerSpectrum(signal)

        // Find top 2 peak bins
        val sortedBins = spectrum.indices
            .sortedByDescending { spectrum[it] }
            .take(2)

        val expectedBin = (freq * fftSize / sampleRate).toInt()
        assertTrue("expected $expectedBin in top 2", sortedBins.contains(expectedBin))
    }
}

/** Minimal math functions for test independence */
private const val PI = 3.14159265358979323846
private fun sin(x: Double): Double = kotlin.math.sin(x)
