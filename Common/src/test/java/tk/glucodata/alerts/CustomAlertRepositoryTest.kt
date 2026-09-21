package tk.glucodata.alerts

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import tk.glucodata.settings.store.InMemoryKeyValueStore
import tk.glucodata.settings.store.SettingsStoreImpl

/**
 * The `tk.glucodata.custom_alerts` area migrated onto
 * [tk.glucodata.settings.store.SettingsStore] (plan task T2.3). One JSON blob,
 * run on an in-memory backend through [CustomAlertRepository.storeOverride].
 */
class CustomAlertRepositoryTest {

    @Before
    fun setUp() {
        CustomAlertRepository.storeOverride = SettingsStoreImpl(InMemoryKeyValueStore())
    }

    @After
    fun tearDown() {
        CustomAlertRepository.storeOverride = null
    }

    @Test
    fun thereAreNoAlertsInitially() {
        assertEquals(emptyList<CustomAlertConfig>(), CustomAlertRepository.getAll())
    }

    @Test
    fun anAddedAlertIsStored() {
        val alert = CustomAlertConfig(id = "a", name = "High", threshold = 180f)

        CustomAlertRepository.add(alert)

        assertEquals(listOf(alert), CustomAlertRepository.getAll())
    }

    @Test
    fun updatingAnAlertReplacesItInPlace() {
        val first = CustomAlertConfig(id = "a", name = "High", threshold = 180f)
        val second = CustomAlertConfig(id = "b", name = "Low", threshold = 70f)
        CustomAlertRepository.add(first)
        CustomAlertRepository.add(second)

        CustomAlertRepository.update(first.copy(name = "Renamed"))

        assertEquals(listOf("Renamed", "Low"), CustomAlertRepository.getAll().map { it.name })
    }

    @Test
    fun updatingAnUnknownAlertChangesNothing() {
        val alert = CustomAlertConfig(id = "a", name = "High")
        CustomAlertRepository.add(alert)

        CustomAlertRepository.update(CustomAlertConfig(id = "missing", name = "Nope"))

        assertEquals(listOf(alert), CustomAlertRepository.getAll())
    }

    @Test
    fun deletingRemovesOnlyThatAlert() {
        val first = CustomAlertConfig(id = "a", name = "High")
        val second = CustomAlertConfig(id = "b", name = "Low")
        CustomAlertRepository.add(first)
        CustomAlertRepository.add(second)

        CustomAlertRepository.delete("a")

        assertEquals(listOf("b"), CustomAlertRepository.getAll().map { it.id })
    }
}
