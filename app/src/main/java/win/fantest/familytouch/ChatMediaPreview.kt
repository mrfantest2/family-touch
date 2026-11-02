package win.fantest.familytouch

import android.graphics.ImageDecoder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode only local authenticated attachments, bounded in image dimensions. No network image fetch. */
@Composable
internal fun ChatImageThumbnail(info: AttachmentTransport.Info, size: Dp = 210.dp,
                                 modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(info.id) { mutableStateOf<ImageBitmap?>(null) }
    var unavailable by remember(info.id) { mutableStateOf(false) }
    LaunchedEffect(info.id, info.size) {
        val value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = AttachmentTransport.localUri(context, info)
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, source, _ ->
                    val scale = minOf(1.0, 512.0 / maxOf(source.size.width, source.size.height))
                    decoder.setTargetSize(maxOf(1, (source.size.width * scale).toInt()),
                        maxOf(1, (source.size.height * scale).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }
        }
        bitmap = value.getOrNull()
        unavailable = value.isFailure
    }
    Box(modifier.size(size).background(Color(0xFF31424A), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, info.name, Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop)
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Image, "Image preview", tint = Color(0xFFB5C6CF))
            if (unavailable) Text("Image unavailable", color = Color(0xFFBAC9CF), fontSize = 10.sp)
        }
    }
}

/** A schematic card, not a street map. Coordinates are opened in the installed Maps application. */
@Composable
internal fun ChatLocationPreview(url: String, modifier: Modifier = Modifier, open: () -> Unit) {
    val raw = url.substringAfter("?q=", "")
    val parts = raw.split(",")
    val lat = parts.getOrNull(0)?.toDoubleOrNull()
    val lon = parts.getOrNull(1)?.toDoubleOrNull()
    val valid = lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0
    Column(modifier.widthIn(max = 260.dp).clickable(onClick = open)) {
        Box(Modifier.fillMaxWidth().height(108.dp)
            .background(Color(0xFF233C3C), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val ink = Color(0xFF618E88).copy(alpha = .48f)
                for (i in 0..5) {
                    val x = size.width * i / 5f
                    drawLine(ink, Offset(x, 0f), Offset(x + size.width * .22f, size.height), 2.dp.toPx())
                }
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(ink, Offset(0f, y), Offset(size.width, y + size.height * .13f), 3.dp.toPx())
                }
                drawCircle(Color(0xFF00A884).copy(alpha = .23f), 19.dp.toPx(), center)
            }
            Icon(Icons.Outlined.Place, "Shared location", Modifier.size(34.dp), tint = Color(0xFF60E2B7))
        }
        Spacer(Modifier.height(5.dp))
        Text("Shared location", color = Color(0xFFE9EDEF), fontWeight = FontWeight.Medium)
        Text(if (valid) "View coordinates in Maps" else "Open shared location",
            color = Color(0xFF87C7B8), fontSize = 12.sp)
    }
}
