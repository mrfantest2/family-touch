package win.fantest.familytouch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ChatBackupPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val content = pending
        pending = null
        if (uri != null && content != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { ChatBackup.write(context, uri, content) }
                result = "Encrypted chat backup saved. Keep your passphrase safe."
            } catch (e: Exception) { result = "Backup not saved: " + (e.message ?: "Storage unavailable") }
            finally { busy = false; content.fill(0) }
        } else {
            content?.fill(0)
            busy = false
        }
    }
    val restore = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val secret = password.toCharArray()
            try {
                val items = withContext(Dispatchers.IO) {
                    ChatBackup.decrypt(ChatBackup.read(context, uri), secret)
                }
                val imported = FamilyStore.mergeBackupMessages(items)
                result = "Imported $imported new messages. Existing messages were preserved."
            } catch (_: javax.crypto.AEADBadTagException) {
                result = "Wrong passphrase or damaged backup."
            } catch (e: Exception) {
                result = "Import failed: " + (e.message ?: "Unreadable backup")
            } finally { secret.fill('\u0000'); busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Chat backup and restore", style = MaterialTheme.typography.titleMedium)
        Text("Encrypted chat history only. Files, photos, voice recordings, pairing credentials and your PIN are not included. " +
             "Choose Google Drive in Android's save/open picker for a cloud copy.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = password, onValueChange = { password = it.take(128) },
            label = { Text("Backup passphrase (8+ characters)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = !busy && password.length >= 8,
                modifier = Modifier.weight(1f), onClick = {
                    val snapshot = ChatBackup.snapshot()
                    val secret = password.toCharArray()
                    busy = true
                    scope.launch {
                        try {
                            pending = withContext(Dispatchers.IO) { ChatBackup.encrypt(snapshot, secret) }
                            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                            save.launch("FamilyTouch-Chats-$date.ftbackup")
                        } catch (e: Exception) {
                            result = "Export failed: " + (e.message ?: "Unknown error")
                            busy = false
                        } finally { secret.fill('\u0000') }
                    }
                }) { Text("Export") }
            OutlinedButton(enabled = !busy && password.length >= 8,
                modifier = Modifier.weight(1f), onClick = {
                    restore.launch(arrayOf("application/octet-stream", "*/*"))
                }) { Text("Import") }
        }
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)
    }
}
