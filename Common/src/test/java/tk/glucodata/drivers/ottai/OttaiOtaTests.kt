package tk.glucodata.drivers.ottai

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

internal fun testGbl(payloadSize: Int = 600): ByteArray {
    // Independent GBL v3 fixture: header, program data, end with CRC over preceding bytes.
    val out = ByteArrayOutputStream()
    fun word(value: Long) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()) }
    word(0x03a617eb); word(8); word(0x03000000); word(0)
    word(0xfe0101fe); word(payloadSize.toLong())
    out.write(ByteArray(payloadSize) { it.toByte() })
    word(0xfc0404fc); word(4)
    word(CRC32().apply { update(out.toByteArray()) }.value)
    return out.toByteArray()
}

class OttaiOtaTests {
    @Test fun gblAcceptsBoundedEnvelopeAndErasedTail() {
        val bytes = testGbl()
        assertEquals(bytes.size, OttaiOtaPackage.parse(bytes).size)
        assertEquals(bytes.size + 4, OttaiOtaPackage.parse(bytes + ByteArray(4) { 0xff.toByte() }).size)
        bytes[30] = (bytes[30].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { OttaiOtaPackage.parse(bytes) }
    }

    @Test fun truncatedOverflowMissingEndAndGarbageAreRejected() {
        val valid = testGbl()
        listOf(valid.copyOf(15), valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            valid.copyOf().also { it[20] = 0xff.toByte(); it[21] = 0xff.toByte(); it[22] = 0xff.toByte(); it[23] = 0x7f },
        ).forEach { assertThrows(IllegalArgumentException::class.java) { OttaiOtaPackage.parse(it) } }
    }

    @Test fun mtuBoundHoldsAcrossAndroidNegotiationRange() {
        for (mtu in 23..517) {
            val chunk = OttaiOtaProtocol.chunkSize(mtu)
            assertTrue(chunk in 1..244 && chunk <= mtu - 3 && chunk % 4 == 0)
        }
        assertEquals(244, OttaiOtaProtocol.chunkSize(247))
        assertEquals(244, OttaiOtaProtocol.chunkSize(256))
        assertEquals(20, OttaiOtaProtocol.chunkSize(23))
    }

    @Test fun offsetsAdvanceOnlyOnAcknowledgementsAndReconstructTheExactFile() {
        val image = OttaiOtaPackage.parse(testGbl())
        val transfer = OttaiOtaTransfer(image, 247)
        val bytes = ByteArrayOutputStream()
        while (!transfer.complete) {
            val before = transfer.acknowledgedBytes
            val chunk = transfer.nextChunk()
            assertEquals(before, transfer.acknowledgedBytes)
            assertThrows(IllegalStateException::class.java) { transfer.nextChunk() }
            bytes.write(chunk)
            transfer.acknowledge()
            assertEquals(before + chunk.size, transfer.acknowledgedBytes)
            assertThrows(IllegalStateException::class.java) { transfer.acknowledge() }
        }
        assertArrayEquals(image.bytes(), bytes.toByteArray())
    }

    @Test fun coefficientPacketMatchesIndependentModbusVector() {
        // 09 02 + LE float(1) + LE float(2). Verify the full packet with a bit-at-a-time oracle.
        val packet = OttaiOtaProtocol.coefficientPacket("1,2")
        assertArrayEquals(byteArrayOf(9, 2, 0, 0, 0x80.toByte(), 0x3f, 0, 0, 0, 0x40), packet.copyOf(10))
        var crc = 0xffff
        for (byte in packet) {
            for (bit in 0..7) {
                val low = (crc xor (byte.toInt() ushr bit)) and 1
                crc = crc ushr 1
                if (low != 0) crc = crc xor 0xa001
            }
        }
        assertEquals(0, crc)
        listOf("", "1,,2", "NaN", "Infinity", "1e1000").forEach {
            assertThrows(IllegalArgumentException::class.java) { OttaiOtaProtocol.coefficientPacket(it) }
        }
    }

    @Test fun calibrationMissingIsNotReplacedWithInventedDefaults() {
        val key = "00112233445566778899aabbccddeeff"
        assertThrows(IllegalArgumentException::class.java) { OttaiOtaProtocol.calibrationPacket(null, 0.0, key) }
        assertThrows(IllegalArgumentException::class.java) { OttaiOtaProtocol.calibrationPacket(1.0, Double.NaN, key) }
        val packet = OttaiOtaProtocol.calibrationPacket(1.0, -2.0, key)
        assertEquals(17, packet.size)
        assertEquals(11.toByte(), packet[0])
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(OttaiCrypto.hexToBytes(key), "AES"))
        assertArrayEquals(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putDouble(1.0).putDouble(-2.0).array(),
            cipher.doFinal(packet.copyOfRange(1, 17)))
    }

