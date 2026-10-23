package win.fantest.familytouch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun resumeFamilyNetwork(context: Context) {
    if (FamilyNetworkMode.selected(context) == ConnectionMode.BUILT_IN &&
        context.getSharedPreferences("vpn", Context.MODE_PRIVATE).getBoolean("enabled", false) && VpnService.prepare(context) == null)
        ContextCompat.startForegroundService(context, Intent(context, FamilyVpnService::class.java))
}

@Composable
internal fun VpnSettings() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(EmbeddedTailnet.status) }
    var active by remember { mutableStateOf(EmbeddedTailnet.serviceActive) }
    var stopping by remember { mutableStateOf(EmbeddedTailnet.stopping) }
    var notice by remember { mutableStateOf("") }
    fun start() {
        try {
            EmbeddedTailnet.status = VpnConnectionStatus("Connecting")
            EmbeddedTailnet.serviceActive = true
            ContextCompat.startForegroundService(context, Intent(context, FamilyVpnService::class.java))
        } catch (_: Exception) {
            EmbeddedTailnet.serviceActive = false
            EmbeddedTailnet.status = VpnConnectionStatus("Unable to start — try again")
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) start() else notice = "VPN permission was declined"
    }
    LaunchedEffect(Unit) {
        val preferences = context.getSharedPreferences("vpn", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("initialConsentRequested", false)) {
            preferences.edit().putBoolean("initialConsentRequested", true).apply()
            if (!EmbeddedTailnet.serviceActive) {
                val intent = VpnService.prepare(context)
                if (intent != null) consent.launch(intent) else start()
            }
        }
    }
    LaunchedEffect(Unit) { while (true) {
        status = EmbeddedTailnet.status
        active = EmbeddedTailnet.serviceActive
        stopping = EmbeddedTailnet.stopping
        delay(500)
    } }
    Text("Family network", style = MaterialTheme.typography.titleLarge)
    val inCall = FamilyStore.activeCall != null || FamilyStore.incomingCall != null ||
        GroupCallController.session != null || GroupCallController.incoming != null
    Text("Built-in private VPN", style = MaterialTheme.typography.titleMedium)
    Text("Family Touch manages its own private connection. No separate VPN app needed.",
        style = MaterialTheme.typography.bodySmall)
    Text(status.label, color = if (status.connected) androidx.compose.ui.graphics.Color(0xFF5DDAA7)
        else MaterialTheme.colorScheme.onSurface)
    if (status.connected) Text("Private connection active • ${status.address}")
    Button(enabled = !stopping, onClick = {
        if (active) {
            EmbeddedTailnet.stopping = true
            EmbeddedTailnet.status = VpnConnectionStatus("Disconnecting")
            try { context.startService(Intent(context, FamilyVpnService::class.java).setAction(FamilyVpnService.STOP)) }
            catch (_: Exception) { EmbeddedTailnet.stopping = false; notice = "Unable to disconnect — try again" }
        } else {
            val intent = VpnService.prepare(context)
            if (intent != null) consent.launch(intent) else start()
        }
    }) { Text(if (active) "Disconnect" else "Connect") }
    Text("Android may request VPN permission; only one VPN can run at a time.",
        style = MaterialTheme.typography.bodySmall)
    if (notice.isNotEmpty()) Text(notice)
}
