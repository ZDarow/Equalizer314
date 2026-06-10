package com.bearinmind.equalizer314.autoeq

import com.bearinmind.equalizer314.dsp.BiquadFilter
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [AutoEqParser] — parses Equalizer APO config.txt format
 * including per-channel directives, slope qualifiers, and edge cases.
 */
class AutoEqParserTest {

    @Test
    fun `parse flat PK boost`() {
        val text = """
            Preamp: -3.5 dB
            Filter 1: ON PK Fc 1000 Hz Gain 5.0 dB Q 1.41
        """.trimIndent()
        val result = AutoEqParser.parse(text)
        assertNotNull(result)
        assertEquals(-3.5f, result!!.preampDb, 0.01f)
        assertEquals(1, result.filters.size)
        assertEquals("PK", result.filters[0].filterType)
        assertEquals(1000f, result.filters[0].frequency, 0.01f)
        assertEquals(5.0f, result.filters[0].gain, 0.01f)
        assertEquals(1.41f, result.filters[0].q, 0.01f)
    }

    @Test
    fun `parse multiple filters`() {
        val text = """
            Filter 1: ON PK Fc 60 Hz Gain 4.0 dB Q 0.50
            Filter 2: ON LSC Fc 150 Hz Gain 2.0 dB Q 0.71
            Filter 3: ON HSC Fc 6000 Hz Gain -1.5 dB Q 0.71
        """.trimIndent()
        val result = AutoEqParser.parse(text)
        assertNotNull(result)
        assertEquals(3, result!!.filters.size)
        assertEquals("LSC", result.filters[1].filterType)
        assertEquals("HSC", result.filters[2].filterType)
    }

    @Test
    fun `parse per-channel preset`() {
        val text = """
            Preamp: -2.0 dB
            Channel: L
            Filter 1: ON PK Fc 1000 Hz Gain 4.0 dB Q 1.41
            Filter 2: ON PK Fc 3000 Hz Gain -2.0 dB Q 1.41
            Channel: R
            Filter 3: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.41
        """.trimIndent()
        val result = AutoEqParser.parse(text)
        assertNotNull(result)
        assertTrue(result!!.perChannel)
        assertEquals(3, result.filters.size)
        assertEquals(2, result.leftFilters.size)
        assertEquals(1, result.rightFilters.size)
    }

    @Test
    fun `parse shelf with slope qualifier`() {
        val text = """
            Filter 1: ON LS 6 dB Fc 150 Hz Gain 3.0 dB
        """.trimIndent()
        val result = AutoEqParser.parse(text)
        assertNotNull(result)
        assertEquals("LS", result!!.filters[0].filterType)
    }

    @Test
    fun `12 dB shelf maps to LSC`() {
        val text = """
            Filter 1: ON LS 12 dB Fc 150 Hz Gain 3.0 dB
        """.trimIndent()
        val result = AutoEqParser.parse(text)
        assertNotNull(result)
        assertEquals("LSC", result!!.filters[0].filterType)
    }

    @Test
    fun `parse all filter types`() {
        val types = listOf("PK", "LSC", "HSC", "LS", "HS", "LPQ", "HPQ", "LP", "HP", "BP", "NO", "AP")
        for (type in types) {
            val text = "Filter 1: ON $type Fc 1000 Hz Gain 3.0 dB Q 1.41"
            val result = AutoEqParser.parse(text)
            assertNotNull("$type should parse", result)
            assertEquals(type, result!!.filters[0].filterType)
        }
    }

    @Test
    fun `gainless types still parse`() {
        val text = """
            Filter 1: ON BP Fc 1000 Hz Q 2.0
            Filter 2: ON NO Fc 1000 Hz Q 10.0
        """.trimIndent()
        val result = AutoEqParser.parse(text)
        assertNotNull(result)
        assertEquals("BP", result!!.filters[0].filterType)
        assertEquals("NO", result.filters[1].filterType)
        assertEquals(0f, result.filters[0].gain, 0.01f)
    }

    @Test
    fun `empty text returns null`() {
        assertNull(AutoEqParser.parse(""))
    }

    @Test
    fun `text without filters returns null`() {
        assertNull(AutoEqParser.parse("Preamp: 0.0 dB\n"))
    }

    @Test
    fun `skip disabled filters`() {
        val text = """
            Filter 1: OFF PK Fc 1000 Hz Gain 5.0 dB Q 1.41
        """.trimIndent()
        assertNull(AutoEqParser.parse(text))
    }

    @Test
    fun `apoTokenToFilterType maps correctly`() {
        assertEquals(BiquadFilter.FilterType.BELL, apoTokenToFilterType("PK"))
        assertEquals(BiquadFilter.FilterType.LOW_SHELF, apoTokenToFilterType("LSC"))
        assertEquals(BiquadFilter.FilterType.HIGH_SHELF, apoTokenToFilterType("HSC"))
        assertEquals(BiquadFilter.FilterType.LOW_SHELF_1, apoTokenToFilterType("LS"))
        assertEquals(BiquadFilter.FilterType.HIGH_SHELF_1, apoTokenToFilterType("HS"))
        assertEquals(BiquadFilter.FilterType.LOW_PASS, apoTokenToFilterType("LPQ"))
        assertEquals(BiquadFilter.FilterType.HIGH_PASS, apoTokenToFilterType("HPQ"))
        assertEquals(BiquadFilter.FilterType.LOW_PASS_1, apoTokenToFilterType("LP"))
        assertEquals(BiquadFilter.FilterType.HIGH_PASS_1, apoTokenToFilterType("HP"))
        assertEquals(BiquadFilter.FilterType.BAND_PASS, apoTokenToFilterType("BP"))
        assertEquals(BiquadFilter.FilterType.NOTCH, apoTokenToFilterType("NO"))
        assertEquals(BiquadFilter.FilterType.ALL_PASS, apoTokenToFilterType("AP"))
        assertEquals(BiquadFilter.FilterType.BELL, apoTokenToFilterType("UNKNOWN"))
    }

    @Test
    fun `parse preamp only with valid dB`() {
        val text = """
            Preamp: +1.5 dB
            Filter 1: ON PK Fc 1000 Hz Gain 0.0 dB Q 1.0
        """.trimIndent()
        val result = AutoEqParser.parse(text)
        assertNotNull(result)
        assertEquals(1.5f, result!!.preampDb, 0.01f)
    }

    @Test
    fun `per-channel stereo L R scope`() {
        val text = """
            Channel: L R 
            Filter 1: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.41
        """.trimIndent()
        val result = AutoEqParser.parse(text)
        assertNotNull(result)
        assertFalse(result!!.perChannel)
        assertEquals(1, result.leftFilters.size)
        assertEquals(1, result.rightFilters.size)
    }

    @Test
    fun `filters outside valid range are skipped`() {
        val text = """
            Filter 1: ON PK Fc 0 Hz Gain 3.0 dB Q 1.41
            Filter 2: ON PK Fc 200000 Hz Gain 3.0 dB Q 1.41
        """.trimIndent()
        assertNull(AutoEqParser.parse(text))
    }
}
