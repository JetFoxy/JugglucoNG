package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.settings.store.InMemoryKeyValueStore
import tk.glucodata.settings.store.SettingsStoreImpl

/**
 * The `cgm_readiness` dismissals migrated onto
 * [tk.glucodata.settings.store.SettingsStore] (plan task T2.3). Three keys, and a
 * dismiss writes all three together.
 */
class CgmReadinessDismissalsTest {

    private val backend = InMemoryKeyValueStore()
    private val dismissals = CgmReadinessDismissals(SettingsStoreImpl(backend))

    @Test
    fun nothingIsDismissedInitially() {
        assertNull(dismissals.sensorsSignature())
        assertNull(dismissals.dashboardSignature())
        assertNull(dismissals.setupSignature())
    }

    @Test
    fun aDismissWritesAllThreeSignaturesAtOnce() {
        dismissals.dismissAll(signature = "ble:Warning", criticalSignature = "ble:Critical")

        assertEquals("ble:Warning", dismissals.sensorsSignature())
        assertEquals("ble:Critical", dismissals.dashboardSignature())
        assertEquals("ble:Warning", dismissals.setupSignature())
    }

    @Test
    fun clearingOneBannerLeavesTheOthers() {
        dismissals.dismissAll(signature = "ble:Warning", criticalSignature = "ble:Critical")

        dismissals.clearSensors()

        assertNull(dismissals.sensorsSignature())
        assertEquals("ble:Critical", dismissals.dashboardSignature())
        assertEquals("ble:Warning", dismissals.setupSignature())
    }

    @Test
    fun clearingTheDashboardBannerLeavesTheOthers() {
        dismissals.dismissAll(signature = "ble:Warning", criticalSignature = "ble:Critical")

        dismissals.clearDashboard()

        assertNull(dismissals.dashboardSignature())
        assertEquals("ble:Warning", dismissals.sensorsSignature())
    }

    @Test
    fun clearingTheSetupBannerLeavesTheOthers() {
        dismissals.dismissAll(signature = "ble:Warning", criticalSignature = "ble:Critical")

        dismissals.clearSetup()

        assertNull(dismissals.setupSignature())
        assertEquals("ble:Warning", dismissals.sensorsSignature())
    }

    @Test
    fun dismissalsSurviveANewStoreOverTheSameBackend() {
        dismissals.dismissAll(signature = "ble:Warning", criticalSignature = "ble:Critical")

        val reopened = CgmReadinessDismissals(SettingsStoreImpl(backend))

        assertEquals("ble:Warning", reopened.sensorsSignature())
        assertEquals("ble:Critical", reopened.dashboardSignature())
    }
}
