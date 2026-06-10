package com.bearinmind.equalizer314.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-Kotlin tests for [PresetConverter] — validates legacy JSON
 * conversion and entity construction logic without any Android
 * dependencies.
 *
 * Room DAO integration tests require a full Android environment
 * (Robolectric or instrumentation test) and are not included here.
 */
class PresetRepositoryTest {

    @Test
    fun `fromLegacyJson maps bands correctly`() {
        val bandsJson = JSONArray().apply {
            put(JSONObject().apply {
                put("frequency", 60.0)
                put("gain", 4.0)
                put("filterType", "BELL")
                put("q", 0.71)
                put("enabled", true)
            })
            put(JSONObject().apply {
                put("frequency", 1000.0)
                put("gain", -2.0)
                put("filterType", "LOW_SHELF")
                put("q", 0.5)
                put("enabled", false)
            })
        }
        val json = JSONObject().apply {
            put("bands", bandsJson)
            put("preamp", -3.5)
        }.toString()

        val entity = PresetConverter.fromLegacyJson("Test Preset", json)

        assertEquals("Test Preset", entity.name)
        assertEquals(-3.5, entity.preamp, 0.01)
        assertFalse(entity.isChannelSideEq)
        assertNull(entity.leftBandsJson)
        assertNull(entity.rightBandsJson)

        val parsedBands = JSONArray(entity.bandsJson)
        assertEquals(2, parsedBands.length())
        assertEquals(60.0, parsedBands.getJSONObject(0).getDouble("frequency"), 0.01)
    }

    @Test
    fun `fromLegacyJson maps Channel Side EQ bands`() {
        val bandJson = JSONObject().apply {
            put("frequency", 100.0)
            put("gain", 3.0)
            put("filterType", "BELL")
            put("q", 1.0)
            put("enabled", true)
        }
        val leftJson = JSONArray().apply { put(JSONObject(bandJson.toString()).put("gain", 5.0)) }
        val rightJson = JSONArray().apply { put(JSONObject(bandJson.toString()).put("gain", 2.0)) }
        val json = JSONObject().apply {
            put("bands", JSONArray().apply { put(bandJson) })
            put("channelSideEqEnabled", true)
            put("leftBands", leftJson)
            put("rightBands", rightJson)
            put("preamp", 0.0)
        }.toString()

        val entity = PresetConverter.fromLegacyJson("CSE Preset", json)

        assertTrue(entity.isChannelSideEq)
        assertNotNull(entity.leftBandsJson)
        assertNotNull(entity.rightBandsJson)
    }

    @Test
    fun `toLegacyJson round-trips correctly`() {
        val originalJson = JSONObject().apply {
            put("bands", JSONArray().apply {
                put(JSONObject().apply {
                    put("frequency", 250.0)
                    put("gain", -3.0)
                    put("filterType", "HIGH_SHELF")
                    put("q", 0.8)
                    put("enabled", true)
                })
            })
            put("preamp", 2.0)
        }.toString()

        val entity = PresetConverter.fromLegacyJson("Roundtrip", originalJson)
        val restoredJson = PresetConverter.toLegacyJson(entity)
        val restored = JSONObject(restoredJson)

        val restoredBands = restored.getJSONArray("bands")
        assertEquals(1, restoredBands.length())
        assertEquals(250.0, restoredBands.getJSONObject(0).getDouble("frequency"), 0.01)
        assertEquals(-3.0, restoredBands.getJSONObject(0).getDouble("gain"), 0.01)
        assertEquals(2.0, restored.getDouble("preamp"), 0.01)
        assertFalse(restored.optBoolean("channelSideEqEnabled", false))
    }

    @Test
    fun `preset name is preserved through round-trip`() {
        val json = JSONObject().apply {
            put("bands", JSONArray())
            put("preamp", 0.0)
        }.toString()

        val entity = PresetConverter.fromLegacyJson("My Custom Preset", json)
        assertEquals("My Custom Preset", entity.name)
    }

    @Test
    fun `empty bands array is valid`() {
        val json = JSONObject().apply {
            put("bands", JSONArray())
            put("preamp", 0.0)
        }.toString()

        val entity = PresetConverter.fromLegacyJson("Empty", json)
        assertNotNull(entity)
        assertEquals("[]", entity.bandsJson)
    }

    @Test
    fun `entity timestamps are set to current time`() {
        val before = System.currentTimeMillis()
        val json = JSONObject().apply {
            put("bands", JSONArray())
            put("preamp", 0.0)
        }.toString()
        val entity = PresetConverter.fromLegacyJson("Time Test", json)
        val after = System.currentTimeMillis()

        assertTrue(
            "createdAt ($before..$after): ${entity.createdAt}",
            entity.createdAt in before..after,
        )
        assertTrue(
            "updatedAt ($before..$after): ${entity.updatedAt}",
            entity.updatedAt in before..after,
        )
    }
}
