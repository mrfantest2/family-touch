package win.fantest.familytouch

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** In-chat voice playback; no external viewer or unexpected auto-play. */
@Composable
internal fun VoiceMessagePlayer(info: AttachmentTransport.Info, tint: Color) {
    val context = LocalContext.current
    var player by remember(info.id) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(info.id) { mutableStateOf(false) }
    var position by remember(info.id) { mutableIntStateOf(0) }
    var duration by remember(info.id) { mutableIntStateOf(0) }
    var error by remember(info.id) { mutableStateOf(false) }
    DisposableEffect(info.id) {
        onDispose { player?.release(); player = null }
    }
    LaunchedEffect(playing, player) {
        while (playing) {
            val p = player ?: break
            position = runCatching { p.currentPosition }.getOrDefault(0)
            delay(250)
        }
    }
    Column(Modifier.widthIn(min = 190.dp, max = 255.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !error, onClick = {
                try {
                    val p = player ?: MediaPlayer.create(context, AttachmentTransport.localUri(context, info))
                        ?.also { created ->
                            duration = created.duration.coerceAtLeast(1)
                            created.setOnCompletionListener { playing = false; position = 0; created.seekTo(0) }
                            player = created
                        } ?: throw IllegalStateException("Unsupported audio")
                    if (p.isPlaying) { p.pause(); playing = false }
                    else { p.start(); playing = true }
                } catch (_: Exception) { error = true; playing = false }
            }) { Text(if (playing) "Pause" else "Play", color = tint) }
            Text("${position / 60000}:${(position / 1000) % 60}", color = tint,
                style = MaterialTheme.typography.labelSmall)
        }
        if (duration > 0) {
            Slider(value = position.coerceIn(0, duration).toFloat(),
                onValueChange = { target ->
                    position = target.toInt()
                    runCatching { player?.seekTo(position) }
                }, valueRange = 0f..duration.toFloat(), modifier = Modifier.fillMaxWidth())
        } else Text(if (error) "Voice note unavailable" else "Voice message", color = tint,
            style = MaterialTheme.typography.labelSmall)
    }
}
