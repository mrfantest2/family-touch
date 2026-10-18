package win.fantest.familytouch

import android.graphics.ImageDecoder
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChatMediaViewer(info: AttachmentTransport.Info, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var error by remember(info.id) { mutableStateOf<String?>(null) }
    val uri = remember(info.id) { runCatching { AttachmentTransport.localUri(context, info) }
        .onFailure { error = "File is missing or incomplete" }.getOrNull() }
    var bitmap by remember(info.id) { mutableStateOf<ImageBitmap?>(null) }
    var scale by remember(info.id) { mutableFloatStateOf(1f) }
    var offset by remember(info.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    LaunchedEffect(uri) {
        if (uri != null && info.mime.startsWith("image/")) {
            try {
                bitmap = withContext(Dispatchers.IO) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, source, _ ->
                        val ratio = minOf(1.0, 2048.0 / maxOf(source.size.width, source.size.height))
                        d.setTargetSize(maxOf(1, (source.size.width * ratio).toInt()),
                            maxOf(1, (source.size.height * ratio).toInt()))
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }.asImageBitmap()
                }
            } catch (_: Exception) { error = "Cannot decode this image" }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF071424)).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Back") }
                Text(info.name, Modifier.weight(1f).padding(12.dp), maxLines = 2)
                TextButton(onClick = { runCatching { AttachmentTransport.share(context, info) }
                    .onFailure { error = "No compatible sharing app" } }) { Text("Share") }
            }
            if (error != null) Text(error!!, Modifier.padding(16.dp))
            else if (info.mime.startsWith("image/")) {
                val transform = rememberTransformableState { zoom, pan, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                }
                bitmap?.let { Image(it, info.name, Modifier.weight(1f).fillMaxWidth()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .transformable(transform), contentScale = ContentScale.Fit) }
                    ?: CircularProgressIndicator(Modifier.padding(24.dp))
            } else if (uri != null) {
                val player = remember(info.id) { VideoView(context) }
                DisposableEffect(player) { onDispose { player.stopPlayback() } }
                AndroidView(factory = {
                    player.apply {
                        setMediaController(MediaController(context).also { it.setAnchorView(this) })
                        setOnErrorListener { _, _, _ -> error = "This device cannot play this media"; true }
                        setOnPreparedListener { start() }
                        setVideoURI(uri)
                    }
                }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
