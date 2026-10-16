package win.fantest.familytouch

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetTest {
    @Test fun acceptsCgnatBounds() {
        assertTrue(Tailnet.isTailIp("100.64.0.1"))
        assertTrue(Tailnet.isTailIp("100.100.20.40"))
        assertTrue(Tailnet.isTailIp("100.127.255.254"))
    }
    @Test fun wireFramesReadOneLineAndStripCrLf() {
        val input = ByteArrayInputStream("hello\r\nsecond\n".toByteArray())
        assertEquals("hello", Tailnet.readWireLine(input, 10))
        assertEquals("second", Tailnet.readWireLine(input, 10))
    }

    @Test fun wireFramesRejectUnboundedOrTruncatedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            Tailnet.readWireLine(ByteArrayInputStream("123456789\n".toByteArray()), 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Tailnet.readWireLine(ByteArrayInputStream("missing newline".toByteArray()), 100)
        }
    }

    @Test fun excludesPublicLanAndMalformedIps() {
        assertFalse(Tailnet.isTailIp("100.63.1.1"))
        assertFalse(Tailnet.isTailIp("100.128.0.1"))
        assertFalse(Tailnet.isTailIp("192.168.0.1"))
        assertFalse(Tailnet.isTailIp("100.100.256.1"))
        assertFalse(Tailnet.isTailIp("100.100.20"))
        assertFalse(Tailnet.isTailIp("localhost"))
        assertFalse(Tailnet.isTailIp(""))
    }
}