    @Test fun onlyHttpsAndKnownVersionedGblNamesAreAccepted() {
        assertTrue(OttaiOtaProtocol.isUpgrade("V1.7.9", "V1.8.0"))
        assertTrue(OttaiOtaProtocol.isUpgrade("V1.7.9", "V1.7.10"))
        assertFalse(OttaiOtaProtocol.isUpgrade("V1.8.0", "V1.7.9"))
        assertFalse(OttaiOtaProtocol.isUpgrade("V1.8.0", "V1.8.0.0"))
        assertEquals("V1.8.2", OttaiOtaProtocol.targetVersion("https://cdn.example/V1.8.2.GBL?token=private"))
        listOf("http://cdn.example/V1.8.2.gbl", "https://user:pass@cdn.example/V1.8.2.gbl",
            "https://cdn.example/random.gbl", "https://cdn.example/V1.9.0.gbl").forEach {
            assertThrows(IllegalArgumentException::class.java) { OttaiOtaProtocol.targetVersion(it) }
        }
    }
}

class OttaiFirmwareControllerTests {
    private val materials = OttaiRegistry.DeviceMaterials("00".repeat(96), "V0", "1,2", 0, "V1.7.0", 1)
    private val image = OttaiFirmwareImage("V1.7.0", "V1.8.0", OttaiOtaPackage.parse(testGbl()))

    private class FakeIo(val image: OttaiFirmwareImage) : OttaiFirmwareIo {
        val writes = mutableListOf<Pair<UUID, ByteArray>>()
        val scheduled = ArrayDeque<() -> Unit>()
        var connected: ((Result<OttaiFirmwareGatt>) -> Unit)? = null
        var ack: ((Result<Unit>) -> Unit)? = null
        var saved = false
        var closed = false
        var readAnswer: ((UUID) -> ByteArray)? = null
        var fresh: OttaiRegistry.DeviceMaterials? = null
        var refresh: ((Result<OttaiRegistry.DeviceMaterials>) -> Unit)? = null
        var reported = false
        var cleared = false
        override fun connect(done: (Result<OttaiFirmwareGatt>) -> Unit) { connected = done }
        override fun rediscover(done: (Result<OttaiFirmwareGatt>) -> Unit) {
            done(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        }
        override fun read(service: UUID, characteristic: UUID, done: (Result<ByteArray>) -> Unit) {
            done(Result.success(requireNotNull(readAnswer)(characteristic)))
        }
        override fun write(service: UUID, characteristic: UUID, value: ByteArray, done: (Result<Unit>) -> Unit) {
            check(ack == null)
            writes += characteristic to value.copyOf()
            ack = done
        }
        fun acknowledge(result: Result<Unit> = Result.success(Unit)) {
            val callback = requireNotNull(ack)
            ack = null
            callback(result)
        }
        override fun after(delayMs: Long, action: () -> Unit) { scheduled += action }
        fun tick() { scheduled.removeFirst().invoke() }
        override fun prepareImage(version: String, done: (Result<OttaiFirmwareImage?>) -> Unit) { done(Result.success(image)) }
        override fun refreshMaterials(version: String, done: (Result<OttaiRegistry.DeviceMaterials>) -> Unit) {
            refresh = done
            fresh?.let { done(Result.success(it)) }
        }
        override fun savedImage() = image
        override fun markTransferStarted(image: OttaiFirmwareImage) { saved = true }
        override fun reportVerified(image: OttaiFirmwareImage) { reported = true }
        override fun clearRecovery() { cleared = true }
        override fun log(message: String) {}
        override fun disconnect() {}
        override fun close() { closed = true }
    }

