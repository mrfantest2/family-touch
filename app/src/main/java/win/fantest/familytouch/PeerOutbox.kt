package win.fantest.familytouch

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.UUID

/** Durable sender-side queue. Content is never uploaded to the directory service. */
internal object PeerOutbox {
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Synchronized fun init(value: Context) {
        if (::context.isInitialized) return
        context = value.applicationContext
        scope.launch { while (isActive) { flush(); delay(10000) } }
    }
    fun queue(peer: Peer, text: String, attachment: AttachmentTransport.Info? = null): ChatItem {
        require(FamilyStore.messages.count { it.outgoing && !it.delivered } < 500) { "Outbox is full; connect to deliver queued messages" }
        require(attachment != null || text.length <= 6000) { "Reply is too long; shorten it before sending" }
        val body = attachment?.let { AttachmentTransport.message(it.id, it.name, it.mime, it.size) } ?: text.trim()
        require(body.isNotBlank())
        val item = ChatItem(attachment?.id ?: UUID.randomUUID().toString(), peer.id, true, body, System.currentTimeMillis(), false)
        FamilyStore.addChat(item)
        scope.launch { flush() }
        return item
    }
    suspend fun flush() = mutex.withLock {
        if (!::context.isInitialized || !EmbeddedTailnet.status.connected) return@withLock
        val pending = withContext(Dispatchers.Main) { FamilyStore.messages.filter {
            it.outgoing && !it.delivered && !it.peerId.startsWith("group:")
        }.toList() }
        val permits = Semaphore(4)
        coroutineScope {
            pending.groupBy { it.peerId }.map { (peerId, items) -> async {
                permits.withPermit {
                    val peer = withContext(Dispatchers.Main) { FamilyStore.find(peerId) } ?: return@withPermit
                    for (item in items.take(20)) {
                        try {
                            val media = AttachmentTransport.parse(item.text)
                            if (media != null) AttachmentTransport.sendPrepared(context, peer, media)
                            else Tailnet.send(peer, "message", JSONObject().put("messageId", item.id).put("text", item.text),
                                onWritten = { withContext(Dispatchers.Main) { FamilyStore.sent(item.id) } })
                            withContext(Dispatchers.Main) { FamilyStore.delivered(item.id) }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { break /* Retry in order when this peer becomes reachable. */ }
                    }
                }
            } }.awaitAll()
        }
    }
}
