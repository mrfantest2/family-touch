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
    private var watchJob: Job? = null
    private var cleanupJob: Job? = null
    private var engineReady = false
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork()
        override fun onLost(network: Network) = updateNetwork()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateNetwork()
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = updateNetwork()
    }
    private fun updateNetwork() {
        val network = selectedPhysicalNetwork(cm)
        setUnderlyingNetworks(network?.let { arrayOf(it) } ?: emptyArray())
        if (network == null && EmbeddedTailnet.status.connected)
            EmbeddedTailnet.status = VpnConnectionStatus("Waiting for internet")
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
            EmbeddedTailnet.stopping = true
            EmbeddedTailnet.status = VpnConnectionStatus("Disconnecting")
            getSharedPreferences("vpn", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            close()
            return START_NOT_STICKY
        }
        if (FamilyNetworkMode.selected(this) != ConnectionMode.BUILT_IN) { stopSelf(); return START_NOT_STICKY }
        if (prepare(this) != null) { EmbeddedTailnet.status = VpnConnectionStatus("VPN permission required"); stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("embedded_vpn", "Private family network", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, FamilyVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2401, NotificationCompat.Builder(this, "embedded_vpn").setSmallIcon(R.drawable.ic_family_touch)
            .setContentTitle("Family Touch private network").setContentText("Connecting")
            .setContentIntent(open).setOngoing(true).addAction(0, "Disconnect", stop).build())
        getSharedPreferences("vpn", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
        if (started) return START_STICKY
        started = true
        EmbeddedTailnet.serviceActive = true
        EmbeddedTailnet.stopping = false
        EmbeddedTailnet.status = VpnConnectionStatus("Connecting")
        watchJob = scope.launch {
            try {
                EmbeddedTailnet.engine(this@FamilyVpnService)
                engineReady = true
                FamilyStore.init(this@FamilyVpnService)
                ensureActive()
                Libtailscale.requestVPN(this@FamilyVpnService)
                ensureActive()
                updateNetwork()
                ensureActive()
                EmbeddedTailnet.api(this@FamilyVpnService, "PATCH", "prefs", JSONObject().put("WantRunning", true).put("WantRunningSet", true)
                    .put("Hostname", "familytouch-" + FamilyIdentity.fingerprint().take(12)).put("HostnameSet", true))
                ensureActive()
                var nextEnrollment = 0L
                var registered = false
                var registrationLabel = "Contacting family server"
                var authenticationStartedAt = 0L
                var previousBackend = ""
                while (isActive) {
                    val status = EmbeddedTailnet.api(this@FamilyVpnService, "GET", "status")
                    ensureActive()
                    val addresses = status.optJSONArray("TailscaleIPs")
                    val nodeIp = (0 until (addresses?.length() ?: 0)).map { addresses!!.optString(it) }
                        .firstOrNull { Tailnet.isTailIp(it) }
                    val backend = status.optString("BackendState")
                    val needsAuth = backend == "NeedsLogin" || backend == "NoState"
                    if (backend != previousBackend) {
                        // Log only a bounded status enum, never auth keys or URLs.
                        android.util.Log.i("FamilyVpnEnrollment", "Private backend state: " +
                            backend.take(24) + ", hasIP=" + (nodeIp != null))
                        previousBackend = backend
                    }
                    if (needsAuth && authenticationStartedAt == 0L)
                        authenticationStartedAt = System.currentTimeMillis()
                    if (!needsAuth) authenticationStartedAt = 0L
                    if ((!registered || needsAuth) && System.currentTimeMillis() >= nextEnrollment) {
                        nextEnrollment = System.currentTimeMillis() + 30000
                        registrationLabel = try {
                            FamilyDirectory.enroll(this@FamilyVpnService, needsAuth).also {
                                registered = it == "Connecting"
                            }
                        } catch (e: Exception) {
                            // Log status codes only. Never log authorization URLs, envelopes or keys.
                            val httpStatus = Regex("Registration HTTP ([0-9]{3})")
                                .find(e.message.orEmpty())?.groupValues?.get(1)
                            android.util.Log.e("FamilyVpnEnrollment", "Provisioning failed: " +
                                (httpStatus?.let { "HTTP $it" } ?: e.javaClass.simpleName))
                            if (httpStatus == "429") "Registration rate limited — retrying"
                            else if (httpStatus == "409") "Registration identity conflict — retrying"
                            else "Family registration unavailable — retrying"
                        }
                        ensureActive()
                    }
                    val displayedLabel = if (needsAuth &&
                        registrationLabel == "Registering this device" &&
                        authenticationStartedAt > 0L &&
                        System.currentTimeMillis() - authenticationStartedAt > 90000L)
                        "Private sign-in delayed — retrying automatically"
                    else registrationLabel
                    val next = if (needsAuth) VpnConnectionStatus(displayedLabel) else VpnConnectionStatus.fromBackend(backend,
                        EmbeddedTailnet.tunnelActive, nodeIp, Tailnet.vpnIp(this@FamilyVpnService),
                        selectedPhysicalNetwork(cm)?.let { cm.getNetworkCapabilities(it)
                            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true)
                    if (next != EmbeddedTailnet.status) {
                        EmbeddedTailnet.status = next
                        manager.notify(2401, NotificationCompat.Builder(this@FamilyVpnService, "embedded_vpn")
                            .setSmallIcon(R.drawable.ic_family_touch).setContentTitle("Family Touch private network")
                            .setContentText(next.label).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                            .addAction(0, "Disconnect", stop).build())
                    }
                    delay(3000)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { EmbeddedTailnet.status = VpnConnectionStatus("Connection failed — try again"); close() }
        }
        return START_STICKY
    }
    override fun id() = serviceId
    override fun updateVpnStatus(status: Boolean) {
        EmbeddedTailnet.tunnelActive = status
        if (!status && EmbeddedTailnet.status.connected) EmbeddedTailnet.status = VpnConnectionStatus("Reconnecting")
    }
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
    override fun disconnectVPN() { close() }
    override fun close() {
        synchronized(this) { if (closed) return; closed = true }
        watchJob?.cancel()
        EmbeddedTailnet.stopping = true
        cleanupJob = scope.launch {
            try {
                // Cancellation cannot interrupt JNI. Wait for startup to leave native code,
                // then apply the final stop so a late WantRunning=true cannot undo it.
                watchJob?.join()
                if (engineReady) {
                    runCatching {
                        EmbeddedTailnet.api(this@FamilyVpnService, "PATCH", "prefs",
                            JSONObject().put("WantRunning", false).put("WantRunningSet", true))
                    }
                    runCatching { Libtailscale.serviceDisconnect(this@FamilyVpnService) }
                }
            } finally {
                EmbeddedTailnet.tunnelActive = false
                EmbeddedTailnet.serviceActive = false
                if (EmbeddedTailnet.engineStatus.label in setOf("Disconnecting", "Connecting", "Reconnecting", "Waiting for internet") || EmbeddedTailnet.engineStatus.connected)
                    EmbeddedTailnet.status = VpnConnectionStatus("Disconnected")
                stopSelf()
            }
        }
    }
    override fun onRevoke() {
        getSharedPreferences("vpn", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        EmbeddedTailnet.status = VpnConnectionStatus("VPN permission revoked — another VPN may be active")
        close()
        super.onRevoke()
    }
    override fun onDestroy() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        close()
        // Native startup/stop may take longer than a fixed grace period.
        cleanupJob?.invokeOnCompletion {
            EmbeddedTailnet.stopping = false
            scope.cancel()
        }
        super.onDestroy()
    }
    companion object { const val STOP = "win.fantest.familytouch.STOP_VPN" }
}
