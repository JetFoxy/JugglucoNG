package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.settings.store.InMemoryKeyValueStore
import tk.glucodata.settings.store.SettingsStoreImpl

/**
 * The `tk.glucodata.snooze` area migrated onto
 * [tk.glucodata.settings.store.SettingsStore] (plan task T2.3). [SnoozeStore] is
 * the pure part of SnoozeManager — no alarms — with a fixed clock.
 */
class SnoozeStoreTest {

    private var now = 1_000_000L
    private val store = SnoozeStore(SettingsStoreImpl(InMemoryKeyValueStore()), now = { now })
    private val low = AlertType.LOW
    private val high = AlertType.HIGH

    @Test
    fun nothingIsSnoozedInitially() {
        assertFalse(store.isSnoozed(low))
        assertNull(store.state(low))
        assertEquals(0, store.remainingMinutes(low))
    }

    @Test
    fun aSnoozeIsActiveUntilItExpires() {
        store.snooze(low, untilMillis = now + 10 * 60_000, preemptive = false)

        assertTrue(store.isSnoozed(low))
        assertEquals(10, store.remainingMinutes(low))

        now += 11 * 60_000

        assertFalse(store.isSnoozed(low))
        assertNull(store.state(low))
    }

    @Test
    fun theStateCarriesThePreemptiveFlag() {
        store.snooze(low, untilMillis = now + 5 * 60_000, preemptive = true)

        assertEquals(true, store.state(low)?.isPreemptive)
    }

    @Test
    fun snoozesArePerAlertType() {
        store.snooze(low, untilMillis = now + 5 * 60_000, preemptive = false)

        assertTrue(store.isSnoozed(low))
        assertFalse(store.isSnoozed(high))
    }

    @Test
    fun clearingRemovesTheSnoozeForThatTypeOnly() {
        store.snooze(low, untilMillis = now + 5 * 60_000, preemptive = false)
        store.snooze(high, untilMillis = now + 5 * 60_000, preemptive = false)

        store.clear(low)

        assertFalse(store.isSnoozed(low))
        assertTrue(store.isSnoozed(high))
    }

    @Test
    fun clearingAllRemovesEverything() {
        store.snooze(low, untilMillis = now + 5 * 60_000, preemptive = false)
        store.snooze(high, untilMillis = now + 5 * 60_000, preemptive = false)

        store.clearAll()

        assertEquals(emptyList<SnoozeState>(), store.activeSnoozes())
    }

    @Test
    fun activeSnoozesListsOnlyTheUnexpiredOnes() {
        store.snooze(low, untilMillis = now + 5 * 60_000, preemptive = false)
        store.snooze(high, untilMillis = now - 1, preemptive = false)

        assertEquals(listOf(low), store.activeSnoozes().map { it.alertType })
    }
}
