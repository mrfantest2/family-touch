package win.fantest.familytouch

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

data class Peer(val id: String, val name: String, val ip: String, val secret: String)
data class ChatItem(val id: String, val peerId: String, val outgoing: Boolean, val text: String, val time: Long, val delivered: Boolean)
data class CallInvite(val peerId: String, val isVideo: Boolean, val offer: String = "", val outgoing: Boolean = false)

object FamilyStore {
    private lateinit var context: Context
    private val peers = mutableStateListOf<Peer>()
    private val chat = mutableStateListOf<ChatItem>()
    val contacts: List<Peer> get() = peers
    val messages: List<ChatItem> get() = chat
    var nickname by mutableStateOf("")
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
        deviceId = p.getString("deviceId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() }
        pairingSecret = p.getString("pairingSecret", null) ?: ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { p.edit().putString("pairingSecret", it).apply() }
        nickname = p.getString("nickname", null) ?: (Build.MANUFACTURER + " " + Build.MODEL).trim()
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
                chat.add(ChatItem(o.getString("id"), o.getString("peer"), o.getBoolean("outgoing"), o.getString("text"), o.getLong("time"), o.optBoolean("delivered", true)))
            }
        } catch (_: Exception) { lastError = "Some messages could not be loaded" }
    }

    fun rename(value: String) {
        val name = value.trim().take(36)
        if (name.isEmpty()) return
        nickname = name
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit().putString("nickname", name).apply()
    }

    fun find(id: String): Peer? = peers.firstOrNull { it.id == id }

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
            .put("text", m.text).put("time", m.time).put("delivered", m.delivered)) }
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit().putString("messages", a.toString()).apply()
    }
}
