package win.fantest.familytouch

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** QA-only. Validates creator deletion and tombstone persistence without
 * contacting real family devices or deleting any live group/chat.
 */
@RunWith(AndroidJUnit4::class)
class GroupDeleteDeviceTest {
    @Test fun creatorDeleteRemovesQaGroupAndRetainsRevisionedTombstone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("win.fantest.familytouch.qa", context.packageName)
        FamilyStore.init(context)
        GroupStore.init(context)
        val owner = FamilyStore.deviceId
        val id = "group:" + UUID.randomUUID()
        val temporary = FamilyGroup(id, "QA Owner Delete Test", owner, setOf(owner), 1)
        val prefs = context.getSharedPreferences("groups_v1", Context.MODE_PRIVATE)
        assertNull(GroupStore.find(id))
        GroupStore.groups.add(temporary)
        try {
            GroupStore.deleteOwned(temporary)
            assertFalse(GroupStore.groups.any { it.id == id })
            val tombstone = requireNotNull(GroupStore.find(id))
            assertEquals(setOf(owner), tombstone.members)
            assertEquals(2L, tombstone.revision)
            // Tombstones must never allow stale activity or outgoing group messages.
            val staleMessage = JSONObject().put("groupId", id).put("revision", tombstone.revision)
            assertThrows(IllegalArgumentException::class.java) {
                GroupStore.validateSender(owner, staleMessage)
            }
            assertThrows(IllegalArgumentException::class.java) {
                GroupStore.queue(temporary, "must-not-queue")
            }
            val saved = JSONArray(prefs.getString("tombstones", "[]"))
            assertTrue((0 until saved.length()).any {
                val entry = saved.getJSONObject(it)
                entry.getString("groupId") == id &&
                    entry.getLong("revision") == 2L &&
                    entry.getJSONArray("members").length() == 1
            })
        } finally {
            // Clean only the UUID allocated by this test. Preserve all other QA
            // groups, queued work, tombstones, chats, and credentials.
            GroupStore.groups.removeAll { it.id == id }
            fun withoutOwnId(key: String): String {
                val arr = JSONArray(prefs.getString(key, "[]"))
                val kept = JSONArray()
                for (i in 0 until arr.length()) {
                    val row = arr.getJSONObject(i)
                    if (row.optString("groupId") != id) kept.put(row)
                }
                return kept.toString()
            }
            prefs.edit()
                .putString("groups", withoutOwnId("groups"))
                .putString("tombstones", withoutOwnId("tombstones"))
                .putString("updates", withoutOwnId("updates"))
                .putString("pending", withoutOwnId("pending"))
                .commit()
        }
    }
}
