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
    val senderId: String = "")
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
    var profilePhoto by mutableStateOf("")
        private set
    fun updateProfilePhoto(encoded: String) {
        require(encoded.length <= 26000) { "Profile photo is too large" }
        profilePhoto = encoded
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit()
            .putString("profilePhoto", encoded).apply()
    }
    fun setPeerPhoto(peerId: String, encoded: String) {
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
                chat.add(ChatItem(o.getString("id"), o.getString("peer"), o.getBoolean("outgoing"), o.getString("text"), o.getLong("time"), o.optBoolean("delivered", true), o.optString("senderId")))
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

    fun addCallLog(record: CallLogRecord) {
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

    fun applyDirectory(roster: List<Peer>) {
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

    fun upsert(peer: Peer) {
        if (peer.id == deviceId || peer.ip.isBlank()) return
        val index = peers.indexOfFirst { it.id == peer.id }
        if (index < 0) peers.add(peer) else peers[index] = peer
        val a = JSONArray()
        peers.forEach { p -> a.put(JSONObject().put("id", p.id).put("name", p.name).put("ip", p.ip).put("secret", p.secret)) }
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit().putString("peers", a.toString()).apply()
    }

    fun addChat(item: ChatItem) {
        if (chat.any { it.id == item.id }) return
        chat.add(item)
        if (chat.size > 5000) chat.removeAt(0)
        persistMessages()
    }

    fun delivered(id: String) {
        val index = chat.indexOfFirst { it.id == id }
        if (index >= 0) { chat[index] = chat[index].copy(delivered = true); persistMessages() }
    }

    private fun persistMessages() {
        val a = JSONArray()
        chat.forEach { m -> a.put(JSONObject().put("id", m.id).put("peer", m.peerId).put("outgoing", m.outgoing)
            .put("text", m.text).put("time", m.time).put("delivered", m.delivered).put("senderId", m.senderId)) }
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit().putString("messages", a.toString()).apply()
    }
}
