package win.fantest.familytouch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val tailscalePackage = "com.tailscale.ipn"
private fun installed(context: Context) = runCatching {
    context.packageManager.getPackageInfo(tailscalePackage, 0); true
}.getOrDefault(false)

/** External build adapter. Contains no native engine and cannot acquire Android VPN ownership. */
internal object EmbeddedTailnet {
    @Volatile private var context: Context? = null
    fun initialize(value: Context) { context = value.applicationContext }
    val status: VpnConnectionStatus get() {
        val value = context ?: return VpnConnectionStatus("Checking Tailscale connection")
        return ExternalVpnPolicy.status(installed(value), Tailnet.vpnIp(value))
    }
    fun api(context: Context, method: String, endpoint: String, body: JSONObject? = null): JSONObject =
        error("Embedded VPN is unavailable in the external build")
}

internal fun resumeFamilyNetwork(context: Context) { EmbeddedTailnet.initialize(context) }

@Composable
internal fun VpnSettings() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(EmbeddedTailnet.status) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { status = EmbeddedTailnet.status; delay(1000) } }
    Text("External family network", style = MaterialTheme.typography.titleLarge)
    Text(status.label)
    status.address?.let { Text("Private VPN address: $it") }
    Button(onClick = {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(tailscalePackage)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn"))
            context.startActivity(intent)
        } catch (_: Exception) { notice = "Open Tailscale manually, connect, then return here." }
    }) { Text(if (installed(context)) "Open Tailscale" else "Install Tailscale") }
    Text("Connect Tailscale to the same family network, then return here. Disconnect the embedded Family Touch VPN first if you use both versions. Android allows one VPN at a time.")
    if (notice.isNotEmpty()) Text(notice)
}
