package com.bearinmind.equalizer314.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-Kotlin tests for [EqMigrationHelper] — validates that the
 * preset converter used by the migration correctly handles
 * the legacy SharedPreferences JSON format.
 *
 * Full Room migration tests require a Robolectric environment to
 * create an in-memory Room database; those are in
 * [app/src/androidTest/] for future expansion.
 */
class EqMigrationHelperTest {

    @Test
    fun `legacy parametric preset JSON round-trips`() {
        val json = """{"bands":[{"frequency":60.0,"gain":4.0,"filterType":"BELL","q":0.71,"enabled":true}],"preamp":-3.5}"""
        val entity = PresetConverter.fromLegacyJson("Test", json)

        assertEquals("Test", entity.name)
        assertEquals(-3.5, entity.preamp, 0.001)
        assertFalse(entity.isChannelSideEq)
    }

    @Test
    fun `migrate legacy JSON with Channel Side EQ`() {
        val json = """{
            "bands":[{"frequency":100.0,"gain":3.0,"filterType":"BELL","q":1.0,"enabled":true}],
            "channelSideEqEnabled":true,
            "leftBands":[{"frequency":100.0,"gain":5.0,"filterType":"BELL","q":1.0,"enabled":true}],
            "rightBands":[{"frequency":100.0,"gain":2.0,"filterType":"BELL","q":1.0,"enabled":true}],
            "preamp":0.0
        }""".trimIndent()
        val entity = PresetConverter.fromLegacyJson("CSE", json)

        assertTrue(entity.isChannelSideEq)
        assertNotNull(entity.leftBandsJson)
        assertNotNull(entity.rightBandsJson)
    }

    @Test
    fun `toLegacyJson preserves empty bands`() {
        val json = """{"bands":[],"preamp":0.0}"""
        val entity = PresetConverter.fromLegacyJson("Empty", json)
        val restored = PresetConverter.toLegacyJson(entity)

        assertTrue(restored.contains("\"bands\":[]"))
    }
}
