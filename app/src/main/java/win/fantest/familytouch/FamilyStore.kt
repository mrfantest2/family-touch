package win.fantest.familytouch

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

data class Peer(val id: String, val name: String, val ip: String, val secret: String)
data class ChatItem(val id: String, val peerId: String, val outgoing: Boolean, val text: String, val time: Long, val delivered: Boolean,
    val senderId: String = "", val sent: Boolean = delivered, val read: Boolean = false,
    val editedAt: Long = 0L, val deletedForEveryone: Boolean = false)
data class CallInvite(val peerId: String, val isVideo: Boolean, val offer: String = "", val outgoing: Boolean = false,
    val sessionId: String = "")
data class CallLogRecord(val id: String, val peerId: String, val isVideo: Boolean, val outgoing: Boolean,
    val startedAt: Long, val connectedAt: Long, val endedAt: Long, val outcome: String) {
    val durationMs: Long get() = if (connectedAt > 0L) (endedAt - connectedAt).coerceAtLeast(0L) else 0L
}

object FamilyStore {
    private lateinit var context: Context
    private val peers = mutableStateListOf<Peer>()
    private val chat = mutableStateListOf<ChatItem>()
    private val calls = mutableStateListOf<CallLogRecord>()
    val callHistory: List<CallLogRecord> get() = calls
    val peerPresence = mutableStateMapOf<String, String>()
    val peerPhotos = mutableStateMapOf<String, String>()
    private val chatViewedAt = mutableStateMapOf<String, Long>()
    fun unreadFor(peerId: String): Int {
        val viewed = chatViewedAt[peerId] ?: 0L
        return chat.count { !it.outgoing && it.peerId == peerId && !it.read && it.time > viewed }
    }
    fun unreadTotal(): Int = contacts.sumOf { unreadFor(it.id) }
    @Synchronized fun markChatViewed(peerId: String) {
        chatViewedAt[peerId] = System.currentTimeMillis()
        val obj = JSONObject()
        chatViewedAt.forEach { (key, value) -> obj.put(key, value) }
        if (::context.isInitialized)
            context.getSharedPreferences("identity", Context.MODE_PRIVATE)
                .edit().putString("chatViewedAt", obj.toString()).apply()
    }
    var profilePhoto by mutableStateOf("")
        private set
    fun updateProfilePhoto(encoded: String) {
        require(encoded.length <= 26000) { "Profile photo is too large" }
        profilePhoto = encoded
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit()
            .putString("profilePhoto", encoded).apply()
    }
    @Synchronized fun setPeerPhoto(peerId: String, encoded: String) {
        if (encoded.length > 26000 || find(peerId) == null) return
        peerPhotos[peerId] = encoded
        val root = JSONObject()
        peerPhotos.forEach { (id, img) -> root.put(id, img) }
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit()
            .putString("peerPhotos", root.toString()).apply()
    }
    @Volatile var appVisible: Boolean = false
    fun presenceLabel(peerId: String): String = when (peerPresence[peerId]) {
        "available" -> "Available"
        "on_call" -> "On call"
        "away" -> "Away"
        else -> "Offline"
    }
    val contacts: List<Peer> get() = peers
    val messages: List<ChatItem> get() = chat
    var nickname by mutableStateOf("")
        private set
    var needsNicknamePrompt by mutableStateOf(false)
        private set
    var lastError by mutableStateOf("")
    var directoryStatus by mutableStateOf("Connect your private network to discover registered family devices")
        private set
    fun updateDirectoryStatus(message: String) { directoryStatus = message }
    var selectedPeer by mutableStateOf<String?>(null)
    var incomingCall by mutableStateOf<CallInvite?>(null)
    var activeCall by mutableStateOf<CallInvite?>(null)
    lateinit var deviceId: String
        private set
    lateinit var pairingSecret: String
        private set

