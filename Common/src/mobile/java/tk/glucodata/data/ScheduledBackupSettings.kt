package tk.glucodata.data

import android.content.Context
import android.net.Uri
import tk.glucodata.settings.store.SettingKey
import tk.glucodata.settings.store.SettingsEdit
import tk.glucodata.settings.store.SettingsStore
import tk.glucodata.settings.store.SettingsStoreImpl
import tk.glucodata.settings.store.SharedPreferencesKeyValueStore

data class ScheduledBackupMetrics(
    val compression: ExportCompression,
    val byteSize: Long,
    val historyReadings: Int,
    val journalEntries: Int,
    val journalFoods: Int,
    val insulinPresets: Int,
    val calibrations: Int
)

data class ScheduledBackupConfig(
    val enabled: Boolean,
    val destination: Uri?,
    val hour: Int,
    val minute: Int,
    val compression: ExportCompression,
    val dailyRetention: Int,
    val weeklyRetention: Int,
    val monthlyRetention: Int,
    val lastSuccessAtMillis: Long,
    val lastFileName: String?,
    val lastAttemptAtMillis: Long,
    val lastError: String?,
    val integrityWarning: String?,
    val baselineMetrics: ScheduledBackupMetrics?,
    val pendingMetrics: ScheduledBackupMetrics?
)

internal val scheduledBackupRetentionOptions = listOf(1, 3, 4, 5, 6, 7, 14, 30)

/**
 * The scheduled-backups keys, on the `scheduled_backups` prefs file (plan task
 * T2.3). The metrics under `baseline_`/`pending_` are built from a prefix.
 */
object ScheduledBackupKeys {
    private const val FILE = "scheduled_backups"

    const val BASELINE_PREFIX = "baseline_"
    const val PENDING_PREFIX = "pending_"

    val ENABLED = SettingKey(FILE, "enabled", false)
    val DESTINATION = SettingKey(FILE, "destination", null as String?)
    val HOUR = SettingKey(FILE, "hour", 3)
    val MINUTE = SettingKey(FILE, "minute", 0)
    val COMPRESSION = SettingKey(FILE, "compression", null as String?)
    val DAILY_RETENTION = SettingKey(FILE, "daily_retention", 5)
    val WEEKLY_RETENTION = SettingKey(FILE, "weekly_retention", 4)
    val MONTHLY_RETENTION = SettingKey(FILE, "monthly_retention", 6)
    val LAST_SUCCESS = SettingKey(FILE, "last_success", 0L)
    val LAST_FILE = SettingKey(FILE, "last_file", null as String?)
    val LAST_ATTEMPT = SettingKey(FILE, "last_attempt", 0L)
    val LAST_ERROR = SettingKey(FILE, "last_error", null as String?)
    val INTEGRITY_WARNING = SettingKey(FILE, "integrity_warning", null as String?)

    fun metricCompression(prefix: String) = SettingKey(FILE, prefix + "compression", null as String?)
    fun metricBytes(prefix: String) = SettingKey(FILE, prefix + "bytes", 0L)
    fun metricHistory(prefix: String) = SettingKey(FILE, prefix + "history", 0)
    fun metricJournal(prefix: String) = SettingKey(FILE, prefix + "journal", 0)
    fun metricFoods(prefix: String) = SettingKey(FILE, prefix + "foods", 0)
    fun metricInsulins(prefix: String) = SettingKey(FILE, prefix + "insulins", 0)
    fun metricCalibrations(prefix: String) = SettingKey(FILE, prefix + "calibrations", 0)
}

/**
 * The scheduled-backup settings, over [SettingsStore]. Every write goes through
 * one `edit`, so the last-attempt/success/error/baseline bundle is applied
 * together rather than key by key.
 */
internal class ScheduledBackupStore(private val store: SettingsStore) {

    private fun retention(key: SettingKey<Int>, fallback: Int): Int =
        store.get(key).takeIf { it in scheduledBackupRetentionOptions } ?: fallback

