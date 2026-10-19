package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test
class MessageDeliveryTest {
    @Test fun readReceiptBatchesEventuallyCoverOldAndNewUnreadMessages() {
        val messages = (1..250).map { ChatItem("m$it", "peer", false, "text", it.toLong(), true) }
        val first = MessageDelivery.nextUnread(messages, "peer")
        assertEquals(100, first.size)
        assertEquals("m1", first.first())
        val after = messages.map { if (it.id in first) it.copy(read = true) else it }
        assertEquals("m101", MessageDelivery.nextUnread(after, "peer").first())
        assertTrue(MessageDelivery.nextUnread(after, "other").isEmpty())
    }
    @Test fun readRequiresAcknowledgedDeliveryAndPendingMessagesRetry() {
        assertEquals("Queued", MessageDelivery.label(false, false, false))
        assertEquals("Sent", MessageDelivery.label(true, false, false))
        assertEquals("Delivered", MessageDelivery.label(true, true, false))
        assertEquals("Read", MessageDelivery.label(true, true, true))
        assertEquals("Queued", MessageDelivery.label(false, false, true))
    }
}
