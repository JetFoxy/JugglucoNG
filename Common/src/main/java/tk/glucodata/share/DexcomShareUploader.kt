package tk.glucodata.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.Keep
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.SensorIdentity

/** Where a flush reports what happened; the Android side persists it. */
internal interface DexcomShareStatusSink {
    fun recordSuccess(count: Int, newestMs: Long)
    fun recordFailure(code: Int, message: String)
    fun recordAuthBlocked()
    fun persistQueue()
}

/**
 * One flush of the pending queue: log in if there is no session, then upload in batches of
 * [DexcomShareJson.MAX_EGVS_PER_REQUEST].
 *
 * Everything that decides whether a failure may be retried is here rather than in
 * [DexcomShareUploader], so it can be tested without Android or a network. The rules that matter:
 * an expired session is re-logged in at most once per flush, bad credentials and lockouts stop
 * everything until the user acts, and a transient failure keeps the queue and backs off.
 *
 * @param clock injected so the tests can move time instead of waiting.
 */
internal class DexcomShareFlush(
    private val queue: DexcomShareQueue,
    private val sink: DexcomShareStatusSink,
    private val transportFor: (DexcomShareRegion) -> DexcomShareClient.Transport,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        const val INITIAL_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_MS = 30L * 60_000L

        /**
         * Logins Dexcom answered with an error, in a row, before the account is treated as
         * blocked. A wrong password Dexcom reports under a code this app does not know would
         * otherwise be retried on every backoff (about 48 times a day) until the account locks.
         * Transport failures do not count: they never reached Dexcom.
         */
        const val MAX_FAILED_LOGINS = 5
    }

    var sessionId: String? = null
        private set

    var authBlocked: Boolean = false
        private set

    private var backoffMs = 0L
    private var nextAttemptAtMs = 0L
    private var failedLogins = 0

    /** Either a session, or what [flush] must return because there is none. */
    private sealed class Login {
        object Ok : Login()
        class Stop(val delayMs: Long?) : Login()
    }

    /** The persisted flag from a previous process, restored when the engine is built. */
    @Synchronized
    fun markAuthBlocked(blocked: Boolean) {
        authBlocked = blocked
    }

    /**
     * @return the delay before the next attempt when one is scheduled, or null when there is
     *         nothing left to do (or nothing that may be tried).
     */
    @Synchronized
    fun flush(config: DexcomShareSettings.Config): Long? {
        val now = clock()
        if (!config.isReady() || authBlocked) return null
        if (now < nextAttemptAtMs) return nextAttemptAtMs - now

        val pending = queue.pending(now)
        if (pending.isEmpty()) return null

        val client = DexcomShareClient(config.region, transportFor(config.region))
        if (sessionId == null) {
            val login = logIn(client, config, now)
            if (login is Login.Stop) return login.delayMs
        }

        var relogged = false
        var index = 0
        while (index < pending.size) {
            val end = minOf(index + DexcomShareJson.MAX_EGVS_PER_REQUEST, pending.size)
            val chunk = pending.subList(index, end)
            when (val result = client.upload(sessionId!!, config.serial, chunk)) {
                is DexcomShareClient.UploadResult.Ok -> {
                    queue.markDelivered(chunk)
                    sink.recordSuccess(chunk.size, chunk.last().timeMs)
                    resetBackoff()
                    index = end
                }

                is DexcomShareClient.UploadResult.Failed -> when (result.kind) {
                    DexcomShareError.Kind.SESSION_EXPIRED -> {
                        // One re-login per flush: a server that keeps answering 500 must not
                        // turn into a login loop.
                        if (relogged) return delayNextAttempt(now, result.code, result.message)
                        relogged = true
                        sessionId = null
                        val login = logIn(client, config, now)
                        if (login is Login.Stop) return login.delayMs
                    }

                    DexcomShareError.Kind.BAD_CREDENTIALS,
                    DexcomShareError.Kind.LOCKED -> return blockAccount(result.code, result.message)

                    DexcomShareError.Kind.TRANSIENT ->
                        return delayNextAttempt(now, result.code, result.message)

                    DexcomShareError.Kind.REJECTED -> {
                        // This batch would never succeed; drop it and carry on with the rest.
                        queue.drop(chunk)
                        sink.recordFailure(result.code, result.message)
                        sink.persistQueue()
                        index = end
                    }
                }
            }
        }
        sink.persistQueue()
        return null
    }

    private fun logIn(client: DexcomShareClient, config: DexcomShareSettings.Config, now: Long): Login =
        when (val login = client.login(config.account, config.password, config.serial)) {
            is DexcomShareClient.LoginResult.Ok -> {
                sessionId = login.sessionId
                failedLogins = 0
                Login.Ok
            }
            is DexcomShareClient.LoginResult.Failed -> {
                if (login.code != DexcomShareClient.TRANSPORT_FAILURE_CODE) failedLogins++
                when {
                    login.kind == DexcomShareError.Kind.BAD_CREDENTIALS ||
                        login.kind == DexcomShareError.Kind.LOCKED ||
                        failedLogins >= MAX_FAILED_LOGINS ->
                        Login.Stop(blockAccount(login.code, login.message))
                    else -> Login.Stop(delayNextAttempt(now, login.code, login.message))
                }
            }
        }

    private fun blockAccount(code: Int, message: String): Long? {
        authBlocked = true
        sink.recordFailure(code, message)
        sink.recordAuthBlocked()
        sink.persistQueue()
        return null
    }

    private fun delayNextAttempt(now: Long, code: Int, message: String): Long {
        sink.recordFailure(code, message)
        backoffMs = if (backoffMs <= 0L) {
            INITIAL_BACKOFF_MS
        } else {
            (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        nextAttemptAtMs = now + backoffMs
        sink.persistQueue()
        return backoffMs
    }

    private fun resetBackoff() {
        backoffMs = 0L
        nextAttemptAtMs = 0L
    }
}

/**
 * Sends what the app already produces to the user's Dexcom Share publisher account, so Dexcom
 * Follow and the followers built on Share (xDrip+, Nightscout's bridge, Loop) show it.
 *
 * Phone only: [tk.glucodata.SuperGattCallback] calls [onLiveReading] only when it is not the
 * watch, because the credentials and the queue live on the phone. Nothing here blocks the caller:
 * the queue is updated inline and the network work is handed to a single background thread.
 */
@Keep
object DexcomShareUploader {
    private const val LOG_ID = "DexcomShare"
    private const val THREAD_NAME = "DexcomShareSend"

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, THREAD_NAME) }

    /** A stored reading as the history writer hands it over. */
    data class Row(val timeMs: Long, val mgdl: Int, val trendIndex: Int)

    data class Result(val ok: Boolean, val message: String)

    private class State(val queue: DexcomShareQueue, val engine: DexcomShareFlush)

    private val lock = Any()
    private var state: State? = null

    /** 0 while nothing is queued; otherwise the time the pending flush is due. */
    private var scheduledAtMs = 0L
    private var scheduled: ScheduledFuture<*>? = null

    /** A live reading. Called on the BLE callback thread, so it must not block or throw. */
    @JvmStatic
    fun onLiveReading(sensorId: String?, mgdl: Int, trendIndex: Int, timeMs: Long) {
        if (sensorId.isNullOrEmpty() || mgdl <= 0 || timeMs <= 0L) return
        try {
            val context = Applic.app ?: return
            if (!DexcomShareSettings.isEnabled(context)) return
            val current = stateFor(context)
            val accepted = current.queue.offerLive(
                sensorKey(sensorId),
                ShareEgv(timeMs, mgdl, DexcomShareJson.normalizeTrend(trendIndex)),
                System.currentTimeMillis()
            )
            if (accepted) requestFlush(context)
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "onLiveReading", error)
        }
    }

    /**
     * Readings the history writer just stored, which the live path may have missed. Only the
     * sensor that produced the last live reading is backfilled, and only for readings newer than
     * the moment Share was switched on — see [DexcomShareQueue.offerBackfill].
     */
    @JvmStatic
    fun onHistoryStored(sensorSerial: String, rows: List<Row>) {
        if (sensorSerial.isEmpty() || rows.isEmpty()) return
        try {
            val context = Applic.app ?: return
            if (!DexcomShareSettings.isEnabled(context)) return
            val current = stateFor(context)
            val accepted = current.queue.offerBackfill(
                sensorKey(sensorSerial),
                rows.filter { it.mgdl > 0 }.map {
                    ShareEgv(it.timeMs, it.mgdl, DexcomShareJson.normalizeTrend(it.trendIndex))
                },
                System.currentTimeMillis()
            )
            if (accepted > 0) requestFlush(context)
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "onHistoryStored", error)
        }
    }

    /**
     * The settings screen saved something: forget the session and try again from scratch. A flush
     * still waiting out a backoff is cancelled, or the new settings would sit unused for up to
     * [DexcomShareFlush.MAX_BACKOFF_MS].
     *
     * @param flush false when the caller is about to run [testConnection]: that test logs in
     *        itself and starts the flush when it succeeds, so flushing here too would spend a
     *        second login, and with a wrong password a second failed attempt, on one tap.
     */
    fun onSettingsChanged(context: Context, flush: Boolean = true) {
        try {
            val appContext = context.applicationContext
            synchronized(lock) { state = null }
            if (flush) requestFlush(appContext, replace = true) else cancelScheduledFlush()
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "onSettingsChanged", error)
        }
    }

    /**
     * The settings screen's Test button. Runs the full login sequence off the UI thread and hands
     * the outcome back; a success clears a blocked login and starts sending again.
     */
    fun testConnection(
        context: Context,
        config: DexcomShareSettings.Config,
        callback: (Result) -> Unit
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val serial = DexcomShareSettings.normalizeSerial(config.serial)
            val result = try {
                val client = DexcomShareClient(config.region, DexcomShareClient.HttpTransport)
                when (val login = client.login(config.account, config.password, serial)) {
                    is DexcomShareClient.LoginResult.Ok -> Result(true, "Connected")
                    is DexcomShareClient.LoginResult.Failed -> {
                        if (login.kind == DexcomShareError.Kind.BAD_CREDENTIALS ||
                            login.kind == DexcomShareError.Kind.LOCKED
                        ) {
                            DexcomShareSettings.setAuthBlocked(appContext, true)
                        }
                        Result(false, login.message)
                    }
                }
            } catch (error: Throwable) {
                Log.stack(LOG_ID, "testConnection", error)
                Result(false, error.message ?: "Connection failed")
            }
            if (result.ok) {
                DexcomShareSettings.setAuthBlocked(appContext, false)
                synchronized(lock) { state = null }
                requestFlush(appContext, replace = true)
            }
            runCatching { callback(result) }.onFailure { Log.stack(LOG_ID, "testConnection callback", it) }
        }
    }

    /** The queue and the session state, restored lazily from the prefs on first use. */
    private fun stateFor(context: Context): State {
        synchronized(lock) {
            state?.let { return it }
            val stored = DexcomShareSettings.queueJson(context)
            val queue = if (stored.isNullOrBlank()) {
                DexcomShareQueue(enabledAtMs = DexcomShareSettings.enabledAtMs(context))
            } else {
                DexcomShareQueue.decode(stored)
            }
            val engine = DexcomShareFlush(
                queue = queue,
                sink = AndroidSink(context, queue),
                transportFor = { DexcomShareClient.HttpTransport },
            )
            engine.markAuthBlocked(DexcomShareSettings.isAuthBlocked(context))
            val created = State(queue, engine)
            state = created
            return created
        }
    }

    /**
     * Queues a flush unless one is already waiting; the engine itself owns the backoff.
     *
     * @param replace cancel a waiting flush and start over now, for a change the user made.
     */
    private fun requestFlush(context: Context, delayMs: Long = 0L, replace: Boolean = false) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (replace) {
                scheduled?.cancel(false)
                scheduled = null
                scheduledAtMs = 0L
            }
            if (scheduledAtMs != 0L) return
            scheduledAtMs = System.currentTimeMillis() + delayMs
            scheduled = executor.schedule({ runFlush(appContext) }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun cancelScheduledFlush() {
        synchronized(lock) {
            scheduled?.cancel(false)
            scheduled = null
            scheduledAtMs = 0L
        }
    }

    private fun runFlush(context: Context) {
        synchronized(lock) {
            scheduledAtMs = 0L
            scheduled = null
        }
        try {
            val config = DexcomShareSettings.load(context)
            if (!config.isReady()) return
            if (!hasUsableNetwork(context)) return
            val delay = stateFor(context).engine.flush(config)
            if (delay != null) requestFlush(context, delay)
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "flush", error)
        }
    }

    /** Same rule as the outbound API: no network means no attempt, and no recorded failure. */
    private fun hasUsableNetwork(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sensorKey(sensorId: String): String =
        runCatching { SensorIdentity.resolveAppSensorId(sensorId) }.getOrNull() ?: sensorId

    private class AndroidSink(
        private val context: Context,
        private val queue: DexcomShareQueue
    ) : DexcomShareStatusSink {
        override fun recordSuccess(count: Int, newestMs: Long) =
            DexcomShareSettings.recordSuccess(context, count, newestMs)

        override fun recordFailure(code: Int, message: String) =
            DexcomShareSettings.recordFailure(context, code, message)

        override fun recordAuthBlocked() = DexcomShareSettings.setAuthBlocked(context, true)

        override fun persistQueue() {
            DexcomShareSettings.saveQueueJson(context, queue.encode())
            DexcomShareSettings.recordPending(context, queue.pending(System.currentTimeMillis()).size)
        }
    }
}
