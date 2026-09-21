package tk.glucodata.update

import android.content.Context
import org.json.JSONObject
import tk.glucodata.BuildConfig
import tk.glucodata.settings.store.SettingKey
import tk.glucodata.settings.store.SettingsStore
import tk.glucodata.settings.store.SettingsStoreImpl
import tk.glucodata.settings.store.SharedPreferencesKeyValueStore

/** The updater's settings, as typed keys on the `app_updates` prefs file. */
object AppUpdateKeys {
    private const val FILE = "app_updates"

    val AUTO_CHECK = SettingKey(FILE, "auto_check", false)
    val INTRO_ANSWERED = SettingKey(FILE, "intro_answered", false)
    val SOURCE = SettingKey(FILE, "update_source", null as String?)
    val LAST_CHECK_AT = SettingKey(FILE, "last_check_at", 0L)
    val LAST_ERROR = SettingKey(FILE, "last_error", null as String?)
    val CACHED_UPDATE = SettingKey(FILE, "cached_update", null as String?)
    val DISMISSED = SettingKey(FILE, "dismissed_update", null as String?)
    val DISMISSED_AT = SettingKey(FILE, "dismissed_update_at", 0L)
}

/**
 * Preferences for the in-app updater, plus the cached result of the last check.
 *
 * Caching the found release matters for a background check: the worker runs while no UI exists,
 * and the settings card has to be able to say "1.2.0-Alpha is available" without going back to
 * the network the moment the user opens Settings.
 *
 * Everything goes through [SettingsStore] (plan task T2.3): the keys live in [AppUpdateKeys] and
 * the multi-key writes use one `edit` so they stay atomic. [now] is injectable so the dismissal
 * TTL is testable without a real clock.
 */
class AppUpdateStore(
    private val store: SettingsStore,
    private val defaultSource: String,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val dismissalTtlMs = 7L * 24 * 60 * 60 * 1000

    /**
     * Whether the one-time "JugglucoNG can check for updates" card has been answered.
     * Unanswered means the card is still owed to the user — including to users upgrading from a
     * build that had no updater at all.
     */
    fun isIntroAnswered(): Boolean = store.get(AppUpdateKeys.INTRO_ANSWERED)

    fun setIntroAnswered(answered: Boolean) {
        store.set(AppUpdateKeys.INTRO_ANSWERED, answered)
    }

    /**
     * Off until the user says yes. An update check is an outbound request that reveals the
     * device's IP to the update source, so it is opt-in rather than opt-out.
     */
    fun isAutoCheckEnabled(): Boolean = store.get(AppUpdateKeys.AUTO_CHECK)

    fun setAutoCheckEnabled(enabled: Boolean) {
        store.edit {
            put(AppUpdateKeys.AUTO_CHECK, enabled)
            put(AppUpdateKeys.INTRO_ANSWERED, true)
        }
    }

    /** The https URL releases are read from. Defaults to this build's own project. */
    fun updateSource(): String {
        val stored = store.get(AppUpdateKeys.SOURCE)
        return stored?.takeIf { UpdateSource.isValid(it) } ?: defaultSource
    }

    fun isDefaultUpdateSource(): Boolean = updateSource() == defaultSource

    /** Pass null to go back to the default. Invalid values are ignored rather than stored. */
    fun setUpdateSource(url: String?) {
        val cleaned = url?.let(UpdateSource::sanitize)
        when {
            cleaned == null || cleaned == defaultSource -> store.edit { remove(AppUpdateKeys.SOURCE) }
            UpdateSource.isValid(cleaned) -> store.set(AppUpdateKeys.SOURCE, cleaned)
            else -> return
        }
    }

    fun lastCheckAtMillis(): Long = store.get(AppUpdateKeys.LAST_CHECK_AT)

    fun lastError(): UpdateError? =
        store.get(AppUpdateKeys.LAST_ERROR)
            ?.let { name -> UpdateError.entries.firstOrNull { it.name == name } }

    /** Records the outcome of a check; a successful check clears any previous error. */
    fun recordCheck(result: UpdateCheckResult, atMillis: Long) {
        store.edit {
            put(AppUpdateKeys.LAST_CHECK_AT, atMillis)
            when (result) {
                is UpdateCheckResult.Available -> {
                    remove(AppUpdateKeys.LAST_ERROR)
                    put(AppUpdateKeys.CACHED_UPDATE, encode(result.update))
                }
                UpdateCheckResult.UpToDate -> {
                    remove(AppUpdateKeys.LAST_ERROR)
                    remove(AppUpdateKeys.CACHED_UPDATE)
                }
                is UpdateCheckResult.Failed -> put(AppUpdateKeys.LAST_ERROR, result.error.name)
            }
        }
    }

    fun cachedUpdate(): AvailableUpdate? =
        store.get(AppUpdateKeys.CACHED_UPDATE)?.let(::decode)

    fun clearCachedUpdate() {
        store.edit { remove(AppUpdateKeys.CACHED_UPDATE) }
    }

    /** Banner dismissal is per release and expires, so a still-pending update comes back. */
    fun dismissedIdentity(): String? {
        val identity = store.get(AppUpdateKeys.DISMISSED) ?: return null
        val age = now() - store.get(AppUpdateKeys.DISMISSED_AT)
        // A clock that moved backwards counts as expired rather than as dismissed forever.
        if (age !in 0 until dismissalTtlMs) {
            store.edit {
                remove(AppUpdateKeys.DISMISSED)
                remove(AppUpdateKeys.DISMISSED_AT)
            }
            return null
        }
        return identity
    }

    fun setDismissedIdentity(identity: String?) {
        store.edit {
            if (identity == null) {
                remove(AppUpdateKeys.DISMISSED)
                remove(AppUpdateKeys.DISMISSED_AT)
            } else {
                put(AppUpdateKeys.DISMISSED, identity)
                put(AppUpdateKeys.DISMISSED_AT, now())
            }
        }
    }

    private fun encode(update: AvailableUpdate): String = JSONObject().apply {
        put("versionName", update.versionName)
        update.versionCode?.let { put("versionCode", it) }
        put("tagName", update.tagName)
        put("notes", update.notes)
        put("publishedAt", update.publishedAtMillis)
        put("prerelease", update.prerelease)
        put("fileName", update.artifact.fileName)
        put("downloadUrl", update.artifact.downloadUrl)
        put("size", update.artifact.sizeBytes)
        update.artifact.sha256?.let { put("sha256", it) }
    }.toString()

    private fun decode(raw: String): AvailableUpdate? = runCatching {
        val obj = JSONObject(raw)
        val url = obj.getString("downloadUrl")
        // A cached entry survives app restarts, and the source setting may have changed since.
        if (!UpdateSourceClient.isDownloadableUrl(url)) return@runCatching null
        AvailableUpdate(
            versionName = obj.getString("versionName"),
            versionCode = if (obj.has("versionCode")) obj.getInt("versionCode") else null,
            tagName = obj.getString("tagName"),
            notes = obj.optString("notes"),
            publishedAtMillis = obj.optLong("publishedAt", 0L),
            prerelease = obj.optBoolean("prerelease", false),
            artifact = UpdateArtifact(
                fileName = obj.getString("fileName"),
                downloadUrl = url,
                sizeBytes = obj.optLong("size", 0L),
                sha256 = obj.optString("sha256").takeIf { it.length == 64 }
            )
        )
    }.getOrNull()
}