    fun load(): ScheduledBackupConfig = ScheduledBackupConfig(
        enabled = store.get(ScheduledBackupKeys.ENABLED),
        destination = store.get(ScheduledBackupKeys.DESTINATION)?.let(Uri::parse),
        hour = store.get(ScheduledBackupKeys.HOUR).coerceIn(0, 23),
        minute = store.get(ScheduledBackupKeys.MINUTE).coerceIn(0, 59),
        compression = store.get(ScheduledBackupKeys.COMPRESSION)
            ?.let { runCatching { ExportCompression.valueOf(it) }.getOrNull() }
            ?.takeIf { it == ExportCompression.GZIP || it == ExportCompression.ZSTD }
            ?: ExportCompression.GZIP,
        dailyRetention = retention(ScheduledBackupKeys.DAILY_RETENTION, 5),
        weeklyRetention = retention(ScheduledBackupKeys.WEEKLY_RETENTION, 4),
        monthlyRetention = retention(ScheduledBackupKeys.MONTHLY_RETENTION, 6),
        lastSuccessAtMillis = store.get(ScheduledBackupKeys.LAST_SUCCESS),
        lastFileName = store.get(ScheduledBackupKeys.LAST_FILE),
        lastAttemptAtMillis = store.get(ScheduledBackupKeys.LAST_ATTEMPT),
        lastError = store.get(ScheduledBackupKeys.LAST_ERROR),
        integrityWarning = store.get(ScheduledBackupKeys.INTEGRITY_WARNING),
        baselineMetrics = readMetrics(ScheduledBackupKeys.BASELINE_PREFIX),
        pendingMetrics = readMetrics(ScheduledBackupKeys.PENDING_PREFIX),
    )

    fun saveConfiguration(config: ScheduledBackupConfig) {
        require(!config.enabled || config.destination != null) { "A backup folder is required" }
        require(config.dailyRetention in scheduledBackupRetentionOptions) { "Unsupported daily retention count" }
        require(config.weeklyRetention in scheduledBackupRetentionOptions) { "Unsupported weekly retention count" }
        require(config.monthlyRetention in scheduledBackupRetentionOptions) { "Unsupported monthly retention count" }
        require(config.compression != ExportCompression.NONE) {
            "Scheduled backups must use compression"
        }
        store.edit {
            put(ScheduledBackupKeys.ENABLED, config.enabled)
            put(ScheduledBackupKeys.DESTINATION, config.destination?.toString())
            put(ScheduledBackupKeys.HOUR, config.hour.coerceIn(0, 23))
            put(ScheduledBackupKeys.MINUTE, config.minute.coerceIn(0, 59))
            put(ScheduledBackupKeys.COMPRESSION, config.compression.name)
            put(ScheduledBackupKeys.DAILY_RETENTION, config.dailyRetention)
            put(ScheduledBackupKeys.WEEKLY_RETENTION, config.weeklyRetention)
            put(ScheduledBackupKeys.MONTHLY_RETENTION, config.monthlyRetention)
        }
    }

    fun recordSuccess(timestamp: Long, fileName: String, metrics: ScheduledBackupMetrics) {
        store.edit {
            put(ScheduledBackupKeys.LAST_ATTEMPT, timestamp)
            put(ScheduledBackupKeys.LAST_SUCCESS, timestamp)
            put(ScheduledBackupKeys.LAST_FILE, fileName)
            remove(ScheduledBackupKeys.LAST_ERROR)
            remove(ScheduledBackupKeys.INTEGRITY_WARNING)
            removeMetrics(ScheduledBackupKeys.PENDING_PREFIX)
            putMetrics(ScheduledBackupKeys.BASELINE_PREFIX, metrics)
        }
    }

    fun recordSuspiciousSuccess(
        timestamp: Long,
        fileName: String,
        metrics: ScheduledBackupMetrics,
        warning: String
    ) {
        store.edit {
            put(ScheduledBackupKeys.LAST_ATTEMPT, timestamp)
            put(ScheduledBackupKeys.LAST_SUCCESS, timestamp)
            put(ScheduledBackupKeys.LAST_FILE, fileName)
            remove(ScheduledBackupKeys.LAST_ERROR)
            put(ScheduledBackupKeys.INTEGRITY_WARNING, warning.take(1_000))
            putMetrics(ScheduledBackupKeys.PENDING_PREFIX, metrics)
        }
    }

    fun recordSuccessWhileWarningIsPending(
        timestamp: Long,
        fileName: String,
        metrics: ScheduledBackupMetrics
    ) {
        store.edit {
            put(ScheduledBackupKeys.LAST_ATTEMPT, timestamp)
            put(ScheduledBackupKeys.LAST_SUCCESS, timestamp)
            put(ScheduledBackupKeys.LAST_FILE, fileName)
            remove(ScheduledBackupKeys.LAST_ERROR)
            putMetrics(ScheduledBackupKeys.PENDING_PREFIX, metrics)
        }
    }

