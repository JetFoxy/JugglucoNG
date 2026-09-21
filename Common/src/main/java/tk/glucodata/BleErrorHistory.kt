package tk.glucodata

import java.util.Base64
import java.util.Locale
import tk.glucodata.settings.store.SettingKey
import tk.glucodata.settings.store.SettingsStore
import tk.glucodata.settings.store.SettingsStoreImpl
import tk.glucodata.settings.store.SharedPreferencesKeyValueStore

data class BleErrorEvent(
    val sensorId: String,
    val status: String,
    val atMs: Long,
)

internal const val BLE_ERROR_CARD_WINDOW_MS = 60L * 60L * 1_000L
internal const val BLE_ERROR_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
internal const val BLE_ERROR_MAX_PER_SENSOR = 20
internal const val BLE_ERROR_MAX_TOTAL = 100

/**
 * Keep diagnostics useful without letting a sensor that repeatedly reconnects grow storage
 * forever. Results are newest-first so callers can use the first matching event as the latest.
 */
internal fun retainBleErrorEvents(
    events: List<BleErrorEvent>,
    nowMs: Long,
    maxPerSensor: Int = BLE_ERROR_MAX_PER_SENSOR,
    maxTotal: Int = BLE_ERROR_MAX_TOTAL,
    retentionMs: Long = BLE_ERROR_RETENTION_MS,
): List<BleErrorEvent> {
    if (maxPerSensor <= 0 || maxTotal <= 0) return emptyList()
    val oldestAllowed = nowMs - retentionMs
    val counts = HashMap<String, Int>()
    return events.asSequence()
        .filter { event ->
            event.atMs > 0L &&
                event.atMs >= oldestAllowed &&
                event.sensorId.isNotBlank() &&
                event.status.isNotBlank()
        }
        .sortedByDescending(BleErrorEvent::atMs)
        .filter { event ->
            val key = event.sensorId.trim().uppercase(Locale.ROOT)
            val count = counts[key] ?: 0
            if (count >= maxPerSensor) {
                false
            } else {
                counts[key] = count + 1
                true
            }
        }
        .take(maxTotal)
        .toList()
}

internal fun encodeBleErrorEvents(events: List<BleErrorEvent>): String =
    events.joinToString("\n") { event ->
        val encoder = Base64.getUrlEncoder().withoutPadding()
        listOf(
            event.atMs.toString(),
            encoder.encodeToString(event.sensorId.toByteArray(Charsets.UTF_8)),
            encoder.encodeToString(event.status.toByteArray(Charsets.UTF_8)),
        ).joinToString("\t")
    }

internal fun decodeBleErrorEvents(encoded: String): List<BleErrorEvent> {
    if (encoded.isBlank()) return emptyList()
    val decoder = Base64.getUrlDecoder()
    return encoded.lineSequence().mapNotNull { line ->
        runCatching {
            val parts = line.split('\t')
            if (parts.size != 3) return@runCatching null
            BleErrorEvent(
                sensorId = String(decoder.decode(parts[1]), Charsets.UTF_8),
                status = String(decoder.decode(parts[2]), Charsets.UTF_8),
                atMs = parts[0].toLong(),
            )
        }.getOrNull()
    }.toList()
}

/** Durable, bounded diagnostics for BLE failures. This is independent of trace recording. */
object BleErrorHistory {
    private const val PREFS_NAME = "ble_error_history"

    /** The one key, on the `ble_error_history` prefs file (plan task T2.3). */
    private object Keys {
        val EVENTS = SettingKey(PREFS_NAME, "events_v1", null as String?)
    }

    /** Set by tests to run on an in-memory backend. */
    @Volatile
    internal var storeOverride: SettingsStore? = null

    private val lock = Any()

    private fun store(): SettingsStore? =
        storeOverride ?: Applic.app?.let {
            SettingsStoreImpl(SharedPreferencesKeyValueStore(it.applicationContext))
        }

    @JvmStatic
    fun record(sensorId: String?, status: String?, atMs: Long = System.currentTimeMillis()) {
        val validSensorId = sensorId?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return
        val validStatus = status?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            val store = store() ?: return
            val retained = retainBleErrorEvents(
                listOf(BleErrorEvent(validSensorId, validStatus, atMs)) + load(store),
                nowMs = System.currentTimeMillis(),
            )
            save(store, retained)
        }
    }

    @JvmStatic
    fun latest(sensorId: String?): BleErrorEvent? {
        if (sensorId.isNullOrBlank()) return null
        return events().firstOrNull { SensorIdentity.matches(it.sensorId, sensorId) }
    }

    @JvmStatic
    fun events(): List<BleErrorEvent> {
        synchronized(lock) {
            val store = store() ?: return emptyList()
            val loaded = load(store)
            val retained = retainBleErrorEvents(loaded, System.currentTimeMillis())
            if (retained != loaded) save(store, retained)
            return retained
        }
    }

    @JvmStatic
    fun clear() {
        synchronized(lock) {
            val store = store() ?: return
            store.edit { remove(Keys.EVENTS) }
        }
    }

    private fun load(store: SettingsStore): List<BleErrorEvent> =
        decodeBleErrorEvents(store.get(Keys.EVENTS).orEmpty())

    private fun save(store: SettingsStore, events: List<BleErrorEvent>) {
        store.set(Keys.EVENTS, encodeBleErrorEvents(events))
    }
}
