package win.fantest.familytouch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.*

internal data class GlobePoint(val x: Double, val y: Double)
internal object FamilyGlobeProjection {
    fun normalized(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    fun project(lat: Double, lon: Double, centerLat: Double, centerLon: Double): GlobePoint? {
        if (!LiveLocationProtocol.validCoordinates(lat, lon) || centerLat !in -90.0..90.0 ||
            !centerLon.isFinite()) return null
        val p = Math.toRadians(lat)
        val l = Math.toRadians(normalized(lon - centerLon))
        val c = Math.toRadians(centerLat)
        if (sin(c) * sin(p) + cos(c) * cos(p) * cos(l) <= 1e-10) return null
        return GlobePoint(cos(p) * sin(l), sin(c) * cos(p) * cos(l) - cos(c) * sin(p))
    }
}
internal data class FamilyGlobePin(val peer: Peer, val fix: LiveLocationFix)
internal fun familyGlobePins(peers: List<Peer>, messages: List<ChatItem>, now: Long) =
    peers.filter { it.secret.isNotBlank() }.mapNotNull { peer ->
        val latest = messages.asReversed().firstNotNullOfOrNull { item ->
            if (item.peerId == peer.id && !item.outgoing)
                LiveLocationProtocol.parse(item.text) else null
        }
        if (latest != null && !latest.ended && latest.expiresAt > now &&
            LiveLocationProtocol.validCoordinates(latest.latitude, latest.longitude))
            FamilyGlobePin(peer, latest) else null
    }

/** The globe itself collects NO location. Pins require active, consented, incoming direct shares. */
@Composable
internal fun FamilyGlobePage(peers: List<Peer>, onOpen: (String) -> Unit) {
    val tone = FamilyAppearance.tone
    var lon by remember { mutableFloatStateOf(15f) }
    var lat by remember { mutableFloatStateOf(19f) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15000); now = System.currentTimeMillis() } }
    val pins = familyGlobePins(peers, FamilyStore.messages, now)
    val online = peers.count { FamilyStore.peerPresence[it.id] in setOf("available", "on_call") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Family globe", color = tone.text, style = MaterialTheme.typography.headlineSmall)
            Text("Drag to spin. Locations appear only when family members explicitly share.",
                color = tone.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { lat = 19f; lon = 15f }, label = { Text("Reset") })
                AssistChip(onClick = { lon = FamilyGlobeProjection.normalized(lon - 45.0).toFloat() },
                    label = { Text("Rotate left") })
                AssistChip(onClick = { lon = FamilyGlobeProjection.normalized(lon + 45.0).toFloat() },
                    label = { Text("Rotate right") })
            }
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = tone.card) {
                Column(Modifier.fillMaxWidth().padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit) {
                        detectDragGestures { change, delta ->
                            change.consume()
                            lon = FamilyGlobeProjection.normalized(lon - delta.x * .35).toFloat()
                            lat = (lat + delta.y * .25f).coerceIn(-75f, 75f)
                        }
                    }) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = min(size.width, size.height) * .46f
                        drawCircle(brush = Brush.radialGradient(
                            listOf(Color(0xFF4786B8), Color(0xFF194575), Color(0xFF071B3A)),
                            center = Offset(center.x-radius*.3f,center.y-radius*.3f),
                            radius = radius*1.75f), radius = radius, center = center)
                        fun project(y: Double, x: Double): Offset? =
                            FamilyGlobeProjection.project(y,x,lat.toDouble(),lon.toDouble())
                                ?.let { Offset(center.x+it.x.toFloat()*radius,
                                    center.y+it.y.toFloat()*radius) }
                        for (parallel in -75..75 step 15) {
                            var last: Offset? = null
                            for (meridian in -180..180 step 3) {
                                val next = project(parallel.toDouble(),meridian.toDouble())
                                if (next != null && last != null)
                                    drawLine(Color(0x758DCAE9),last,next,strokeWidth = 1f)
                                last = next
                            }
                        }
                        for (meridian in -180..165 step 15) {
                            var last: Offset? = null
                            for (parallel in -89..89 step 3) {
                                val next = project(parallel.toDouble(),meridian.toDouble())
                                if (next != null && last != null)
                                    drawLine(Color(0x658DCAE9),last,next,strokeWidth = 1f)
                                last = next
                            }
                        }
                        val coast = listOf(
                            listOf(-168.0 to 65.0,-115.0 to 68.0,-83.0 to 23.0,-110.0 to 15.0,-145.0 to 56.0,-168.0 to 65.0),
                            listOf(-79.0 to 11.0,-48.0 to 9.0,-36.0 to -19.0,-69.0 to -55.0,-79.0 to 11.0),
                            listOf(-14.0 to 37.0,30.0 to 69.0,100.0 to 69.0,156.0 to 54.0,120.0 to 3.0,52.0 to 20.0,-14.0 to 37.0),
                            listOf(-16.0 to 32.0,38.0 to 31.0,51.0 to 4.0,19.0 to -35.0,-16.0 to 12.0,-16.0 to 32.0),
                            listOf(113.0 to -13.0,151.0 to -11.0,154.0 to -34.0,114.0 to -35.0,113.0 to -13.0))
                        coast.forEach { outline ->
                            var last: Offset? = null
                            outline.forEach { (x,y) ->
                                val next = project(y,x)
                                if (next != null && last != null)
                                    drawLine(Color(0xBB9FD5AC),last,next,strokeWidth = 3f)
                                last = next
                            }
                        }
                        pins.forEach { pin ->
                            project(pin.fix.latitude,pin.fix.longitude)?.let { point ->
                                drawCircle(Color(0x883AF0BA),radius=15f,center=point)
                                drawCircle(Color(0xFF88FFD7),radius=7f,center=point)
                                drawCircle(Color.White,radius=2.5f,center=point)
                            }
                        }
                        drawCircle(color=Color(0xAAB7E7FA),radius=radius,center=center,
                            style=Stroke(width=2.3f))
                    }
                    Text(online.toString()+" online • "+pins.size+" sharing location",
                        color=tone.text,style=MaterialTheme.typography.titleSmall)
                    Text("Opening this view does not request or broadcast your location.",
                        color=tone.secondaryText,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Registered family devices",color=tone.text,
            style=MaterialTheme.typography.titleMedium) }
        items(peers,key={ it.id }) { peer ->
            val live = pins.any { it.peer.id == peer.id }
            Surface(color=tone.card,shape=RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().clickable { onOpen(peer.id) }.padding(12.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    Surface(color=tone.selected,shape=CircleShape,modifier=Modifier.size(36.dp)) {
                        Box(contentAlignment=Alignment.Center) {
                            Text(peer.name.take(1).uppercase(),color=tone.text)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(peer.name,color=tone.text)
                        Text(FamilyStore.presenceLabel(peer.id)+" • "+
                            (if(live) "Live location shared" else "Location not shared"),
                            color=tone.secondaryText,fontSize=12.sp)
                    }
                    Text("Chat",color=tone.accent)
                }
            }
        }
        item {
            Text("Only unexpired, consented direct-chat live shares appear on the map. Stopped or expired sessions disappear. Offline status does not reveal a location.",
                color=tone.secondaryText,style=MaterialTheme.typography.bodySmall)
        }
    }
}
