package win.fantest.familytouch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.VideoTrack
import java.util.UUID

/** Terminal consent state. Silence never implies acceptance; withdrawn requests cannot restart. */
internal class RecordingConsent(val sessionId: String, val recordingId: String, participants: Set<String>) {
    val participants = participants.toSet()
    private val accepted = mutableSetOf<String>()
    private var stopped = false
    val allowed get() = !stopped && this.participants.size in 2..4 && accepted == this.participants
    val approvals get() = accepted.toSet()
    fun accept(session: String, recording: String, sender: String): Boolean {
        if (stopped || session != sessionId || recording != recordingId || sender !in participants) return false
        accepted.add(sender); return true
    }
    fun withdraw(sender: String) { if (sender in participants) stopped = true }
    fun membershipChanged(current: Set<String>) { if (current != participants) stopped = true }
}
internal data class RecordingRequest(val id: String, val sessionId: String, val requester: String,
    val participants: Set<String>, val video: Boolean)
internal data class RecordingCall(val sessionId: String, val participants: Set<String>, val video: Boolean,
    val tracks: Map<String, VideoTrack>)

internal object RecordingCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var request by mutableStateOf<RecordingRequest?>(null); private set
    var approvals by mutableStateOf<Set<String>>(emptySet()); private set
    var recording by mutableStateOf(false); private set
    var status by mutableStateOf("Not recording"); private set
    private var policy: RecordingConsent? = null
    private var startedNotice = false
    private var timeout: Job? = null
    private var voteSync: Job? = null
    var startCapture: ((RecordingRequest, RecordingCall) -> Unit)? = null
    var stopCapture: (() -> Unit)? = null
    fun current(): RecordingCall? {
        val group = GroupCallController.session
        if (group != null) {
            val participants = GroupCallController.currentParticipants()
            if (participants.size < 2 || participants.any { it != FamilyStore.deviceId && GroupCallController.states[it] != "Connected" }) return null
            return RecordingCall(group.sessionId, participants, group.video, GroupCallController.videos.toMap())
        }
        val call = FamilyStore.activeCall ?: return null
        if (call.sessionId.isBlank() || CallController.connectedSinceMs == null) return null
        return RecordingCall(call.sessionId, setOf(FamilyStore.deviceId, call.peerId), call.isVideo, CallController.recordingTracks())
    }
    private fun json(req: RecordingRequest) = JSONObject().put("recordingId", req.id).put("callSession", req.sessionId)
        .put("requester", req.requester).put("participants", JSONArray(req.participants.toList())).put("video", req.video)
    private fun broadcast(type: String, req: RecordingRequest) {
        req.participants.filter { it != FamilyStore.deviceId }.forEach { id ->
            FamilyStore.find(id)?.let { peer -> scope.launch {
                try { Tailnet.send(peer, type, json(req)) }
                catch (_: Exception) { if (request?.id == req.id) stop("Participant unavailable") }
            } }
        }
    }
    fun begin(video: Boolean) {
        check(request == null) { "A recording request is already active" }
        val call = requireNotNull(current()) { "Wait for all call connections to be ready" }
        require(!video || call.video)
        val req = RecordingRequest(UUID.randomUUID().toString(), call.sessionId, FamilyStore.deviceId, call.participants, video)
        install(req); accept(); broadcast("record_request", req)
    }
    private fun install(req: RecordingRequest) {
        request = req; policy = RecordingConsent(req.sessionId, req.id, req.participants)
        approvals = emptySet(); recording = false; startedNotice = false; status = "Waiting for everyone's consent"
        timeout?.cancel(); timeout = scope.launch { delay(60000); if (request?.id == req.id && !recording) stop("Recording consent timed out") }
        voteSync?.cancel()
        voteSync = scope.launch {
            while (request?.id == req.id && !recording) {
                if (FamilyStore.deviceId in approvals) {
                    req.participants.filter { it !in approvals }.forEach { id ->
                        val peer = FamilyStore.find(id) ?: return@forEach
                        try {
                            val result = Tailnet.send(peer, "record_check", json(req))
                            if (result.optBoolean("accepted") && request?.id == req.id) {
                                policy?.accept(req.sessionId, req.id, id); approvals = policy?.approvals.orEmpty(); maybeStart()
                            }
                        } catch (_: Exception) { /* The 60-second consent deadline remains authoritative. */ }
                    }
                }
                delay(2000)
            }
        }
    }
    fun accept() {
        val req = request ?: return
        if (policy?.accept(req.sessionId, req.id, FamilyStore.deviceId) == true) {
            approvals = policy!!.approvals; broadcast("record_vote", req); maybeStart()
        }
    }
    private fun maybeStart() {
        val req = request ?: return
        if (policy?.allowed != true || recording) return
        val call = current()
        if (call == null || call.sessionId != req.sessionId || call.participants != req.participants) { stop("Call membership changed"); return }
        if (req.requester == FamilyStore.deviceId) {
            try {
                checkNotNull(startCapture) { "Recorder is unavailable" }.invoke(req, call)
                recording = true; status = "Recording · stored only on your phone"; timeout?.cancel()
                broadcast("record_start", req)
            } catch (_: Exception) { stop("Unable to start recorder") }
        } else if (startedNotice) {
            recording = true; status = "Recording on " + (FamilyStore.find(req.requester)?.name ?: "family device"); timeout?.cancel()
        }
    }
    fun receive(type: String, sender: String, data: JSONObject) {
        val id = data.getString("recordingId"); UUID.fromString(id)
        if (type == "record_request") {
            val call = requireNotNull(current()) { "No matching active call" }
            val array = data.getJSONArray("participants")
            require(array.length() in 2..4)
            val members = (0 until array.length()).map { array.getString(it) }.toSet()
            require(sender == data.getString("requester") && sender in call.participants && members == call.participants &&
                data.getString("callSession") == call.sessionId && (!data.getBoolean("video") || call.video))
            require(request == null || request?.id == id) { "Another recording request is active" }
            if (request == null) install(RecordingRequest(id, call.sessionId, sender, members, data.getBoolean("video")))
            policy!!.accept(call.sessionId, id, sender); approvals = policy!!.approvals
            // Every vote is sent directly to every participant, not vouched for by the requester.
            if (FamilyStore.deviceId in approvals) broadcast("record_vote", request!!)
            return
        }
        val req = request ?: return
        require(req.id == id && req.sessionId == data.getString("callSession") && sender in req.participants)
        when (type) {
            "record_vote" -> {
                if (policy!!.accept(req.sessionId, req.id, sender)) { approvals = policy!!.approvals; maybeStart() }
            }
            "record_start" -> { require(sender == req.requester); startedNotice = true; maybeStart() }
            "record_stop" -> stop("Recording stopped", announce = false)
        }
    }
    fun proof(sender: String, data: JSONObject): JSONObject {
        val req = request ?: return JSONObject().put("accepted", false)
        require(sender in req.participants && req.id == data.getString("recordingId") && req.sessionId == data.getString("callSession"))
        return JSONObject().put("accepted", FamilyStore.deviceId in approvals)
    }
    fun stop(reason: String = "Recording stopped", announce: Boolean = true) {
        val req = request ?: return
        policy?.withdraw(FamilyStore.deviceId)
        // Close capture eligibility before any asynchronous finalization.
        val owned = req.requester == FamilyStore.deviceId
        request = null; recording = false; approvals = emptySet(); policy = null; startedNotice = false
        timeout?.cancel(); timeout = null; voteSync?.cancel(); voteSync = null; status = reason
        if (owned) stopCapture?.invoke()
        if (announce) broadcast("record_stop", req)
    }
}

