package tk.glucodata.share

import org.json.JSONArray
import org.json.JSONObject

/**
 * What Share has not delivered yet, plus a memory of what it has.
 *
 * Share is a five-minute stream, so most of the readings the app collects are noise to it: the
 * live path may call in every minute, and a reconnect hands over a whole gap at once. The queue
 * is what turns both into one reading per [minSpacingMs], and what stops a reading that was
 * already sent from being sent again when the same moment arrives from history.
 *
 * The delivered memory is deliberately kept for a whole [windowMs]: after a disconnect the
 * history writer re-offers everything it stored, and only the timestamps can tell which of those
 * already went out live.
 */
class DexcomShareQueue(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val minSpacingMs: Long = DEFAULT_MIN_SPACING_MS,
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private var enabledAtMs: Long = 0L
) {

    companion object {
        const val DEFAULT_WINDOW_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_MIN_SPACING_MS = 270_000L
        const val DEFAULT_MAX_PENDING = 288

        /** A reading may arrive slightly before its own timestamp; that is not the future. */
        private const val FUTURE_SKEW_MS = 60_000L

        private const val KEY_WINDOW_MS = "window_ms"
        private const val KEY_MIN_SPACING_MS = "min_spacing_ms"
        private const val KEY_MAX_PENDING = "max_pending"
        private const val KEY_ENABLED_AT_MS = "enabled_at_ms"
        private const val KEY_LIVE_SENSOR = "live_sensor"
        private const val KEY_PENDING = "pending"
        private const val KEY_DELIVERED = "delivered"
        private const val KEY_TIME_MS = "t"
        private const val KEY_MGDL = "v"
        private const val KEY_TREND = "x"

        /** Never throws: a damaged or absent payload means "nothing waiting". */
        fun decode(s: String?): DexcomShareQueue {
            val root = runCatching { JSONObject(s.orEmpty()) }.getOrNull() ?: return DexcomShareQueue()
            val queue = DexcomShareQueue(
                windowMs = root.optLong(KEY_WINDOW_MS, DEFAULT_WINDOW_MS),
                minSpacingMs = root.optLong(KEY_MIN_SPACING_MS, DEFAULT_MIN_SPACING_MS),
                maxPending = root.optInt(KEY_MAX_PENDING, DEFAULT_MAX_PENDING),
                enabledAtMs = root.optLong(KEY_ENABLED_AT_MS, 0L)
            )
            queue.liveSensor = if (root.isNull(KEY_LIVE_SENSOR)) {
                null
            } else {
                root.optString(KEY_LIVE_SENSOR).takeIf { it.isNotEmpty() }
            }
            val pending = root.optJSONArray(KEY_PENDING)
            if (pending != null) {
                for (index in 0 until pending.length()) {
                    val item = pending.optJSONObject(index) ?: continue
                    queue.pending.add(
                        ShareEgv(
                            timeMs = item.optLong(KEY_TIME_MS, 0L),
                            mgdl = item.optInt(KEY_MGDL, 0),
                            trend = item.optInt(KEY_TREND, 0)
                        )
                    )
                }
            }
            val delivered = root.optJSONArray(KEY_DELIVERED)
            if (delivered != null) {
                for (index in 0 until delivered.length()) {
                    queue.delivered.add(delivered.optLong(index, 0L))
                }
            }
            queue.sortPending()
            return queue
        }
    }

    /** Oldest first, so the caller can send them in the order Dexcom should store them. */
    private val pending = ArrayList<ShareEgv>()
    private val delivered = HashSet<Long>()
    private var liveSensor: String? = null

    /**
     * A live reading, which also makes [sensorId] the sensor Share follows: only that one is
     * backfilled, so a second sensor's history cannot interleave into the stream.
     */
    fun offerLive(sensorId: String, egv: ShareEgv, nowMs: Long): Boolean {
        if (sensorId.isEmpty()) return false
        liveSensor = sensorId
        return offer(egv, nowMs)
    }

    /**
     * Stored history for [sensorId]. Readings at or before the moment Share was turned on are
     * refused, so enabling the feature does not dump the readings already on the phone, and a
     * sensor other than the live one is refused entirely.
     *
     * @return how many of [egvs] were accepted.
     */
    fun offerBackfill(sensorId: String, egvs: List<ShareEgv>, nowMs: Long): Int {
        if (sensorId.isEmpty() || sensorId != liveSensor) return 0
        var accepted = 0
        egvs.sortedBy(ShareEgv::timeMs).forEach { egv ->
            if (egv.timeMs <= enabledAtMs) return@forEach
            if (offer(egv, nowMs)) accepted++
        }
        return accepted
    }

    /** Oldest first, without anything that has left [windowMs]. */
    fun pending(nowMs: Long): List<ShareEgv> {
        trim(nowMs)
        return pending.toList()
    }

    fun markDelivered(egvs: List<ShareEgv>) {
        val times = egvs.map(ShareEgv::timeMs).toSet()
        pending.removeAll { it.timeMs in times }
        delivered.addAll(times)
    }

    /**
     * A batch Dexcom rejected. It is remembered as delivered as well as removed: the same
     * moments would otherwise come back with the next history batch and be rejected again.
     */
    fun drop(egvs: List<ShareEgv>) = markDelivered(egvs)

    fun liveSensorId(): String? = liveSensor

    fun encode(): String {
        val pendingJson = JSONArray()
        pending.forEach { egv ->
            pendingJson.put(
                JSONObject()
                    .put(KEY_TIME_MS, egv.timeMs)
                    .put(KEY_MGDL, egv.mgdl)
                    .put(KEY_TREND, egv.trend)
            )
        }
        val deliveredJson = JSONArray()
        delivered.sorted().forEach { deliveredJson.put(it) }
        return JSONObject()
            .put(KEY_WINDOW_MS, windowMs)
            .put(KEY_MIN_SPACING_MS, minSpacingMs)
            .put(KEY_MAX_PENDING, maxPending)
            .put(KEY_ENABLED_AT_MS, enabledAtMs)
            .put(KEY_LIVE_SENSOR, liveSensor ?: JSONObject.NULL)
            .put(KEY_PENDING, pendingJson)
            .put(KEY_DELIVERED, deliveredJson)
            .toString()
    }

    private fun offer(egv: ShareEgv, nowMs: Long): Boolean {
        if (egv.mgdl <= 0 || egv.timeMs <= 0L) return false
        if (egv.timeMs > nowMs + FUTURE_SKEW_MS) return false
        if (egv.timeMs < nowMs - windowMs) return false
        if (isCovered(egv.timeMs)) return false
        pending.add(egv)
        sortPending()
        trim(nowMs)
        return true
    }

    private fun isCovered(timeMs: Long): Boolean =
        pending.any { kotlin.math.abs(it.timeMs - timeMs) < minSpacingMs } ||
            delivered.any { kotlin.math.abs(it - timeMs) < minSpacingMs }

    private fun trim(nowMs: Long) {
        val oldest = nowMs - windowMs
        pending.removeAll { it.timeMs < oldest }
        delivered.removeAll { it < oldest }
        while (pending.size > maxPending) {
            pending.removeAt(0)
        }
    }

    private fun sortPending() {
        pending.sortBy(ShareEgv::timeMs)
    }
}
