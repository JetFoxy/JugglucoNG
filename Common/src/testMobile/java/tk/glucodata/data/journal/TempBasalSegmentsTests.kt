package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** When an AAPS temp basal stopped running, for the chart's icicles. */
class TempBasalSegmentsTests {

    private val t0 = 1_700_000_000_000L
    private fun minutes(m: Int) = t0 + m * 60_000L

    private fun entry(
        id: Long,
        atMinute: Int,
        durationMinutes: Int?,
        type: JournalEntryType = JournalEntryType.TEMP_BASAL,
    ) = JournalEntry(
        id = id,
        timestamp = minutes(atMinute),
        sensorSerial = null,
        type = type,
        title = type.name,
        note = null,
        amount = 0.8f,
        glucoseValueMgDl = null,
        durationMinutes = durationMinutes,
        intensity = null,
        insulinPresetId = null,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = JournalEntrySource.AAPS,
        sourceRecordId = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun aSegmentRunsItsOwnDurationWhenNothingReplacesIt() {
        val ends = tempBasalEndsMillis(listOf(entry(1, atMinute = 0, durationMinutes = 30)))
        assertEquals(minutes(30), ends[1])
    }

    /**
     * 10:00 for 30 min, replaced at 10:05 by a 5 min one. The first ends at 10:05, so a
     * 10:15–10:25 chart window, which shows neither start, draws nothing for it.
     */
    @Test
    fun aReplacedSegmentEndsWhereItsReplacementStarts() {
        val ends = tempBasalEndsMillis(
            listOf(
                entry(2, atMinute = 5, durationMinutes = 5),
                entry(1, atMinute = 0, durationMinutes = 30),
            )
        )
        assertEquals(minutes(5), ends[1])
        assertEquals(minutes(10), ends[2])
    }

    @Test
    fun otherTypesAndSegmentsWithoutADurationAreLeftOut() {
        val ends = tempBasalEndsMillis(
            listOf(
                entry(1, atMinute = 0, durationMinutes = null),
                entry(2, atMinute = 1, durationMinutes = 30, type = JournalEntryType.CARBS),
                entry(3, atMinute = 2, durationMinutes = 30),
            )
        )
        assertFalse(1L in ends)
        assertFalse(2L in ends)
        assertEquals(minutes(32), ends[3])
    }
}
