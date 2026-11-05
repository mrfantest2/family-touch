package win.fantest.familytouch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatPreviewFormatterTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private val now = 1_800_000_000_000L

    @Test fun plainTextRemainsUnchanged() {
        assertEquals("Check-in confirmed", ChatPreviewFormatter.forContact("Check-in confirmed", now))
    }

    @Test fun activeLocationHidesRawPayloadAndCoordinates() {
        val text = LiveLocationProtocol.encode(LiveLocationFix(id, now + 60_000L, 33.266_001, 35.244_001, false))
        val preview = ChatPreviewFormatter.forContact(text, now)
        assertEquals("Live location shared", preview)
        assertFalse(preview.contains("33.266"))
        assertFalse(preview.contains(id))
    }

    @Test fun endedLocationHasExplicitStatus() {
        val text = LiveLocationProtocol.encode(LiveLocationFix(id, now + 60_000L, 33.0, 35.0, true))
        assertEquals("Live location stopped", ChatPreviewFormatter.forContact(text, now))
    }

    @Test fun expiredLocationHasExplicitStatus() {
        val text = LiveLocationProtocol.encode(LiveLocationFix(id, now - 1L, 33.0, 35.0, false))
        assertEquals("Live location expired", ChatPreviewFormatter.forContact(text, now))
    }

    @Test fun malformedLocationProtocolIsNotShownRaw() {
        assertEquals("Location update", ChatPreviewFormatter.forContact("FTLIVE1:untrusted-malformed-payload", now))
    }
}
