package tk.glucodata.settings.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A [KeyValueStore] that keeps everything in memory. Used by the contract test
 * and available to previews; not a place to put real settings.
 *
 * Writes are serialised on one lock and the change signal is emitted after the
 * value is stored, so a listener can never observe a change before the value.
 */
class InMemoryKeyValueStore : KeyValueStore {

    private val lock = Any()
    private val values = HashMap<String, HashMap<String, Any?>>()
    private val changeFlows = HashMap<String, MutableSharedFlow<String>>()

    override fun read(file: String, key: String): Any? = synchronized(lock) {
        values[file]?.get(key)
    }

    override fun write(file: String, key: String, value: Any?) {
        synchronized(lock) {
            values.getOrPut(file) { HashMap() }[key] = value
        }
        flowFor(file).tryEmit(key)
    }

    override fun writeAll(file: String, values: Map<String, Any?>) {
        synchronized(lock) {
            val target = this.values.getOrPut(file) { HashMap() }
            target.putAll(values)
        }
        values.keys.forEach { flowFor(file).tryEmit(it) }
    }

    override fun changes(file: String): Flow<String> = flowFor(file)

    override fun entries(file: String): Map<String, Any?> = synchronized(lock) {
        HashMap(values[file] ?: emptyMap())
    }

    private fun flowFor(file: String): MutableSharedFlow<String> = synchronized(lock) {
        changeFlows.getOrPut(file) { MutableSharedFlow(extraBufferCapacity = 64) }
    }
}
