package win.fantest.familytouch

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.crypto.AEADBadTagException

class ChatManagementTest {
    private fun item(now: Long, text: String = "Hello", outgoing: Boolean = true,
                     delivered: Boolean = true) = ChatItem(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        outgoing, text, now - 45_000L, delivered
    )

    @Test fun editPolicyOnlyAllowsRecentlyDeliveredDirectText() {
        val now = 1_800_000_000_000L
        val good = item(now)
        assertTrue(MessageMutations.editable(good, now))
        assertFalse(MessageMutations.editable(good.copy(outgoing = false), now))
        assertFalse(MessageMutations.editable(good.copy(delivered = false), now))
        assertFalse(MessageMutations.editable(good.copy(time = now - 16*60_000L), now))
        assertFalse(MessageMutations.editable(good.copy(peerId = "group:example"), now))
        assertFalse(MessageMutations.editable(good.copy(deletedForEveryone = true), now))
        assertFalse(MessageMutations.editable(good.copy(text = "Location: https://www.google.com/maps?q=1,2"), now))
        assertTrue(MessageMutations.deletableForEveryone(good))
        assertFalse(MessageMutations.deletableForEveryone(good.copy(delivered = false)))
    }

    @Test fun encryptedBackupRejectsWrongPasswordAndRoundTrips() {
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("format","family-touch-chats").put("version",1)
            .put("messages", JSONArray().put(JSONObject()
                .put("id",id).put("peerId","peer-one").put("outgoing",true)
                .put("text","Backup content").put("time",12345L))).toString()
        val secret = "a-long-backup-passphrase".toCharArray()
        val encrypted = ChatBackup.encrypt(payload, secret)
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("Backup content"))
        val restored = ChatBackup.decrypt(encrypted, secret)
        assertEquals(1,restored.size)
        assertEquals("Backup content", restored[0].text)
        assertEquals(id,restored[0].id)
        try {
            ChatBackup.decrypt(encrypted,"wrong-password".toCharArray())
            fail("Wrong passphrase should fail authentication")
        } catch (_: AEADBadTagException) { }
    }

    @Test fun supportedDocumentAttachmentIsNotMistakenForText() {
        val id = UUID.randomUUID().toString()
        val message = AttachmentTransport.message(id, "notes.pdf", "application/pdf", 100L)
        assertEquals("notes.pdf", AttachmentTransport.parse(message)?.name)
        assertNull(AttachmentTransport.parse(
            AttachmentTransport.message(id,"danger.apk","application/vnd.android.package-archive",100L)))
    }
}
