package win.fantest.familytouch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import libtailscale.AppContext
import libtailscale.Libtailscale
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

internal fun selectedPhysicalNetwork(cm: ConnectivityManager): android.net.Network? = cm.allNetworks
    .filter { cm.getNetworkCapabilities(it)?.let { c ->
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    } == true }
    .maxByOrNull { network ->
        val caps = cm.getNetworkCapabilities(network)
        (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 2 else 0) +
            (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) 1 else 0)
    }

/** Android platform adapter for the pinned upstream BSD-licensed engine. */
internal class TailscalePlatform(private val context: Context) : AppContext {
    private val prefs by lazy {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "embedded_tailnet_state", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    override fun encryptToPref(key: String, value: String) {
        check(prefs.edit().putString(key, value).commit()) { "Cannot persist encrypted node state" }
    }
    override fun decryptFromPref(key: String): String = prefs.getString(key, "") ?: ""
    override fun getStateStoreKeysJSON(): String = JSONArray(prefs.all.keys.filter { it.startsWith("statestore-") }.map { it.removePrefix("statestore-") }).toString()
    // Native diagnostics can contain authorization URLs; do not forward them to logcat.
    override fun log(tag: String, logLine: String) {}
    override fun getOSVersion(): String = Build.VERSION.RELEASE
    override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()
    override fun getDeviceName(): String = "FamilyTouch-${Build.MODEL}"
    override fun getInstallSource(): String = context.packageName
    override fun shouldUseGoogleDNSFallback() = false
    override fun isChromeOS() = false
    override fun isClientLoggingEnabled() = false
    override fun getInterfacesAsJson(): String {
        val result = JSONArray()
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { n ->
            runCatching {
                val addresses = JSONArray()
                n.interfaceAddresses.forEach { a -> a.address?.hostAddress?.let {
                    addresses.put(JSONObject().put("ip", it).put("prefixLen", a.networkPrefixLength.toInt()))
                } }
                result.put(JSONObject().put("name", n.name).put("index", n.index).put("mtu", n.mtu)
                    .put("up", n.isUp).put("broadcast", n.supportsMulticast()).put("loopback", n.isLoopback)
                    .put("pointToPoint", n.isPointToPoint).put("multicast", n.supportsMulticast()).put("addrs", addresses))
            }
        }
        return result.toString()
    }
    override fun getPlatformDNSConfig(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val props = underlying()?.let { cm.getLinkProperties(it) } ?: return ""
        return props.dnsServers.joinToString(" ") { it.hostAddress ?: "" } + "\n" + (props.domains ?: "")
    }
    fun underlying(): android.net.Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return selectedPhysicalNetwork(cm)
    }
    override fun bindSocketToNetwork(fd: Int): Boolean = runCatching {
        val network = underlying() ?: return false
        ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }; true
    }.getOrDefault(false)
    override fun getSyspolicyStringValue(key: String): String = throw java.io.IOException("No managed policy")
    override fun getSyspolicyBooleanValue(key: String): Boolean = throw java.io.IOException("No managed policy")
    override fun getSyspolicyStringArrayJSONValue(key: String): String = throw java.io.IOException("No managed policy")
    override fun hardwareAttestationKeySupported() = false
    override fun hardwareAttestationKeyCreate(): String = throw UnsupportedOperationException()
    override fun hardwareAttestationKeyRelease(id: String) = Unit
    override fun hardwareAttestationKeyPublic(id: String): ByteArray = throw UnsupportedOperationException()
    override fun hardwareAttestationKeySign(id: String, data: ByteArray): ByteArray = throw UnsupportedOperationException()
    override fun hardwareAttestationKeyLoad(id: String): Unit = throw UnsupportedOperationException()
    override fun getUserCACertsPEM(): ByteArray = byteArrayOf()
}

internal object EmbeddedTailnet {
    @Volatile private var engine: libtailscale.Application? = null
    @Volatile var status = VpnConnectionStatus("Disconnected")
    @Volatile var serviceActive = false
    @Volatile var stopping = false
    @Volatile var tunnelActive = false
    @Synchronized fun engine(context: Context): libtailscale.Application {
        return engine ?: Libtailscale.start(java.io.File(context.noBackupFilesDir, "tailnet").apply { mkdirs() }.absolutePath,
            "", false, TailscalePlatform(context.applicationContext)).also { engine = it }
    }
    fun api(context: Context, method: String, endpoint: String, body: JSONObject? = null): JSONObject {
        val bytes = body?.toString()?.toByteArray()
        val stream = bytes?.let { object : libtailscale.InputStream {
            var consumed = false
            override fun read(): ByteArray? { if (consumed) return null; consumed = true; return it }
            override fun close() {}
        } }
        val response = engine(context).callLocalAPI(15000, method, "/localapi/v0/$endpoint", stream)
        // Successful mutation endpoints (including start) return HTTP 204 with no body.
        val result = response.bodyBytes() ?: byteArrayOf()
        check(response.statusCode() in 200..299) { "Tailnet request failed (${response.statusCode()}); check provisioning and network" }
        return if (result.isEmpty()) JSONObject() else JSONObject(String(result))
    }
}
