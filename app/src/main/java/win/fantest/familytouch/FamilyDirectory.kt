package win.fantest.familytouch

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection

internal object FamilyDirectory {
    private const val base = "https://fantest.win/family-touch"
    private fun request(path: String, json: JSONObject): JSONObject {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val device = FamilyIdentity.fingerprint()
        val time = (System.currentTimeMillis() / 1000).toString()
        val nonce = DirectorySecurity.hex(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val connection = URL(base + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-FT-Device", device)
            connection.setRequestProperty("X-FT-Time", time)
            connection.setRequestProperty("X-FT-Nonce", nonce)
            connection.setRequestProperty("X-FT-Signature", FamilyIdentity.sign(DirectorySecurity.canonical("POST", path, device, time, nonce, bytes)))
            connection.outputStream.use { it.write(bytes) }
            check(connection.responseCode == 200) { "Registration HTTP ${connection.responseCode}" }
            val response = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (out.size() <= 65536) {
                    val n = input.read(buffer, 0, minOf(buffer.size, 65537 - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            require(response.size <= 65536)
            return JSONObject(String(response, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    /** Called only by the VPN's serialized watch job. Credentials never enter preferences. */
    fun enroll(context: Context, needsAuth: Boolean): String {
        var stage = "request"
        try {
        val rsa = DeviceProvisioning(context)
        val rsaRequest = JSONObject(rsa.request())
        val prefs = context.getSharedPreferences("directory", Context.MODE_PRIVATE)
        val result = request("/api/v1/enroll", JSONObject()
            .put("publicKey", FamilyIdentity.publicKey()).put("rsaPublicKey", rsaRequest.getString("publicKey"))
            .put("rsaDeviceId", rsa.deviceId()).put("profileId", FamilyStore.deviceId)
            .put("name", FamilyStore.nickname.take(36)).put("model", Build.MODEL.take(80))
            .put("needsAuth", needsAuth).put("previousEnvelopeHash", prefs.getString("envelopeHash", "")))
        return when (result.getString("state")) {
            "pending" -> "Waiting for family approval"
            "credential" -> {
                stage = "decrypt credential"
                val envelope = result.getString("envelope")
                val hash = DirectorySecurity.hex(MessageDigest.getInstance("SHA-256").digest(envelope.toByteArray(Charsets.UTF_8)))
                if (hash != prefs.getString("envelopeHash", "")) {
                    val key = rsa.decrypt(envelope.toByteArray(Charsets.UTF_8))
                    stage = "start private network"
                    EmbeddedTailnet.api(context, "POST", "start", JSONObject().put("AuthKey", key))
                    prefs.edit().putString("envelopeHash", hash).apply()
                }
                "Registering this device"
            }
            "approved" -> "Connecting"
            else -> "Family registration unavailable"
        }
        } catch (e: Exception) {
            // Do not log exception messages, keys, envelopes, or authorization URLs.
            android.util.Log.e("FamilyEnrollment", "Failed at $stage: ${e.javaClass.simpleName}")
            throw e
        }
    }
    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        if (!EmbeddedTailnet.status.connected) return@withContext
        val ip = EmbeddedTailnet.status.address ?: return@withContext
        val result = request("/api/v1/sync", JSONObject().put("name", FamilyStore.nickname.take(36))
            .put("profileId", FamilyStore.deviceId).put("ip", ip))
        require(result.getString("state") == "ready")
        val array = result.getJSONArray("peers")
        require(array.length() <= 64)
        val peers = (0 until array.length()).map {
            val p = array.getJSONObject(it)
            Peer(p.getString("id"), p.getString("name"), p.getString("ip"), p.getString("secret"))
        }
        DirectorySecurity.validateRoster(peers, FamilyStore.deviceId)
        withContext(Dispatchers.Main) { FamilyStore.applyDirectory(peers) }
    }
}
