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
    var state by remember { mutableStateOf(EmbeddedTailnet.state) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val provisioning = remember { DeviceProvisioning(context) }
    fun start() { ContextCompat.startForegroundService(context, Intent(context, FamilyVpnService::class.java)) }
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
    LaunchedEffect(Unit) { while (true) { state = EmbeddedTailnet.state; delay(1000) } }
    Text("Embedded private network", style = MaterialTheme.typography.titleLarge)
    Text(state)
    Text("Android allows one active VPN. Connecting Family Touch replaces the current VPN for this profile. Only Family Touch traffic uses this tunnel.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = {
            val intent = VpnService.prepare(context)
            if (intent != null) consent.launch(intent) else start()
        }) { Text("Connect") }
        OutlinedButton(onClick = { context.startService(Intent(context, FamilyVpnService::class.java).setAction(FamilyVpnService.STOP)) }) { Text("Disconnect") }
    }
    Text("First setup: export this device's public request, then import the encrypted file supplied privately by your tailnet administrator. No Tailscale login screen or separate app is needed.")
    OutlinedButton(enabled = !busy, onClick = { export.launch("Family-Touch-device-request.json") }) { Text("Export device request") }
    OutlinedButton(enabled = !busy, onClick = { import.launch(arrayOf("*/*")) }) { Text("Import encrypted provisioning") }
    if (notice.isNotEmpty()) Text(notice)
}