    fun init(ctx: Context) {
        if (::context.isInitialized) return
        context = ctx.applicationContext
        val p = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        // Migrate existing installations without interrupting their next launch.
        // Persist false for a fresh identity even if a service initializes us first.
        if (!p.contains("nicknamePromptCompleted")) {
            p.edit().putBoolean("nicknamePromptCompleted", p.contains("deviceId")).apply()
        }
        needsNicknamePrompt = !p.getBoolean("nicknamePromptCompleted", false)
        deviceId = p.getString("deviceId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() }
        pairingSecret = p.getString("pairingSecret", null) ?: ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { p.edit().putString("pairingSecret", it).apply() }
        nickname = p.getString("nickname", null) ?: (Build.MANUFACTURER + " " + Build.MODEL).trim()
        profilePhoto = p.getString("profilePhoto", "").orEmpty()
        try {
            val stored = JSONObject(p.getString("chatViewedAt", "{}").orEmpty())
            for (key in stored.keys()) chatViewedAt[key] = stored.optLong(key, 0)
        } catch (_: Exception) { }
        try {
            val images = JSONObject(p.getString("peerPhotos", "{}").orEmpty())
            for (key in images.keys()) peerPhotos[key] = images.optString(key)
        } catch (_: Exception) { }
        try {
            val arr = JSONArray(p.getString("peers", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                peers.add(Peer(o.getString("id"), o.getString("name"), o.getString("ip"), o.getString("secret")))
            }
        } catch (_: Exception) { lastError = "Some contacts could not be loaded" }
        try {
            val arr = JSONArray(p.getString("messages", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                chat.add(ChatItem(o.getString("id"), o.getString("peer"), o.getBoolean("outgoing"), o.getString("text"), o.getLong("time"), o.optBoolean("delivered", true), o.optString("senderId"), o.optBoolean("sent", o.optBoolean("delivered", true)), o.optBoolean("read"),
                    o.optLong("editedAt", 0L), o.optBoolean("deletedForEveryone", false)))
            }
        } catch (_: Exception) { lastError = "Some messages could not be loaded" }
        try {
            val arr = JSONArray(p.getString("callHistory", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                calls.add(CallLogRecord(o.getString("id"),o.getString("peerId"),
                    o.getBoolean("isVideo"),o.getBoolean("outgoing"),o.getLong("startedAt"),
                    o.optLong("connectedAt"),o.getLong("endedAt"),o.optString("outcome","ended")))
            }
        } catch (_: Exception) { lastError = "Some call history could not be loaded" }
    }

    @Synchronized fun addCallLog(record: CallLogRecord) {
        if (calls.any { it.id == record.id }) return
        calls.add(0, record)
        if (calls.size > 1000) calls.removeAt(calls.lastIndex)
        val a = JSONArray()
        calls.forEach { r -> a.put(JSONObject()
            .put("id",r.id).put("peerId",r.peerId).put("isVideo",r.isVideo)
            .put("outgoing",r.outgoing).put("startedAt",r.startedAt)
            .put("connectedAt",r.connectedAt).put("endedAt",r.endedAt).put("outcome",r.outcome)) }
        context.getSharedPreferences("identity", Context.MODE_PRIVATE)
            .edit().putString("callHistory",a.toString()).apply()
    }

    fun rename(value: String) {
        val name = value.trim().take(36)
        if (name.isEmpty()) return
        nickname = name
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit().putString("nickname", name).apply()
    }

    fun finishNicknameSetup(value: String?): Boolean {
        val name = (value ?: nickname).trim().take(36)
        if (name.isEmpty()) return false
        if (!context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit()
                .putString("nickname", name).putBoolean("nicknamePromptCompleted", true).commit()) {
            lastError = "Could not save your nickname. Please try again."
            return false
        }
        nickname = name
        needsNicknamePrompt = false
        return true
    }

    fun find(id: String): Peer? = peers.firstOrNull { it.id == id }

    @Synchronized fun applyDirectory(roster: List<Peer>) {
        DirectorySecurity.validateRoster(roster, deviceId)
        val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        val managed = prefs.getStringSet("directoryPeers", emptySet()).orEmpty()
        val ids = roster.map { it.id }.toSet()
        peers.removeAll { it.id in managed && it.id !in ids }
        roster.forEach { upsert(it) }
        val a = JSONArray()
        peers.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("ip", it.ip).put("secret", it.secret)) }
        prefs.edit().putStringSet("directoryPeers", ids).putString("peers", a.toString()).apply()
    }

    @Synchronized fun upsert(peer: Peer) {
        if (peer.id == deviceId || peer.ip.isBlank()) return
        val index = peers.indexOfFirst { it.id == peer.id }
        if (index < 0) peers.add(peer) else peers[index] = peer
        val a = JSONArray()
        peers.forEach { p -> a.put(JSONObject().put("id", p.id).put("name", p.name).put("ip", p.ip).put("secret", p.secret)) }
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit().putString("peers", a.toString()).apply()
    }

    @Synchronized fun addChat(item: ChatItem) {
        if (chat.any { it.id == item.id }) return
        chat.add(item)
        if (chat.size > 5000) {
            val removable = chat.indexOfFirst { !it.outgoing || it.delivered }
            if (removable >= 0) chat.removeAt(removable)
        }
        persistMessages()
    }

    @Synchronized fun delivered(id: String) {
        val index = chat.indexOfFirst { it.id == id }
        if (index >= 0) { chat[index] = chat[index].copy(sent = true, delivered = true); persistMessages() }
    }
    @Synchronized fun sent(id: String) {
        val index = chat.indexOfFirst { it.id == id }
        if (index >= 0) { chat[index] = chat[index].copy(sent = true); persistMessages() }
    }
    @Synchronized fun markRead(peerId: String, ids: Set<String>) {
        var changed = false
        for (i in chat.indices) {
            val item = chat[i]
            if (item.outgoing && item.peerId == peerId && item.id in ids && !item.read) {
                chat[i] = item.copy(sent = true, delivered = true, read = true); changed = true
            }
        }
        if (changed) persistMessages()
    }
    @Synchronized fun acknowledgeIncomingRead(peerId: String, ids: Set<String>) {
        var changed = false
        for (i in chat.indices) {
            val item = chat[i]
            if (!item.outgoing && item.peerId == peerId && item.id in ids && !item.read) {
                chat[i] = item.copy(read = true); changed = true
            }
        }
        if (changed) persistMessages()
    }

    /** Reuse one message bubble for updates to an authenticated live-location session. */
    @Synchronized internal fun upsertLiveLocation(fix: LiveLocationFix, peerId: String, outgoing: Boolean): Boolean {
        val text = LiveLocationProtocol.encode(fix)
        val now = System.currentTimeMillis()
        if (!LiveLocationProtocol.validWire(fix, now)) return false
        val index = chat.indexOfFirst { it.id == fix.sessionId }
        if (index < 0) {
            addChat(ChatItem(fix.sessionId, peerId, outgoing, text, now,
                delivered = !outgoing))
            return true
        }
        val existing = chat[index]
        val previous = LiveLocationProtocol.parse(existing.text) ?: return false
        if (existing.peerId != peerId || existing.outgoing != outgoing ||
            previous.expiresAt != fix.expiresAt || (previous.ended && !fix.ended)) return false
        if (previous == fix) return true
        chat[index] = existing.copy(text = text)
        persistMessages()
        return true
    }

    /** Local-only action; never claims a remote deletion. */
    @Synchronized fun deleteForMe(messageId: String): Boolean {
        val removed = chat.removeAll { it.id == messageId }
        if (removed) persistMessages()
        return removed
    }

    /** Clear the visible conversation on this device only. */
    @Synchronized fun clearConversation(peerId: String): Int {
        val before = chat.size
        chat.removeAll { it.peerId == peerId }
        if (chat.size != before) persistMessages()
        return before - chat.size
    }

    @Synchronized fun editMessageLocal(id: String, replacement: String, version: Long): Boolean {
        val index = chat.indexOfFirst { it.id == id }
        if (index < 0 || chat[index].deletedForEveryone ||
            chat[index].editedAt >= version || replacement.length !in 1..6000) return false
        chat[index] = chat[index].copy(text = replacement, editedAt = version)
        persistMessages()
        return true
    }

    @Synchronized fun deleteForEveryoneLocal(id: String, version: Long): Boolean {
        val index = chat.indexOfFirst { it.id == id }
        if (index < 0 || chat[index].editedAt > version) return false
        chat[index] = chat[index].copy(text = "", deletedForEveryone = true, editedAt = version)
        persistMessages()
        return true
    }

    /** Backup import is merge-only and never overwrites device identity or credentials. */
    @Synchronized fun mergeBackupMessages(items: List<ChatItem>): Int {
        require(items.size <= 5000)
        val existing = chat.map { it.id }.toMutableSet()
        var count = 0
        for (item in items) {
            if (chat.size >= 5000) break
            if (item.id.length !in 8..100 || item.peerId.isBlank() ||
                item.text.length > 6000 || item.time !in 1..(System.currentTimeMillis() + 300_000L)) continue
            if (existing.add(item.id)) { chat.add(item); count++ }
        }
        if (count > 0) persistMessages()
        return count
    }

    /**
     * Messages are written on one background thread and coalesced: a burst of
     * receipts or incoming messages produces one JSON write of the latest state
     * instead of re-serializing up to 5000 messages on the caller's thread each time.
     */
    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "FamilyStorePersist").apply { isDaemon = true }
    }
    private val messagesDirty = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun persistMessages() {
        if (!messagesDirty.compareAndSet(false, true)) return
        persistExecutor.execute {
            messagesDirty.set(false)
            writeMessages(synchronized(FamilyStore) { chat.toList() })
        }
    }

    private fun writeMessages(snapshot: List<ChatItem>) {
        val a = JSONArray()
        snapshot.forEach { m -> a.put(JSONObject().put("id", m.id).put("peer", m.peerId).put("outgoing", m.outgoing)
            .put("text", m.text).put("time", m.time).put("delivered", m.delivered).put("senderId", m.senderId)
            .put("sent", m.sent).put("read", m.read)
            .put("editedAt", m.editedAt).put("deletedForEveryone", m.deletedForEveryone)) }
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit().putString("messages", a.toString()).apply()
    }
}
