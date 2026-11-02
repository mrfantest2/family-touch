package win.fantest.familytouch

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable, authenticated one-to-one edit/unsend requests.
 * Messages sent to older clients cannot be retracted until they update.
 * "Delete for me" never sends a remote request.
 */
internal object MessageMutations {
    const val EDIT_LIMIT_MS = 15 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushLock = Mutex()
    private lateinit var context: Context
    private val pending = mutableListOf<JSONObject>()
    val pendingLabels = mutableStateMapOf<String, String>()

    fun editable(item: ChatItem, now: Long = System.currentTimeMillis()): Boolean =
        item.outgoing && item.delivered && !item.deletedForEveryone &&
            !item.peerId.startsWith("group:") &&
            !item.text.startsWith("Location: ") &&
            AttachmentTransport.parse(item.text) == null &&
            item.text.isNotBlank() && now >= item.time && now - item.time <= EDIT_LIMIT_MS

    fun deletableForEveryone(item: ChatItem): Boolean =
        item.outgoing && item.delivered && !item.deletedForEveryone &&
            !item.peerId.startsWith("group:")

    @Synchronized
    fun init(ctx: Context) {
        if (::context.isInitialized) return
        context = ctx.applicationContext
        runCatching {
            val data = JSONArray(context.getSharedPreferences("message_mutations", Context.MODE_PRIVATE)
                .getString("pending", "[]"))
            for (i in 0 until minOf(data.length(), 500)) {
                val value = data.getJSONObject(i)
                if (value.optString("peerId").isNotBlank()) {
                    pending.add(value)
                    pendingLabels[value.optString("messageId")] = "Syncing to recipient"
                }
            }
        }
        scope.launch {
            while (isActive) {
                try { flush() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
                delay(10_000L)
            }
        }
    }

    @Synchronized private fun save() {
        check(context.getSharedPreferences("message_mutations", Context.MODE_PRIVATE)
            .edit().putString("pending", JSONArray(pending).toString()).commit()) {
            "Unable to save pending message change"
        }
    }

    @Synchronized private fun schedule(item: ChatItem, action: String, text: String = "") {
        check(::context.isInitialized) { "Message service not initialized" }
        require(pending.size < 500) { "Message sync queue is full" }
        val version = maxOf(System.currentTimeMillis(), item.editedAt + 1L)
        val event = JSONObject().put("messageId", item.id).put("peerId", item.peerId)
            .put("action", action).put("text", text).put("version", version)
        // Replace pending edits by a later edit or deletion.
        pending.removeAll { it.optString("messageId") == item.id }
        pending.add(event)
        save()
        pendingLabels[item.id] = "Syncing to recipient"
        if (action == "delete") FamilyStore.deleteForEveryoneLocal(item.id, version)
        else FamilyStore.editMessageLocal(item.id, text, version)
        scope.launch { flush() }
    }

    fun edit(item: ChatItem, text: String) {
        require(editable(item)) { "Only delivered text messages can be edited for 15 minutes" }
        require(text.trim().isNotBlank() && text.trim().length <= 6000) { "Invalid message" }
        schedule(item, "edit", text.trim())
    }

    fun deleteForEveryone(item: ChatItem) {
        require(deletableForEveryone(item)) { "Only sent direct messages can be deleted for everyone" }
        schedule(item, "delete")
    }

    /** Receiver is authenticated by PeerService before this method is called. */
    fun receive(sender: String, request: JSONObject) {
        val id = UUID.fromString(request.getString("messageId")).toString()
        val action = request.getString("action")
        require(action == "delete" || action == "edit") { "Unsupported edit action" }
        val version = request.getLong("version")
        require(version > 0 && version <= System.currentTimeMillis() + 5 * 60 * 1000L)
        val item = FamilyStore.messages.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Message not available on this device")
        require(!item.outgoing && item.peerId == sender) { "Not the original message author" }
        if (item.deletedForEveryone) {
            require(action == "delete") { "Deleted messages cannot be edited" }
            return
        }
        if (version <= item.editedAt) return
        when (action) {
            "delete" -> FamilyStore.deleteForEveryoneLocal(id, version)
            "edit" -> {
                require(AttachmentTransport.parse(item.text) == null &&
                    !item.text.startsWith("Location: ")) { "Only text can be edited" }
                val text = request.getString("text")
                require(text.isNotBlank() && text.length <= 6000) { "Invalid edited text" }
                FamilyStore.editMessageLocal(id, text, version)
            }
        }
    }

    suspend fun flush() = flushLock.withLock {
        if (!::context.isInitialized || !EmbeddedTailnet.status.connected) return@withLock
        val snapshot = synchronized(this) { pending.toList() }
        for (event in snapshot) {
            try {
                val peer = withContext(Dispatchers.Main) {
                    FamilyStore.find(event.getString("peerId"))
                } ?: continue
                if (Tailnet.send(peer, "presence").optInt("protocol", 1) < 5) continue
                Tailnet.send(peer, "message_mutation", JSONObject()
                    .put("messageId", event.getString("messageId"))
                    .put("action", event.getString("action"))
                    .put("text", event.optString("text"))
                    .put("version", event.getLong("version")))
                withContext(Dispatchers.Main) {
                    synchronized(this@MessageMutations) {
                        // Do not discard a more recent queued mutation for the same message.
                        if (pending.remove(event)) {
                            save()
                            if (pending.none { it.optString("messageId") == event.getString("messageId") }) {
                                pendingLabels.remove(event.getString("messageId"))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep for retry; never claim remote success. */ }
        }
    }
}
