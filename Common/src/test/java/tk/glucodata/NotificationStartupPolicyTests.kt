package tk.glucodata

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.NotificationStartupPolicy.RestoreGuard
import tk.glucodata.NotificationStartupPolicy.RestoreOutcome

/**
 * Portable JVM tests for the N1 startup subset. These call the real policy code
 * that Notify uses (same class, no mirrors): header-timestamp selection, freshness
 * classification of the actual resolved timestamp, and the async-restore lifecycle
 * guard. No Android framework, no native libraries, no Notify class loading.
 */
class NotificationStartupPolicyTests {
    private val now = 1_700_000_000_000L
    private val window = Notify_glucosetimeout()

    private fun classify(sensorPresent: Boolean, currentMillis: Long, historyMillis: Long) =
        NotificationStartupPolicy.classifyRestore(sensorPresent, currentMillis, historyMillis, now, window)

    @Test fun headerShowsDisplayedReadingTimestampNotRenderTime() {
        val displayed = now - 47_000L
        assertEquals(displayed, NotificationStartupPolicy.resolveHeaderWhenMillis(displayed))
        assertTrue(NotificationStartupPolicy.showHeaderWhen(displayed))
    }

    @Test fun headerPrefersDisplayedReadingOverIncomingArgument() {
        // The displayed (fallback-resolved) reading carries its own time; a newer
        // incoming argument must not leak into the header.
        val displayed = now - 120_000L
        assertEquals(displayed, NotificationStartupPolicy.resolveHeaderWhenMillis(displayed))
    }

    @Test fun headerPreservesEvenStaleDisplayedTimeInsteadOfSubstitutingNow() {
        val staleDisplayed = now - 20 * 60_000L
        assertEquals(staleDisplayed, NotificationStartupPolicy.resolveHeaderWhenMillis(staleDisplayed))
        assertTrue(NotificationStartupPolicy.showHeaderWhen(staleDisplayed))
    }

    @Test fun invalidOrMissingReadingHidesHeaderTimestamp() {
        for (displayed in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertEquals(0L, NotificationStartupPolicy.resolveHeaderWhenMillis(displayed))
            assertFalse(NotificationStartupPolicy.showHeaderWhen(displayed))
        }
    }

    @Test fun freshLiveReadingRestoresAsFresh() {
        assertEquals(RestoreOutcome.FRESH_READING, classify(true, now - 60_000L, 0L))
    }

    @Test fun freshnessBoundaryIsInclusive() {
        assertEquals(RestoreOutcome.FRESH_READING, classify(true, now - window, 0L))
        assertEquals(RestoreOutcome.STALE_READING, classify(true, now - window - 1L, 0L))
    }

    @Test fun sixMinuteHistoryFallbackIsStaleNotFresh() {
        // resolveCurrent(330s) can still return source=history from the 25-minute
        // trend window; 6 minutes already exceeds the freshness window.
        assertEquals(RestoreOutcome.STALE_READING, classify(true, 0L, now - 360_000L))
    }

    @Test fun fifteenAndTwentyFourMinuteHistoryAreStale() {
        assertEquals(RestoreOutcome.STALE_READING, classify(true, 0L, now - 15 * 60_000L))
        assertEquals(RestoreOutcome.STALE_READING, classify(true, 0L, now - 24 * 60_000L))
    }

    @Test fun latestOfCurrentAndHistoryDecides() {
        assertEquals(RestoreOutcome.FRESH_READING, classify(true, 0L, now - 60_000L))
        assertEquals(RestoreOutcome.FRESH_READING, classify(true, now - 400_000L, now - 60_000L))
        assertEquals(RestoreOutcome.STALE_READING, classify(true, now - 400_000L, now - 340_000L))
    }

    @Test fun sensorPresentWithNoDataWaits() {
        assertEquals(RestoreOutcome.AWAITING_DATA, classify(true, 0L, 0L))
    }

    @Test fun absentSensorReportsNoSensorEvenWithData() {
        assertEquals(RestoreOutcome.NO_SENSOR, classify(false, now - 60_000L, now - 60_000L))
        assertEquals(RestoreOutcome.NO_SENSOR, classify(false, 0L, 0L))
    }

    @Test fun supersededStartupGenerationNeverPosts() {
        val guard = RestoreGuard()
        val first = guard.begin()
        val second = guard.begin()
        assertFalse(guard.shouldPost(first, true, true, now - 60_000L))
        assertTrue(guard.shouldPost(second, true, true, now - 60_000L))
    }

    @Test fun restoreDropsAfterServiceStopped() {
        val guard = RestoreGuard()
        val generation = guard.begin()
        assertFalse(guard.shouldPost(generation, true, false, now - 60_000L))
        assertFalse(guard.shouldPost(generation, false, false, now - 60_000L))
    }

    @Test fun restoreDropsAfterServiceReplaced() {
        val guard = RestoreGuard()
        val generation = guard.begin()
        assertFalse(guard.shouldPost(generation, false, true, now - 60_000L))
    }

    @Test fun newerGenuineReadingWinsWhileRestoreComputes() {
        val guard = RestoreGuard()
        val generation = guard.begin()
        val genuine = now - 10_000L
        guard.markPosted(genuine)
        assertFalse("older restore must not overwrite", guard.shouldPost(generation, true, true, genuine - 1L))
        assertTrue("strictly newer restore still applies", guard.shouldPost(generation, true, true, genuine + 1L))
    }

    @Test fun alreadyPostedReadingIsNotMirroredTwice() {
        val guard = RestoreGuard()
        val generation = guard.begin()
        val posted = now - 30_000L
        guard.markPosted(posted)
        assertFalse(guard.shouldPost(generation, true, true, posted))
    }

    @Test fun noDataRestoreDropsOnceGenuineContentPosted() {
        val guard = RestoreGuard()
        val generation = guard.begin()
        guard.markPosted(now - 5_000L)
        assertFalse(guard.shouldPost(generation, true, true, 0L))
    }

    @Test fun noDataRestoreAllowedWhenNothingPostedYet() {
        val guard = RestoreGuard()
        val generation = guard.begin()
        assertTrue(guard.shouldPost(generation, true, true, 0L))
    }

    @Test fun repeatedStartupLeavesOnlyLatestRestoreEligible() {
        val guard = RestoreGuard()
        val first = guard.begin()
        guard.begin()
        val latest = guard.currentGeneration()
        assertFalse(guard.shouldPost(first, true, true, now - 60_000L))
        assertTrue(guard.shouldPost(latest, true, true, now - 60_000L))
    }

    @Test fun nonPositiveMarksNeverCountAsPostedData() {
        val guard = RestoreGuard()
        guard.markPosted(0L)
        guard.markPosted(-100L)
        assertEquals(0L, guard.newestPosted())
        val generation = guard.begin()
        assertTrue(guard.shouldPost(generation, true, true, 0L))
    }

    @Test fun staleCandidateOlderThanPostedReadingDrops() {
        val guard = RestoreGuard()
        val generation = guard.begin()
        guard.markPosted(now - 60_000L)
        assertFalse(guard.shouldPost(generation, true, true, now - 600_000L))
    }

    companion object {
        // 330 seconds: same freshness window Notify shares with the resolver.
        // Kept as a literal so loading this test never initializes Notify
        // (whose static initializer needs native libraries).
        private fun Notify_glucosetimeout() = 330_000L
    }
}
