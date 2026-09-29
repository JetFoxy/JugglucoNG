package tk.glucodata.glucosemeter

import androidx.annotation.Keep
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.drivers.aidex.native.crypto.Crc16CcittFalse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * OneTouch Verio Flex (LifeScan) glucose meter protocol, ported from xDrip.
 *
 * The meter is not GDM compliant: it has no glucose service at all, just a
 * vendor service with one write and one notify characteristic. The exchange
 * is strictly one command at a time and every answer has to be acked with a
 * single 0x81 byte before the next command goes out, so commands are queued
 * here and drained one per write callback.
 *
 * A command is 0x01 followed by a framed payload, and the frame is
 * `0x02 | size | 0x00 | 0x03 | payload | 0x03 | crc16`, where size counts the
 * whole frame. The CRC covers the frame without the leading 0x01 and is
 * appended little endian.
 */
@Keep
class VerioSession {

    private enum class Stage { IDLE, TIME, T_COUNTER, R_COUNTER, RECORDS, DONE }

    /** A reading the meter sent us; storing it is the caller's job. */
    data class Reading(val timestampMillis: Long, val mgdlTenths: Int)

    private val outbox = ArrayDeque<ByteArray>()
    private val pendingReadings = ArrayList<Reading>()
    private var stage = Stage.IDLE
    private var begun = false
    private var meterIndex = -1
    private var meterTimeOffset = 0L
    private var haveMeterTime = false
    private var highestRecordNumber = -1
    private var numberOfRecords = -1
    private var requestedRecord = -1
    private var nextRecordToRequest = -1
    private var recordsRequested = 0

    fun reset() {
        outbox.clear()
        pendingReadings.clear()
        stage = Stage.IDLE
        begun = false
        meterTimeOffset = 0L
        haveMeterTime = false
        highestRecordNumber = -1
        numberOfRecords = -1
        requestedRecord = -1
        nextRecordToRequest = -1
        recordsRequested = 0
    }

    fun begin(index: Int) {
        reset()
        meterIndex = index
        begun = true
        stage = Stage.TIME
        queue(command(0x20, 0x02)) // meter time first, every reading needs it
    }

    /** Next command to write, or null when there is nothing to send. */
    fun take(): ByteArray? = outbox.pollFirst()

    /** Readings received since the last call. */
    fun takeReadings(): List<Reading> {
        if (pendingReadings.isEmpty()) return emptyList()
        val copy = pendingReadings.toList()
        pendingReadings.clear()
        return copy
    }

    private fun queue(value: ByteArray?) {
        if (value != null) outbox.addLast(value)
    }

    private fun highestRecordSeen() = Natives.GlucoseMeterGetLastPos(meterIndex)

    @Synchronized
    fun onNotification(message: ByteArray?) {
        if (!begun) {
            Log.e(TAG, "notification outside a session, ignored")
            return
        }
        if (message == null || message.isEmpty()) {
            Log.e(TAG, "empty notification")
            return
        }
        // a single 0x81 is the meter acknowledging one of our commands. It says
        // nothing about which answer is coming, so the stage only moves on the
        // data packet itself.
        if (message.size == 1) {
            if ((message[0].toInt() and 0x81) == 0x81) {
                Log.i(TAG, "ack")
            } else {
                Log.e(TAG, String.format(Locale.US, "unexpected byte 0x%02X", message[0]))
            }
            return
        }
        if (message.size <= 5) {
            Log.e(TAG, String.format(Locale.US, "unexpected message size %d", message.size))
            return
        }
        // no CRC check on received packets for now, the meter decides whether to talk
        if (message[0] != HEADER || message[1] != FRAME_START) {
            Log.e(TAG, String.format(Locale.US, "invalid header %02X %02X", message[0], message[1]))
        }
        if (message[2].toInt() != message.size - 1) {
            Log.e(TAG, String.format(
                Locale.US, "length field %02X but message is %d bytes", message[2], message.size))
            return
        }
        val messageType = message[5]
        if (messageType != 0x06.toByte()) {
            reportError(messageType, message)
            return
        }
        handleData(message)
        // every data packet has to be acked before anything else goes out
        queue(byteArrayOf(0x81.toByte()))
        queue(advance())
    }

    private fun handleData(message: ByteArray) {
        val result = message.copyOfRange(6, message.size - 3)
        val data = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        // the stage says what we asked for, which is more reliable than guessing
        // from the payload length
        when (stage) {
            Stage.TIME -> {
                if (result.size != 4) {
                    Log.e(TAG, String.format(Locale.US, "meter time expected 4 bytes, got %d", result.size))
                    return
                }
                val meterTime = (data.int.toLong() + TIME_OFFSET) * 1000L
                meterTimeOffset = System.currentTimeMillis() - meterTime
                haveMeterTime = true
                Log.i(TAG, String.format(Locale.US, "meter time received, offset=%dms", meterTimeOffset))
            }
            Stage.T_COUNTER -> {
                if (result.size != 4) {
                    Log.e(TAG, String.format(Locale.US, "record counter expected 4 bytes, got %d", result.size))
                    return
                }
                highestRecordNumber = data.int
                Log.i(TAG, String.format(Locale.US, "record counter=%d", highestRecordNumber))
            }
            Stage.R_COUNTER -> {
                if (result.size != 2) {
                    Log.e(TAG, String.format(Locale.US, "record count expected 2 bytes, got %d", result.size))
                    return
                }
                numberOfRecords = data.short.toInt() and 0xFFFF
                Log.i(TAG, String.format(Locale.US, "number of records=%d", numberOfRecords))
                startRecordRequests()
            }
            Stage.RECORDS -> handleReading(data)
            else -> Log.e(TAG, String.format(Locale.US, "data packet of %d bytes outside a request", result.size))
        }
    }

