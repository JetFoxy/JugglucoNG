// MedtrumRegistry.kt — persist a Medtrum sensor and start its driver.
//
// Mirrors ICanHealthRegistry.addSensor: write prefs first (so the identity adapter
// resurrects the driver on every later app start), then reuse-or-create the callback.

package tk.glucodata.drivers.medtrum

import android.content.Context
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity

object MedtrumRegistry {

    private const val TAG = "MedtrumRegistry"

    @JvmStatic
    fun addSensor(context: Context, serialNumber: Long): String? {
        if (serialNumber <= 0L) {
            Log.w(TAG, "addSensor: invalid serial $serialNumber")
            return null
        }
        val sensorId = MedtrumPersistence.canonicalSensorId(serialNumber)
        MedtrumPersistence.setSerialNumber(context, serialNumber)
        MedtrumPersistence.setSensorId(context, sensorId)

        val blue = SensorBluetooth.blueone ?: return sensorId
        val existing = SensorBluetooth.gattcallbacks.firstOrNull { callback ->
            val medtrum = callback as? MedtrumBleManager ?: return@firstOrNull false
            SensorIdentity.matches(medtrum.SerialNumber, sensorId) || medtrum.matchesManagedSensorId(sensorId)
        }
        val callback = (existing as? MedtrumBleManager) ?: MedtrumBleManager(sensorId, 0L).also {
            it.mActiveDeviceAddress = MedtrumPersistence.getLastBtAddress(context).takeIf { addr -> addr.isNotBlank() }
            SensorBluetooth.gattcallbacks.add(it)
            runCatching { Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size) }
        }
        if (SensorBluetooth.blueone === blue) {
            callback.connectDevice(0)
        }
        return sensorId
    }
}