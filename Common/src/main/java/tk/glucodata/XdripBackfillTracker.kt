package tk.glucodata

import java.util.TreeSet

/**
 * Remembers what the xDrip broadcast has already carried to AAPS, and picks the readings of a
 * history batch that fell into a gap and still need to go.
 *
 * The live path sends only the newest reading, so after a disconnect the sensor's backfill lands in
 * the database but never reaches AAPS. xDrip sends every missed value under its own timestamp; this
 * follows its rules:
 *
 * - A reading counts as already delivered when something was sent within 5/6 of the sensor's sample
 *   period of it (xDrip's `DexCollectionType.getCurrentDeduplicationPeriod`). Sensors here sample
 *   every 1 to 5 minutes, and nothing says which, so the period is measured from the history itself.
 * - The caller hands batches over a little after they are stored, so the live reading of the same
 *   moment has gone out first (xDrip processes backfill the same way, after a delay).
 *
 * A sensor is only known once a live send has been recorded (or a persisted watermark loaded), so
 * non-main sensors, which never live-send, are never backfilled, and enabling the broadcast does not
 * dump the existing history.
 */
class XdripBackfillTracker(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    data class Candidate(val timeMs: Long, val mgdl: Double, val rate: Float)

    data class Sensor(val startWatermarkMs: Long, val sensorGen: Int, val sensorStartMs: Long)

    private class State(var startWatermarkMs: Long, var sensorGen: Int, var sensorStartMs: Long) {
        val sent = TreeSet<Long>()
        var periodMs = 0L
    }

    private val states = HashMap<String, State>()

    /** Restore a sensor from persisted state. Readings at or before [watermarkMs] are never backfilled. */
    @Synchronized
    fun restore(sensorId: String, watermarkMs: Long, sensorGen: Int, sensorStartMs: Long) {
        if (watermarkMs <= 0L || states.containsKey(sensorId)) return
        states[sensorId] = State(watermarkMs, sensorGen, sensorStartMs)
    }

    /** A live reading went out. The first one of a sensor fixes where backfill may start. */
    @Synchronized
    fun recordSent(sensorId: String, timeMs: Long, sensorGen: Int, sensorStartMs: Long) {
        if (timeMs <= 0L) return
        val state = states.getOrPut(sensorId) { State(timeMs, sensorGen, sensorStartMs) }
        state.sensorGen = sensorGen
        if (sensorStartMs > 0L) state.sensorStartMs = sensorStartMs
        state.sent.add(timeMs)
        prune(state, timeMs)
    }

    @Synchronized
    fun sensor(sensorId: String): Sensor? =
        states[sensorId]?.let { Sensor(it.startWatermarkMs, it.sensorGen, it.sensorStartMs) }

    /**
     * The readings of [batch] to send, oldest first. A reading is skipped when it is outside the
     * window, in the future, at or before the sensor's start watermark, or within the
     * deduplication distance of something already sent: 5/6 of the sample period, or [spacingMs]
     * (the collapse interval) when that is longer. The ones returned are recorded as sent.
     */
    @Synchronized
    fun pick(
        sensorId: String,
        batch: List<Candidate>,
        nowMs: Long,
        spacingMs: Long = 0L,
    ): List<Candidate> {
        val state = states[sensorId] ?: return emptyList()
        val sorted = batch.sortedBy(Candidate::timeMs)
        measurePeriod(sorted)?.let { state.periodMs = it }
        val period = if (state.periodMs > 0L) state.periodMs else DEFAULT_PERIOD_MS
        val dedupMs = maxOf(period - period / 6, spacingMs)
        val lowerBound = maxOf(nowMs - windowMs, state.startWatermarkMs)
        val picked = ArrayList<Candidate>()
        for (candidate in sorted) {
            if (candidate.timeMs <= lowerBound || candidate.timeMs > nowMs) continue
            if (!(candidate.mgdl.isFinite() && candidate.mgdl > 0.0)) continue
            if (hasSentWithin(state.sent, candidate.timeMs, dedupMs)) continue
            state.sent.add(candidate.timeMs)
            picked.add(candidate)
        }
        return picked
    }

    /** Median spacing of consecutive readings, clamped to the 1-5 minute range sensors use. */
    private fun measurePeriod(sorted: List<Candidate>): Long? {
        val gaps = sorted.zipWithNext { a, b -> b.timeMs - a.timeMs }
            .filter { it in MIN_PERIOD_MS / 2..MAX_PERIOD_MS * 2 }
            .sorted()
        if (gaps.isEmpty()) return null
        return gaps[gaps.size / 2].coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)
    }

    private fun hasSentWithin(sent: TreeSet<Long>, timeMs: Long, distanceMs: Long): Boolean {
        val before = sent.floor(timeMs)
        if (before != null && timeMs - before < distanceMs) return true
        val after = sent.ceiling(timeMs)
        return after != null && after - timeMs < distanceMs
    }

    private fun prune(state: State, newestMs: Long) {
        val cutoff = newestMs - windowMs
        while (state.sent.isNotEmpty() && state.sent.first() < cutoff) state.sent.pollFirst()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 24L * 60L * 60L * 1000L
        const val MIN_PERIOD_MS = 60_000L
        const val MAX_PERIOD_MS = 5L * 60_000L
        const val DEFAULT_PERIOD_MS = MIN_PERIOD_MS
    }
}
