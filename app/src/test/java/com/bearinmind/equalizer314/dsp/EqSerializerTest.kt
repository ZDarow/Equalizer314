package com.bearinmind.equalizer314.dsp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [EqSerializer] — EQ ↔ JSON serialization.
 */
class EqSerializerTest {

    private lateinit var eq: ParametricEqualizer

    @Before
    fun setUp() {
        eq = ParametricEqualizer(48000)
    }

    @Test
    fun `bandsToJson round-trips correctly`() {
        eq.clearBands()
        eq.addBand(100f, 3f, BiquadFilter.FilterType.BELL, 0.707)
        eq.addBand(1000f, -2f, BiquadFilter.FilterType.HIGH_SHELF, 0.5)
        eq.setBandEnabled(1, false)

        val json = EqSerializer.bandsToJson(eq)
        val restored = ParametricEqualizer(48000)
        EqSerializer.loadBandsTo(restored, json)

        assertEquals(2, restored.getBandCount())
        val b0 = restored.getBand(0)!!
        assertEquals(100f, b0.frequency, 0.01f)
        assertEquals(3f, b0.gain, 0.01f)
        assertEquals(BiquadFilter.FilterType.BELL, b0.filterType)
        assertEquals(0.707, b0.q, 0.001)
        assertTrue(b0.enabled)

        val b1 = restored.getBand(1)!!
        assertEquals(1000f, b1.frequency, 0.01f)
        assertEquals(-2f, b1.gain, 0.01f)
        assertEquals(BiquadFilter.FilterType.HIGH_SHELF, b1.filterType)
        assertFalse(b1.enabled)
    }

    @Test
    fun `loadBandsTo with JSON string returns true on valid input`() {
        val jsonStr = """[{"frequency":200.0,"gain":1.5,"filterType":"BELL","q":0.7,"enabled":true}]"""
        assertTrue(EqSerializer.loadBandsTo(eq, jsonStr))
        assertEquals(1, eq.getBandCount())
        assertEquals(200f, eq.getBand(0)!!.frequency, 0.01f)
    }

    @Test
    fun `loadBandsTo with JSON string returns false on malformed input`() {
        assertFalse(EqSerializer.loadBandsTo(eq, "not-json"))
        // EQ should be unchanged on failure
        assertEquals(4, eq.getBandCount())
    }

    @Test
    fun `parseBands with string returns null on malformed input`() {
        assertNull(EqSerializer.parseBands("not-json"))
    }

    @Test
    fun `parseBands with JSON array returns non-null EQ`() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("frequency", 500.0); put("gain", 2.0)
                put("filterType", "BELL"); put("q", 1.0); put("enabled", true)
            })
        }
        val result = EqSerializer.parseBands(arr)
        assertNotNull(result)
        assertEquals(1, result!!.getBandCount())
        assertTrue(result.isEnabled)
    }

    @Test
    fun `parsePresetJson with valid preset returns EQ`() {
        val preset = """{"preamp":-3.0,"bands":[{"frequency":1000.0,"gain":5.0,"filterType":"LOW_SHELF","q":0.5,"enabled":true}]}"""
        val result = EqSerializer.parsePresetJson(preset)
        assertNotNull(result)
        assertEquals(1, result!!.getBandCount())
        assertEquals(1000f, result.getBand(0)!!.frequency, 0.01f)
    }

    @Test
    fun `parsePresetJson with invalid JSON returns null`() {
        assertNull(EqSerializer.parsePresetJson(""))
        assertNull(EqSerializer.parsePresetJson("{}"))
    }

    @Test
    fun `presetToJson and eqToPresetJson produce valid output`() {
        eq.clearBands()
        eq.addBand(100f, 0f, BiquadFilter.FilterType.BELL)
        eq.addBand(1000f, 3f, BiquadFilter.FilterType.BELL)

        val jsonStr = EqSerializer.eqToPresetJson(eq, -2.5f)
        assertTrue(jsonStr.contains("preamp"))
        assertTrue(jsonStr.contains("bands"))
        assertTrue(jsonStr.contains("-2.5"))

        val parsed = JSONObject(jsonStr)
        assertEquals(-2.5, parsed.getDouble("preamp"), 0.01)
        assertEquals(2, parsed.getJSONArray("bands").length())
    }

    @Test
    fun `bandToJson includes all fields`() {
        val band = ParametricEqualizer.EqualizerBand(
            frequency = 250f,
            gain = -1.5f,
            filterType = BiquadFilter.FilterType.NOTCH,
            q = 2.0,
            enabled = false,
        )
        val json = EqSerializer.bandToJson(band)
        assertTrue(json.has("frequency"))
        assertTrue(json.has("gain"))
        assertTrue(json.has("filterType"))
        assertTrue(json.has("q"))
        assertTrue(json.has("enabled"))
        assertEquals("NOTCH", json.getString("filterType"))
        assertFalse(json.getBoolean("enabled"))
    }

    @Test
    fun `empty EQ serializes to empty JSON array`() {
        eq.clearBands()
        val json = EqSerializer.bandsToJson(eq)
        assertEquals(0, json.length())
    }

    @Test
    fun `loadBandsTo clears existing bands before loading`() {
        eq.addBand(100f, 1f, BiquadFilter.FilterType.BELL)
        assertEquals(5, eq.getBandCount())

        val bandsArr = JSONArray().apply {
            put(JSONObject().apply {
                put("frequency", 500.0); put("gain", 2.0)
                put("filterType", "BELL"); put("q", 0.7); put("enabled", true)
            })
        }
        EqSerializer.loadBandsTo(eq, bandsArr)
        assertEquals(1, eq.getBandCount())
        assertEquals(500f, eq.getBand(0)!!.frequency, 0.01f)
    }
}
