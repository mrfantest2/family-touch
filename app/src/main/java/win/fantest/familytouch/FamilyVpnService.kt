package win.fantest.familytouch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.*
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import libtailscale.Libtailscale
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID

class FamilyVpnService : VpnService(), libtailscale.IPNService {
    private val serviceId = UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closed = false
    private var started = false
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork()
        override fun onLost(network: Network) = updateNetwork()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateNetwork()
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = updateNetwork()
    }
    private fun updateNetwork() {
        val network = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.let { c ->
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true }
        setUnderlyingNetworks(network?.let { arrayOf(it) } ?: emptyArray())
        if (started) {
            val props = network?.let { cm.getLinkProperties(it) }
            Libtailscale.onGatewayChanged(props?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: "")
            Libtailscale.onDNSConfigChanged(props?.interfaceName ?: "")
        }
    }
    override fun onCreate() {
        super.onCreate()
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            EmbeddedTailnet.state = "Disconnected"
            getSharedPreferences("vpn", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            scope.launch { runCatching { EmbeddedTailnet.api(this@FamilyVpnService, "PATCH", "prefs", JSONObject().put("WantRunning", false).put("WantRunningSet", true)) }; close() }
            return START_NOT_STICKY
        }
        if (prepare(this) != null) { EmbeddedTailnet.state = "VPN permission required"; stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("embedded_vpn", "Private family network", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, FamilyVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2401, NotificationCompat.Builder(this, "embedded_vpn").setSmallIcon(R.drawable.ic_family_touch)
            .setContentTitle("Family Touch private network").setContentText("Embedded Tailscale is active")
            .setContentIntent(open).setOngoing(true).addAction(0, "Disconnect", stop).build())
        getSharedPreferences("vpn", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
        if (started) return START_STICKY
        started = true
        EmbeddedTailnet.state = "Connecting"
        scope.launch {
            try {
                EmbeddedTailnet.engine(this@FamilyVpnService)
                Libtailscale.requestVPN(this@FamilyVpnService)
                updateNetwork()
                EmbeddedTailnet.api(this@FamilyVpnService, "PATCH", "prefs", JSONObject().put("WantRunning", true).put("WantRunningSet", true))
                while (isActive) {
                    val status = EmbeddedTailnet.api(this@FamilyVpnService, "GET", "status")
                    EmbeddedTailnet.state = when (val state = status.optString("BackendState")) {
                        "NeedsLogin" -> "Import a device provisioning file in Settings"
                        "NeedsMachineAuth" -> "Waiting for tailnet administrator approval"
                        "Running" -> if (EmbeddedTailnet.tunnelActive) "Connected" else "Establishing VPN"
                        else -> state.ifEmpty { "Connecting" }
                    }
                    delay(3000)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { EmbeddedTailnet.state = "Connection failed; reconnect or provision this device"; close() }
        }
        return START_STICKY
    }
    override fun id() = serviceId
    override fun updateVpnStatus(status: Boolean) { EmbeddedTailnet.tunnelActive = status }
    override fun newBuilder(): libtailscale.VPNServiceBuilder {
        val builder = Builder().setSession("Family Touch").setMtu(1280).setMetered(false)
            .addAllowedApplication(packageName)
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        return object : libtailscale.VPNServiceBuilder {
            override fun setMTU(mtu: Int) { builder.setMtu(mtu) }
            override fun addDNSServer(address: String) { builder.addDnsServer(address) }
            override fun addSearchDomain(domain: String) { builder.addSearchDomain(domain) }
            override fun addRoute(address: String, prefix: Int) { builder.addRoute(address, prefix) }
            override fun excludeRoute(address: String, prefix: Int) {
                if (Build.VERSION.SDK_INT >= 33) builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
            }
            override fun addAddress(address: String, prefix: Int) { builder.addAddress(address, prefix) }
            override fun establish(): libtailscale.ParcelFileDescriptor {
                val fd = checkNotNull(builder.establish()) { "VPN authorization was revoked" }
                return object : libtailscale.ParcelFileDescriptor { override fun detach(): Int = fd.detachFd() }
            }
        }
    }
    override fun disconnectVPN() { stopSelf() }
    override fun close() {
        synchronized(this) { if (closed) return; closed = true }
        if (started) scope.launch { Libtailscale.serviceDisconnect(this@FamilyVpnService) }
        EmbeddedTailnet.tunnelActive = false
        stopSelf()
    }
    override fun onRevoke() {
        getSharedPreferences("vpn", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        EmbeddedTailnet.state = "VPN permission revoked — another VPN may be active"
        close()
        super.onRevoke()
    }
    override fun onDestroy() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        close()
        // Deliver the disconnect to Go before cancelling this service's coroutine scope.
        scope.launch { delay(250); scope.cancel() }
        super.onDestroy()
    }
    companion object { const val STOP = "win.fantest.familytouch.STOP_VPN" }
}
