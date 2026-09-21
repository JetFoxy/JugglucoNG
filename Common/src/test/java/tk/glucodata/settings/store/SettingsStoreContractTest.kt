package tk.glucodata.settings.store

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The [SettingsStore] contract (plan task T2.2), run against every
 * implementation. The Android backend is a thin editor wrapper; the behaviour
 * that matters lives in [SettingsStoreImpl], so the in-memory store exercises it
 * on the JVM.
 */
abstract class SettingsStoreContractTest {

    abstract fun newStore(): SettingsStore

    private val file = "test_prefs"
    private val volume = SettingKey(file, "volume", 7)
    private val muted = SettingKey(file, "muted", false)
    private val name = SettingKey(file, "name", "unset")

    @Test
    fun returnsTheDefaultWhenNothingWasWritten() {
        val store = newStore()

        assertEquals(7, store.get(volume))
        assertEquals(false, store.get(muted))
        assertEquals("unset", store.get(name))
    }

    @Test
    fun roundTripsEverySupportedType() {
        val store = newStore()
        val longKey = SettingKey(file, "long", 0L)
        val floatKey = SettingKey(file, "float", 0f)
        val setKey = SettingKey(file, "set", emptySet<String>())

        store.set(volume, 42)
        store.set(muted, true)
        store.set(name, "glucose")
        store.set(longKey, 1_700_000_000_000L)
        store.set(floatKey, 5.5f)
        store.set(setKey, setOf("a", "b"))

        assertEquals(42, store.get(volume))
        assertEquals(true, store.get(muted))
        assertEquals("glucose", store.get(name))
        assertEquals(1_700_000_000_000L, store.get(longKey))
        assertEquals(5.5f, store.get(floatKey))
        assertEquals(setOf("a", "b"), store.get(setKey))
    }

    @Test
    fun differentFilesDoNotSeeEachOther() {
        val store = newStore()
        val elsewhere = SettingKey("other_prefs", "volume", -1)

        store.set(volume, 42)

        assertEquals(42, store.get(volume))
        assertEquals(-1, store.get(elsewhere))
    }

    @Test
    fun observeEmitsTheCurrentValueThenTheNewOne() = runTest {
        val store = newStore()
        val seen = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observe(volume).take(2).toList(seen)
        }
        runCurrent()

        store.set(volume, 9)
        advanceUntilIdle()

        assertEquals(listOf(7, 9), seen)
    }

    @Test
    fun observeIgnoresOtherKeysInTheSameFile() = runTest {
        val store = newStore()
        val seen = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observe(volume).take(2).toList(seen)
        }
        runCurrent()

        store.set(muted, true)
        advanceUntilIdle()

        assertEquals(listOf(7), seen)
    }

    @Test
    fun concurrentWritesAreNotLost() {
        val store = newStore()
        val keys = (0 until 100).map { SettingKey(file, "k$it", -1) }

        val threads = keys.map { key -> Thread { store.set(key, key.name.hashCode()) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        keys.forEach { key -> assertEquals(key.name.hashCode(), store.get(key)) }
    }
}

class InMemorySettingsStoreContractTest : SettingsStoreContractTest() {
    override fun newStore(): SettingsStore = SettingsStoreImpl(InMemoryKeyValueStore())
}
