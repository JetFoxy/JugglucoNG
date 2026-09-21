package tk.glucodata.settings.store

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The Android [KeyValueStore]: one SharedPreferences file per [SettingKey.file].
 *
 * Thin on purpose — it holds no logic beyond mapping value types onto the
 * editor, so the behaviour worth testing (defaults, change notification,
 * concurrency) lives in [SettingsStoreImpl] and is covered on the JVM through
 * [InMemoryKeyValueStore].
 */
class SharedPreferencesKeyValueStore(private val context: Context) : KeyValueStore {

    private fun prefs(file: String): SharedPreferences =
        context.getSharedPreferences(file, Context.MODE_PRIVATE)

    override fun read(file: String, key: String): Any? = prefs(file).all[key]

    override fun write(file: String, key: String, value: Any?) {
        val editor = prefs(file).edit()
        put(editor, key, value)
        editor.apply()
    }

    override fun writeAll(file: String, values: Map<String, Any?>) {
        val editor = prefs(file).edit()
        values.forEach { (key, value) -> put(editor, key, value) }
        editor.apply()
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
            else -> editor.putString(key, value.toString())
        }
    }

    override fun changes(file: String): Flow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) trySend(key)
        }
        prefs(file).registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs(file).unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
