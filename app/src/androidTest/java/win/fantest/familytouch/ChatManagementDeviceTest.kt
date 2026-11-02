package win.fantest.familytouch

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Isolated QA-package tests: no real chats, contacts, or network traffic. */
class ChatManagementDeviceTest {
    @Test fun authorizedEditDeleteAndForgedSenderBlocked() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("win.fantest.familytouch.qa", context.packageName)
        withContext(Dispatchers.Main) {
            FamilyStore.init(context)
            val id = UUID.randomUUID().toString()
            val peerId = "synthetic-qa-peer"
            val record = ChatItem(id, peerId, false, "test-only message",
                System.currentTimeMillis(), true)
            FamilyStore.addChat(record)
            try {
                val event = JSONObject().put("messageId", id)
                    .put("action", "edit").put("text", "changed")
                    .put("version", System.currentTimeMillis())
                assertTrue(runCatching { MessageMutations.receive("forged-sender", event) }.isFailure)
                assertEquals("test-only message", FamilyStore.messages.first { it.id == id }.text)
                MessageMutations.receive(peerId, event)
                assertEquals("changed", FamilyStore.messages.first { it.id == id }.text)
                val delete = JSONObject().put("messageId", id)
                    .put("action", "delete").put("version", event.getLong("version") + 1)
                MessageMutations.receive(peerId, delete)
                assertTrue(FamilyStore.messages.first { it.id == id }.deletedForEveryone)
                val laterEdit = JSONObject().put("messageId", id).put("action","edit")
                    .put("text","forged second edit").put("version",delete.getLong("version") + 1)
                assertTrue(runCatching { MessageMutations.receive(peerId, laterEdit) }.isFailure)
            } finally {
                FamilyStore.deleteForMe(id)
            }
        }
    }
}
