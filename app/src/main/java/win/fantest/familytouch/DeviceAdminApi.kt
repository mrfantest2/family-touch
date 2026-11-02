package win.fantest.familytouch

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit

/** One enrolled Android Keystore key; the private key and admin password never enter the APK. */
internal object DeviceAdminApi {
    private const val HOST = "https://family-admin.fantest.win"
    private const val ALIAS = "familytouch_admin_device_v1"
    private const val ENROLL = "/admin/api/v1/device-enroll"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()

    private fun keyPair(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = store.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (entry != null) return KeyPair(entry.certificate.publicKey, entry.privateKey)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build())
        return generator.generateKeyPair()
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun keyId(pair: KeyPair): String =
        hex(MessageDigest.getInstance("SHA-256").digest(pair.public.encoded))
    suspend fun fingerprint(): String = withContext(Dispatchers.IO) { keyId(keyPair()) }

    private fun request(method: String, path: String, json: JSONObject? = null): JSONObject {
        val keys = keyPair()
        val raw = json?.toString()?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val digest = hex(MessageDigest.getInstance("SHA-256").digest(raw))
        val canonical = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + digest
        val signing = Signature.getInstance("SHA256withECDSA")
        signing.initSign(keys.private)
        signing.update(canonical.toByteArray(Charsets.UTF_8))
        val sig = Base64.encodeToString(signing.sign(), Base64.NO_WRAP)
        val builder = Request.Builder().url(HOST + path)
            .header("X-Requested-With", "FamilyTouchNative")
            .header("X-FT-KeyId", keyId(keys))
            .header("X-FT-Timestamp", timestamp)
            .header("X-FT-Nonce", nonce)
            .header("X-FT-Signature", sig)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        if (method == "GET") builder.get() else
            builder.post(raw.toRequestBody("application/json; charset=utf-8".toMediaType()))
        http.newCall(builder.build()).execute().use { response ->
            if (response.code == 401) throw IOException("This S25 requires a one-time MASTER-PC authorization.")
            if (response.code == 403) throw IOException("Device enrollment denied by MASTER-PC.")
            if (!response.isSuccessful) throw IOException("Server unavailable (HTTP " + response.code + ")")
            return JSONObject(response.body?.string()?.take(65536) ?: throw IOException("Empty response"))
        }
    }

    suspend fun register(): Boolean = withContext(Dispatchers.IO) {
        if (!Build.MANUFACTURER.equals("samsung", true) || Build.MODEL != "SM-S938B")
            throw IOException("Only the enrolled S25 can use Family Touch Admin.")
        val keys = keyPair()
        val body = JSONObject()
            .put("publicKey", Base64.encodeToString(keys.public.encoded, Base64.NO_WRAP))
            .put("model", Build.MODEL).put("label", "Family Touch Admin S25")
        request("POST", ENROLL, body).optString("state") == "approved"
    }

    suspend fun load(): Dashboard = withContext(Dispatchers.IO) {
        val result = request("GET", "/admin/api/v1/devices")
        val array = result.optJSONArray("devices") ?: throw IOException("Invalid device list")
        val list = buildList {
            for (i in 0 until array.length()) {
                val d = array.getJSONObject(i)
                add(DeviceRecord(d.getString("id"), d.optString("name", "Unknown"),
                    d.optString("model", ""), d.optBoolean("approved"), d.optBoolean("revoked")))
            }
        }
        Dashboard(list, result.optJSONArray("deleted")?.length() ?: 0, result.optBoolean("autoApprove"))
    }

    suspend fun update(device: DeviceRecord, operation: String) = withContext(Dispatchers.IO) {
        require(operation in setOf("approve", "revoke", "delete", "reauthorize"))
        val json = JSONObject().put("id", device.id).put("action", operation)
        if (!request("POST", "/admin/api/v1/device", json).optBoolean("ok"))
            throw IOException("Server did not confirm the change")
    }
}