    fun recordFailure(timestamp: Long, error: String) {
        store.edit {
            put(ScheduledBackupKeys.LAST_ATTEMPT, timestamp)
            put(ScheduledBackupKeys.LAST_ERROR, error.take(500))
        }
    }

    fun acknowledgeIntegrityWarning() {
        val pending = load().pendingMetrics
        store.edit {
            remove(ScheduledBackupKeys.INTEGRITY_WARNING)
            removeMetrics(ScheduledBackupKeys.PENDING_PREFIX)
            pending?.let { putMetrics(ScheduledBackupKeys.BASELINE_PREFIX, it) }
        }
    }

    private fun readMetrics(prefix: String): ScheduledBackupMetrics? {
        val compressionName = store.get(ScheduledBackupKeys.metricCompression(prefix)) ?: return null
        val compression = runCatching { ExportCompression.valueOf(compressionName) }.getOrNull()
            ?: return null
        return ScheduledBackupMetrics(
            compression = compression,
            byteSize = store.get(ScheduledBackupKeys.metricBytes(prefix)),
            historyReadings = store.get(ScheduledBackupKeys.metricHistory(prefix)),
            journalEntries = store.get(ScheduledBackupKeys.metricJournal(prefix)),
            journalFoods = store.get(ScheduledBackupKeys.metricFoods(prefix)),
            insulinPresets = store.get(ScheduledBackupKeys.metricInsulins(prefix)),
            calibrations = store.get(ScheduledBackupKeys.metricCalibrations(prefix)),
        )
    }

    private fun SettingsEdit.putMetrics(prefix: String, metrics: ScheduledBackupMetrics) {
        put(ScheduledBackupKeys.metricCompression(prefix), metrics.compression.name)
        put(ScheduledBackupKeys.metricBytes(prefix), metrics.byteSize)
        put(ScheduledBackupKeys.metricHistory(prefix), metrics.historyReadings)
        put(ScheduledBackupKeys.metricJournal(prefix), metrics.journalEntries)
        put(ScheduledBackupKeys.metricFoods(prefix), metrics.journalFoods)
        put(ScheduledBackupKeys.metricInsulins(prefix), metrics.insulinPresets)
        put(ScheduledBackupKeys.metricCalibrations(prefix), metrics.calibrations)
    }

    private fun SettingsEdit.removeMetrics(prefix: String) {
        remove(ScheduledBackupKeys.metricCompression(prefix))
        remove(ScheduledBackupKeys.metricBytes(prefix))
        remove(ScheduledBackupKeys.metricHistory(prefix))
        remove(ScheduledBackupKeys.metricJournal(prefix))
        remove(ScheduledBackupKeys.metricFoods(prefix))
        remove(ScheduledBackupKeys.metricInsulins(prefix))
        remove(ScheduledBackupKeys.metricCalibrations(prefix))
    }
}

/**
 * The Context-shaped entry point the worker and the UI already call. Kept so call
 * sites do not change; everything delegates to [ScheduledBackupStore].
 */
object ScheduledBackupSettings {

    val retentionOptions = scheduledBackupRetentionOptions

    private fun logic(context: Context) = ScheduledBackupStore(
        SettingsStoreImpl(SharedPreferencesKeyValueStore(context.applicationContext))
    )

    fun load(context: Context): ScheduledBackupConfig = logic(context).load()

    fun saveConfiguration(context: Context, config: ScheduledBackupConfig) =
        logic(context).saveConfiguration(config)

    fun recordSuccess(
        context: Context,
        timestamp: Long,
        fileName: String,
        metrics: ScheduledBackupMetrics
    ) = logic(context).recordSuccess(timestamp, fileName, metrics)

    fun recordSuspiciousSuccess(
        context: Context,
        timestamp: Long,
        fileName: String,
        metrics: ScheduledBackupMetrics,
        warning: String
    ) = logic(context).recordSuspiciousSuccess(timestamp, fileName, metrics, warning)

    fun recordSuccessWhileWarningIsPending(
        context: Context,
        timestamp: Long,
        fileName: String,
        metrics: ScheduledBackupMetrics
    ) = logic(context).recordSuccessWhileWarningIsPending(timestamp, fileName, metrics)

    fun recordFailure(context: Context, timestamp: Long, error: String) =
        logic(context).recordFailure(timestamp, error)

    fun acknowledgeIntegrityWarning(context: Context) =
        logic(context).acknowledgeIntegrityWarning()

    fun hasPersistedWritePermission(context: Context, destination: Uri?): Boolean {
        if (destination == null) return false
        return context.applicationContext.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == destination && permission.isWritePermission
        }
    }
}
