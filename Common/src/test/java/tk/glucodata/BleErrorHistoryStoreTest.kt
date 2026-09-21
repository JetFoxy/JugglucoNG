package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tk.glucodata.settings.store.InMemoryKeyValueStore
import tk.glucodata.settings.store.SettingsStoreImpl

/**
 * The `ble_error_history` area migrated onto
 * [tk.glucodata.settings.store.SettingsStore] (plan task T2.3). Runs on an
 * in-memory backend through [BleErrorHistory.storeOverride].
 */
class BleErrorHistoryStoreTest {

    @Before
    fun setUp() {
        BleErrorHistory.storeOverride = SettingsStoreImpl(InMemoryKeyValueStore())
    }

    @After
    fun tearDown() {
        BleErrorHistory.storeOverride = null
    }

    @Test
    fun nothingIsRecordedUntilSomethingFails() {
        assertEquals(emptyList<BleErrorEvent>(), BleErrorHistory.events())
    }

    @Test
    fun aRecordedEventComesBack() {
        BleErrorHistory.record("TEST-SENSOR-001", "Status=147", atMs = System.currentTimeMillis())

        val events = BleErrorHistory.events()

        assertEquals(1, events.size)
        assertEquals("TEST-SENSOR-001", events.first().sensorId)
        assertEquals("Status=147", events.first().status)
    }

    @Test
    fun theNewestEventForASensorIsTheLatest() {
        val now = System.currentTimeMillis()
        BleErrorHistory.record("TEST-SENSOR-001", "old", atMs = now - 1_000)
        BleErrorHistory.record("TEST-SENSOR-001", "new", atMs = now)

        assertEquals("new", BleErrorHistory.latest("TEST-SENSOR-001")?.status)
        assertNull(BleErrorHistory.latest("SOME-OTHER-SENSOR"))
    }

    @Test
    fun anUnusableSensorOrStatusIsIgnored() {
        BleErrorHistory.record("", "Status=147")
        BleErrorHistory.record("TEST-SENSOR-001", "  ")

        assertEquals(emptyList<BleErrorEvent>(), BleErrorHistory.events())
    }

    @Test
    fun clearingForgetsEverything() {
        BleErrorHistory.record("TEST-SENSOR-001", "Status=147", atMs = System.currentTimeMillis())

        BleErrorHistory.clear()

        assertEquals(emptyList<BleErrorEvent>(), BleErrorHistory.events())
    }
}
