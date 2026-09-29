package tk.glucodata.glucosemeter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        session.onNotification(byteArrayOf(0x81.toByte()))
        assertEquals("nothing queued before a session is begun", null, session.take())
    }

    @Test
    fun `begin queues the meter time command first`() {
        val session = VerioSession()
        session.begin(-1)
        assertArrayEquals(VerioSession.command(0x20, 0x02), session.take())
        assertEquals("only one command may be in flight", null, session.take())
    }

    @Test
    fun `the ack alone does not move the handshake on`() {
        val session = VerioSession()
        session.begin(-1)
        assertArrayEquals(VerioSession.command(0x20, 0x02), session.take())
        session.onNotification(byteArrayOf(0x81.toByte()))
        assertEquals("ack must not queue the next request", null, session.take())
    }

    @Test
    fun `a data packet acks and then asks for the next thing`() {
        val session = VerioSession()
        session.begin(-1)
        assertArrayEquals(VerioSession.command(0x20, 0x02), session.take())
        session.onNotification(VerioSession.command(0x06, 0x3e, 0x3c, 0x4e, 0x32, 0x03, 0x65, 0x2a))
        assertArrayEquals("ack first", byteArrayOf(0x81.toByte()), session.take())
        assertArrayEquals("then the record counter", VerioSession.command(0x0a, 0x02, 0x06), session.take())
        assertEquals("and nothing after that", null, session.take())
    }
}
