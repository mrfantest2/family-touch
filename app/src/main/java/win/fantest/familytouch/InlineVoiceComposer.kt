package win.fantest.familytouch

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Inline chat composer, no modal. Stop, review, scrub, send or discard. */
@Composable
internal fun InlineVoiceComposer(onDismiss: () -> Unit, onSend: (Uri, (Boolean) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember { VoiceNoteController(ctx.applicationContext) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var playbackMs by remember { mutableIntStateOf(0) }
    var playerDuration by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }
    fun disposePlayer() {
        player?.release()
        player = null
        playing = false
        playbackMs = 0
    }
    fun cancel() {
        if (busy) return
        disposePlayer()
        controller.cancel()
        onDismiss()
    }
    DisposableEffect(lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Never leave microphone capture running without an active, visible composer.
                if (controller.recording) {
                    controller.cancel()
                    onDismiss()
                }
                disposePlayer()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            disposePlayer()
            controller.cancel()
        }
    }
    LaunchedEffect(Unit) {
        try { controller.start() }
        catch (e: Exception) { failure = e.message ?: "Microphone unavailable" }
    }
    LaunchedEffect(controller.recording, controller.paused) {
        while (controller.recording) {
            controller.tick()
            if (controller.durationMs >= VoiceNotePolicy.MAX_DURATION_MS) controller.stop()
            delay(180)
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            playbackMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(180)
        }
    }
    LaunchedEffect(FamilyStore.incomingCall, FamilyStore.activeCall,
        GroupCallController.incoming, GroupCallController.session) {
        if (FamilyStore.activeCall != null || FamilyStore.incomingCall != null ||
            GroupCallController.incoming != null || GroupCallController.session != null) cancel()
    }
    fun preview() {
        try {
            if (controller.recording) controller.stop()
            if (player == null) {
                val file = requireNotNull(controller.file) { "No voice note recorded" }
                val p = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    setOnCompletionListener { done ->
                        playing = false
                        playbackMs = 0
                        done.seekTo(0)
                    }
                }
                player = p
                playerDuration = p.duration.coerceAtLeast(1)
            }
            player?.let { p ->
                if (p.isPlaying) { p.pause(); playing = false }
                else { p.start(); playing = true }
            }
        } catch (e: Exception) { failure = e.message ?: "Cannot preview recording" }
    }
    fun send() {
        try {
            if (controller.recording) controller.stop()
            disposePlayer()
            val uri = controller.uri()
            busy = true
            onSend(uri) { success ->
                busy = false
                if (success) { controller.cancel(); onDismiss() }
                else failure = "Transfer failed. You may retry or cancel."
            }
        } catch (e: Exception) {
            busy = false
            failure = e.message ?: "Voice note unavailable"
        }
    }
    Row(Modifier.fillMaxWidth().background(Color(0xFF202C33),
        RoundedCornerShape(26.dp)).padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { cancel() }, enabled = !busy) {
            Icon(Icons.Outlined.DeleteOutline, "Discard recording", tint = Color(0xFFE99090))
        }
        Column(Modifier.weight(1f)) {
            val status = when {
                controller.recording && controller.paused -> "Paused"
                controller.recording -> "Recording"
                else -> "Review voice note"
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Mic, null, Modifier.size(16.dp),
                    tint = if (controller.recording && !controller.paused)
                        Color(0xFFE8858B) else Color(0xFF8FBFB1))
                Text(status, fontSize = 12.sp, color = Color(0xFFE9EDEF))
                Spacer(Modifier.weight(1f))
                Text("%d:%02d".format(controller.durationMs / 60000L,
                    (controller.durationMs / 1000L) % 60L),
                    color = Color(0xFFABCDD0), fontSize = 12.sp)
            }
            if (!controller.recording && controller.file != null) {
                Slider(value = (playbackMs.toFloat() / playerDuration).coerceIn(0f, 1f),
                    onValueChange = { value ->
                        val pos = (value * playerDuration).toInt()
                        playbackMs = pos
                        runCatching { player?.seekTo(pos) }
                    }, modifier = Modifier.fillMaxWidth().height(24.dp))
            }
            if (failure.isNotBlank()) Text(failure, color = Color(0xFFFFA5A5),
                fontSize = 11.sp, maxLines = 2)
        }
        when {
            controller.recording -> {
                IconButton(enabled = !busy, onClick = {
                    try {
                        if (controller.paused) controller.resume() else controller.pause()
                    } catch (e: Exception) { failure = e.message ?: "Cannot pause recording" }
                }) {
                    Icon(if (controller.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        if (controller.paused) "Resume recording" else "Pause recording",
                        tint = Color(0xFF87E4C2))
                }
                IconButton(enabled = !busy, onClick = { controller.stop() }) {
                    Icon(Icons.Outlined.Stop, "Stop and review", tint = Color(0xFF87E4C2))
                }
            }
            controller.file != null -> {
                IconButton(enabled = !busy, onClick = { preview() }) {
                    Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (playing) "Pause preview" else "Listen to recording",
                        tint = Color(0xFF87E4C2))
                }
                IconButton(enabled = !busy, onClick = { send() }) {
                    Icon(Icons.Outlined.Send, "Send voice note", tint = Color(0xFF00A884))
                }
            }
        }
    }
}
