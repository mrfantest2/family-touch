package win.fantest.familytouch

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection

internal object FamilyDirectory {
    // One active registry writer. Standby promotion changes routing after fencing the primary.
    private const val publicEndpoint = "https://family-api.fantest.win"
    private const val privateEndpoint = "https://agentbox.tail46be8b.ts.net:18897"
    // External Tailscale may omit MagicDNS from Android VPN DNS settings.
    // Pin only the trusted AgentBox tailnet routing address, never bypass
    // HTTPS hostname verification. OkHttp maintains hostname SNI validation.
    private val privateHttp = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == "agentbox.tail46be8b.ts.net") {
                    listOf(InetAddress.getByAddress(byteArrayOf(100, 65, 177.toByte(), 100)))
                } else Dns.SYSTEM.lookup(hostname)
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private fun request(context: Context, path: String, json: JSONObject): JSONObject {
        // Connect privately once either VPN mode is online. Public registration
        // is retained for first-time built-in VPN provisioning (before login).
        // MASTER-PC replaced offline AgentBox. Every family device uses the
        // verified Cloudflare HTTPS API; never wait for the stale AgentBox tailnet IP.
        val endpoints = listOf(publicEndpoint)
        var failure: java.io.IOException? = null
        for (base in endpoints) {
            try { return requestAt(context, base, path, json) }
            catch (e: java.io.IOException) { failure = e }
        }
        throw failure ?: java.io.IOException("Family registration unavailable")
    }
    private fun requestAt(context: Context, base: String, path: String, json: JSONObject): JSONObject {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val device = FamilyIdentity.fingerprint()
        val time = (System.currentTimeMillis() / 1000).toString()
        val nonce = DirectorySecurity.hex(ByteArray(32).also { SecureRandom().nextBytes(it) })
        if (base == privateEndpoint) {
            val signed = FamilyIdentity.sign(DirectorySecurity.canonical("POST", path, device, time, nonce, bytes))
            val req = Request.Builder().url(base + path)
                .header("X-FT-Device", device).header("X-FT-Time", time)
                .header("X-FT-Nonce", nonce).header("X-FT-Signature", signed)
                .post(bytes.toRequestBody("application/json".toMediaType()))
                .build()
            privateHttp.newCall(req).execute().use { response ->
                if (response.code >= 500) throw java.io.IOException("Registration temporarily unavailable")
                if (response.code != 200) {
                    val reason = runCatching { response.peekBody(120).string().trim() }.getOrDefault("")
                    val safeReason = if (reason in setOf("forbidden", "address verification failed",
                            "private connection address mismatch", "address already registered",
                            "profile identity already registered", "profile identity cannot change")) reason else ""
                    error("Registration HTTP " + response.code + ": " + safeReason)
                }
                val responseBody = response.body ?: throw java.io.IOException("Missing private response")
                val data = responseBody.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (out.size() <= 65536) {
                        val n = input.read(buffer, 0, minOf(buffer.size, 65537 - out.size()))
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                require(data.size <= 65536)
                return JSONObject(String(data, Charsets.UTF_8))
            }
        }
        // Enrollment must travel over the physical underlay. This app is its own
        // per-app VPN client; default URL.openConnection() may route the HTTPS
        // bootstrap request *into the not-yet-authenticated VPN* and deadlock.
        // Network.openConnection keeps normal TLS hostname/certificate checks.
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val physical = selectedPhysicalNetwork(connectivity)
            ?: throw java.io.IOException("No physical internet network for enrollment")
        val connection = physical.openConnection(URL(base + path)) as HttpsURLConnection
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
        val result = request(context, "/api/v1/enroll", JSONObject()
            .put("publicKey", FamilyIdentity.publicKey()).put("rsaPublicKey", rsaRequest.getString("publicKey"))
            .put("rsaDeviceId", rsa.deviceId()).put("profileId", FamilyStore.deviceId)
            .put("name", FamilyStore.nickname.take(36)).put("model", Build.MODEL.take(80))
            // Auth keys are single-use. If the backend still NeedsLogin after a
            // start attempt, acknowledge the last envelope by hash so the broker
            // can rotate the consumed key after its minimum issuance interval.
            // Never send the key, private device identity or plaintext credential.
            .put("needsAuth", needsAuth).put("previousEnvelopeHash",
                prefs.getString("envelopeHash", "") ?: ""))
        return when (result.getString("state")) {
            "pending" -> "Waiting for family approval"
            "credential" -> {
                stage = "decrypt credential"
                val envelope = result.getString("envelope")
                val hash = DirectorySecurity.hex(MessageDigest.getInstance("SHA-256").digest(envelope.toByteArray(Charsets.UTF_8)))
                // After process/VPN replacement the previous credential may already be
                // marked as applied while the private engine needs authentication.
                // A cached envelope must be replayed when backend state is NeedsLogin.
                if (needsAuth || hash != prefs.getString("envelopeHash", "")) {
                    val key = rsa.decrypt(envelope.toByteArray(Charsets.UTF_8))
                    stage = "start private network"
                    // Persist only this non-secret envelope digest before start:
                    // even if localapi consumes a one-time key and then throws,
                    // the next signed attempt must not loop on the same key.
                    prefs.edit().putString("envelopeHash", hash).apply()
                    EmbeddedTailnet.api(context, "POST", "start", JSONObject().put("AuthKey", key))
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
        val result = request(context, "/api/v1/sync", JSONObject().put("name", FamilyStore.nickname.take(36))
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
