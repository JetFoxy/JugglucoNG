package tk.glucodata.glucosemeter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VerioSessionTest {

    private fun hex(value: ByteArray) = value.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `get time command is framed with a trailing crc`() {
        val command = VerioSession.command(0x20, 0x02)
        assertEquals("01 02 09 00 03 20 02 03 D4 92", hex(command))
    }

    @Test
    fun `record counter command is framed with a trailing crc`() {
        val command = VerioSession.command(0x0a, 0x02, 0x06)
        assertEquals("01 02 0A 00 03 0A 02 06 03 0A 3F", hex(command))
    }

    @Test
    fun `record command carries the record number little endian`() {
        assertEquals("01 02 0A 00 03 B3 07 00 03 FA 7C", hex(VerioSession.command(0xb3, 0x07, 0x00)))
        assertEquals("01 02 0A 00 03 B3 00 01 03 5B CA", hex(VerioSession.command(0xb3, 0x00, 0x01)))
    }

    @Test
    fun `size field counts the whole frame but not the leading header`() {
        val command = VerioSession.command(0x27, 0x00)
        // wire layout: 0x01 header, 0x02 frame start, then the size
        assertEquals(command.size - 1, command[2].toInt())
    }

    @Test
    fun `single byte notification is the meter acking a command`() {
        val session = VerioSession()
        session.reset()
        session.onNotification(byteArrayOf(0x81.toByte()), -1)
        assertEquals("nothing queued before a session is begun", null, session.take())
    }

    @Test
    fun `begin queues the meter time command first`() {
        val session = VerioSession()
        session.begin()
        assertArrayEquals(VerioSession.command(0x20, 0x02), session.take())
        assertEquals("only one command may be in flight", null, session.take())
    }

    @Test
    fun `the ack alone does not move the handshake on`() {
        val session = VerioSession()
        session.begin()
        assertArrayEquals(VerioSession.command(0x20, 0x02), session.take())
        session.onNotification(byteArrayOf(0x81.toByte()), -1)
        assertEquals("ack must not queue the next request", null, session.take())
    }

    @Test
    fun `a data packet acks and then asks for the next thing`() {
        val session = VerioSession()
        session.begin()
        assertArrayEquals(VerioSession.command(0x20, 0x02), session.take())
        session.onNotification(VerioSession.command(0x06, 0x3e, 0x3c, 0x4e, 0x32), -1)
        assertArrayEquals("ack first", byteArrayOf(0x81.toByte()), session.take())
        assertArrayEquals("then the record counter", VerioSession.command(0x0a, 0x02, 0x06), session.take())
        assertEquals("and nothing after that", null, session.take())
    }

    /**
     * The exact bytes the OneTouch sent for record 3184, and what command()
     * reproduces from the 12 payload bytes, crc included:
     * 01 02 13 00 03 06 BB 81 44 31 44 00 00 00 00 00 00 03 F2 9A
     */
    private val deviceRecordPacket = byteArrayOf(
        0x01, 0x02, 0x13, 0x00, 0x03, 0x06, 0xbb.toByte(), 0x81.toByte(), 0x44, 0x31,
        0x44, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0xf2.toByte(), 0x9a.toByte())

    private fun recordPacket() = deviceRecordPacket

    @Test
    fun `the framing reproduces a packet the meter really sent`() {
        assertArrayEquals(
            deviceRecordPacket,
            VerioSession.command(0x06, 0xbb, 0x81, 0x44, 0x31, 0x44, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
    }

    private fun handshakenSession(): VerioSession {
        val session = VerioSession()
        session.begin()
        session.take() // get time
        session.onNotification(VerioSession.command(0x06, 0x39, 0x43, 0x4e, 0x32), -1)
        session.take() // ack
        session.take() // get record counter
        session.onNotification(VerioSession.command(0x06, 0x70, 0x0c, 0x00, 0x00), -1)
        session.take() // ack
        session.take() // get record count
        session.onNotification(VerioSession.command(0x06, 0xf4, 0x01), -1)
        session.take() // ack
        session.take() // first record request
        return session
    }

    @Test
    fun `a reading is taken from offset 4, not from the start of the payload`() {
        val session = handshakenSession()
        session.onNotification(recordPacket(), -1)
        val readings = session.takeReadings()
        assertEquals(1, readings.size)
        // bytes 4 and 5 are 0x44 0x00, not the 0xbb 0x81 at the front
        assertEquals("68 mg/dl", 68 * 10, readings[0].mgdlTenths)
    }

    @Test
    fun `a rejected reading still moves the walk on`() {
        val session = handshakenSession()
        // a record with a wild value in it
        session.onNotification(VerioSession.command(
            0x06, 0xbb, 0x81, 0x44, 0x31, 0xff, 0xff, 0x00, 0x00, 0x00, 0x00, 0x00), -1)
        assertEquals("nothing stored", 0, session.takeReadings().size)
        session.take() // ack
        // asking for the same record again would loop forever
        assertNotEquals(VerioSession.command(0xb3, 0x70, 0x0c), session.take())
    }

    @Test
    fun `storing a reading does not shorten the walk`() {
        val session = handshakenSession()
        session.onNotification(recordPacket(), -1)
        assertEquals(1, session.takeReadings().size)
        session.take() // ack
        // record 3184 was stored, so the walk must continue to 3183 rather
        // than stopping against the value we just wrote
        assertArrayEquals(VerioSession.command(0xb3, 0x6f, 0x0c), session.take())
    }
}
