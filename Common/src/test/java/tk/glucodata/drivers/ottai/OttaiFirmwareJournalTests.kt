package tk.glucodata.drivers.ottai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class OttaiFirmwareJournalTests {
    private val id = "AABBCCDDEEFF"
    private val address = "AA:BB:CC:DD:EE:FF"
    private val image = OttaiFirmwareImage("V1.7.0", "V1.8.1", OttaiOtaPackage.parse(testGbl()),
        OttaiFirmwareMode.STARTED_SENSOR, OttaiFirmwareObservation(3, 28L * 86_400, 720, 100))

    @Test fun persistedModeAndBaselineSurviveRecovery() {
        val out = ByteArrayOutputStream()
        OttaiFirmwareJournal.write(DataOutputStream(out), id, address, image)
        val saved = OttaiFirmwareJournal.read(DataInputStream(ByteArrayInputStream(out.toByteArray())), id, address)
        assertEquals(image.mode, saved.mode)
        assertEquals(image.before, saved.before)
        assertEquals(image.image.sha256, saved.image.sha256)
        assertArrayEquals(image.image.bytes(), saved.image.bytes())
        assertThrows(IllegalArgumentException::class.java) {
            OttaiFirmwareJournal.read(DataInputStream(ByteArrayInputStream(out.toByteArray())), id, "00:11:22:33:44:55")
        }
    }

    @Test fun oldPreactivationJournalRemainsReadable() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(1); writeUTF(id); writeUTF(address); writeUTF(image.source); writeUTF(image.target)
            writeUTF(image.image.sha256); writeInt(image.image.size); write(image.image.bytes()); flush()
        }
        val saved = OttaiFirmwareJournal.read(DataInputStream(ByteArrayInputStream(out.toByteArray())), id, address)
        assertEquals(OttaiFirmwareMode.PREACTIVATION, saved.mode)
        assertNull(saved.before)
    }

    @Test fun undecodableObservationsStayUnknown() {
        assertNull(OttaiOtaProtocol.maxActiveSeconds(byteArrayOf(1, 2), "invalid"))
        assertNull(OttaiOtaProtocol.latestDataNo(ByteArray(16), "invalid", "V1.7.0"))
        assertEquals(0L, OttaiOtaProtocol.maxActiveSeconds(ByteArray(4), "invalid"))
    }
}
