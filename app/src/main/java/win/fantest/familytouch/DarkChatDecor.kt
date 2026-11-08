package win.fantest.familytouch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original minimalist wallpaper marks; no copied WhatsApp assets. */
@Composable
internal fun DarkChatWallpaper(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = 94.dp.toPx()
        val ink = Color(0xFF7EA59A).copy(alpha = .065f)
        val rows = (size.height / unit).toInt() + 2
        val cols = (size.width / unit).toInt() + 2
        for (r in 0..rows) for (c in 0..cols) {
            val x = c * unit + (r % 2) * unit * .46f
            val y = r * unit
            val radius = 10.dp.toPx()
            when ((r * 7 + c * 3) % 4) {
                0 -> drawCircle(ink, radius, Offset(x + 13.dp.toPx(), y + 17.dp.toPx()), style = Stroke(1.dp.toPx()))
                1 -> {
                    drawLine(ink, Offset(x, y + radius), Offset(x + radius, y), 1.dp.toPx())
                    drawLine(ink, Offset(x + radius, y), Offset(x + radius * 2, y + radius), 1.dp.toPx())
                }
                2 -> drawRect(ink, Offset(x, y), androidx.compose.ui.geometry.Size(radius * 1.5f, radius * 1.5f),
                    style = Stroke(1.dp.toPx()))
                else -> {
                    drawLine(ink, Offset(x, y), Offset(x + radius * 1.8f, y + radius), 1.dp.toPx())
                    drawCircle(ink, 2.dp.toPx(), Offset(x + radius * 1.8f, y + radius * 1.5f))
                }
            }
        }
    }
}

@Composable
internal fun DarkChatTail(outgoing: Boolean, color: Color) {
    Canvas(Modifier.width(7.dp).height(13.dp)) {
        val p = Path()
        if (outgoing) {
            p.moveTo(0f, 0f); p.lineTo(size.width, 0f); p.lineTo(0f, size.height)
        } else {
            p.moveTo(size.width, 0f); p.lineTo(0f, 0f); p.lineTo(size.width, size.height)
        }
        p.close()
        drawPath(p, color)
    }
}

@Composable
internal fun DarkChatReceipt(sent: Boolean, delivered: Boolean, read: Boolean) {
    val tint = if (read) Color(0xFF53BDEB) else Color(0xFF8696A0)
    if (!sent) Text("◷", fontSize = 11.sp, color = tint)
    else Icon(if (delivered || read) Icons.Outlined.DoneAll else Icons.Outlined.Check,
        if (read) "Read" else if (delivered) "Delivered" else "Sent",
        tint = tint, modifier = Modifier.size(15.dp))
}

@Composable
internal fun DarkChatSecurityNotice() {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(color = Color(0xFF17272B), shape = RoundedCornerShape(9.dp)) {
            Text("🔒 Messages and calls use your private family connection",
                color = Color(0xFFE8C994), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
        }
    }
}
