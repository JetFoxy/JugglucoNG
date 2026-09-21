package tk.glucodata.alerts

import org.json.JSONArray
import tk.glucodata.Applic
import tk.glucodata.settings.store.SettingKey
import tk.glucodata.settings.store.SettingsStore
import tk.glucodata.settings.store.SettingsStoreImpl
import tk.glucodata.settings.store.SharedPreferencesKeyValueStore

/**
 * The custom-alert list, over [SettingsStore] (plan task T2.3). One JSON blob
 * under one key.
 */
object CustomAlertRepository {
    private const val PREFS_NAME = "tk.glucodata.custom_alerts"

    private object Keys {
        val ALERTS = SettingKey(PREFS_NAME, "custom_alerts_list", null as String?)
    }

    /** Set by tests to run on an in-memory backend. */
    @Volatile
    internal var storeOverride: SettingsStore? = null

    private fun store(): SettingsStore? =
        storeOverride ?: Applic.app?.let {
            SettingsStoreImpl(SharedPreferencesKeyValueStore(it.applicationContext))
        }

    fun getAll(): List<CustomAlertConfig> {
        val jsonString = store()?.get(Keys.ALERTS) ?: return emptyList()
        val list = mutableListOf<CustomAlertConfig>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(CustomAlertConfig.fromJson(obj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveAll(alerts: List<CustomAlertConfig>) {
        val jsonArray = JSONArray()
        alerts.forEach {
            jsonArray.put(it.toJson())
        }
        store()?.set(Keys.ALERTS, jsonArray.toString())
    }

    fun add(alert: CustomAlertConfig) {
        val current = getAll().toMutableList()
        current.add(alert)
        saveAll(current)
    }

    fun update(updatedAlert: CustomAlertConfig) {
        val current = getAll().toMutableList()
        val index = current.indexOfFirst { it.id == updatedAlert.id }
        if (index != -1) {
            current[index] = updatedAlert
            saveAll(current)
        }
    }

    fun delete(alertId: String) {
        val current = getAll().toMutableList()
        current.removeIf { it.id == alertId }
        saveAll(current)
    }
}
