package tk.glucodata.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import tk.glucodata.settings.store.InMemoryKeyValueStore
import tk.glucodata.settings.store.SettingsStoreImpl

/**
 * The `scheduled_backups` area migrated onto
 * [tk.glucodata.settings.store.SettingsStore] (plan task T2.3). Pins the old
 * SharedPreferences behaviour: defaults, validation, and how a success moves
 * metrics between the baseline and the pending slot.
 */
class ScheduledBackupStoreTest {

    private val backend = InMemoryKeyValueStore()
    private val store = ScheduledBackupStore(SettingsStoreImpl(backend))

    private fun metrics(bytes: Long) = ScheduledBackupMetrics(
        compression = ExportCompression.GZIP,
        byteSize = bytes,
        historyReadings = 1,
        journalEntries = 2,
        journalFoods = 3,
        insulinPresets = 4,
        calibrations = 5,
    )

    // android.net.Uri is an Android framework class and returns defaults under
    // JVM unit tests, so the destination is exercised by the app, not here; the
    // round-trip below covers everything else.
    private fun config(
        enabled: Boolean = false,
        destination: Uri? = null,
        hour: Int = 4,
        minute: Int = 30,
        compression: ExportCompression = ExportCompression.ZSTD,
        daily: Int = 7,
        weekly: Int = 3,
        monthly: Int = 6,
    ) = ScheduledBackupConfig(
        enabled = enabled,
        destination = destination,
        hour = hour,
        minute = minute,
        compression = compression,
        dailyRetention = daily,
        weeklyRetention = weekly,
        monthlyRetention = monthly,
        lastSuccessAtMillis = 0L,
        lastFileName = null,
        lastAttemptAtMillis = 0L,
        lastError = null,
        integrityWarning = null,
        baselineMetrics = null,
        pendingMetrics = null,
    )

    @Test
    fun defaultsMatchTheOldPreferences() {
        val loaded = store.load()

        assertEquals(false, loaded.enabled)
        assertNull(loaded.destination)
        assertEquals(3, loaded.hour)
        assertEquals(0, loaded.minute)
        assertEquals(ExportCompression.GZIP, loaded.compression)
        assertEquals(5, loaded.dailyRetention)
        assertEquals(4, loaded.weeklyRetention)
        assertEquals(6, loaded.monthlyRetention)
        assertEquals(0L, loaded.lastSuccessAtMillis)
        assertNull(loaded.lastFileName)
        assertNull(loaded.lastError)
        assertNull(loaded.integrityWarning)
        assertNull(loaded.baselineMetrics)
        assertNull(loaded.pendingMetrics)
    }

    @Test
    fun savedConfigurationComesBack() {
        store.saveConfiguration(config())

        val loaded = store.load()
        assertEquals(false, loaded.enabled)
        assertNull(loaded.destination)
        assertEquals(4, loaded.hour)
        assertEquals(30, loaded.minute)
        assertEquals(ExportCompression.ZSTD, loaded.compression)
        assertEquals(7, loaded.dailyRetention)
        assertEquals(3, loaded.weeklyRetention)
        assertEquals(6, loaded.monthlyRetention)
    }

    @Test
    fun anEnabledBackupNeedsAFolder() {
        assertThrows(IllegalArgumentException::class.java) {
            store.saveConfiguration(config(enabled = true, destination = null))
        }
    }

    @Test
    fun anUnsupportedStoredRetentionFallsBackToTheDefault() {
        SettingsStoreImpl(backend).set(ScheduledBackupKeys.DAILY_RETENTION, 99)

        assertEquals(5, store.load().dailyRetention)
    }

    @Test
    fun aSuccessClearsTheWarningAndMovesTheBaseline() {
        store.recordSuspiciousSuccess(1L, "old.apk", metrics(10L), "suspicious")
        store.recordSuccess(2L, "new.apk", metrics(20L))

        val loaded = store.load()
        assertEquals(2L, loaded.lastSuccessAtMillis)
        assertEquals(2L, loaded.lastAttemptAtMillis)
        assertEquals("new.apk", loaded.lastFileName)
        assertNull(loaded.lastError)
        assertNull(loaded.integrityWarning)
        assertEquals(20L, loaded.baselineMetrics?.byteSize)
        assertNull(loaded.pendingMetrics)
    }

    @Test
    fun aSuspiciousSuccessKeepsTheMetricsPending() {
        store.recordSuspiciousSuccess(5L, "odd.apk", metrics(7L), "shrank")

        val loaded = store.load()
        assertEquals("shrank", loaded.integrityWarning)
        assertEquals(7L, loaded.pendingMetrics?.byteSize)
        assertNull(loaded.baselineMetrics)
    }

    @Test
    fun aSuccessWhileAWarningIsPendingReplacesThePendingMetrics() {
        store.recordSuspiciousSuccess(5L, "odd.apk", metrics(7L), "shrank")
        store.recordSuccessWhileWarningIsPending(6L, "odd2.apk", metrics(9L))

        val loaded = store.load()
        assertEquals("shrank", loaded.integrityWarning)
        assertEquals(9L, loaded.pendingMetrics?.byteSize)
        assertNull(loaded.baselineMetrics)
    }

    @Test
    fun aFailureRecordsTheAttemptAndTheErrorWithoutTouchingTheSuccess() {
        store.recordSuccess(1L, "ok.apk", metrics(10L))

        store.recordFailure(2L, "disk full")

        val loaded = store.load()
        assertEquals(1L, loaded.lastSuccessAtMillis)
        assertEquals(2L, loaded.lastAttemptAtMillis)
        assertEquals("disk full", loaded.lastError)
    }

    @Test
    fun acknowledgingTheWarningPromotesThePendingMetricsToBaseline() {
        store.recordSuspiciousSuccess(5L, "odd.apk", metrics(7L), "shrank")

        store.acknowledgeIntegrityWarning()

        val loaded = store.load()
        assertNull(loaded.integrityWarning)
        assertEquals(7L, loaded.baselineMetrics?.byteSize)
        assertNull(loaded.pendingMetrics)
    }
}
