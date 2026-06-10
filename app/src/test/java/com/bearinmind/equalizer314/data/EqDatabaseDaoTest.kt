package com.bearinmind.equalizer314.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric-based integration tests for Room DAOs.
 *
 * Uses an in-memory Room database to verify CRUD operations
 * on [PresetDao], [DeviceBindingDao], and [SeenDeviceDao].
 */
@RunWith(RobolectricTestRunner::class)
class EqDatabaseDaoTest {

    private lateinit var db: EqDatabase
    private lateinit var presetDao: PresetDao
    private lateinit var deviceBindingDao: DeviceBindingDao
    private lateinit var seenDeviceDao: SeenDeviceDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, EqDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        presetDao = db.presetDao()
        deviceBindingDao = db.deviceBindingDao()
        seenDeviceDao = db.seenDeviceDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- PresetDao ----

    @Test
    fun `presetDao insert and read`() = runBlocking {
        val preset = PresetEntity(
            name = "Test Preset",
            bandsJson = """[{"frequency":1000.0,"gain":3.0,"filterType":"BELL","q":0.71,"enabled":true}]""",
            preamp = -2.5,
        )
        presetDao.upsert(preset)

        val loaded = presetDao.getByName("Test Preset")
        assertNotNull(loaded)
        assertEquals("Test Preset", loaded!!.name)
        assertEquals(-2.5, loaded.preamp, 0.001)
    }

    @Test
    fun `presetDao upsert replaces existing`() = runBlocking {
        val p1 = PresetEntity(name = "P", bandsJson = "[]", preamp = 0.0)
        presetDao.upsert(p1)

        val p2 = PresetEntity(name = "P", bandsJson = "[]", preamp = 5.0)
        presetDao.upsert(p2)

        val loaded = presetDao.getByName("P")
        assertEquals(5.0, loaded!!.preamp, 0.001)
    }

    @Test
    fun `presetDao observeAll returns flow`() = runBlocking {
        presetDao.upsert(PresetEntity(name = "A", bandsJson = "[]"))
        presetDao.upsert(PresetEntity(name = "B", bandsJson = "[]"))

        val all = presetDao.observeAll().first()
        assertEquals(2, all.size)
    }

    @Test
    fun `presetDao deleteByName`() = runBlocking {
        presetDao.upsert(PresetEntity(name = "X", bandsJson = "[]"))
        presetDao.deleteByName("X")

        assertNull(presetDao.getByName("X"))
    }

    @Test
    fun `presetDao count`() = runBlocking {
        assertEquals(0, presetDao.count())
        presetDao.upsert(PresetEntity(name = "C", bandsJson = "[]"))
        assertEquals(1, presetDao.count())
    }

    @Test
    fun `presetDao update fields`() = runBlocking {
        presetDao.upsert(PresetEntity(name = "U", bandsJson = "[]", preamp = 0.0))
        presetDao.update(
            name = "U",
            bandsJson = """[{"frequency":500.0,"gain":-1.0,"filterType":"BELL","q":0.5,"enabled":true}]""",
            preamp = -3.0,
            isCse = true,
            leftBandsJson = "[]",
            rightBandsJson = "[]",
        )

        val loaded = presetDao.getByName("U")
        assertEquals(-3.0, loaded!!.preamp, 0.001)
        assertTrue(loaded.isChannelSideEq)
        assertNotNull(loaded.leftBandsJson)
    }

    // ---- DeviceBindingDao ----

    @Test
    fun `deviceBindingDao insert and read`() = runBlocking {
        deviceBindingDao.upsert(DeviceBindingEntity(
            deviceKey = "BT:00:1A:7D:DA:71:13",
            label = "My Headphones",
            presetName = "Bass Boost",
        ))

        val loaded = deviceBindingDao.getByDeviceKey("BT:00:1A:7D:DA:71:13")
        assertNotNull(loaded)
        assertEquals("My Headphones", loaded!!.label)
    }

    @Test
    fun `deviceBindingDao getAll`() = runBlocking {
        deviceBindingDao.upsert(DeviceBindingEntity("k1", "L1", "P1"))
        deviceBindingDao.upsert(DeviceBindingEntity("k2", "L2", "P2"))

        assertEquals(2, deviceBindingDao.getAll().size)
    }

    @Test
    fun `deviceBindingDao delete`() = runBlocking {
        deviceBindingDao.upsert(DeviceBindingEntity("k", "L", "P"))
        deviceBindingDao.deleteByDeviceKey("k")

        assertNull(deviceBindingDao.getByDeviceKey("k"))
    }

    @Test
    fun `deviceBindingDao count`() = runBlocking {
        assertEquals(0, deviceBindingDao.count())
        deviceBindingDao.upsert(DeviceBindingEntity("k", "L", "P"))
        assertEquals(1, deviceBindingDao.count())
    }

    @Test
    fun `deviceBindingDao observeAll`() = runBlocking {
        deviceBindingDao.upsert(DeviceBindingEntity("b1", "Beta", "P"))
        deviceBindingDao.upsert(DeviceBindingEntity("b2", "Alpha", "P"))

        val all = deviceBindingDao.observeAll().first()
        assertEquals(2, all.size)
        // Sorted by label ASC — Alpha before Beta
        assertEquals("Alpha", all[0].label)
        assertEquals("Beta", all[1].label)
    }

    // ---- SeenDeviceDao ----

    @Test
    fun `seenDeviceDao insert and getAll`() = runBlocking {
        seenDeviceDao.upsert(SeenDeviceEntity("k1", "Sony WH"))
        seenDeviceDao.upsert(SeenDeviceEntity("k2", "AirPods"))

        val all = seenDeviceDao.getAll()
        assertEquals(2, all.size)
    }

    @Test
    fun `seenDeviceDao upsert updates label`() = runBlocking {
        seenDeviceDao.upsert(SeenDeviceEntity("k", "Old Label"))
        seenDeviceDao.upsert(SeenDeviceEntity("k", "New Label"))

        val all = seenDeviceDao.getAll()
        assertEquals(1, all.size)
        assertEquals("New Label", all[0].label)
    }

    @Test
    fun `seenDeviceDao delete`() = runBlocking {
        seenDeviceDao.upsert(SeenDeviceEntity("k", "L"))
        seenDeviceDao.deleteByDeviceKey("k")

        assertEquals(0, seenDeviceDao.count())
    }

    // ---- Cross-table ----

    @Test
    fun `presets and device bindings are independent`() = runBlocking {
        presetDao.upsert(PresetEntity(name = "P", bandsJson = "[]"))
        deviceBindingDao.upsert(DeviceBindingEntity("k", "L", "P"))

        presetDao.deleteByName("P")

        // Deleting a preset should NOT cascade-delete the binding
        assertNotNull(deviceBindingDao.getByDeviceKey("k"))
        assertNull(presetDao.getByName("P"))
    }
}
