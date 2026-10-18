package win.fantest.familytouch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

@Composable
internal fun GroupCallScreen(call: GroupCallInvite) {
    Column(Modifier.fillMaxSize()) {
        Text(GroupStore.find(call.groupId)?.title ?: "Family group call", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        val ids = call.participants.toList()
        Column(Modifier.weight(1f)) {
            ids.chunked(2).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    row.forEach { id ->
                        Column(Modifier.weight(1f).fillMaxHeight().padding(4.dp).background(Color(0xFF10263D))) {
                            val name = if (id == FamilyStore.deviceId) "You" else FamilyStore.find(id)?.name ?: "Family device"
                            Text(name, Modifier.padding(8.dp))
                            val track = GroupCallController.videos[id]
                            if (track != null) GroupVideoTile(track, id == FamilyStore.deviceId, Modifier.weight(1f))
                            Text(if (id == FamilyStore.deviceId) (if (GroupCallController.micMuted) "Microphone muted" else "In call")
                                else GroupCallController.states[id] ?: "Waiting to join", Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
        RecordingControls(call.video)
        if (call.video) TextButton(onClick = { GroupCallController.toggleCamera() }) {
            Text(if (GroupCallController.cameraEnabled) "Turn camera off" else "Turn camera on")
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { GroupCallController.mute() }, Modifier.weight(1f)) {
                Text(if (GroupCallController.micMuted) "Unmute" else "Mute")
            }
            OutlinedButton(onClick = { CallController.selectSpeakerRoute(!CallController.speakerEnabled) }, Modifier.weight(1f)) {
                Text(if (CallController.speakerEnabled) "Speaker" else "Earpiece")
            }
            Button(onClick = { GroupCallController.hangup() }, Modifier.weight(1f)) { Text("Leave call") }
        }
    }
}
@Composable
private fun GroupVideoTile(track: VideoTrack, mirror: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember(track) { SurfaceViewRenderer(context) }
    DisposableEffect(renderer, track) {
        renderer.init(CallController.eglContext, null)
        renderer.setMirror(mirror)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        track.addSink(renderer)
        onDispose { try { runCatching { track.removeSink(renderer) } } finally { renderer.release() } }
    }
    AndroidView(factory = { renderer }, modifier = modifier.fillMaxWidth())
}
