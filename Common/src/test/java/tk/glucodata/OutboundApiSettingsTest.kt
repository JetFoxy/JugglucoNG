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
 * The `outbound_api` area migrated onto
 * [tk.glucodata.settings.store.SettingsStore] (plan task T2.3). Runs on an
 * in-memory backend through [OutboundApiSettings.storeOverride], so the live
 * config and the legacy migration are exercised without Android.
 */
class OutboundApiSettingsTest {

    private val file = "outbound_api"
    private val backend = InMemoryKeyValueStore()
    private val store = SettingsStoreImpl(backend)

    private val enabledKey = SettingKey(file, "enabled", true)
    private val destinationsKey = SettingKey(file, "destinations_json", null as String?)
    private val providerKey = SettingKey(file, "provider", null as String?)
    private val tokenKey = SettingKey(file, "token", null as String?)
    private val urlKey = SettingKey(file, "url", null as String?)

    @Before
    fun setUp() {
        OutboundApiSettings.storeOverride = store
        OutboundApiSettings.clearCache()
    }

    @After
    fun tearDown() {
        OutboundApiSettings.storeOverride = null
        OutboundApiSettings.clearCache()
    }

    @Test
    fun anEmptyStoreHasNoDestinations() {
        val config = OutboundApiSettings.load()

        assertEquals(emptyList<OutboundApiSettings.Destination>(), config.destinations)
        assertFalse(OutboundApiSettings.isEnabled())
    }

    @Test
    fun aSavedConfigurationComesBack() {
        val destination = OutboundApiSettings.createDestination(OutboundApiSettings.PRESET_CUSTOM_JSON)
            .copy(url = "https://example.org/hook", token = "secret")
        OutboundApiSettings.save(config = OutboundApiSettings.Config(true, listOf(destination)))
        OutboundApiSettings.clearCache()

        val loaded = OutboundApiSettings.load()

        assertEquals(1, loaded.destinations.size)
        assertEquals(OutboundApiSettings.PRESET_CUSTOM_JSON, loaded.destinations.first().preset)
        assertEquals("secret", loaded.destinations.first().token)
        assertTrue(OutboundApiSettings.isEnabled())
        assertTrue(store.get(destinationsKey).orEmpty().isNotBlank())
    }

    @Test
    fun theGlobalOffSwitchDisablesEveryDestination() {
        val destination = OutboundApiSettings.createDestination(OutboundApiSettings.PRESET_CUSTOM_JSON)
        OutboundApiSettings.save(config = OutboundApiSettings.Config(true, listOf(destination)))
        store.set(enabledKey, false)
        OutboundApiSettings.clearCache()

        val loaded = OutboundApiSettings.load()

        assertEquals(1, loaded.destinations.size)
        assertFalse(loaded.destinations.first().enabled)
    }

    @Test
    fun aLegacyVkConfigurationIsMigrated() {
        store.set(providerKey, "vk")
        store.set(tokenKey, "legacy-token")
        OutboundApiSettings.clearCache()

        val loaded = OutboundApiSettings.load()

        assertEquals(1, loaded.destinations.size)
        val destination = loaded.destinations.first()
        assertEquals(OutboundApiSettings.PRESET_GLUCO_WATCH_VK, destination.preset)
        assertEquals("legacy-token", destination.token)
        assertEquals(OutboundApiSettings.DEFAULT_VK_URL, destination.url)
        // The migration is saved, so a second load reads the new format.
        assertTrue(store.get(destinationsKey).orEmpty().isNotBlank())
    }

    @Test
    fun aLegacyUrlIsKeptWhenItWasSet() {
        store.set(providerKey, "webhook_json")
        store.set(urlKey, "https://example.org/hook")
        OutboundApiSettings.clearCache()

        val loaded = OutboundApiSettings.load()

        assertEquals(OutboundApiSettings.PRESET_CUSTOM_JSON, loaded.destinations.first().preset)
        assertEquals("https://example.org/hook", loaded.destinations.first().url)
    }
}
