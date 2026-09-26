package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The loop-feed payload choice in SuperGattCallback.emitExchangeOutputs, pulled into
 * [LoopFeedPayload.choose] (review follow-up on #413/#414). The replay tests model the smoothing
 * decisions but never ran this branch, so a wrong reuse (or a missing fallback) could throttle or
 * freeze what AAPS doses from with no test failing.
 */
class LoopFeedPayloadTests {

    @Test
    fun nothingIsChosenWhenNoLoopFeedTargetWantsThisMinute() {
        var smoothingReads = 0
        var resolves = 0

        val chosen = LoopFeedPayload.choose(
            false,
            "5.5 mmol/L",
            { smoothingReads++; true },
            { smoothingReads++; true },
            { resolves++; "fresh" },
        )

        assertNull(chosen)
        assertEquals("neither smoothing setting is read", 0, smoothingReads)
        assertEquals("no payload is resolved", 0, resolves)
    }

    @Test
    fun theSharedPayloadIsReusedWhenBothModesSmoothAlike() {
        var resolves = 0

        val chosen = LoopFeedPayload.choose(
            true,
            "5.5 mmol/L",
            { true },
            { true },
            { resolves++; "6.1 mmol/L" },
        )

        assertEquals("5.5 mmol/L", chosen)
        assertEquals("no second payload is built", 0, resolves)
    }

    @Test
    fun aFreshPayloadIsResolvedWhenTheLoopModeSmoothsDifferently() {
        val chosen = LoopFeedPayload.choose(
            true,
            "5.5 mmol/L",
            { true },
            { false },
            { "6.1 mmol/L" },
        )

        assertEquals("the unsmoothed loop-feed payload, in mmol", "6.1 mmol/L", chosen)
    }

    @Test
    fun aFreshPayloadIsResolvedWhenThereIsNoSharedSnapshot() {
        var smoothingReads = 0

        val chosen = LoopFeedPayload.choose<String>(
            true,
            null,
            { smoothingReads++; true },
            { smoothingReads++; false },
            { "5.5 mmol/L" },
        )

        assertEquals("the null-snapshot fallback is still in mmol", "5.5 mmol/L", chosen)
        assertEquals(
            "a null shared snapshot short-circuits before the smoothing settings are read",
            0,
            smoothingReads,
        )
    }
}
