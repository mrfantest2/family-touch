package win.fantest.familytouch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

object Tailnet {
    const val PORT = 48321
    @Volatile private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext; EmbeddedTailnet.initialize(context) }

    /** Read one newline-terminated wire frame with a strict byte bound. */
    internal fun readWireLine(input: InputStream, maxBytes: Int = 131072): String {
        require(maxBytes > 0)
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            require(next >= 0) { "Peer disconnected mid-message" }
            if (next == 10) break
            require(bytes.size() < maxBytes) { "Oversized peer message" }
            bytes.write(next)
        }
        val data = bytes.toByteArray()
        val length = if (data.isNotEmpty() && data.last() == 13.toByte()) data.size - 1 else data.size
        return String(data, 0, length, Charsets.UTF_8)
    }

    fun isTailIp(ip: String): Boolean = DirectorySecurity.isPrivateIp(ip)

    fun vpnIp(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstNotNullOfOrNull { network ->
            if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return@firstNotNullOfOrNull null
            cm.getLinkProperties(network)?.linkAddresses?.firstNotNullOfOrNull { address ->
                val a = address.address
                if (a is Inet4Address && isTailIp(a.hostAddress.orEmpty())) a.hostAddress else null
            }
        }
    }

    fun invitation(context: Context): String? {
        val ip = vpnIp(context) ?: return null
        val data = JSONObject().put("v", 1).put("id", FamilyStore.deviceId)
            .put("ip", ip).put("nick", FamilyStore.nickname).put("secret", FamilyStore.pairingSecret)
        return "FT1:" + Base64.encodeToString(data.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun parseInvitation(code: String): Peer {
        require(code.startsWith("FT1:")) { "Invalid Family Touch invitation" }
        val decoded = String(Base64.decode(code.removePrefix("FT1:").trim(), Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        val json = JSONObject(decoded)
        require(json.getInt("v") == 1) { "Unsupported invitation version" }
        val ip = json.getString("ip")
        require(isTailIp(ip)) { "Peer must have a valid private network IPv4 address" }
        val id = json.getString("id")
        val secret = json.getString("secret")
        require(id.length in 20..80 && secret.length >= 32) { "Invalid invitation identity" }
        return Peer(id, json.getString("nick").take(36), ip, secret)
    }

    suspend fun send(peer: Peer, type: String, payload: JSONObject = JSONObject(), onWritten: (suspend () -> Unit)? = null): JSONObject = withContext(Dispatchers.IO) {
        require(isTailIp(peer.ip)) { "private network-only connections" }
        val ctx = requireNotNull(appContext) { "Family Touch networking is not initialized" }
        check(vpnIp(ctx) != null) { "Connect to private network before messaging or calling" }
        val req = JSONObject().put("type", type).put("id", FamilyStore.deviceId)
            .put("nick", FamilyStore.nickname).put("secret", peer.secret)
        for (key in payload.keys()) {
            require(key !in setOf("type", "id", "nick", "secret")) { "Reserved protocol field" }
            req.put(key, payload.get(key))
        }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(peer.ip, PORT), 4500)
            socket.soTimeout = 8000
            socket.getOutputStream().bufferedWriter(Charsets.UTF_8).apply { write(req.toString()); newLine(); flush() }
            onWritten?.invoke()
            val response = readWireLine(socket.getInputStream().buffered(), 32768)
            val result = JSONObject(response)
            if (!result.optBoolean("ok")) throw IllegalStateException(result.optString("error", "Request rejected"))
            result
        }
    }

    suspend fun pair(peer: Peer) {
        val result = send(peer, "pair", JSONObject().put("ip", peer.ip))
        require(result.getString("id") == peer.id) { "Identity mismatch: decline the invitation" }
        val grantedToken = result.getString("secret")
        require(grantedToken.length >= 40) { "Pairing response missing peer credential" }
        FamilyStore.upsert(peer.copy(name = result.optString("nick", peer.name), secret = grantedToken))
    }
}
