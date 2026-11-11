package win.fantest.familytouch

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable QA-only peer/messages for physical keyboard testing.
 * Persists synchronously so the data survives instrumentation process exit.
 * Never sends a message; TEST-NET-1 is reserved and not a real recipient.
 */
@RunWith(AndroidJUnit4::class)
class ImeFixtureDeviceTest {
    private val id = "ft-qa-ime-local-20261111"
    private val prefix = "ft-qa-ime-msg-"
    private fun prefs() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        assertEquals("win.fantest.familytouch.qa", it.packageName)
    }.getSharedPreferences("identity", Context.MODE_PRIVATE)

    @Test fun createIsolatedConversation() {
        val p = prefs()
        val peers = JSONArray(p.getString("peers", "[]") ?: "[]")
        assertTrue("Do not overwrite existing QA contact", (0 until peers.length()).none {
            peers.optJSONObject(it)?.optString("id") == id
        })
        peers.put(JSONObject().put("id", id).put("name", "QA Keyboard Fixture")
            .put("ip", "192.0.2.123").put("secret", "qa-only-unused"))
        val messages = JSONArray(p.getString("messages", "[]") ?: "[]")
        val now = System.currentTimeMillis()
        repeat(28) { idx ->
            messages.put(JSONObject()
                .put("id", prefix + idx.toString().padStart(3, '0'))
                .put("peer", id).put("outgoing", false)
                .put("text", "Keyboard QA fixture message " + (idx + 1) + "; not transmitted")
                .put("time", now - (28 - idx) * 60_000L)
                .put("delivered", true).put("sent", true).put("read", true))
        }
        assertTrue("QA fixture must survive instrumentation exit", p.edit()
            .putString("peers", peers.toString())
            .putString("messages", messages.toString()).commit())
        assertTrue(p.getString("peers", "")!!.contains(id))
        assertTrue(p.getString("messages", "")!!.contains(prefix))
    }

    @Test fun removeIsolatedConversation() {
        val p = prefs()
        fun filtered(key: String, keep: (JSONObject) -> Boolean): String {
            val source = JSONArray(p.getString(key, "[]") ?: "[]")
            val out = JSONArray()
            for (i in 0 until source.length()) {
                val item = source.optJSONObject(i) ?: continue
                if (keep(item)) out.put(item)
            }
            return out.toString()
        }
        val retainedPeers = filtered("peers") { it.optString("id") != id }
        val retainedMessages = filtered("messages") {
            it.optString("peer") != id && !it.optString("id").startsWith(prefix)
        }
        val retainedCallHistory = filtered("callHistory") {
            it.optString("peerId") != id
        }
        val views = JSONObject(p.getString("chatViewedAt", "{}") ?: "{}")
        views.remove(id)
        assertTrue(p.edit().putString("peers", retainedPeers)
            .putString("messages", retainedMessages)
            .putString("callHistory", retainedCallHistory)
            .putString("chatViewedAt", views.toString()).commit())
        assertFalse(p.getString("peers", "")!!.contains(id))
        assertFalse(p.getString("messages", "")!!.contains(prefix))
        assertFalse(p.getString("callHistory", "")!!.contains(id))
    }
}
