package tk.glucodata.ui.alerts

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.settings.store.InMemoryKeyValueStore
import tk.glucodata.settings.store.SettingsStoreImpl

/**
 * The `custom_sounds` area migrated onto
 * [tk.glucodata.settings.store.SettingsStore] (plan task T2.3): one key holding
 * a set of URIs.
 */
class CustomSoundStoreTest {

    private val backend = InMemoryKeyValueStore()
    private val store = CustomSoundStore(SettingsStoreImpl(backend))

    @Test
    fun thereAreNoCustomSoundsUntilOneIsAdded() {
        assertEquals(emptySet<String>(), store.customSounds())
    }

    @Test
    fun addedSoundsAreKept() {
        store.add("content://one")
        store.add("content://two")

        assertEquals(setOf("content://one", "content://two"), store.customSounds())
    }

    @Test
    fun addingTheSameSoundTwiceKeepsOne() {
        store.add("content://one")
        store.add("content://one")

        assertEquals(setOf("content://one"), store.customSounds())
    }

    @Test
    fun removedSoundsAreGoneAndTheRestStay() {
        store.add("content://one")
        store.add("content://two")

        store.remove("content://one")

        assertEquals(setOf("content://two"), store.customSounds())
    }

    @Test
    fun removingSomethingThatWasNeverAddedIsHarmless() {
        store.add("content://one")

        store.remove("content://absent")

        assertEquals(setOf("content://one"), store.customSounds())
    }

    @Test
    fun theValueSurvivesANewStoreOverTheSameBackend() {
        store.add("content://one")

        val reopened = CustomSoundStore(SettingsStoreImpl(backend))

        assertEquals(setOf("content://one"), reopened.customSounds())
    }
}
