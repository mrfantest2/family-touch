package win.fantest.familytouch

import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin

/** An original, deterministic waveform based on the note ID. Not actual audio analysis. */
@Composable
internal fun VoiceMessagePlayer(
    info: AttachmentTransport.Info,
    tint: Color,
    senderName: String = "Family member",
    senderPhoto: String? = null
) {
    val context = LocalContext.current
    var player by remember(info.id) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(info.id) { mutableStateOf(false) }
    var position by remember(info.id) { mutableIntStateOf(0) }
    var duration by remember(info.id) { mutableIntStateOf(0) }
    var error by remember(info.id) { mutableStateOf(false) }
    DisposableEffect(info.id) { onDispose { player?.release(); player = null } }
    LaunchedEffect(info.id) {
        val measured = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, AttachmentTransport.localUri(context, info))
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                } finally { retriever.release() }
            }.getOrDefault(0)
        }
        if (measured > 0) duration = measured
    }
    LaunchedEffect(playing, player) {
        while (playing) {
            val p = player ?: break
            position = runCatching { p.currentPosition }.getOrDefault(0)
            delay(200)
        }
    }
    fun stamp(ms: Int): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, (ms.coerceAtLeast(0) / 1000) % 60)
    Row(Modifier.widthIn(min = 218.dp, max = 294.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.BottomEnd) {
            Avatar(senderName, senderPhoto, 36.dp)
            Box(Modifier.size(15.dp).background(Color(0xFF00A884), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Mic, "Voice note", Modifier.size(11.dp), tint = Color.White)
            }
        }
        IconButton(enabled = !error, modifier = Modifier.size(34.dp), onClick = {
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
        }) {
            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                if (playing) "Pause voice message" else "Play voice message",
                tint = tint, modifier = Modifier.size(29.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val played = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val rawSeed = abs(info.id.hashCode() % 997)
            Canvas(Modifier.fillMaxWidth().height(27.dp).pointerInput(duration) {
                detectTapGestures { tap ->
                    if (duration > 0) {
                        position = ((tap.x / size.width).coerceIn(0f, 1f) * duration).toInt()
                        runCatching { player?.seekTo(position) }
                    }
                }
            }) {
                val count = 34
                val distance = size.width / count
                for (i in 0 until count) {
                    val wave = abs(sin((i * 1.37 + rawSeed / 13.0))) * .7 + abs(sin(i * .51)) * .3
                    val h = (size.height * (.2 + wave * .74)).toFloat()
                    val x = (i + .5f) * distance
                    drawLine(if (i.toFloat() / count < played) Color(0xFF53BDA9) else tint.copy(alpha = .42f),
                        Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Text(if (error) "Audio unavailable" else stamp(if (playing) position else if (duration > 0) duration else position),
                fontSize = 11.sp, color = tint.copy(alpha = .7f))
        }
    }
}
