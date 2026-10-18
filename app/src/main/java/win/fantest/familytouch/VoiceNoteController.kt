package win.fantest.familytouch

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

internal object VoiceNotePolicy {
    const val MAX_DURATION_MS = 300_000L
    fun canSend(durationMs: Long, bytes: Long) = durationMs in 1..MAX_DURATION_MS && bytes in 1..AttachmentTransport.MAX_BYTES
    fun audioMime(mime: String) = mime in setOf("audio/mp4", "audio/x-m4a", "audio/mpeg", "audio/ogg", "audio/wav", "audio/x-wav")
}

internal class VoiceNoteController(private val context: Context) {
    var recording by mutableStateOf(false); private set
    var durationMs by mutableLongStateOf(0L); private set
    var file by mutableStateOf<File?>(null); private set
    private var recorder: MediaRecorder? = null
    private var started = 0L
    fun start() {
        check(FamilyStore.activeCall == null && FamilyStore.incomingCall == null && GroupCallController.session == null &&
            GroupCallController.incoming == null) { "End the call before recording a voice note" }
        cancel()
        val folder = File(context.cacheDir, "voice-notes").apply { mkdirs() }
        file = File(folder, "voice-${UUID.randomUUID()}.m4a")
        @Suppress("DEPRECATION")
        val media = MediaRecorder()
        recorder = media
        try {
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioEncodingBitRate(64000)
            media.setAudioSamplingRate(48000)
            media.setMaxDuration(VoiceNotePolicy.MAX_DURATION_MS.toInt())
            media.setOutputFile(file!!.absolutePath)
            media.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop()
            }
            media.prepare(); media.start()
            started = SystemClock.elapsedRealtime(); durationMs = 0; recording = true
        } catch (e: Exception) { cancel(); throw e }
    }
    fun tick() { if (recording) durationMs = (SystemClock.elapsedRealtime() - started).coerceAtMost(VoiceNotePolicy.MAX_DURATION_MS) }
    fun stop() {
        if (!recording) return
        tick(); recording = false
        try { recorder?.stop() } catch (_: RuntimeException) { file?.delete(); file = null }
        finally { recorder?.release(); recorder = null }
    }
    fun uri(): Uri {
        check(!recording)
        val saved = requireNotNull(file) { "No voice note available" }
        require(VoiceNotePolicy.canSend(durationMs, saved.length())) { "Voice note is empty or too long" }
        return FileProvider.getUriForFile(context, context.packageName + ".files", saved)
    }
    fun cancel() {
        if (recording) runCatching { recorder?.stop() }
        recording = false; recorder?.release(); recorder = null
        file?.delete(); file = null; durationMs = 0
    }
}

@Composable
internal fun VoiceNoteDialog(onDismiss: () -> Unit, onSend: (Uri, (Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    val controller = remember { VoiceNoteController(context.applicationContext) }
    var failure by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var sending by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun cancel() { player?.release(); player = null; controller.cancel(); onDismiss() }
    DisposableEffect(lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { if (controller.recording) controller.cancel() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player?.release(); controller.cancel() }
    }
    LaunchedEffect(Unit) {
        try { controller.start() } catch (e: Exception) { failure = e.message ?: "Microphone unavailable" }
    }
    LaunchedEffect(controller.recording) { while (controller.recording) { controller.tick(); delay(200) } }
    LaunchedEffect(FamilyStore.incomingCall, FamilyStore.activeCall, GroupCallController.incoming, GroupCallController.session) {
        if (FamilyStore.incomingCall != null || FamilyStore.activeCall != null || GroupCallController.incoming != null || GroupCallController.session != null) cancel()
    }
    AlertDialog(onDismissRequest = { if (!sending) cancel() }, title = { Text("Voice note") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (controller.recording) "Recording · ${controller.durationMs / 1000}s / 300s" else "Preview · ${controller.durationMs / 1000}s")
            if (failure.isNotEmpty()) Text(failure)
            if (controller.recording) Button(onClick = { controller.stop() }) { Text("Stop recording") }
            else if (controller.file != null) OutlinedButton(enabled = !sending, onClick = {
                player?.release()
                player = MediaPlayer.create(context, Uri.fromFile(controller.file))?.apply { start() }
            }) { Text("Play preview") }
            Text("Stored on your phone. Shared only when you tap Send.")
        }
    }, confirmButton = {
        Button(enabled = !sending && !controller.recording && controller.file != null, onClick = {
            try {
                player?.release(); player = null
                val uri = controller.uri(); sending = true
                onSend(uri) { success ->
                    sending = false
                    if (success) cancel() else failure = "Transfer failed. Your voice note is ready to retry."
                }
            } catch (e: Exception) { sending = false; failure = e.message ?: "Cannot send voice note" }
        }) { Text(if (sending) "Sending…" else "Send") }
    }, dismissButton = { TextButton(enabled = !sending, onClick = { cancel() }) { Text("Cancel") } })
}
