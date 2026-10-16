package win.fantest.familytouch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class FeatureCheckpointTest {
    private val id = UUID.randomUUID().toString()

    @Test fun callHistoryDurationUsesConnectedTimeNotDialingTime() {
        val record = CallLogRecord(id, "peer", true, false, 1000L, 3400L, 8400L, "completed")
        assertEquals(5000L, record.durationMs)
    }

    @Test fun unansweredCallsHaveZeroConnectedDuration() {
        val record = CallLogRecord(id, "peer", false, true, 1000L, 0L, 19000L, "unanswered")
        assertEquals(0L, record.durationMs)
    }

    @Test fun parsesValidFiftyMiBAttachment() {
        val payload = AttachmentTransport.message(id, "family photo.jpg", "image/jpeg", 50L * 1024L * 1024L)
        val parsed = AttachmentTransport.parse(payload)
        assertNotNull(parsed)
        assertEquals("family photo.jpg", parsed?.name)
        assertEquals(50L * 1024L * 1024L, parsed?.size)
    }

    @Test fun rejectsOversizeOrUnsupportedAttachmentMetadata() {
        assertNull(AttachmentTransport.parse(AttachmentTransport.message(id, "oversize.mp4", "video/mp4",
            50L * 1024L * 1024L + 1L)))
        assertNull(AttachmentTransport.parse(AttachmentTransport.message(id, "malware.exe", "application/octet-stream", 50)))
        assertNull(AttachmentTransport.parse("[FT_FILE]|../escape|bad.jpg|image/jpeg|200"))
    }
}
