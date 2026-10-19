package win.fantest.familytouch

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun MediaGallery(peerId: String, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AttachmentTransport.Info?>(null) }
    val rows = FamilyStore.messages.filter { it.peerId == peerId }.mapNotNull { row ->
        AttachmentTransport.parse(row.text)?.takeIf { filter.isEmpty() || it.mime.startsWith(filter) }?.let { row to it }
    }.reversed()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
            Row { TextButton(onClick = onDismiss) { Text("Back") }; Text("Shared media", Modifier.padding(12.dp)) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All" to "", "Photos" to "image/", "Videos" to "video/", "Audio" to "audio/").forEach { (label, type) ->
                    FilterChip(selected = filter == type, onClick = { filter = type }, label = { Text(label) })
                }
            }
            if (rows.isEmpty()) Text("No shared media in this conversation yet.", Modifier.padding(16.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.first.id }) { (row, info) ->
                    Card(Modifier.fillMaxWidth().clickable { selected = info }) { Column(Modifier.padding(8.dp)) {
                        MediaThumbnail(info)
                        Text(info.name, maxLines = 2)
                        Text(DateFormat.getDateInstance().format(Date(row.time)), style = MaterialTheme.typography.labelSmall)
                    } }
                }
            }
        } }
    }
    selected?.let { ChatMediaViewer(it, onDismiss = { selected = null }) }
}
@Composable
private fun MediaThumbnail(info: AttachmentTransport.Info) {
    val context = LocalContext.current
    var bitmap by remember(info.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(info.id) {
        if (info.mime.startsWith("image/")) bitmap = withContext(Dispatchers.IO) { runCatching {
            val uri = AttachmentTransport.localUri(context, info)
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, source, _ ->
                val ratio = minOf(1.0, 256.0 / maxOf(source.size.width, source.size.height))
                decoder.setTargetSize(maxOf(1, (source.size.width * ratio).toInt()), maxOf(1, (source.size.height * ratio).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.asImageBitmap()
        }.getOrNull() }
    }
    if (bitmap != null) Image(bitmap!!, info.name, Modifier.fillMaxWidth().height(120.dp), contentScale = ContentScale.Fit)
    else Box(Modifier.fillMaxWidth().height(90.dp)) {
        Text(if (info.mime.startsWith("video/")) "▶ Video" else if (info.mime.startsWith("audio/")) "♫ Audio" else "Photo", Modifier.padding(12.dp))
    }
}