/**
 * The Context-shaped entry point the rest of the updater calls. Kept so call sites
 * do not change; all of it delegates to [AppUpdateStore] over the `app_updates`
 * prefs file.
 */
object AppUpdateSettings {

    val defaultUpdateSource: String get() = "https://github.com/${BuildConfig.UPDATE_REPO}"

    private fun logic(context: Context): AppUpdateStore =
        AppUpdateStore(
            SettingsStoreImpl(SharedPreferencesKeyValueStore(context.applicationContext)),
            defaultUpdateSource,
        )

    fun isIntroAnswered(context: Context): Boolean = logic(context).isIntroAnswered()

    fun setIntroAnswered(context: Context, answered: Boolean) =
        logic(context).setIntroAnswered(answered)

    fun isAutoCheckEnabled(context: Context): Boolean = logic(context).isAutoCheckEnabled()

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) =
        logic(context).setAutoCheckEnabled(enabled)

    fun updateSource(context: Context): String = logic(context).updateSource()

    fun isDefaultUpdateSource(context: Context): Boolean = logic(context).isDefaultUpdateSource()

    fun setUpdateSource(context: Context, url: String?) = logic(context).setUpdateSource(url)

    fun lastCheckAtMillis(context: Context): Long = logic(context).lastCheckAtMillis()

    fun lastError(context: Context): UpdateError? = logic(context).lastError()

    fun recordCheck(context: Context, result: UpdateCheckResult, atMillis: Long) =
        logic(context).recordCheck(result, atMillis)

    fun cachedUpdate(context: Context): AvailableUpdate? = logic(context).cachedUpdate()

    fun clearCachedUpdate(context: Context) = logic(context).clearCachedUpdate()

    fun dismissedIdentity(context: Context): String? = logic(context).dismissedIdentity()

    fun setDismissedIdentity(context: Context, identity: String?) =
        logic(context).setDismissedIdentity(identity)
}
