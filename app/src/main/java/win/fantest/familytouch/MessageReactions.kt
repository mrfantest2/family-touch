package win.fantest.familytouch

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object ReactionPolicy {
    val choices = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
    fun valid(emoji: String, expectedChat: String, actualChat: String) =
        expectedChat == actualChat && (emoji.isEmpty() || emoji in choices)
}
internal object MessageReactions {
    private lateinit var context: Context
    private val values = mutableStateMapOf<String, String>()
    private val pending = mutableListOf<JSONObject>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Synchronized fun init(value: Context) {
        if (::context.isInitialized) return
        context = value.applicationContext
        runCatching {
            val prefs = context.getSharedPreferences("reactions", Context.MODE_PRIVATE)
            val stored = JSONObject(prefs.getString("values", "{}"))
            stored.keys().forEach { values[it] = stored.getString(it) }
            val queue = JSONArray(prefs.getString("pending", "[]"))
            for (i in 0 until minOf(500, queue.length())) pending.add(queue.getJSONObject(i))
        }
        scope.launch { while (isActive) { runCatching { flush() }; delay(10000) } }
    }
    @Synchronized private fun save() {
        val stored = JSONObject(); values.forEach { (key, value) -> stored.put(key, value) }
        context.getSharedPreferences("reactions", Context.MODE_PRIVATE).edit()
            .putString("values", stored.toString()).putString("pending", JSONArray(pending).toString()).apply()
    }
    private fun key(id: String, sender: String) = "$id/$sender"
    @Synchronized fun summary(id: String): String = values.filterKeys { it.startsWith("$id/") }.values
        .mapNotNull { runCatching { JSONObject(it).getString("emoji") }.getOrNull()?.takeIf { it.isNotEmpty() } }
        .groupingBy { it }.eachCount().entries.joinToString("  ") { "${it.key} ${it.value}" }
    @Synchronized fun react(item: ChatItem, emoji: String) {
        require(ReactionPolicy.valid(emoji, item.peerId, item.peerId))
        val previous = values[key(item.id, FamilyStore.deviceId)]?.let { JSONObject(it).getLong("version") } ?: 0L
        val version = maxOf(System.currentTimeMillis(), previous + 1)
        val group = GroupStore.find(item.peerId)
        val targets = group?.members?.minus(FamilyStore.deviceId) ?: setOf(item.peerId)
        val replaced = pending.count { it.getString("messageId") == item.id && it.getString("peer") in targets }
        require(pending.size - replaced + targets.size <= 500) { "Reaction queue is full" }
        for (peer in targets) {
            pending.removeAll { it.getString("peer") == peer && it.getString("messageId") == item.id }
            pending.add(JSONObject().put("peer", peer).put("group", group?.id ?: "").put("messageId", item.id)
                .put("emoji", emoji).put("version", version))
        }
        values[key(item.id, FamilyStore.deviceId)] = JSONObject().put("emoji", emoji).put("version", version).toString()
        save(); scope.launch { flush() }
    }
    @Synchronized fun receive(sender: String, data: JSONObject) {
        val id = data.getString("messageId"); UUID.fromString(id)
        val group = if (data.has("groupId")) GroupStore.validateSender(sender, data) else null
        val chat = group?.id ?: sender
        val item = requireNotNull(FamilyStore.messages.firstOrNull { it.id == id })
        val emoji = data.getString("emoji"); val version = data.getLong("version")
        require(ReactionPolicy.valid(emoji, item.peerId, chat) && version > 0)
        val key = key(id, sender)
        val old = values[key]?.let { JSONObject(it).getLong("version") } ?: 0
        if (version > old) { values[key] = JSONObject().put("emoji", emoji).put("version", version).toString(); save() }
    }
    private suspend fun flush() = mutex.withLock {
        if (!EmbeddedTailnet.status.connected) return@withLock
        for (item in synchronized(this) { pending.toList() }) {
            try {
                val peer = FamilyStore.find(item.getString("peer")) ?: continue
                val groupId = item.getString("group")
                val group = if (groupId.isNotEmpty()) GroupStore.currentForSending(groupId) else null
                if (group != null && peer.id !in group.members) {
                    synchronized(this) { pending.remove(item); save() }; continue
                }
                val data = (group?.json() ?: JSONObject()).put("messageId", item.getString("messageId"))
                    .put("emoji", item.getString("emoji")).put("version", item.getLong("version"))
                Tailnet.send(peer, "message_reaction", data)
                synchronized(this) { pending.remove(item); save() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep for retry after update/reconnect. */ }
        }
    }
}
