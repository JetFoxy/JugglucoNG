package tk.glucodata;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Which payload the loop feed (xDrip / xInfuus, the outputs a closed loop doses from) gets
 * (plan §6 Q1 follow-up from the #413/#414 review).
 *
 * {@link SuperGattCallback#emitExchangeOutputs} resolves one exchange payload and hands it to the
 * chunked targets. The loop feed only sends when {@code shouldBroadcastMinuteUpdate} fires, and it
 * may reuse that shared payload or resolve its own: the loop feed is never collapsed, so it can
 * need the newest reading even when the chunked snapshot is smoothed differently. Pulled out here
 * so the choice is a pure decision the tests exercise directly, instead of an inline ternary no
 * test ran.
 */
final class LoopFeedPayload {

    private LoopFeedPayload() {
    }

    /**
     * @param loopFeedWanted     false when no loop-feed target is enabled or this minute is not
     *                           being broadcast; then nothing is sent.
     * @param sharedPayload      the already-resolved exchange payload, or null when no exchange
     *                           target needed one.
     * @param sharedSmoothing    whether the chunked exchange output smooths this reading; only
     *                           read when there is a shared payload.
     * @param loopSmoothing      whether the loop feed smooths it; only read when there is a shared
     *                           payload.
     * @param resolveLoopPayload resolves a loop-feed payload when the shared one cannot be reused.
     * @param <T>                the payload type, so the decision is testable without building a
     *                           real {@link ExchangeGlucosePayload}.
     */
    static <T> T choose(
            boolean loopFeedWanted,
            T sharedPayload,
            BooleanSupplier sharedSmoothing,
            BooleanSupplier loopSmoothing,
            Supplier<T> resolveLoopPayload) {
        if (!loopFeedWanted) {
            return null;
        }
        // Reuse only when the loop feed and the chunked output would send the same numbers. A null
        // shared payload short-circuits to a fresh resolve, without reading the smoothing settings.
        if (sharedPayload != null && sharedSmoothing.getAsBoolean() == loopSmoothing.getAsBoolean()) {
            return sharedPayload;
        }
        return resolveLoopPayload.get();
    }
}
