package win.fantest.familytouch

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

internal data class LocalRecording(val id: String, val video: Boolean, val created: Long, val duration: Long,
    val names: String, val complete: Boolean, val recovered: Boolean)
internal object RecordingLibrary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    val recordings = mutableStateListOf<LocalRecording>()
    fun folder(context: Context) = File(context.filesDir, "recordings").apply { mkdirs() }
    @Synchronized fun init(context: Context) {
        if (initialized) return
        initialized = true
        scope.launch { recover(context); refresh(context) }
    }
    private fun metadata(context: Context, id: String): File {
        UUID.fromString(id); return File(folder(context), "$id.json")
    }
    fun pending(context: Context, request: RecordingRequest) {
        val names = request.participants.joinToString { if (it == FamilyStore.deviceId) FamilyStore.nickname else FamilyStore.find(it)?.name ?: "Family device" }
        metadata(context, request.id).writeText(JSONObject().put("id", request.id).put("video", request.video)
            .put("created", System.currentTimeMillis()).put("duration", 0).put("names", names)
            .put("completed", false).put("sessionId", request.sessionId)
            .put("consentingDevices", JSONArray(request.participants.toList())).toString())
    }
    fun complete(context: Context, request: RecordingRequest, temp: File, durationMs: Long) {
        if (!temp.renameTo(File(folder(context), request.id + if (request.video) ".mp4" else ".m4a"))) { refresh(context); return }
        val file = metadata(context, request.id)
        file.writeText(JSONObject(file.readText()).put("completed", true).put("duration", durationMs).toString())
        refresh(context)
    }
    private fun recover(context: Context) {
        folder(context).listFiles()?.filter { it.name.endsWith(".partial.mp4") }?.forEach { partial -> runCatching {
            val id = partial.name.removeSuffix(".partial.mp4")
            val meta = metadata(context, id); if (!meta.isFile) return@runCatching
            val extractor = MediaExtractor()
            var audio = false; var video = false; var durationUs = 0L
            try {
                extractor.setDataSource(partial.absolutePath)
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) audio = true
                    if (mime.startsWith("video/")) video = true
                    if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                }
            } finally { extractor.release() }
            val data = JSONObject(meta.readText())
            if (audio && (!data.getBoolean("video") || video) && durationUs > 0 && partial.renameTo(File(folder(context), id + if (data.getBoolean("video")) ".mp4" else ".m4a"))) {
                meta.writeText(data.put("completed", true).put("recovered", true).put("duration", durationUs / 1000).toString())
            }
            // Without a readable MP4 index, retain the file as interrupted, never label it playable.
        } }
    }
    fun refresh(context: Context) { scope.launch {
        val rows = folder(context).listFiles()?.filter { it.extension == "json" }?.mapNotNull { file -> runCatching {
            val data = JSONObject(file.readText()); val id = data.getString("id"); UUID.fromString(id)
            LocalRecording(id, data.getBoolean("video"), data.getLong("created"), data.optLong("duration"),
                data.optString("names"), data.optBoolean("completed") && File(folder(context), id + if (data.getBoolean("video")) ".mp4" else ".m4a").isFile, data.optBoolean("recovered"))
        }.getOrNull() }.orEmpty().sortedByDescending { it.created }
        withContext(Dispatchers.Main) { recordings.clear(); recordings.addAll(rows) }
    } }
    fun file(context: Context, row: LocalRecording): File {
        UUID.fromString(row.id)
        return File(folder(context), row.id + if (row.video) ".mp4" else ".m4a")
    }
    fun uri(context: Context, row: LocalRecording): Uri {
        UUID.fromString(row.id); require(row.complete)
        val file = file(context, row); require(file.isFile && file.length() > 0)
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }
    fun share(context: Context, row: LocalRecording) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (row.video) "video/mp4" else "audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri(context, row)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share call recording"))
    }
    fun delete(context: Context, row: LocalRecording) {
        UUID.fromString(row.id)
        listOf(".mp4", ".m4a", ".partial.mp4", ".json").forEach { File(folder(context), row.id + it).delete() }
        refresh(context)
    }
}

@Composable
internal fun RecordingLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<LocalRecording?>(null) }
    var deleting by remember { mutableStateOf<LocalRecording?>(null) }
    var sharing by remember { mutableStateOf<LocalRecording?>(null) }
    var sending by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { if (selected != null) selected = null else onBack() }
    selected?.let { row ->
        val file = RecordingLibrary.file(context, row)
        ChatMediaViewer(AttachmentTransport.Info(row.id, "Call recording", if (row.video) "video/mp4" else "audio/mp4", file.length()),
            onDismiss = { selected = null }, sourceUri = RecordingLibrary.uri(context, row), onShare = { sharing = row })
    }
    deleting?.let { row -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete recording from this phone?") },
        text = { Text("Only your local copy is removed. Previously shared copies are unaffected.") },
        confirmButton = { TextButton(onClick = { RecordingLibrary.delete(context, row); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    sharing?.let { row -> AlertDialog(onDismissRequest = { if (!sending) sharing = null }, title = { Text("Share recording") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("Choose a family chat. Files over 50 MiB can be shared through Android.")
            FamilyStore.contacts.forEach { peer -> TextButton(enabled = !sending, onClick = {
                sending = true; scope.launch {
                    try { AttachmentTransport.send(context, peer, listOf(RecordingLibrary.uri(context, row))) { FamilyStore.addChat(it) }; sharing = null }
                    catch (e: Exception) { FamilyStore.lastError = e.message ?: "Cannot share recording" }
                    finally { sending = false }
                }
            }) { Text(peer.name) } }
            GroupStore.groups.forEach { group -> TextButton(enabled = !sending, onClick = {
                sending = true; scope.launch {
                    try { GroupStore.sendMedia(group, listOf(RecordingLibrary.uri(context, row))); sharing = null }
                    catch (e: Exception) { FamilyStore.lastError = e.message ?: "Cannot share recording" }
                    finally { sending = false }
                }
            }) { Text(group.title + " (group)") } }
        } }, confirmButton = { TextButton(onClick = { RecordingLibrary.share(context, row); sharing = null }) { Text("Android share") } },
        dismissButton = { TextButton(enabled = !sending, onClick = { sharing = null }) { Text("Cancel") } }) }
    Column(Modifier.fillMaxSize()) {
        Row { TextButton(onClick = onBack) { Text("Back") }; Text("Local call recordings", Modifier.padding(12.dp)) }
        if (RecordingLibrary.recordings.isEmpty()) Text("No recordings on this phone. Request everyone's permission during a call to record.", Modifier.padding(20.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(RecordingLibrary.recordings.toList(), key = { it.id }) { row ->
                Column(Modifier.fillMaxWidth().clickable(enabled = row.complete) { selected = row }.padding(16.dp)) {
                    Text(if (row.complete) (if (row.video) "Video call" else "Audio call") else "Interrupted recording — not finalized")
                    Text(DateFormat.getDateTimeInstance().format(Date(row.created))); Text(row.names, maxLines = 2)
                    if (row.complete) Text("${row.duration / 1000}s · tap to play${if (row.recovered) " · recovered" else ""}")
                    Row {
                        if (row.complete) TextButton(onClick = { sharing = row }) { Text("Share") }
                        TextButton(onClick = { deleting = row }) { Text("Delete local copy") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

