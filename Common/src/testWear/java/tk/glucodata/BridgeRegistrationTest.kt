package tk.glucodata

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch side of [BridgeRegistrationTest]'s phone check: `Specific.registerBridges()`
 * must register the bridges the watch is supposed to have (plan §6 Q1). The watch
 * intentionally has no journal, uncertainty, notification-prediction or calibration
 * bridges.
 */
class BridgeRegistrationTest {
    @Test
    fun wearSpecificRegistersTheBridgesTheWatchHas() {
        Specific.registerBridges()

        assertTrue("TrendAccess", TrendAccess.isRegistered())
        assertTrue("ComposeHostAccess", tk.glucodata.ui.ComposeHostAccess.isRegistered())
    }
}
