package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reading backfilled to AAPS after a disconnect must carry the number the live path would have
 * sent for that minute — not the raw sample next to live values that were filtered.
 */
class XdripBackfillCausalTests {

    private val minute = 60_000L

    @Test
    fun backfilledValueEqualsTheLiveEstimateForTheSameMinute() {
        val history = (0 until 40).map { i -> GlucosePoint(i * minute, 100f + (i % 4) * 5f, 90f) }
        val rowTimes = listOf(35L * minute, 36L * minute, 37L * minute)
        val backfilled = XdripBackfill.causalLaneValues(history, rowTimes, rawLane = false)
        val live = DataSmoothing.smoothNativePointsCausal(history.take(37), 5, false, 1f, Long.MAX_VALUE / 2)
        assertEquals(live.last().value, backfilled.getValue(36L * minute), 0f)
    }

    @Test
    fun rowsAreMatchedWithinHalfAMinute() {
        val history = (0 until 10).map { i -> GlucosePoint(i * minute + 12_000L, 100f, 100f) }
        val values = XdripBackfill.causalLaneValues(history, listOf(5L * minute), rawLane = true)
        assertEquals(100f, values.getValue(5L * minute), 1e-3f)
    }

    @Test
    fun aRowWithNoHistoryNearItIsLeftOut() {
        val history = (0 until 10).map { i -> GlucosePoint(i * minute, 100f, 100f) }
        val values = XdripBackfill.causalLaneValues(history, listOf(60L * minute), rawLane = false)
        assertTrue(values.isEmpty())
    }
}