    private fun handleReading(data: ByteBuffer) {
        if (requestedRecord <= 0) {
            Log.e(TAG, "reading we did not ask for")
            return
        }
        // xDrip sums two ints at offsets 6 and 10 here, but the buffer is only
        // 11 bytes so its second read overruns; use the two trailing bytes.
        val marker = (data.get(6).toInt() and 0xFF) + (data.get(10).toInt() and 0xFF)
        if (marker != 0) {
            Log.e(TAG, String.format(Locale.US, "record %d has non-zero marker %d", requestedRecord, marker))
        }
        if (!haveMeterTime) {
            Log.e(TAG, "no meter time yet, cannot place the reading in time")
            return
        }
        val mgdl = data.short.toInt() and 0xFFFF
        if (mgdl < MIN_PLAUSIBLE_MGDL || mgdl > MAX_PLAUSIBLE_MGDL) {
            Log.e(TAG, String.format(Locale.US, "implausible value %d mg/dl ignored", mgdl))
            return
        }
        val timestamp = ((data.int.toLong() and 0xFFFFFFFFL) + TIME_OFFSET) * 1000L + meterTimeOffset
        val record = requestedRecord
        pendingReadings.add(Reading(timestamp, mgdl * 10))
        Log.i(TAG, String.format(Locale.US, "reading %d = %d mg/dl at %d", record, mgdl, timestamp))
        Natives.GlucoseMeterSetLastPos(meterIndex, maxOf(record, highestRecordSeen()))
        nextRecordToRequest = record - 1
        recordsRequested++
    }

    private fun startRecordRequests() {
        if (highestRecordNumber < 0 || numberOfRecords < 0) {
            Log.e(TAG, "counters missing, cannot request records")
            nextRecordToRequest = 0
            return
        }
        if (numberOfRecords == 0) {
            Log.i(TAG, "no readings on the meter")
            nextRecordToRequest = 0
            return
        }
        val lowest = maxOf(highestRecordNumber - numberOfRecords, highestRecordNumber - MAX_BACKFILL_RECORDS)
        nextRecordToRequest = highestRecordNumber
        recordsRequested = 0
        Log.i(TAG, String.format(
            Locale.US, "%d records, highest=%d, walking down to %d, have %d",
            numberOfRecords, highestRecordNumber, lowest, highestRecordSeen()))
    }

    /** Moves to the next request and returns the command that asks for it. */
    private fun advance(): ByteArray? {
        stage = when (stage) {
            Stage.TIME -> Stage.T_COUNTER
            Stage.T_COUNTER -> Stage.R_COUNTER
            Stage.R_COUNTER -> Stage.RECORDS
            else -> stage
        }
        return when (stage) {
            Stage.T_COUNTER -> command(0x0a, 0x02, 0x06) // record counter
            Stage.R_COUNTER -> command(0x27, 0x00) // number of records
            Stage.RECORDS -> {
                if (nextRecordToRequest <= highestRecordSeen() || recordsRequested >= MAX_BACKFILL_RECORDS) {
                    stage = Stage.DONE
                    Log.i(TAG, "nothing newer on the meter")
                    null
                } else {
                    requestedRecord = nextRecordToRequest
                    command(0xb3, requestedRecord and 0xff, (requestedRecord shr 8) and 0xff)
                }
            }
            else -> null
        }
    }

    private fun reportError(messageType: Byte, message: ByteArray) {
        val code = if (message.size > 6) message[6].toInt() and 0xFF else 0
        when (messageType) {
            0x07.toByte() -> Log.e(TAG, String.format(Locale.US, "command not allowed (%02X), check the pairing", code))
            0x08.toByte() -> Log.e(TAG, String.format(Locale.US, "command not supported (%02X)", code))
            0x09.toByte() -> Log.e(TAG, String.format(Locale.US, "command not understood (%02X)", code))
            else -> Log.e(TAG, String.format(Locale.US, "unknown message type %02X", messageType))
        }
    }

    companion object {
        private const val TAG = "VerioSession"

        @JvmField
        val SERVICE_UUID: UUID = UUID.fromString("af9df7a1-e595-11e3-96b4-0002a5d5c51b")

        private const val DATA_DELIMITER: Byte = 0x03
        private const val HEADER: Byte = 0x01
        private const val FRAME_START: Byte = 0x02
        /** Meter timestamps count seconds since this epoch offset. */
        private const val TIME_OFFSET = 946684799L
        private const val MAX_BACKFILL_RECORDS = 15
        private const val MIN_PLAUSIBLE_MGDL = 20
        private const val MAX_PLAUSIBLE_MGDL = 700

        /** Frames a payload the way the meter expects it. */
        fun command(vararg payload: Int): ByteArray {
            val frameSize = payload.size + 7
            val frame = ByteArray(frameSize)
            frame[0] = FRAME_START
            frame[1] = frameSize.toByte()
            frame[2] = 0x00
            frame[3] = DATA_DELIMITER
            for (i in payload.indices) {
                frame[4 + i] = payload[i].toByte()
            }
            frame[4 + payload.size] = DATA_DELIMITER
            // the crc covers the frame without the two trailing crc bytes
            val crc = Crc16CcittFalse.checksum(frame.copyOfRange(0, frameSize - 2))
            frame[5 + payload.size] = (crc and 0xFF).toByte()
            frame[6 + payload.size] = ((crc shr 8) and 0xFF).toByte()
            val transmission = ByteArray(frame.size + 1)
            transmission[0] = HEADER
            System.arraycopy(frame, 0, transmission, 1, frame.size)
            return transmission
        }
    }
}
