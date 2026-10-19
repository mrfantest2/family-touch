package win.fantest.familytouch

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection

internal object FamilyDirectory {
    // One active registry writer. Standby promotion changes routing after fencing the primary.
    private const val publicEndpoint = "https://family-api.fantest.win"
    private const val privateEndpoint = "https://agentbox.tail46be8b.ts.net:18897"
    private fun request(path: String, json: JSONObject): JSONObject {
        // Connect privately once either VPN mode is online. Public registration
        // is retained for first-time built-in VPN provisioning (before login).
        val endpoints = if (EmbeddedTailnet.status.connected)
            listOf(privateEndpoint, publicEndpoint) else listOf(publicEndpoint)
        var failure: java.io.IOException? = null
        for (base in endpoints) {
            try { return requestAt(base, path, json) }
            catch (e: java.io.IOException) { failure = e }
        }
        throw failure ?: java.io.IOException("Family registration unavailable")
    }
    private fun requestAt(base: String, path: String, json: JSONObject): JSONObject {
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
            val code = connection.responseCode
            if (code >= 500) throw java.io.IOException("Registration temporarily unavailable")
            if (code != 200) {
                val reason = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText().take(120).trim() }
                }.getOrNull().orEmpty()
                // Never display authorization or credential responses from the server.
                val safeReason = if (reason in setOf("forbidden", "address verification failed",
                        "private connection address mismatch", "address already registered",
                        "profile identity already registered", "profile identity cannot change")) reason else ""
                error("Registration HTTP $code: $safeReason")
            }
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
    /** Safe refresh for automatic polling and Contacts; no credentials are shown. */
    suspend fun refresh(context: Context) {
        try { sync(context) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val label = when {
                e is java.io.IOException -> "Family directory unavailable - retrying automatically"
                e.message?.contains("address verification failed") == true ->
                    "Family peer port is blocked by your VPN access policy - retrying"
                e.message?.contains("private connection address mismatch") == true ->
                    "VPN address changed - reconnect the private network"
                e.message?.contains("HTTP 403") == true ->
                    "Family registration not authorized - retrying automatically"
                e.message?.contains("HTTP 409") == true -> "Family registration conflict - check other device profiles"
                else -> "Could not refresh registered devices - retrying automatically"
            }
            withContext(Dispatchers.Main) { FamilyStore.updateDirectoryStatus(label) }
        }
    }
    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        if (!EmbeddedTailnet.status.connected) {
            withContext(Dispatchers.Main) { FamilyStore.updateDirectoryStatus("Private network disconnected - connect to discover devices") }
            return@withContext
        }
        // Both VPN modes share one Android Keystore identity and one approval
        // roster. Re-enrollment without an auth-key request is idempotent and
        // recovers automatically if the broker registry was restored or reset.
        val enrollment = enroll(context, false)
        if (enrollment != "Connecting") {
            withContext(Dispatchers.Main) { FamilyStore.updateDirectoryStatus(enrollment) }
            return@withContext
        }
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
        withContext(Dispatchers.Main) {
            FamilyStore.applyDirectory(peers)
            FamilyStore.updateDirectoryStatus("Directory updated - " + peers.size + " other registered device(s)")
        }
    }
}
