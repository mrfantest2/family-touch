package win.fantest.familytouch

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID

internal object CallSessionPolicy {
    fun validParticipants(ids: Set<String>, self: String) = ids.size in 2..4 && self in ids && ids.none { it.isBlank() }
    fun acceptsSignal(expectedSession: String, actualSession: String, participants: Set<String>, sender: String) =
        expectedSession.isNotBlank() && expectedSession == actualSession && sender in participants
}
internal data class GroupCallInvite(val sessionId: String, val groupId: String, val caller: String,
    val participants: Set<String>, val video: Boolean, val revision: Long)

/** Full-mesh call: one shared local capture, one peer connection per accepted member. */
internal object GroupCallController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var context: Context
    var session by mutableStateOf<GroupCallInvite?>(null); private set
    var incoming by mutableStateOf<GroupCallInvite?>(null); private set
    val states = mutableStateMapOf<String, String>()
    val videos = mutableStateMapOf<String, VideoTrack>()
    val channels = mutableMapOf<String, DataChannel>()
    var channelListener: ((String, DataChannel) -> Unit)? = null
    var micMuted by mutableStateOf(false); private set
    var cameraEnabled by mutableStateOf(true); private set
    private val connections = mutableMapOf<String, PeerConnection>()
    private val accepted = mutableSetOf<String>()
    private val joinSeen = mutableSetOf<String>()
    private val pendingIce = mutableMapOf<String, MutableList<IceCandidate>>()
    private val remoteSet = mutableSetOf<String>()
    private val offered = mutableSetOf<String>()
    private var source: AudioSource? = null
    private var audio: AudioTrack? = null
    private var camera: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private var capture: VideoTrack? = null
    private var waitingJob: Job? = null
    private var connectedOnce = false
    fun currentParticipants(): Set<String> = accepted.toSet()
    fun init(ctx: Context) { context = ctx.applicationContext }
    private fun body(call: GroupCallInvite): JSONObject = JSONObject().put("sessionId", call.sessionId)
        .put("groupId", call.groupId).put("revision", call.revision)
        .put("participants", org.json.JSONArray(call.participants.toList())).put("video", call.video)
    private fun send(peerId: String, type: String, payload: JSONObject = JSONObject()) {
        val call = session ?: incoming ?: return
        val peer = FamilyStore.find(peerId) ?: return
        val data = body(call)
        payload.keys().forEach { data.put(it, payload.get(it)) }
        scope.launch {
            try { Tailnet.send(peer, type, data) }
            catch (_: Exception) { if (session?.sessionId == call.sessionId) states[peerId] = "Unavailable" }
        }
    }
    fun start(group: FamilyGroup, video: Boolean) {
        require(CallSessionPolicy.validParticipants(group.members, FamilyStore.deviceId)) { "Group calls need two to four devices" }
        check(session == null && incoming == null && FamilyStore.activeCall == null && FamilyStore.incomingCall == null) { "Already in a call" }
        val call = GroupCallInvite(UUID.randomUUID().toString(), group.id, FamilyStore.deviceId, group.members, video, group.revision)
        session = call; accepted.add(FamilyStore.deviceId)
        try { capture(call.video) } catch (e: Exception) { close(); throw e }
        call.participants.filter { it != FamilyStore.deviceId }.forEach { id ->
            states[id] = "Inviting"
            scope.launch {
                try {
                    val peer = requireNotNull(FamilyStore.find(id))
                    require(Tailnet.send(peer, "presence").optInt("protocol", 1) >= 4) { "Update required" }
                    send(id, "group_call_invite")
                } catch (_: Exception) { states[id] = "Unavailable / update required" }
            }
        }
        timeout(call.sessionId)
    }
    fun accept() {
        val call = incoming ?: return
        RingtonePlayer.stop()
        check(FamilyStore.activeCall == null && session == null)
        incoming = null; session = call; accepted.add(FamilyStore.deviceId); accepted.add(call.caller)
        try {
            capture(call.video)
            call.participants.filter { it != FamilyStore.deviceId }.forEach { id -> send(id, "group_call_join") }
            // Negotiate only after reciprocal join acknowledgement.
            timeout(call.sessionId)
        } catch (e: Exception) { FamilyStore.lastError = e.message ?: "Cannot start group call"; hangup() }
    }
    private fun timeout(id: String) {
        waitingJob?.cancel()
        waitingJob = scope.launch { delay(45000)
            if (session?.sessionId == id && !connectedOnce) {
                FamilyStore.lastError = "No connected group participants"; hangup()
            }
            if (incoming?.sessionId == id) { send(incoming!!.caller, "group_call_leave"); incoming = null; accepted.clear() }
        }
    }
    private fun capture(video: Boolean) {
        CallController.beginGroupAudio(video)
        val factory = CallController.peerFactory
        source = factory.createAudioSource(MediaConstraints())
        audio = factory.createAudioTrack("GroupAudio", source)
        micMuted = false
        cameraEnabled = true
        if (video) {
            val enumerator = Camera2Enumerator(context)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull()
            camera = requireNotNull(name?.let { enumerator.createCapturer(it, null) }) { "Camera unavailable" }
            videoSource = factory.createVideoSource(false)
            helper = SurfaceTextureHelper.create("FamilyGroupCamera", CallController.eglContext)
            camera!!.initialize(helper, context, videoSource!!.capturerObserver)
            camera!!.startCapture(640, 480, 15)
            capture = factory.createVideoTrack("GroupVideo", videoSource)
            videos[FamilyStore.deviceId] = capture!!
        }
    }
    private fun setChannel(peer: String, channel: DataChannel) {
        channels[peer] = channel; channelListener?.invoke(peer, channel)
    }
    private fun connect(peer: String, answerOffer: String? = null) {
        if (peer == FamilyStore.deviceId) return
        val call = session ?: return
        var connection = connections[peer]
        if (connection == null) {
            states[peer] = "Connecting"
            val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
            connection = requireNotNull(CallController.peerFactory.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    scope.launch {
                        if (session?.sessionId != call.sessionId) return@launch
                        states[peer] = when(state) {
                            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> { connectedOnce = true; "Connected" }
                            PeerConnection.IceConnectionState.DISCONNECTED -> "Reconnecting"
                            PeerConnection.IceConnectionState.FAILED -> "Connection failed"
                            else -> "Connecting"
                        }
                        if (state == PeerConnection.IceConnectionState.DISCONNECTED || state == PeerConnection.IceConnectionState.FAILED)
                            RecordingCoordinator.stop("Call connection interrupted")
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) { candidate?.let {
                    send(peer, "group_call_ice", JSONObject().put("mid", it.sdpMid).put("mLine", it.sdpMLineIndex).put("candidate", it.sdp))
                } }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) { channel?.let { scope.launch { setChannel(peer, it) } } }
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val videoTrack = receiver?.track() as? VideoTrack ?: return
                    scope.launch { if (session?.sessionId == call.sessionId) videos[peer] = videoTrack }
                }
            }))
            connections[peer] = connection
            audio?.let { connection.addTrack(it, listOf("FamilyGroup")) }
            capture?.let { connection.addTrack(it, listOf("FamilyGroup")) }
            if (FamilyStore.deviceId < peer) setChannel(peer, connection.createDataChannel("family-recording", DataChannel.Init()))
        }
        val pc = connection
        if (answerOffer != null) setRemote(peer, SessionDescription(SessionDescription.Type.OFFER, answerOffer)) {
            pc.createAnswer(observer { sdp -> pc.setLocalDescription(observer(), sdp)
                send(peer, "group_call_answer", JSONObject().put("sdp", sdp.description)) }, constraints(call.video))
        } else if (FamilyStore.deviceId < peer && offered.add(peer)) {
            pc.createOffer(observer { sdp -> pc.setLocalDescription(observer(), sdp)
                send(peer, "group_call_offer", JSONObject().put("sdp", sdp.description)) }, constraints(call.video))
        }
    }
    private fun constraints(video: Boolean) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", video.toString()))
    }
    private fun observer(created: (SessionDescription) -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) { if (sdp != null) scope.launch { created(sdp) } }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { FamilyStore.lastError = "Group negotiation failed" }
        override fun onSetFailure(error: String?) { FamilyStore.lastError = "Group negotiation failed" }
    }
    private fun setRemote(peer: String, description: SessionDescription, done: () -> Unit = {}) {
        connections[peer]?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() { scope.launch {
                remoteSet.add(peer); pendingIce.remove(peer)?.forEach { connections[peer]?.addIceCandidate(it) }; done()
            } }
            override fun onSetFailure(error: String?) { FamilyStore.lastError = "Group description rejected" }
        }, description)
    }
    fun receive(type: String, sender: String, data: JSONObject, alert: (String) -> Unit) {
        val group = GroupStore.validateSender(sender, data)
        val id = data.getString("sessionId"); UUID.fromString(id)
        if (type == "group_call_invite") {
            check(session == null && incoming == null && FamilyStore.activeCall == null && FamilyStore.incomingCall == null) { "Device is busy" }
            val array = data.getJSONArray("participants")
            val participants = (0 until array.length()).map { array.getString(it) }.toSet()
            require(participants == group.members && CallSessionPolicy.validParticipants(participants, FamilyStore.deviceId))
            incoming = GroupCallInvite(id, group.id, sender, participants, data.getBoolean("video"), group.revision)
            accepted.clear(); accepted.add(sender); alert(group.title + " group call"); timeout(id); return
        }
        val call = session ?: incoming ?: return
        require(call.groupId == group.id && CallSessionPolicy.acceptsSignal(call.sessionId, id, call.participants, sender))
        when(type) {
            "group_call_join" -> {
                if (sender !in accepted) RecordingCoordinator.stop("Call participant changed; new consent required")
                accepted.add(sender)
                if (session != null) {
                    send(sender, "group_call_join_ack")
                    if (joinSeen.add(sender)) send(sender, "group_call_join")
                }
            }
            "group_call_join_ack" -> {
                if (session != null) {
                    if (sender !in accepted) RecordingCoordinator.stop("Call participant changed; new consent required")
                    accepted.add(sender); connect(sender)
                }
            }
            "group_call_offer" -> { require(sender in accepted && session != null); connect(sender, data.getString("sdp")) }
            "group_call_answer" -> { require(sender in accepted); setRemote(sender, SessionDescription(SessionDescription.Type.ANSWER, data.getString("sdp"))) }
            "group_call_ice" -> {
                require(sender in accepted)
                val ice = IceCandidate(data.optString("mid"), data.getInt("mLine"), data.getString("candidate"))
                if (sender in remoteSet) connections[sender]?.addIceCandidate(ice)
                else pendingIce.getOrPut(sender) { mutableListOf() }.also { require(it.size < 128); it.add(ice) }
            }
            "group_call_leave" -> {
                RecordingCoordinator.stop("Call participant left")
                accepted.remove(sender); states[sender] = "Left / declined"
                videos.remove(sender); channels.remove(sender)?.close()
                connections.remove(sender)?.apply { close(); dispose() }
                if (session != null && connectedOnce && accepted.size < 2) close()
                if (incoming?.caller == sender) { incoming = null; accepted.clear() }
            }
        }
    }
    fun mute() { micMuted = !micMuted; audio?.setEnabled(!micMuted) }
    fun toggleCamera() { cameraEnabled = !cameraEnabled; capture?.setEnabled(cameraEnabled) }
    fun hangup() {
        val call = session ?: incoming
        call?.participants?.filter { it != FamilyStore.deviceId }?.forEach { send(it, "group_call_leave") }
        close()
    }
    private fun close() {
        RecordingCoordinator.stop("Call ended")
        RingtonePlayer.stop()
        waitingJob?.cancel(); waitingJob = null
        runCatching { camera?.stopCapture() }; camera?.dispose(); camera = null
        helper?.dispose(); helper = null
        channels.values.forEach { it.close(); it.dispose() }; channels.clear()
        connections.values.forEach { it.close(); it.dispose() }; connections.clear()
        videos.clear(); capture?.dispose(); capture = null; videoSource?.dispose(); videoSource = null
        audio?.dispose(); audio = null; source?.dispose(); source = null
        accepted.clear(); joinSeen.clear(); offered.clear(); states.clear(); pendingIce.clear(); remoteSet.clear(); connectedOnce = false
        session = null; incoming = null; micMuted = false; cameraEnabled = true; CallController.endGroupAudio()
    }
}
