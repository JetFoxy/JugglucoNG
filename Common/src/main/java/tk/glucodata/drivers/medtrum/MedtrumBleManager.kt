// MedtrumBleManager.kt — BLE transport for the Medtrum EasySense patch.
//
// Ported from the vendor app (com.medtrum.healthcareforandroid):
//   BLEBaseCentral: service/characteristic constants, writeCommand chunking,
//                   parseCharacteristicValue reassembly, authorizeRequest
//   SensorInstance:  command builders and the CGM_DATA record
// Cross-checked step by step against the 2026-10-17 HCI capture (SN 0x8ADF6216).
//
// Lifecycle:
//   1) connectDevice()                → GATT connect
//   2) onConnectionStateChange(CONNECTED) → discoverServices
//   3) onServicesDiscovered           → enable CGM_DATA notify + CTRLPT indicate
//   4) onDescriptorWrite (both)       → send AUTH_REQ
//   5) onCharacteristicChanged(CTRLPT)→ auth result → calibrateTime + readState
//   6) onCharacteristicChanged(CGM_DATA) → parse record → publish glucose

package tk.glucodata.drivers.medtrum

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

@SuppressLint("MissingPermission")
class MedtrumBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), MedtrumDriver {

    companion object {
        private const val TAG = "MedtrumBLE"

        /** SuperGattCallback generation tag. */
        const val SENSOR_GEN = 0

        /** CGMS service (app: MEDTRUM_UUID_SERVICE_CGMS = 36866). */
        private val SERVICE_UUID = UUID.fromString("669A9002-0008-968F-E311-6050405558B3")

        /** CGM data characteristic (app: MEDTRUM_UUID_CHARACTERISTIC_CGM_DATA = 37184). */
        private val CHAR_CGM_DATA_UUID = UUID.fromString("669a9140-0008-968f-e311-6050405558b3")

        /** Control point (app: MEDTRUM_UUID_CHARACTERISTIC_CTRLPT = 37121), write + indicate. */
        private val CHAR_CTRL_POINT_UUID = UUID.fromString("669a9101-0008-968f-e311-6050405558b3")

        /** Standard CCCD descriptor. */
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Watchdog: no CGM record for this long means the link is dead. */
        private const val WATCHDOG_MS = 10L * 60L * 1000L

        /** Delay before attempting reconnect after disconnect. */
        private const val RECONNECT_DELAY_MS = 3_000L

        /** Delay before starting service discovery after connection. */
        private const val DISCOVERY_DELAY_MS = 250L

        /** Glucose plausibility bounds (mg/dL). */
        private const val GLUCOSE_MIN_MGDL = 1.0
        private const val GLUCOSE_MAX_MGDL = 600.0

        /** Give up after this many failed AUTH rounds — a mistyped serial must not loop. */
        private const val MAX_AUTH_FAILURES = 3

        /**
         * The patch drops an idle link (~13 min observed), and the vendor app keeps it alive by
         * polling readState every couple of seconds. This also refreshes the counters a
         * calibration needs and the battery reading.
         */
        private const val KEEPALIVE_MS = 30_000L

        /** Seconds between 2014-01-01 local and the Unix epoch start used by calibrateTime. */
        private val MEDTRUM_EPOCH_MS: Long = run {
            val cal = java.util.Calendar.getInstance()
            cal.set(2014, 0, 1, 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
    }

    // ---- State machine ----

    enum class Phase { IDLE, CONNECTING, DISCOVERING, SUBSCRIBING, AUTH, SYNCING, STREAMING, ERROR }

    @Volatile var phase: Phase = Phase.IDLE
        private set

    // ---- Background thread ----

    private val handlerThread = HandlerThread("Medtrum-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    // ---- BLE handles ----

    private var charCgmData: BluetoothGattCharacteristic? = null
    private var charCtrlPoint: BluetoothGattCharacteristic? = null

    // ---- Session state ----

    /** Serial number parsed from the constructor arg (decimal string → Long). */
    private val serialLong: Long = serial.toLongOrNull() ?: 0L

    @Volatile private var lastCgmAtMs: Long = 0L

    /** The CCCD we are still waiting on; AUTH is sent once both have landed. */
    @Volatile private var pendingCccd: UUID? = null

    /** Outgoing framed PDUs, sent one at a time. */
    private val txQueue = ArrayDeque<ByteArray>()
    @Volatile private var txInFlight = false

    /** Incoming CTRLPT reassembly. */
    private val rxBuffer = ByteArrayOutputStream()
    private var rxExpected = -1

    /** Firmware version from the AUTH reply, for logging only — it is not the byte the
     *  app compares against 186 (that one comes from the advertisement). */
    @Volatile private var deviceVersion: MedtrumProtocol.Version? = null
    private var authRetriedLegacy = false

    /** Consecutive failed AUTH rounds; a wrong serial stops after MAX_AUTH_FAILURES. */
    private var authFailureCount = 0

    /** Patch counters from the readState record; a calibrate command needs their difference. */
    @Volatile private var runningTime = 0L
    @Volatile private var insertTime = 0L

    /** A fingerstick value waiting for a fresh readState before it can be sent. */
    @Volatile private var pendingCalibrationMgdl: Int? = null

    /** Patch battery from the readState record, millivolts. */
    @Volatile private var batteryMillivolts = 0

    // ---- ManagedBluetoothSensorDriver ----

    override var viewMode: Int = 0

    // ---- MedtrumDriver contract ----

    override fun getSerialNumber(): Long = serialLong

    override fun getDeviceAddress(): String = mActiveDeviceAddress ?: ""

    override fun matchesManagedSensorId(sensorId: String?): Boolean {
        if (sensorId.isNullOrBlank()) return false
        return sensorId == serialLong.toString() || sensorId.equals(SerialNumber, ignoreCase = true)
    }

    override fun getDetailedBleStatus(): String = when (phase) {
        Phase.IDLE -> if (mActiveBluetoothDevice == null) "Searching" else "Idle"
        Phase.CONNECTING -> "Connecting"
        Phase.DISCOVERING -> "Discovering services"
        Phase.SUBSCRIBING -> "Subscribing"
        Phase.AUTH -> "Authenticating"
        Phase.SYNCING -> "Syncing time"
        Phase.STREAMING -> if (lastCgmAtMs > 0L) "Connected" else "Waiting for data"
        Phase.ERROR -> "Error"
    }

    override fun getConnectionStatus(): String = getDetailedBleStatus()

    override fun isConnected(): Boolean =
        phase == Phase.STREAMING || phase == Phase.SYNCING

    override fun getBatteryMillivolts(): Int = batteryMillivolts

    // ---- Watchdog / reconnect ----

    private val reconnectRunnable = Runnable {
        if (stop) return@Runnable
        Log.i(TAG, "Medtrum scheduled reconnect for $SerialNumber")
        connectDevice(0)
    }

    private val keepAliveRunnable: Runnable = Runnable {
        if (stop || phase != Phase.STREAMING) return@Runnable
        enqueue(MedtrumProtocol.readState())
        handler.postDelayed(keepAliveRunnable, KEEPALIVE_MS)
    }

    private val watchdogRunnable = Runnable {
        if (stop || phase != Phase.STREAMING) return@Runnable
        val elapsed = System.currentTimeMillis() - lastCgmAtMs
        if (elapsed < WATCHDOG_MS) {
            armWatchdog()
            return@Runnable
        }
        Log.w(TAG, "Medtrum watchdog: no CGM record for ${elapsed / 1000}s — reconnecting")
        mActiveBluetoothDevice = null
        runCatching { mBluetoothGatt?.disconnect() }
        scheduleReconnect(RECONNECT_DELAY_MS)
    }

    private fun armWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    private fun scheduleReconnect(delayMs: Long) {
        if (stop) return
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun cancelReconnect() {
        handler.removeCallbacks(reconnectRunnable)
    }

    // ---- BLE identity ----

    /**
     * Null: a patch does not advertise its service UUID (captured advert = flags + name "MT" +
     * manufacturer data only), so a service filter would match nothing. Returning null makes the
     * scanner run unfiltered, and [matchScanResult] then picks the patch by its advertised serial.
     */
    override fun getService(): UUID? = null

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val knownAddress = mActiveDeviceAddress?.takeIf { it.isNotBlank() }
        if (knownAddress != null && address != null && address.equals(knownAddress, ignoreCase = true)) {
            return true
        }
        val ctx = Applic.app
        if (ctx != null) {
            val persisted = MedtrumPersistence.getLastBtAddress(ctx)
            if (persisted.isNotBlank() && persisted.equals(address, ignoreCase = true)) return true
        }
        // The advertised name ("MT") is shared with the EasyTouch pump, so it is never enough
        // on its own — the serial in the manufacturer data decides, in matchScanResult.
        return false
    }

    override fun matchScanResult(result: ScanResult): Boolean {
        val manufacturerData = result.scanRecord?.getManufacturerSpecificData(MedtrumProtocol.MANUFACTURER_ID)
            ?: return false
        val advertised = MedtrumProtocol.parseAdvertisedSerial(manufacturerData) ?: return false
        if (advertised != serialLong) return false
        Log.d(TAG, "Medtrum advert matched (SN ${MedtrumPersistence.formatSerialHex(advertised)})")
        return true
    }

    // ---- BLE lifecycle ----

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        if (phase != Phase.IDLE && phase != Phase.ERROR) {
            if (mBluetoothGatt != null || mActiveBluetoothDevice != null) {
                Log.i(TAG, "Skipping duplicate connect: phase=$phase")
                return true
            }
        }
        phase = Phase.CONNECTING
        val scheduled = super.connectDevice(delayMillis)
        if (!scheduled && phase == Phase.CONNECTING) phase = Phase.IDLE
        return scheduled
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        noteFirstGattCallback("onConnectionStateChange", gatt)
        if (stop) return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "Medtrum connected to ${gatt.device?.address}")
                cancelReconnect()
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                connectTime = System.currentTimeMillis()
                phase = Phase.DISCOVERING
                Applic.app?.let { ctx ->
                    gatt.device?.address?.let { MedtrumPersistence.setLastBtAddress(ctx, it) }
                }
                handler.postDelayed({
                    if (phase == Phase.DISCOVERING && mBluetoothGatt === gatt) {
                        try {
                            if (!gatt.discoverServices()) {
                                Log.e(TAG, "discoverServices() returned false")
                                phase = Phase.IDLE
                                runCatching { close() }
                                scheduleReconnect(RECONNECT_DELAY_MS)
                            }
                        } catch (t: Throwable) {
                            Log.stack(TAG, "discoverServices", t)
                            phase = Phase.IDLE
                            runCatching { close() }
                            scheduleReconnect(RECONNECT_DELAY_MS)
                        }
                    }
                }, DISCOVERY_DELAY_MS)
                UiRefreshBus.requestStatusRefresh()
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "Medtrum disconnected (status=$status)")
                handler.removeCallbacksAndMessages(null)
                phase = Phase.IDLE
                charCgmData = null
                charCtrlPoint = null
                pendingCccd = null
                txQueue.clear()
                txInFlight = false
                rxBuffer.reset()
                rxExpected = -1
                mActiveBluetoothDevice = null
                try { gatt.close() } catch (_: Throwable) {}
                mBluetoothGatt = null
                UiRefreshBus.requestStatusRefresh()
                if (!stop) scheduleReconnect(RECONNECT_DELAY_MS)
            }

