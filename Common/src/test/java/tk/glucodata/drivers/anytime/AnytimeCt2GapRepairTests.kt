package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Gap repair for CT-14, which pushes one record per cadence slot.
 *
 * The 2026-10-27 trace: the app process sat frozen for 30 minutes, the
 * transmitter kept producing, and the next live id arrived as 1956 while the
 * newest stored one was 1946. Reconnect backfill asked from 1958 onwards and the
 * transmitter answered "caught up" — ids 1947..1955 were never requested.
 */
class AnytimeCt2GapRepairTests {

    private val threeHourCap = 60

    @Test
    fun theJumpAfterAFreezeBecomesTheMissingRange() {
        val gap = anytimeReconnectGap(
            highestKnownId = 1946,
            liveId = 1956,
            maxRecords = threeHourCap,
        )

        assertEquals(1947, gap?.fromId)
        assertEquals(1956, gap?.stopBeforeId)
        assertEquals(9, gap?.count)
    }

    @Test
    fun theExpectedNextIdIsNotAGap() {
        assertNull(
            anytimeReconnectGap(
                highestKnownId = 1955,
                liveId = 1956,
                maxRecords = threeHourCap,
            )
        )
    }

    @Test
    fun aRepeatedOrOlderLiveIdIsNotAGap() {
        assertNull(anytimeReconnectGap(highestKnownId = 1956, liveId = 1956, maxRecords = threeHourCap))
        assertNull(anytimeReconnectGap(highestKnownId = 1956, liveId = 1950, maxRecords = threeHourCap))
    }

    @Test
    fun nothingIsKnownBeforeTheFirstLiveId() {
        assertNull(anytimeReconnectGap(highestKnownId = -1, liveId = 1956, maxRecords = threeHourCap))
        assertNull(anytimeReconnectGap(highestKnownId = 1946, liveId = 0, maxRecords = threeHourCap))
    }

    @Test
    fun aVeryLongOutageRepairsTheNewestTailOnly() {
        // Two days of silence: the transmitter has long dropped that history, so
        // asking for it would cost a GATT session per range for nothing.
        val gap = anytimeReconnectGap(
            highestKnownId = 100,
            liveId = 1100,
            maxRecords = threeHourCap,
        )

        assertEquals(1040, gap?.fromId)
        assertEquals(1100, gap?.stopBeforeId)
        assertEquals(threeHourCap, gap?.count)
    }

    @Test
    fun ct5KeepsTheSameAnswerThroughItsOwnSpelling() {
        val viaCt5 = ct5ReconnectGap(highestKnownId = 288, liveId = 291, maxRecords = 480)
        val viaFamilyNeutral = anytimeReconnectGap(highestKnownId = 288, liveId = 291, maxRecords = 480)

        assertEquals(viaFamilyNeutral, viaCt5)
        assertEquals(289, viaCt5?.fromId)
        assertEquals(291, viaCt5?.stopBeforeId)
    }
}
