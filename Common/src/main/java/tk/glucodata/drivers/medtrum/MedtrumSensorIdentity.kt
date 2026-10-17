// MedtrumSensorIdentity.kt — sensor life window and short-name aliases, free of Android types
// so the rules can be tested without a driver.

package tk.glucodata.drivers.medtrum

/**
 * The patch reports the seconds since insertion in every readState record; EasySense counts its
 * 14 days down from that same value (checked against the vendor app: 5 h 56 min elapsed showed
 * "13 d 18 h 4 min" left).
 */
object MedtrumSensorLife {

    const val LIFETIME_MS = 14L * 24L * 60L * 60L * 1000L

    /** Elapsed time shorter than this is a patch that has just been inserted or a bad record. */
    private const val MIN_ELAPSED_SECONDS = 1L

    /** A recomputed start within this of the stored one is the same start with a few seconds of jitter. */
    private const val RESTART_TOLERANCE_MS = 2L * 60L * 1000L

    fun startFromElapsed(nowMs: Long, elapsedSeconds: Long): Long {
        if (elapsedSeconds < MIN_ELAPSED_SECONDS) return 0L
        return nowMs - elapsedSeconds * 1000L
    }

    fun endFromStart(startMs: Long): Long = if (startMs > 0L) startMs + LIFETIME_MS else 0L

    /**
     * Store a new start when none is known, or when it moved by more than the jitter (a patch that
     * was replaced under the same serial). Every readState would otherwise rewrite the prefs.
     */
    fun shouldReplaceStart(storedMs: Long, candidateMs: Long): Boolean {
        if (candidateMs <= 0L) return false
        if (storedMs <= 0L) return true
        return kotlin.math.abs(candidateMs - storedMs) > RESTART_TOLERANCE_MS
    }

    fun ageHours(startMs: Long, nowMs: Long): Int =
        if (startMs > 0L && nowMs >= startMs) ((nowMs - startMs) / 3_600_000L).toInt() else -1

    fun remainingHours(startMs: Long, nowMs: Long): Int {
        if (startMs <= 0L) return -1
        val left = endFromStart(startMs) - nowMs
        return if (left > 0L) (left / 3_600_000L).toInt() else 0
    }
}

/**
 * The native layer keeps a mirror of a patch under a shortened name: the serial 2329895446 came
 * back as `95446`. Unrecognised, that name was built into a generic Libre callback, which showed
 * up as a second, phantom sensor. A trailing part of the serial is the same patch.
 */
object MedtrumSerialAlias {

    /** Short enough to be a truncation, long enough that a stray digit run does not match. */
    private const val MIN_ALIAS_LENGTH = 5

    fun isShortForm(candidate: String?, serial: String?): Boolean {
        val short = candidate?.trim().orEmpty()
        val full = serial?.trim().orEmpty()
        if (short.length < MIN_ALIAS_LENGTH || short.length >= full.length) return false
        if (!short.all { it.isDigit() } || !full.all { it.isDigit() }) return false
        return full.endsWith(short)
    }
}