            else -> if (phase == Phase.CONNECTING && status != BluetoothGatt.GATT_SUCCESS) phase = Phase.IDLE
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (stop) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "Service discovery failed status=$status")
            runCatching { gatt.disconnect() }
            return
        }

        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Medtrum service not found on ${gatt.device?.address}")
            runCatching { gatt.disconnect() }
            return
        }
        val cgmData = service.getCharacteristic(CHAR_CGM_DATA_UUID)
        val ctrlPoint = service.getCharacteristic(CHAR_CTRL_POINT_UUID)
        if (cgmData == null || ctrlPoint == null) {
            Log.e(TAG, "Required characteristics missing: cgmData=$cgmData ctrlPoint=$ctrlPoint")
            runCatching { gatt.disconnect() }
            return
        }
        charCgmData = cgmData
        charCtrlPoint = ctrlPoint
        phase = Phase.SUBSCRIBING

        // The app enables both: notifications for the CGM record, indications for responses.
        pendingCccd = CHAR_CGM_DATA_UUID
        Log.i(TAG, "Medtrum services discovered — enabling CGM_DATA notifications")
        if (!enableNotification(gatt, cgmData)) {
            Log.e(TAG, "Failed to enable Medtrum CGM_DATA notifications")
            runCatching { gatt.disconnect() }
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (stop) return
        if (descriptor.uuid != CCCD_UUID) return
        val charUuid = descriptor.characteristic?.uuid ?: return
        if (charUuid != CHAR_CGM_DATA_UUID && charUuid != CHAR_CTRL_POINT_UUID) return

        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "CCCD write failed ($charUuid) status=$status — reconnecting")
            runCatching { gatt.disconnect() }
            return
        }

        if (charUuid == CHAR_CGM_DATA_UUID) {
            pendingCccd = CHAR_CTRL_POINT_UUID
            val ctrlPoint = charCtrlPoint ?: return
            Log.i(TAG, "CGM_DATA subscribed — enabling CTRLPT indications")
            if (!enableIndication(gatt, ctrlPoint)) {
                Log.e(TAG, "Failed to enable Medtrum CTRLPT indications")
                runCatching { gatt.disconnect() }
            }
            return
        }

        if (pendingCccd == CHAR_CTRL_POINT_UUID) {
            pendingCccd = null
            phase = Phase.AUTH
            authRetriedLegacy = false
            UiRefreshBus.requestStatusRefresh()
            Log.i(TAG, "Both CCCDs enabled — sending AUTH_REQ")
            sendAuthRequest()
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (stop) return
        if (characteristic.uuid != CHAR_CTRL_POINT_UUID) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "write failed status=$status — reconnecting")
            runCatching { gatt.disconnect() }
            return
        }
        txInFlight = false
        pumpTxQueue()
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (stop) return
        val data = characteristic.value ?: return
        when (characteristic.uuid) {
            CHAR_CGM_DATA_UUID -> onCgmData(data)
            CHAR_CTRL_POINT_UUID -> onCtrlPointPdu(data)
            else -> Log.d(TAG, "Notification on unknown characteristic ${characteristic.uuid}")
        }
    }

    // ---- CTRLPT reassembly (mirrors BLEBaseCentral.parseCharacteristicValue) ----

    private fun onCtrlPointPdu(pdu: ByteArray) {
        if (rxExpected < 0) {
            val declared = MedtrumProtocol.declaredLength(pdu)
            if (declared < 0 || pdu.size < 2) return
            val available = minOf(declared, pdu.size - 2)
            rxBuffer.write(pdu, 2, available)
            if (rxBuffer.size() >= declared) {
                finishResponse()
            } else {
                rxExpected = declared
            }
            return
        }
        // Continuation PDU: raw bytes, no header.
        rxBuffer.write(pdu, 0, pdu.size)
        if (rxBuffer.size() >= rxExpected) finishResponse()
    }

    private fun finishResponse() {
        val value = rxBuffer.toByteArray()
        rxBuffer.reset()
        rxExpected = -1
        val response = MedtrumProtocol.parseResponse(value)
        if (response == null) {
            Log.w(TAG, "Unparseable CTRLPT response (${value.size} bytes)")
            return
        }
        when (response.opCode) {
            MedtrumProtocol.OP_AUTH -> onAuthResponse(response)
            MedtrumProtocol.OP_READ_STATE -> onStateResponse(response, value)
            MedtrumProtocol.OP_CALIBRATE -> Log.i(TAG, "calibrate ok=${response.ok} error=${response.errorCode}")
            MedtrumProtocol.OP_CALIBRATE_TIME -> Log.d(TAG, "calibrateTime ok=${response.ok}")
            else -> Log.d(TAG, "response op=0x%02X ok=%s error=%d".format(response.opCode, response.ok, response.errorCode))
        }
    }

    private fun onStateResponse(response: MedtrumProtocol.Response, unframed: ByteArray) {
        if (!response.ok) {
            Log.w(TAG, "readState error=${response.errorCode}")
            return
        }
        val state = MedtrumProtocol.parseStateRecord(unframed) ?: return
        insertTime = state.insertTime
        runningTime = state.runningTime
        batteryMillivolts = state.batteryMillivolts
        Log.d(TAG, "readState: ${state.phase} seq=${state.sequence} battery=${state.batteryMillivolts / 512.0}V elapsed=${state.elapsedSeconds}s nextCal=${state.nextCalibrationSequence}")
        val pending = pendingCalibrationMgdl ?: return
        pendingCalibrationMgdl = null
        sendCalibration(pending)
    }

    // ---- Auth ----

    private fun sendAuthRequest() {
        val legacy = Applic.app?.let { MedtrumPersistence.isLegacyAuth(it) } ?: false
        val role = if (legacy) MedtrumProtocol.ROLE_LEGACY else MedtrumProtocol.ROLE_MODERN
        val key = if (legacy) MedtrumCrypt.legacyKeyGen(serialLong) else MedtrumCrypt.keyGen(serialLong)
        Log.i(TAG, "AUTH_REQ role=$role legacy=$legacy")
        enqueue(MedtrumProtocol.authorize(role, key))
    }

    private fun onAuthResponse(response: MedtrumProtocol.Response) {
        if (response.responseCode != MedtrumProtocol.RESPONSE_AUTH || !response.ok) {
            val ctx = Applic.app
            val wasLegacy = ctx?.let { MedtrumPersistence.isLegacyAuth(it) } ?: false
            if (!authRetriedLegacy && !wasLegacy) {
                authRetriedLegacy = true
                ctx?.let { MedtrumPersistence.setLegacyAuth(it, true) }
                Log.w(TAG, "AUTH rejected (code=${response.responseCode} error=${response.errorCode}) — retrying with the legacy key")
                sendAuthRequest()
                return
            }
            authFailureCount++
            if (authFailureCount >= MAX_AUTH_FAILURES) {
                // A mistyped serial fails the key both ways; stop instead of reconnecting forever.
                phase = Phase.ERROR
                UiRefreshBus.requestStatusRefresh()
                Log.e(TAG, "AUTH failed $authFailureCount times — giving up. Check the patch serial.")
                return
            }
            Log.e(TAG, "AUTH failed (attempt $authFailureCount) — reconnecting")
            reconnectOnError("auth-failed")
            return
        }
        authFailureCount = 0
        val version = MedtrumProtocol.parseAuthVersion(response)
        deviceVersion = version
        Log.i(TAG, "AUTH ok, firmware $version")
        phase = Phase.SYNCING
        UiRefreshBus.requestStatusRefresh()
        // Time sync, then the patch's periodic CGM_DATA drives the rest.
        val seconds = (System.currentTimeMillis() - MEDTRUM_EPOCH_MS) / 1000L
        enqueue(MedtrumProtocol.calibrateTime(seconds))
        enqueue(MedtrumProtocol.readState())
        handler.postDelayed({
            if (phase == Phase.SYNCING) {
                phase = Phase.STREAMING
                armWatchdog()
                handler.removeCallbacks(keepAliveRunnable)
                handler.postDelayed(keepAliveRunnable, KEEPALIVE_MS)
                UiRefreshBus.requestStatusRefresh()
            }
        }, 1_000L)
    }

    // ---- CGM data ----

    private fun onCgmData(data: ByteArray) {
        val record = MedtrumProtocol.parseCgmRecord(data)
        if (record == null) {
            Log.d(TAG, "Short CGM_DATA record (${data.size} bytes)")
            return
        }
        // Any record is liveness, including warm-up ones where glucose is null.
        lastCgmAtMs = System.currentTimeMillis()
        if (phase != Phase.STREAMING) {
            phase = Phase.STREAMING
            handler.removeCallbacks(keepAliveRunnable)
            handler.postDelayed(keepAliveRunnable, KEEPALIVE_MS)
            UiRefreshBus.requestStatusRefresh()
        }
        armWatchdog()

        val glucose = record.glucoseMgdl
        if (glucose == null) {
            Log.d(TAG, "Medtrum ${record.phase} seq=${record.sequence} signal=${record.signal} calibFactor=${record.calibrationFactor} — no glucose yet")
            return
        }
        if (glucose < GLUCOSE_MIN_MGDL || glucose > GLUCOSE_MAX_MGDL) {
            Log.w(TAG, "Medtrum glucose out of range: $glucose mg/dL — skipping")
            return
        }

        Log.i(TAG, "Medtrum glucose: ${glucose} mg/dL (signal=${record.signal} calibFactor=${record.calibrationFactor})")
        VirtualGlucoseSensorBridge.publishCurrent(
            sensorSerial = serialLong.toString(),
            reading = VirtualGlucoseSensorBridge.Reading(
                timestampMs = System.currentTimeMillis(),
                glucoseMgdl = glucose.toFloat(),
            ),
            sensorGen = SENSOR_GEN,
            logLabel = "Medtrum",
        )
    }

    // ---- calibration ----

    override fun supportsManualCalibration(): Boolean = true

    /**
     * Send a fingerstick value. The patch wants the elapsed seconds since insertion, which only
     * a readState answer carries, so ask for one first and send the calibration on its reply —
     * the same order SensorInstance uses (readState, then mNeedCalibrate).
     */
    override fun calibrateSensor(glucoseMgDl: Int): Boolean {
        if (glucoseMgDl <= 0) return false
        if (runningTime == 0L || insertTime == 0L) {
            pendingCalibrationMgdl = glucoseMgDl
            enqueue(MedtrumProtocol.readState())
            return true
        }
        return sendCalibration(glucoseMgDl)
    }

    private fun sendCalibration(glucoseMgDl: Int): Boolean {
        val elapsed = (runningTime - insertTime).coerceAtLeast(0L).toInt()
        Log.i(TAG, "calibrate $glucoseMgDl mg/dL, elapsed ${elapsed}s")
        enqueue(MedtrumProtocol.calibrate(glucoseMgDl, elapsed, 0 /* Fingerstick */))
        return true
    }

    // ---- TX queue ----

    private fun enqueue(commandData: ByteArray) {
        handler.post {
            MedtrumProtocol.encode(commandData).forEach { txQueue.addLast(it) }
            pumpTxQueue()
        }
    }

    private fun pumpTxQueue() {
        if (stop || txInFlight) return
        val gatt = mBluetoothGatt ?: return
        val ch = charCtrlPoint ?: return
        val pdu = txQueue.removeFirstOrNull() ?: return
        try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = pdu
            txInFlight = true
            if (!gatt.writeCharacteristic(ch)) {
                txInFlight = false
                Log.w(TAG, "writeCharacteristic returned false — reconnecting")
                runCatching { gatt.disconnect() }
            }
        } catch (t: Throwable) {
            txInFlight = false
            Log.stack(TAG, "pumpTxQueue", t)
        }
    }

    // ---- Error / reconnect ----

    private fun reconnectOnError(reason: String) {
        phase = Phase.ERROR
        UiRefreshBus.requestStatusRefresh()
        Log.w(TAG, "Medtrum reconnecting after error: $reason")
        val gatt = mBluetoothGatt
        if (gatt != null) {
            runCatching { gatt.disconnect() }
        } else {
            scheduleReconnect(RECONNECT_DELAY_MS)
        }
    }

    // ---- ManagedBluetoothSensorDriver lifecycle ----

    override fun softDisconnect() {
        setPause(true)
        cancelReconnect()
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(keepAliveRunnable)
        phase = Phase.IDLE
        runCatching { close() }.onFailure { Log.stack(TAG, "softDisconnect(close)", it) }
        mActiveBluetoothDevice = null
        UiRefreshBus.requestStatusRefresh()
    }

    override fun softReconnect() {
        setPause(false)
        cancelReconnect()
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(keepAliveRunnable)
        // A user-driven reconnect gets a fresh budget after a mistyped serial stopped us.
        authFailureCount = 0
        authRetriedLegacy = false
        runCatching { close() }.onFailure { Log.stack(TAG, "softReconnect(close)", it) }
        phase = Phase.IDLE
        handler.postDelayed({ if (!stop) connectDevice(0) }, 250L)
        UiRefreshBus.requestStatusRefresh()
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        setPause(true)
        cancelReconnect()
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(keepAliveRunnable)
        phase = Phase.IDLE
        runCatching { mBluetoothGatt?.disconnect() }.onFailure { Log.stack(TAG, "terminateManagedSensor(disconnect)", it) }
        runCatching { close() }.onFailure { Log.stack(TAG, "terminateManagedSensor(close)", it) }
        if (wipeData) Applic.app?.let { MedtrumPersistence.clear(it) }
    }

    override fun removeManagedPersistence(context: Context) {
        MedtrumPersistence.clear(context)
    }

    override fun close() {
        phase = Phase.IDLE
        handler.removeCallbacksAndMessages(null)
        try { handlerThread.quitSafely() } catch (_: Throwable) {}
        super.close()
    }
}