    private fun normal(version: String = "V1.7.0", command: Int = 0, badSignature: Boolean = false): Pair<OttaiFirmwareController, FakeIo> {
        val io = FakeIo(image.copy(source = version, target = "V1.8.1"))
        val device = OttaiBleAuth.generateKeyPair()
        val (x, y) = OttaiBleAuth.publicCoords(device.public as java.security.interfaces.ECPublicKey)
        val time = byteArrayOf(1, 2, 3)
        io.readAnswer = { ch ->
            when (ch) {
                OttaiConstants.CHAR_SOFTWARE_VERSION -> version.toByteArray()
                OttaiConstants.CHAR_CURRENT_TIME -> byteArrayOf(1, 2, 3, 4)
                OttaiConstants.CHAR_AUTH_DEVICE_PARAM -> byteArrayOf(0) + time + x + y
                OttaiConstants.CHAR_AUTH_SIGN -> if (badSignature) ByteArray(32) else
                    OttaiBleAuth.authSign(ByteArray(16), OttaiCrypto.hexToBytes("AABBCCDDEEFF"), x, y, time)
                OttaiConstants.CHAR_COMMAND -> byteArrayOf(command.toByte())
                else -> error("unexpected characteristic")
            }
        }
        val controller = OttaiFirmwareController("AABBCCDDEEFF", materials.copy(deviceVersion = version), io) {}
        controller.start()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        if (!badSignature) repeat(2) { io.tick(); io.acknowledge() }
        return controller to io
    }

    @Test fun normalCheckStopsBeforeAnyFirmwareOrActivationWrite() {
        val (controller, io) = normal()
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        assertEquals(listOf(OttaiConstants.CHAR_AUTH_APP_PARAM, OttaiConstants.CHAR_AUTH_SIGN), io.writes.map { it.first })
        assertFalse(io.saved)
        assertFalse(io.reported)
        assertFalse(io.cleared)
    }

    @Test fun activeEndedAndWrongIdentitySensorsCannotReachTheOfferOrFlash() {
        for (command in listOf(3, 4, 255)) {
            val (controller, io) = normal(command = command)
            assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
            assertEquals("sensor_already_active_or_ended", controller.state.errorCode)
            assertEquals(2, io.writes.size)
        }
        val (controller, io) = normal(badSignature = true)
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertTrue(io.writes.isEmpty())
    }

    @Test fun v17PreparationWritesCoefficientsAndNeverLifetimeOrActivation() {
        val (controller, io) = normal()
        io.fresh = materials
        controller.prepareOnly()
        io.tick(); io.acknowledge()
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        assertEquals(3, io.writes.size)
        assertEquals(OttaiConstants.CHAR_DESTRUCTIVE, io.writes.last().first)
        assertEquals(9.toByte(), io.writes.last().second[0])
        assertTrue(io.cleared)
        assertFalse(io.reported)
    }

    @Test fun missingV18CalibrationStopsBeforeEitherFactoryWrite() {
        val (controller, io) = normal(version = "V1.8.0")
        io.fresh = materials.copy(deviceVersion = "V1.8.0")
        controller.prepareOnly()
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals("calibration_missing", controller.state.errorCode)
        assertEquals(2, io.writes.size)
        assertFalse(io.cleared)
    }

    @Test fun staleMaterialsAndLateRefreshCannotWriteFactoryParameters() {
        val (controller, io) = normal()
        controller.prepareOnly()
        controller.close()
        io.refresh!!(Result.success(materials))
        assertTrue(io.scheduled.isEmpty())
        assertEquals(2, io.writes.size)
        val (another, second) = normal()
        second.fresh = materials.copy(deviceVersion = "V1.6.0")
        another.prepareOnly()
        assertEquals(OttaiFirmwareStage.FAILED, another.state.stage)
        assertEquals(2, second.writes.size)
    }

