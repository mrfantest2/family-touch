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

@Composable
internal fun VpnSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(EmbeddedTailnet.status) }
    var active by remember { mutableStateOf(EmbeddedTailnet.serviceActive) }
    var stopping by remember { mutableStateOf(EmbeddedTailnet.stopping) }
    var ownerSetup by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val provisioning = remember { DeviceProvisioning(context) }
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
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use {
                it.write(provisioning.request().toByteArray())
            } ?: error("Cannot write request") } }.onSuccess { notice = "Send this public request to your tailnet administrator" }
                .onFailure { notice = "Could not export request" }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        while (out.size() <= 4096) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 4097 - out.size()))
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    } ?: error("Cannot read provisioning file")
                    val key = provisioning.decrypt(bytes)
                    EmbeddedTailnet.api(context, "POST", "start", JSONObject().put("AuthKey", key))
                }
                notice = "Provisioning submitted. Connect the VPN to finish joining. Delete the provisioning file after use."
            } catch (_: Exception) { notice = "Provisioning rejected. Check device, expiry, and single-use key with the administrator." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { while (true) {
        status = EmbeddedTailnet.status
        active = EmbeddedTailnet.serviceActive
        stopping = EmbeddedTailnet.stopping
        delay(500)
    } }
    Text("Family network", style = MaterialTheme.typography.titleLarge)
    Text(status.label, color = if (status.connected) androidx.compose.ui.graphics.Color(0xFF5DDAA7)
        else MaterialTheme.colorScheme.onSurface)
    if (status.connected) Text("Private connection active • ${status.address}")
    if (status.label == "Family setup needed") {
        Text("This phone is not connected yet. The family organizer needs to finish network setup. Automatic family enrollment is not available in this build.")
    }
    Button(enabled = !busy && !stopping, onClick = {
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
    Text("Android asks for VPN permission once. Connecting replaces another active VPN on this phone.",
        style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { ownerSetup = !ownerSetup }) { Text(if (ownerSetup) "Hide owner setup" else "Owner setup") }
    if (ownerSetup) {
        Text("Owner tools for initial enrollment. These file controls are not automatic enrollment; they remain available while family setup is being completed.")
        OutlinedButton(enabled = !busy, onClick = { export.launch("Family-Touch-device-request.json") }) { Text("Export device request") }
        OutlinedButton(enabled = !busy, onClick = { import.launch(arrayOf("*/*")) }) { Text("Import encrypted provisioning") }
    }
    if (notice.isNotEmpty()) Text(notice)
}
