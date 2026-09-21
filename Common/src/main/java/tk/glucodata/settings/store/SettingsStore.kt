package tk.glucodata.settings.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

/**
 * A typed handle on one stored setting: which prefs file it lives in, its key,
 * and what to return when it has never been written. The default is part of the
 * key, so a missing value is a value, not an exception.
 */
class SettingKey<T>(
    val file: String,
    val name: String,
    val default: T,
)

/**
 * The single facade settings should be read and written through (plan task
 * T2.2). Call sites move onto it in T2.3; this step only establishes it.
 */
interface SettingsStore {
    fun <T> get(key: SettingKey<T>): T
    fun <T> set(key: SettingKey<T>, value: T)
    fun <T> observe(key: SettingKey<T>): Flow<T>
}

/**
 * Where a [SettingsStore] actually keeps bytes. Kept behind an interface so the
 * store's behaviour — defaults, typing, change notification — is testable on the
 * JVM, while the Android implementation stays a thin wrapper over
 * SharedPreferences.
 */
interface KeyValueStore {
    fun read(file: String, key: String): Any?
    fun write(file: String, key: String, value: Any?)

    /** Emits the name of every key that changes in [file]. */
    fun changes(file: String): Flow<String>
}

class SettingsStoreImpl(private val backend: KeyValueStore) : SettingsStore {

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: SettingKey<T>): T =
        (backend.read(key.file, key.name) as? T) ?: key.default

    override fun <T> set(key: SettingKey<T>, value: T) {
        backend.write(key.file, key.name, value)
    }

    override fun <T> observe(key: SettingKey<T>): Flow<T> = flow {
        emit(get(key))
        backend.changes(key.file)
            .filter { it == key.name }
            .collect { emit(get(key)) }
    }.distinctUntilChanged()
}