@Composable
internal fun RecordingControls(videoCall: Boolean) {
    var choice by remember { mutableStateOf(false) }
    val req = RecordingCoordinator.request
    Column(Modifier.padding(horizontal = 12.dp)) {
        if (req != null) {
            Text(if (RecordingCoordinator.recording) "● " + RecordingCoordinator.status
                else "Recording approval: ${RecordingCoordinator.approvals.size}/${req.participants.size}")
            TextButton(onClick = { RecordingCoordinator.stop("Consent withdrawn") }) { Text("Stop / withdraw recording consent") }
        } else TextButton(onClick = { choice = true }) { Text("Request call recording") }
    }
    if (choice) AlertDialog(onDismissRequest = { choice = false }, title = { Text("Request everyone's permission") },
        text = { Text("Recording starts only after every current participant agrees. The completed file stays on your phone until you choose to share it.") },
        confirmButton = { TextButton(onClick = {
            choice = false
            try { RecordingCoordinator.begin(false) } catch (e: Exception) { FamilyStore.lastError = e.message ?: "Cannot request recording" }
        }) { Text("Audio recording") } }, dismissButton = {
            if (videoCall) TextButton(onClick = {
                choice = false
                try { RecordingCoordinator.begin(true) } catch (e: Exception) { FamilyStore.lastError = e.message ?: "Cannot request recording" }
            }) { Text("Video recording") }
            else TextButton(onClick = { choice = false }) { Text("Cancel") }
        })
}
@Composable
internal fun RecordingPermissionDialog() {
    val req = RecordingCoordinator.request ?: return
    if (req.requester != FamilyStore.deviceId && FamilyStore.deviceId !in RecordingCoordinator.approvals)
        AlertDialog(onDismissRequest = { RecordingCoordinator.stop("Recording declined") }, title = { Text("Allow call recording?") },
            text = { Text((FamilyStore.find(req.requester)?.name ?: "Family member") +
                " wants to record this ${if (req.video) "video" else "audio"} call. All ${req.participants.size} participants must agree. " +
                "It will be saved on that device when the call ends. You can withdraw consent at any time.") },
            confirmButton = { Button(onClick = { RecordingCoordinator.accept() }) { Text("Agree") } },
            dismissButton = { TextButton(onClick = { RecordingCoordinator.stop("Recording declined") }) { Text("Decline") } })
}
