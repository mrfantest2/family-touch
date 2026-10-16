package win.fantest.familytouch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {
    @Test fun connectionAndCallIdsAreReserved() {
        assertEquals(40, NotificationIds.CONNECTION)
        assertEquals(41, NotificationIds.INCOMING_CALL)
        assertTrue(NotificationIds.messageForPeer("peer-a") >= 1_000)
    }
    @Test fun repeatedMessagesFromOneContactReuseTheSameNotification() {
        assertEquals(NotificationIds.messageForPeer("peer-a"), NotificationIds.messageForPeer("peer-a"))
    }
    @Test fun differentContactsNormallyHaveDifferentNotifications() {
        assertNotEquals(NotificationIds.messageForPeer("peer-a"), NotificationIds.messageForPeer("peer-b"))
    }
}
