package com.bearinmind.equalizer314.state

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [PresetManager] — custom preset persistence.
 */
@RunWith(RobolectricTestRunner::class)
class PresetManagerTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var mgr: PresetManager

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("test_presets", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        mgr = PresetManager(prefs)
    }

    @Test
    fun `empty preset manager has no names`() {
        assertTrue(mgr.names.isEmpty())
    }

    @Test
    fun `save adds preset and name`() {
        mgr.save("My Preset", """{"preamp":0.0,"bands":[]}""")
        assertTrue("My Preset" in mgr.names)
        assertNotNull(mgr.getJson("My Preset"))
    }

    @Test
    fun `delete removes preset and name`() {
        mgr.save("Test", "{}")
        assertTrue("Test" in mgr.names)
        mgr.delete("Test")
        assertFalse("Test" in mgr.names)
        assertNull(mgr.getJson("Test"))
    }

    @Test
    fun `save updates existing preset`() {
        mgr.save("Update", """{"preamp":-1.0}""")
        mgr.save("Update", """{"preamp":3.0}""")
        assertEquals(1, mgr.names.size)
        val json = mgr.getJson("Update")
        assertNotNull(json)
        assertTrue(json!!.contains("3.0"))
    }

    @Test
    fun `nextCustomName returns incremental names`() {
        val prefix = "Custom #"
        assertEquals("Custom #1", mgr.nextCustomName(prefix))
        mgr.save("Custom #1", "{}")
        assertEquals("Custom #2", mgr.nextCustomName(prefix))
        mgr.save("Custom #5", "{}")
        assertEquals("Custom #6", mgr.nextCustomName(prefix))
    }

    @Test
    fun `parse returns null for nonexistent preset`() {
        assertNull(mgr.parse("nonexistent"))
    }

    @Test
    fun `parse returns parsed preset for valid JSON`() {
        val json = """
            {
                "preamp": -2.5,
                "channelSideEqEnabled": true,
                "bands": [{"frequency":100.0,"gain":3.0,"filterType":"BELL","q":0.7,"enabled":true}],
                "leftBands": [{"frequency":100.0,"gain":4.0,"filterType":"BELL","q":0.7,"enabled":true}],
                "rightBands": [{"frequency":100.0,"gain":2.0,"filterType":"BELL","q":0.7,"enabled":true}]
            }
        """.trimIndent()
        mgr.save("CSE", json)

        val parsed = mgr.parse("CSE")
        assertNotNull(parsed)
        assertTrue(parsed!!.cseOn)
        assertEquals(1, parsed.bothBands.size)
        assertEquals(1, parsed.leftBands.size)
        assertEquals(1, parsed.rightBands.size)
        assertEquals(4.0f, parsed.leftBands[0].gain, 0.01f)
        assertEquals(2.0f, parsed.rightBands[0].gain, 0.01f)
    }

    @Test
    fun `buildThumbnailEq returns EQ for valid preset`() {
        val json = """{"bands":[{"frequency":500.0,"gain":2.0,"filterType":"BELL","q":0.7,"enabled":true}]}"""
        mgr.save("Thumb", json)

        val eq = mgr.buildThumbnailEq("Thumb")
        assertNotNull(eq)
        assertEquals(1, eq!!.getBandCount())
        assertEquals(500f, eq.getBand(0)!!.frequency, 0.01f)
    }

    @Test
    fun `toApoText generates valid APO output`() {
        val json = """
            {
                "preamp": -1.5,
                "bands": [
                    {"frequency":100.0,"gain":3.0,"filterType":"BELL","q":0.7,"enabled":true}
                ]
            }
        """.trimIndent()
        mgr.save("APO", json)

        val apo = mgr.toApoText("APO")
        assertNotNull(apo)
        assertTrue(apo!!.contains("Preamp: -1.5 dB"))
        assertTrue(apo.contains("Filter 1: ON PK Fc 100 Hz Gain 3.0 dB Q 0.70"))
    }

    @Test
    fun `toApoText with CSE outputs channel sections`() {
        val json = """
            {
                "preamp": 0.0,
                "channelSideEqEnabled": true,
                "bands": [],
                "leftBands": [{"frequency":100.0,"gain":3.0,"filterType":"BELL","q":0.7,"enabled":true}],
                "rightBands": [{"frequency":1000.0,"gain":-2.0,"filterType":"HIGH_SHELF","q":0.5,"enabled":true}]
            }
        """.trimIndent()
        mgr.save("CSE-APO", json)

        val apo = mgr.toApoText("CSE-APO")
        assertNotNull(apo)
        assertTrue(apo!!.contains("Channel: L"))
        assertTrue(apo.contains("Channel: R"))
        assertTrue(apo.contains("Filter 1: ON PK Fc 100 Hz"))
        assertTrue(apo.contains("Filter 2: ON HSC Fc 1000 Hz"))
    }

    @Test
    fun `delete nonexistent preset does not throw`() {
        mgr.delete("nothing")
        assertTrue(mgr.names.isEmpty())
    }
}
