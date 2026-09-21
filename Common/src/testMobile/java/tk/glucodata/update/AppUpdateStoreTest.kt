package tk.glucodata.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.settings.store.InMemoryKeyValueStore
import tk.glucodata.settings.store.SettingsStoreImpl

/**
 * The `app_updates` area migrated onto [tk.glucodata.settings.store.SettingsStore]
 * (plan task T2.3). Runs on the in-memory store, so it pins the behaviour the old
 * SharedPreferences code had: defaults, the auto-check/answered pair written
 * together, source validation, what a check caches, and the dismissal TTL.
 */
class AppUpdateStoreTest {

    private val defaultSource = "https://github.com/ctqvva/JugglucoNG"
    private var now = 1_700_000_000_000L

    private fun newStore() = AppUpdateStore(
        SettingsStoreImpl(InMemoryKeyValueStore()),
        defaultSource,
        now = { now },
    )

    private fun update(tag: String = "v1.2.0") = AvailableUpdate(
        versionName = "1.2.0",
        versionCode = 12,
        tagName = tag,
        notes = "notes",
        publishedAtMillis = 0L,
        prerelease = false,
        artifact = UpdateArtifact(
            fileName = "app.apk",
            downloadUrl = "https://github.com/ctqvva/JugglucoNG/releases/download/$tag/app.apk",
            sizeBytes = 10L,
            sha256 = null,
        ),
    )

    @Test
    fun defaultsMatchTheOldPreferences() {
        val store = newStore()

        assertEquals(false, store.isIntroAnswered())
        assertEquals(false, store.isAutoCheckEnabled())
        assertEquals(defaultSource, store.updateSource())
        assertEquals(0L, store.lastCheckAtMillis())
        assertNull(store.lastError())
        assertNull(store.cachedUpdate())
        assertNull(store.dismissedIdentity())
    }

    @Test
    fun enablingAutoCheckAlsoAnswersTheIntro() {
        val store = newStore()

        store.setAutoCheckEnabled(true)

        assertEquals(true, store.isAutoCheckEnabled())
        assertEquals(true, store.isIntroAnswered())
    }

    @Test
    fun aNonDefaultSourceIsStoredAndReturned() {
        val store = newStore()
        val mirror = "https://mirror.example.org/juggluco"

        store.setUpdateSource(mirror)

        assertEquals(mirror, store.updateSource())
        assertEquals(false, store.isDefaultUpdateSource())
    }

    @Test
    fun anInvalidSourceIsIgnoredRatherThanStored() {
        val store = newStore()
        store.setUpdateSource("https://mirror.example.org/juggluco")

        store.setUpdateSource("not a url")

        assertEquals("https://mirror.example.org/juggluco", store.updateSource())
    }

    @Test
    fun settingTheDefaultSourceRemovesTheStoredOne() {
        val store = newStore()
        store.setUpdateSource("https://mirror.example.org/juggluco")

        store.setUpdateSource(defaultSource)

        assertEquals(defaultSource, store.updateSource())
        assertEquals(true, store.isDefaultUpdateSource())
    }

    @Test
    fun anAvailableResultCachesTheReleaseAndClearsAPreviousError() {
        val store = newStore()
        store.recordCheck(UpdateCheckResult.Failed(UpdateError.NETWORK), atMillis = 1L)

        store.recordCheck(UpdateCheckResult.Available(update()), atMillis = 2L)

        assertNull(store.lastError())
        assertEquals(2L, store.lastCheckAtMillis())
        assertEquals(update(), store.cachedUpdate())
    }

    @Test
    fun anUpToDateResultClearsTheCachedRelease() {
        val store = newStore()
        store.recordCheck(UpdateCheckResult.Available(update()), atMillis = 1L)

        store.recordCheck(UpdateCheckResult.UpToDate, atMillis = 2L)

        assertNull(store.cachedUpdate())
        assertEquals(2L, store.lastCheckAtMillis())
    }

    @Test
    fun aFailedResultStoresTheError() {
        val store = newStore()

        store.recordCheck(UpdateCheckResult.Failed(UpdateError.RATE_LIMITED), atMillis = 3L)

        assertEquals(UpdateError.RATE_LIMITED, store.lastError())
        assertEquals(3L, store.lastCheckAtMillis())
    }

    @Test
    fun aDismissalIsRememberedWhileItIsFresh() {
        val store = newStore()

        store.setDismissedIdentity("v1.2.0/app.apk")
        now += 6L * 24 * 60 * 60 * 1000

        assertEquals("v1.2.0/app.apk", store.dismissedIdentity())
    }

    @Test
    fun aDismissalExpiresAfterAWeek() {
        val store = newStore()
        store.setDismissedIdentity("v1.2.0/app.apk")
        now += 8L * 24 * 60 * 60 * 1000

        assertNull(store.dismissedIdentity())
        // And it is forgotten, not merely hidden until the next read.
        assertNull(store.dismissedIdentity())
    }

    @Test
    fun aClockThatMovedBackwardsExpiresTheDismissal() {
        val store = newStore()
        store.setDismissedIdentity("v1.2.0/app.apk")
        now -= 60_000L

        assertNull(store.dismissedIdentity())
    }

    @Test
    fun clearingADismissalForgetsBothTheIdentityAndItsTimestamp() {
        val store = newStore()
        store.setDismissedIdentity("v1.2.0/app.apk")

        store.setDismissedIdentity(null)

        assertNull(store.dismissedIdentity())
    }
}
