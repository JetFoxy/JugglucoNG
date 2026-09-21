package tk.glucodata

import tk.glucodata.settings.store.SettingKey
import tk.glucodata.settings.store.SettingsStore
import tk.glucodata.settings.store.SettingsStoreImpl
import tk.glucodata.settings.store.SharedPreferencesKeyValueStore

/**
 * What the user asked each watch to do, as opposed to what the watch has since
 * proved it is doing.
 *
 * Direct-sensor routing is a two-phase protocol: the phone asks, the watch only
 * claims ownership once a connected driver has accepted a reading, and phone BLE
 * keeps running in between. The config screen used to bind its switches straight
 * to the confirmed native state, so every toggle sprang back to off within a
 * second and the request looked like it had been ignored. Remembering the
 * request lets the switch hold while the status line reports the real phase.
 *
 * Now over [SettingsStore] (plan task T2.3); the keys are per node id.
 */
object WearRoutingRequest {
    private const val PREFS = "wear_routing_request"

    private object Keys {
        const val DIRECT_PREFIX = "direct."
        const val ENTER_PREFIX = "enter."
        const val SENSOR_PREFIX = "sensor."

        fun direct(nodeId: String) = SettingKey(PREFS, DIRECT_PREFIX + nodeId, false)
        fun enter(nodeId: String) = SettingKey(PREFS, ENTER_PREFIX + nodeId, false)
        fun sensor(nodeId: String) = SettingKey(PREFS, SENSOR_PREFIX + nodeId, null as String?)
    }

    /** Set by tests to run on an in-memory backend. */
    @Volatile
    internal var storeOverride: SettingsStore? = null

    private fun store(): SettingsStore? =
        storeOverride ?: Applic.app?.let {
            SettingsStoreImpl(SharedPreferencesKeyValueStore(it.applicationContext))
        }

    data class DirectSensorRoute(
        val nodeId: String,
    )

    @JvmStatic
    fun directRequested(nodeId: String): Boolean = store()?.get(Keys.direct(nodeId)) ?: false

    @JvmStatic
    fun enterRequested(nodeId: String): Boolean = store()?.get(Keys.enter(nodeId)) ?: false

    @JvmStatic
    fun record(nodeId: String, direct: Boolean, enter: Boolean) {
        val store = store() ?: return
        store.edit {
            put(Keys.direct(nodeId), direct)
            put(Keys.enter(nodeId), enter)
            if (direct) {
                SensorIdentity.canonicalSensorId(SensorIdentity.resolveMainSensor())
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put(Keys.sensor(nodeId), it) }
            } else {
                remove(Keys.sensor(nodeId))
            }
        }
    }

    /**
     * Revoke every direct route for [serial] and return the watches that need a
     * stop command. The in-memory preference change is immediate, so ownership
     * arbitration cannot release the phone again while the command is in flight.
     */
    @JvmStatic
    fun revokeSensor(serial: String): List<DirectSensorRoute> = revokeMatching { assigned ->
        SensorIdentity.matches(assigned, serial)
    }

    /** Global Wear-off means no watch remains assigned to a sensor. */
    @JvmStatic
    fun revokeAllDirectSensors(): List<DirectSensorRoute> = revokeMatching { true }

    private fun revokeMatching(matches: (String) -> Boolean): List<DirectSensorRoute> {
        val store = store() ?: return emptyList()
        val snapshot = store.entries(PREFS)
        val routes = snapshot.entries
            .asSequence()
            .filter { (key, value) -> key.startsWith(Keys.DIRECT_PREFIX) && value == true }
            .mapNotNull { (key, _) ->
                val nodeId = key.removePrefix(Keys.DIRECT_PREFIX).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val assigned = (snapshot[Keys.sensor(nodeId).name] as? String)
                    ?.takeIf { it.isNotBlank() }
                    ?: SensorIdentity.resolveMainSensor()
                    ?: ""
                if (!matches(assigned)) return@mapNotNull null
                DirectSensorRoute(nodeId = nodeId)
            }
            .toList()
        if (routes.isEmpty()) return routes
        store.edit {
            routes.forEach { route ->
                put(Keys.direct(route.nodeId), false)
                remove(Keys.sensor(route.nodeId))
            }
        }
        return routes
    }

    /** Dropped when routing is reset to defaults, so nothing stale is shown. */
    @JvmStatic
    fun clear(nodeId: String) {
        store()?.edit {
            remove(Keys.direct(nodeId))
            remove(Keys.enter(nodeId))
            remove(Keys.sensor(nodeId))
        }
    }
}
