package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.settings.store.InMemoryKeyValueStore
import tk.glucodata.settings.store.SettingKey
import tk.glucodata.settings.store.SettingsStoreImpl

/**
 * The `wear_routing_request` area migrated onto
 * [tk.glucodata.settings.store.SettingsStore] (plan task T2.3). Per-node keys,
 * run on an in-memory backend through [WearRoutingRequest.storeOverride].
 *
 * `record(direct = true)` resolves the main sensor through `Natives`, which has
 * no library under unit tests, so the direct-route rows are written straight to
 * the store here; the revoke path is what needs them.
 */
class WearRoutingRequestTest {

    private val store = SettingsStoreImpl(InMemoryKeyValueStore())

    private fun directKey(nodeId: String) = SettingKey("wear_routing_request", "direct.$nodeId", false)
    private fun sensorKey(nodeId: String) = SettingKey("wear_routing_request", "sensor.$nodeId", null as String?)

    @Before
    fun setUp() {
        WearRoutingRequest.storeOverride = store
    }

    @After
    fun tearDown() {
        WearRoutingRequest.storeOverride = null
    }

    @Test
    fun nothingIsRequestedForAnUnknownNode() {
        assertFalse(WearRoutingRequest.directRequested("node-a"))
        assertFalse(WearRoutingRequest.enterRequested("node-a"))
    }

    @Test
    fun aRequestIsRememberedPerNode() {
        WearRoutingRequest.record("node-a", direct = false, enter = true)

        assertTrue(WearRoutingRequest.enterRequested("node-a"))
        assertFalse(WearRoutingRequest.directRequested("node-a"))
        assertFalse(WearRoutingRequest.enterRequested("node-b"))
    }

    @Test
    fun clearingForgetsEveryKeyForTheNode() {
        WearRoutingRequest.record("node-a", direct = false, enter = true)
        store.set(directKey("node-a"), true)

        WearRoutingRequest.clear("node-a")

        assertFalse(WearRoutingRequest.directRequested("node-a"))
        assertFalse(WearRoutingRequest.enterRequested("node-a"))
    }

    @Test
    fun revokeAllReturnsTheDirectRoutesAndStopsThem() {
        store.set(directKey("node-a"), true)
        store.set(sensorKey("node-a"), "TEST-SENSOR")
        store.set(directKey("node-b"), false)

        val revoked = WearRoutingRequest.revokeAllDirectSensors()

        assertEquals(listOf("node-a"), revoked.map { it.nodeId })
        assertFalse("the revoked route is no longer direct", WearRoutingRequest.directRequested("node-a"))
    }

    @Test
    fun revokeAllIsEmptyWhenNothingIsDirect() {
        WearRoutingRequest.record("node-a", direct = false, enter = true)

        assertEquals(
            emptyList<WearRoutingRequest.DirectSensorRoute>(),
            WearRoutingRequest.revokeAllDirectSensors(),
        )
    }
}
