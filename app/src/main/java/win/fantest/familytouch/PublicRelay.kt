package win.fantest.familytouch

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Staging-only HTTPS text relay. No direct peer sockets or Android VPN permission. */
internal object PublicRelay {
    data class Incoming(val queueId: String, val from: String, val type: String, val payload: JSONObject)
    private val acks = mutableListOf<String>()
    private val supported = setOf("message", "delivery_receipt", "read_receipt", "message_reaction", "profile_update")

    private fun key(shared: String): SecretKeySpec {
        require(shared.matches(Regex("[a-f0-9]{64}")))
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(("FamilyTouch HTTPS relay v1\n" + shared).toByteArray(Charsets.UTF_8))
        return SecretKeySpec(bytes, "AES")
    }

    private fun seal(shared: String, from: String, to: String, json: JSONObject): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(shared), GCMParameterSpec(128, nonce))
        cipher.updateAAD((from + "\n" + to).toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(nonce + encrypted, Base64.NO_WRAP)
    }

    private fun open(shared: String, from: String, to: String, encoded: String): JSONObject {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size in 29..12000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(shared), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD((from + "\n" + to).toByteArray(Charsets.UTF_8))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }

    suspend fun send(
        context: Context, peer: Peer, type: String, payload: JSONObject,
        onWritten: (suspend () -> Unit)? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        check(type in supported) { "This feature needs the secure public relay update" }
        require(peer.id != FamilyStore.deviceId && peer.ip == "relay")
        val queueId = when (type) {
            "message", "delivery_receipt", "read_receipt" -> type + ":" + payload.optString("messageId", UUID.randomUUID().toString())
            "message_reaction" -> "reaction:" + payload.optString("messageId") + ":" + payload.optLong("version")
            else -> type + ":" + UUID.randomUUID().toString()
        }
        require(queueId.length in 8..100)
        val inner = JSONObject().put("type", type).put("payload", payload)
        val encrypted = seal(peer.secret, FamilyStore.deviceId, peer.id, inner)
        val result = FamilyDirectory.postPublic(context, "/api/v1/relay/send",
            JSONObject().put("to", peer.id).put("id", queueId).put("ciphertext", encrypted))
        require(result.optBoolean("queued")) { "Relay did not accept the message" }
        onWritten?.invoke()
        result
    }

    suspend fun pull(context: Context): List<Incoming> = withContext(Dispatchers.IO) {
        val acknowledging = synchronized(acks) { acks.take(64) }
        val req = JSONObject().put("ack", JSONArray(acknowledging))
        val response = FamilyDirectory.postPublic(context, "/api/v1/relay/pull", req)
        require(response.optBoolean("ok"))
        synchronized(acks) { acks.removeAll(acknowledging.toSet()) }
        val array = response.getJSONArray("messages")
        require(array.length() <= 32)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val from = item.getString("from")
                val id = item.getString("id")
                val peer = FamilyStore.find(from) ?: continue
                require(peer.ip == "relay")
                val decrypted = open(peer.secret, from, FamilyStore.deviceId, item.getString("ciphertext"))
                val type = decrypted.getString("type")
                if (type !in supported) continue
                add(Incoming(id, from, type, decrypted.getJSONObject("payload")))
            }
        }
    }

    fun acknowledge(queueId: String) {
        synchronized(acks) { if (queueId !in acks) acks.add(queueId) }
    }
}