    @Test fun verifiedRebootRequiresFreshMaterialsAndBothParameterAcknowledgements() {
        val (controller, io) = normal()
        controller.install()
        while (controller.state.stage == OttaiFirmwareStage.INSTALLING) { io.tick(); io.acknowledge() }
        assertFalse(io.reported)
        val answer = requireNotNull(io.readAnswer)
        io.readAnswer = { ch -> if (ch == OttaiConstants.CHAR_SOFTWARE_VERSION) "V1.8.1".toByteArray() else answer(ch) }
        io.fresh = materials.copy(deviceVersion = "V1.8.1", calibrationValueK = 1.0, calibrationValueB = 0.0)
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = false, hasOta = true)))
        repeat(2) { io.tick(); io.acknowledge() }
        assertTrue(io.reported)
        assertFalse(io.cleared)
        assertEquals(OttaiFirmwareStage.PREPARING, controller.state.stage)
        io.tick(); io.acknowledge()
        assertFalse(io.cleared)
        io.tick(); io.acknowledge()
        assertTrue(io.cleared)
        assertEquals(OttaiFirmwareStage.COMPLETE, controller.state.stage)
        val factory = io.writes.filter { it.first == OttaiConstants.CHAR_DESTRUCTIVE }
        assertEquals(listOf(9.toByte(), 11.toByte()), factory.map { it.second[0] })
        assertFalse(io.writes.any { it.first in setOf(OttaiConstants.CHAR_COMMAND,
            OttaiConstants.CHAR_CURRENT_TIME, OttaiConstants.CHAR_MAX_ACTIVE_TIME) })
    }

    @Test fun duplicateAckDoesNotQueueTwoChunks() {
        val (controller, io) = recovery()
        controller.install(); io.tick()
        val callback = requireNotNull(io.ack)
        io.acknowledge()
        callback(Result.success(Unit))
        assertEquals(1, io.scheduled.size)
    }

    private fun recovery(): Pair<OttaiFirmwareController, FakeIo> {
        val io = FakeIo(image)
        val controller = OttaiFirmwareController("AABBCCDDEEFF", materials, io) {}
        controller.start()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = true, hasOta = true)))
        assertEquals(OttaiFirmwareStage.READY, controller.state.stage)
        assertTrue(io.writes.isEmpty())
        return controller to io
    }

    @Test fun explicitRecoverySendsStartExactBytesFinishAndRequiresVersionReadback() {
        val (controller, io) = recovery()
        controller.install()
        assertTrue(io.saved)
        io.tick()
        assertArrayEquals(byteArrayOf(0), io.writes.last().second)
        io.acknowledge()
        while (controller.state.stage == OttaiFirmwareStage.INSTALLING) {
            io.tick()
            io.acknowledge()
        }
        assertEquals(OttaiFirmwareStage.RECONNECTING, controller.state.stage)
        assertEquals(OttaiConstants.CHAR_OTA_CONTROL, io.writes.last().first)
        assertArrayEquals(byteArrayOf(6), io.writes.last().second)
        assertArrayEquals(image.image.bytes(), io.writes.filter { it.first == OttaiConstants.CHAR_OTA_DATA }
            .fold(byteArrayOf()) { bytes, write -> bytes + write.second })
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = true, hasOta = true)))
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals("still_in_bootloader", controller.state.errorCode)
    }

    @Test fun rejectedWriteNeverAdvancesOrRetriesOrActivates() {
        val (controller, io) = recovery()
        controller.install(); io.tick(); io.acknowledge()
        io.tick()
        io.acknowledge(Result.failure(IllegalStateException("att_128")))
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
        assertEquals(0, controller.state.acknowledgedBytes)
        assertEquals(2, io.writes.size)
        assertTrue(io.scheduled.isEmpty())
        assertTrue(io.closed)
        controller.install()
        assertEquals(2, io.writes.size)
    }

    @Test fun cancellationAndLateSuccessCannotStartOrContinueTransfer() {
        val (controller, io) = recovery()
        controller.install(); io.tick()
        controller.close()
        io.acknowledge()
        assertTrue(io.scheduled.isEmpty())
        assertEquals(1, io.writes.size)
        assertEquals(OttaiFirmwareStage.CLOSED, controller.state.stage)
    }

    @Test fun lateConnectionAndScheduledStartAreIgnoredAfterRetirement() {
        val (controller, io) = recovery()
        controller.install()
        controller.fail("disconnected")
        io.tick()
        io.connected!!(Result.success(OttaiFirmwareGatt(247, bootloaderOnly = true, hasOta = true)))
        assertTrue(io.writes.isEmpty())
        assertEquals(OttaiFirmwareStage.FAILED, controller.state.stage)
    }
}
