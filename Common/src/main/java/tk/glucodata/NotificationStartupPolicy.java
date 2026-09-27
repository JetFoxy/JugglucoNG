package tk.glucodata;

/**
 * Testable policy for the truthful foreground-service startup subset (N1).
 *
 * <p>Pure Java with no Android dependencies so the rules can be exercised on the JVM:
 * the notification header must show the timestamp of the reading actually displayed
 * (never render time, never a fabricated value), startup restoration classifies the
 * actual resolved timestamp without relabelling stale history as fresh, and a queued
 * asynchronous restore must not post after the service was destroyed, replaced or
 * stopped, nor overwrite a newer genuine reading.
 *
 * <p>Shared resolver semantics, units, calibration and alert delivery are untouched;
 * this class only classifies and guards.
 */
public final class NotificationStartupPolicy {
    private NotificationStartupPolicy() {
    }

    /** Outcome of classifying the actual resolved reading timestamp at restore time. */
    public enum RestoreOutcome {
        FRESH_READING,
        STALE_READING,
        AWAITING_DATA,
        NO_SENSOR
    }

    /**
     * Classify the restore outcome from the actual resolved timestamps via the shared
     * {@link DisplayDataState} policy. A null resolution flattens failures, and a
     * non-null snapshot does not imply freshness (history fallback), so both the
     * current and the latest history timestamps are considered. All arguments are
     * explicit so loading this class never touches Android or native initializers.
     */
    public static RestoreOutcome classifyRestore(boolean sensorPresent, long currentMillis,
            long historyMillis, long nowMillis, long freshnessWindowMillis) {
        DisplayDataState.Status status = DisplayDataState.resolve(sensorPresent, currentMillis,
                historyMillis, freshnessWindowMillis, nowMillis);
        switch (status.getKind()) {
            case FRESH:
                return RestoreOutcome.FRESH_READING;
            case STALE:
                return RestoreOutcome.STALE_READING;
            case NO_SENSOR:
                return RestoreOutcome.NO_SENSOR;
            case AWAITING_DATA:
            default:
                return RestoreOutcome.AWAITING_DATA;
        }
    }

    /**
     * Header timestamp for reading-bearing ongoing content: the reading actually
     * displayed, including the fallback/multi-source primary resolution. Returns 0
     * when no valid reading is displayed; the caller must then hide the header
     * timestamp instead of fabricating one (not render time, not the blindly passed
     * incoming argument).
     */
    public static long resolveHeaderWhenMillis(long displayedReadingMillis) {
        return displayedReadingMillis > 0L ? displayedReadingMillis : 0L;
    }

    /** Whether a header timestamp may be shown for the displayed reading. */
    public static boolean showHeaderWhen(long displayedReadingMillis) {
        return displayedReadingMillis > 0L;
    }

    /**
     * Lifecycle guard for the asynchronous startup restore.
     *
     * <p>Every foreground entry starts a new epoch; a queued restore carries the
     * epoch and service identity it was queued under. Cancellation alone is not
     * relied upon across the worker/main boundary: a restore that is already
     * resolving re-checks the guard after the expensive render, immediately before
     * posting. Genuine reading posts are recorded so a late restore can neither
     * overwrite newer content nor mirror an already-posted reading a second time.
     * Bookkeeping happens only at actual post time, never speculatively.
     */
    public static final class RestoreGuard {
        private long generation = 0L;
        private long newestPostedReadingMillis = 0L;

        /** Starts a new restore epoch, superseding any previously queued restore. */
        public synchronized long begin() {
            return ++generation;
        }

        /** The current epoch, read by a restore when it starts resolving. */
        public synchronized long currentGeneration() {
            return generation;
        }

        /** Records a genuine reading post; non-positive values never count as data. */
        public synchronized void markPosted(long readingMillis) {
            if (readingMillis > newestPostedReadingMillis) {
                newestPostedReadingMillis = readingMillis;
            }
        }

        public synchronized long newestPosted() {
            return newestPostedReadingMillis;
        }

        /**
         * Whether a queued restore may still post.
         *
         * @param generation generation captured when the restore was queued
         * @param serviceCurrent the queuing service instance is still the active one
         * @param serviceStarted the service is still required/started
         * @param candidateReadingMillis reading the restore resolved, or 0 when the
         *                               restore found no data
         */
        public synchronized boolean shouldPost(long generation, boolean serviceCurrent,
                boolean serviceStarted, long candidateReadingMillis) {
            if (generation != this.generation) {
                return false;
            }
            if (!serviceCurrent || !serviceStarted) {
                return false;
            }
            if (newestPostedReadingMillis > 0L) {
                if (candidateReadingMillis <= 0L) {
                    // No data must not replace genuine content.
                    return false;
                }
                if (candidateReadingMillis <= newestPostedReadingMillis) {
                    // A newer genuine reading wins; an equal one is already mirrored.
                    return false;
                }
            }
            return true;
        }
    }
}
