package win.fantest.familytouch

import android.content.Context
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * No signaling server, STUN service, TURN relay or backend.
 * A Tailscale VPN connection must already exist on both Android devices.
 * Offers, answers and ICE candidates are sent directly to the peer's 100.x IP.
 */
object CallController {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var context: Context
    private var ready = false
    private lateinit var factory: PeerConnectionFactory
    private lateinit var egl: EglBase
    private var connection: PeerConnection? = null
    private var camera: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var pendingIce = mutableListOf<IceCandidate>()
    private val earlyIce = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<IceCandidate>>()
    private var hasRemoteDescription = false
    private var currentPeer: Peer? = null
    var connectedSinceMs by mutableStateOf<Long?>(null)
        private set
    private var callStartedMs: Long = 0L
    private var wasOutgoing = false
    private var wasVideo = false

    private fun beginLog(video: Boolean, outgoing: Boolean) {
        callStartedMs = System.currentTimeMillis()
        connectedSinceMs = null
        wasOutgoing = outgoing
        wasVideo = video
    }

    private fun recordFinish() {
        val peer = currentPeer ?: return
        if (callStartedMs <= 0L) return
        FamilyStore.addCallLog(CallLogRecord(UUID.randomUUID().toString(), peer.id,
            wasVideo, wasOutgoing, callStartedMs, connectedSinceMs ?: 0L,
            System.currentTimeMillis(), if (connectedSinceMs != null) "completed"
            else if (wasOutgoing) "unanswered" else "declined"))
        callStartedMs = 0L
        connectedSinceMs = null
    }
    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int? = null
    private var previousSpeakerphone = false
    @Volatile private var closing = false
    var speakerEnabled by mutableStateOf(false)
        private set
    var microphoneMuted by mutableStateOf(false)
        private set

    private fun routeAudioToCall(video: Boolean) {
        CallForegroundService.begin(context, video)
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager
        previousAudioMode = manager.mode
        @Suppress("DEPRECATION")
        previousSpeakerphone = manager.isSpeakerphoneOn
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        manager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        microphoneMuted = false
        selectSpeakerRoute(video)
    }

    /** Explicitly route WebRTC audio: video defaults to speaker; voice starts at earpiece. */
    fun selectSpeakerRoute(enabled: Boolean) {
        val manager = audioManager ?: return
        speakerEnabled = enabled
        if (Build.VERSION.SDK_INT >= 31) {
            val type = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            manager.availableCommunicationDevices.firstOrNull { it.type == type }
                ?.let { manager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = enabled
        }
    }

    fun toggleMicrophoneMute(muted: Boolean) {
        microphoneMuted = muted
        audioTrack?.setEnabled(!muted)
    }

    private fun restoreAudio() {
        if (::context.isInitialized) CallForegroundService.end(context)
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice()
        @Suppress("DEPRECATION")
        manager.isSpeakerphoneOn = previousSpeakerphone
        previousAudioMode?.let { manager.mode = it }
        @Suppress("DEPRECATION")
        manager.abandonAudioFocus(null)
        previousAudioMode = null
        audioManager = null
        speakerEnabled = false
        microphoneMuted = false
    }

    val eglContext get() = egl.eglBaseContext
    internal val peerFactory get() = factory
    internal fun beginGroupAudio(video: Boolean) = routeAudioToCall(video)
    internal fun endGroupAudio() = restoreAudio()
    internal fun recordingTracks(): Map<String, VideoTrack> = buildMap {
        localVideo?.let { put(FamilyStore.deviceId, it) }
        currentPeer?.id?.let { id -> remoteVideo?.let { put(id, it) } }
    }

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        context = ctx.applicationContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        egl = EglBase.create()
        val adm = JavaAudioDeviceModule.builder(context)
            .setSamplesReadyCallback { CallRecordingEngine.samples(true, it) }
            .setPlaybackSamplesReadyCallback { CallRecordingEngine.samples(false, it) }
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        adm.release()
        ready = true
    }

    fun setRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer?.let { localVideo?.removeSink(it) }
        remoteRenderer?.let { remoteVideo?.removeSink(it) }
        localRenderer = local
        remoteRenderer = remote
        local?.let { localVideo?.addSink(it) }
        remote?.let { remoteVideo?.addSink(it) }
    }

    private fun prepare(peer: Peer, video: Boolean) {
        require(ready) { "Initialize calling while the app is visible" }
        clear(false)
        closing = false
        routeAudioToCall(video)
        currentPeer = peer
        pendingIce.clear()
        hasRemoteDescription = false
        val config = PeerConnection.RTCConfiguration(emptyList())
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        connection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if ((state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED) &&
                    !closing && connectedSinceMs == null) {
                    connectedSinceMs = System.currentTimeMillis()
                }
                if (state == PeerConnection.IceConnectionState.FAILED && !closing && FamilyStore.activeCall != null) {
                    FamilyStore.lastError = "Call network interrupted. Confirm both devices have their private connection turned on."
                }
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED)
                    io.launch(Dispatchers.Main) { RecordingCoordinator.stop("Call connection interrupted") }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                signal("ice", JSONObject().put("mid", candidate.sdpMid)
                    .put("mLine", candidate.sdpMLineIndex).put("candidate", candidate.sdp))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                val track = stream?.videoTracks?.firstOrNull() ?: return
                remoteVideo = track
                remoteRenderer?.let { track.addSink(it) }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() as? VideoTrack ?: return
                remoteVideo = track
                remoteRenderer?.let { track.addSink(it) }
            }
        })
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("FTAudio", audioSource)
        connection?.addTrack(audioTrack, listOf("FamilyTouch"))
        if (video) {
            val enumerator = Camera2Enumerator(context)
            val names = enumerator.deviceNames
            val chosen = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull()
            camera = chosen?.let { enumerator.createCapturer(it, null) }
            if (camera != null) {
                source = factory.createVideoSource(false)
                helper = SurfaceTextureHelper.create("FamilyTouchCamera", egl.eglBaseContext)
                camera?.initialize(helper, context, source?.capturerObserver)
                camera?.startCapture(640, 480, 20)
                localVideo = factory.createVideoTrack("FTVideo", source)
                localVideo?.let { connection?.addTrack(it, listOf("FamilyTouch")) }
                localRenderer?.let { localVideo?.addSink(it) }
            }
        }
    }

    private fun constraints(video: Boolean) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", video.toString()))
    }

    fun start(peer: Peer, video: Boolean) {
        try {
            check(GroupCallController.session == null && GroupCallController.incoming == null) { "Already in a group call" }
            prepare(peer, video)
            beginLog(video, true)
            FamilyStore.activeCall = CallInvite(peer.id, video, outgoing = true, sessionId = UUID.randomUUID().toString())
            io.launch {
                delay(45_000L)
                if (currentPeer?.id == peer.id && connectedSinceMs == null &&
                    FamilyStore.activeCall?.outgoing == true) {
                    hangup()
                    FamilyStore.lastError = "No answer from " + peer.name
                }
            }
            connection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) return
                    connection?.setLocalDescription(noop(), sdp)
                    signal("offer", JSONObject().put("sdp", sdp.description).put("video", video))
                }
                override fun onCreateFailure(error: String?) { FamilyStore.lastError = error.orEmpty() }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, constraints(video))
        } catch (e: Exception) { FamilyStore.lastError = e.message.orEmpty(); clear(false) }
    }

    fun accept() {
        val invitation = FamilyStore.incomingCall ?: return
        val peer = FamilyStore.find(invitation.peerId) ?: return
        try {
            RingtonePlayer.stop()
            prepare(peer, invitation.isVideo)
            beginLog(invitation.isVideo, false)
            pendingIce.addAll(earlyIce.remove(peer.id).orEmpty())
            FamilyStore.activeCall = invitation.copy(outgoing = false)
            FamilyStore.incomingCall = null
            setRemote(SessionDescription(SessionDescription.Type.OFFER, invitation.offer)) {
                connection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp == null) return
                        connection?.setLocalDescription(noop(), sdp)
                        signal("answer", JSONObject().put("sdp", sdp.description))
                    }
                    override fun onCreateFailure(error: String?) { FamilyStore.lastError = error.orEmpty() }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, constraints(invitation.isVideo))
            }
        } catch (e: Exception) { FamilyStore.lastError = e.message.orEmpty(); clear(false) }
    }

    private fun setRemote(sdp: SessionDescription, then: () -> Unit = {}) {
        connection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() {
                hasRemoteDescription = true
                pendingIce.forEach { connection?.addIceCandidate(it) }
                pendingIce.clear()
                then()
            }
            override fun onSetFailure(error: String?) { FamilyStore.lastError = error.orEmpty() }
        }, sdp)
    }

    private fun noop() = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetSuccess() {}
        override fun onSetFailure(error: String?) { FamilyStore.lastError = error.orEmpty() }
    }

    private fun signal(type: String, body: JSONObject) {
        val peer = currentPeer ?: return
        FamilyStore.activeCall?.sessionId?.takeIf { it.isNotBlank() }?.let { body.put("callSession", it) }
        io.launch { try { Tailnet.send(peer, type, body) } catch (e: Exception) { FamilyStore.lastError = "Call signaling: " + e.message } }
    }

    fun receive(type: String, peerId: String, data: JSONObject, alert: (String) -> Unit) {
        when (type) {
            "offer" -> {
                if (FamilyStore.activeCall != null || FamilyStore.incomingCall != null ||
                    GroupCallController.session != null || GroupCallController.incoming != null) return
                val peer = FamilyStore.find(peerId) ?: return
                FamilyStore.incomingCall = CallInvite(peerId, data.optBoolean("video"), data.getString("sdp"),
                    sessionId = data.optString("callSession"))
                alert(peer.name + " is calling")
                val pendingOffer = FamilyStore.incomingCall
                io.launch {
                    delay(45_000L)
                    if (FamilyStore.incomingCall == pendingOffer && FamilyStore.activeCall == null) {
                        RingtonePlayer.stop()
                        FamilyStore.incomingCall = null
                        if (::context.isInitialized) {
                            (context.getSystemService(Context.NOTIFICATION_SERVICE) as
                                android.app.NotificationManager).cancel(41)
                        }
                        FamilyStore.addCallLog(CallLogRecord(UUID.randomUUID().toString(), peerId,
                            data.optBoolean("video"), false, System.currentTimeMillis() - 45_000L,
                            0L, System.currentTimeMillis(), "missed"))
                        io.launch { try { Tailnet.send(peer, "hangup") } catch (_: Exception) {} }
                    }
                }
            }
            "answer" -> if (currentPeer?.id == peerId && ready) {
                setRemote(SessionDescription(SessionDescription.Type.ANSWER, data.getString("sdp")))
            }
            "ice" -> {
                val candidate = IceCandidate(data.optString("mid"), data.getInt("mLine"), data.getString("candidate"))
                if (currentPeer?.id == peerId && ready) {
                    if (hasRemoteDescription) connection?.addIceCandidate(candidate) else pendingIce.add(candidate)
                } else {
                    earlyIce.getOrPut(peerId) { java.util.concurrent.CopyOnWriteArrayList() }.add(candidate)
                }
            }
            "hangup" -> {
                if (FamilyStore.incomingCall?.peerId == peerId && FamilyStore.activeCall == null) {
                    FamilyStore.addCallLog(CallLogRecord(UUID.randomUUID().toString(), peerId,
                        FamilyStore.incomingCall!!.isVideo, false, System.currentTimeMillis(),
                        0L, System.currentTimeMillis(), "missed"))
                }
                if (currentPeer?.id == peerId || FamilyStore.incomingCall?.peerId == peerId) clear(false)
            }
        }
    }

    fun hangup() {
        if (currentPeer == null) FamilyStore.incomingCall?.let {
            FamilyStore.addCallLog(CallLogRecord(UUID.randomUUID().toString(),
                it.peerId,it.isVideo,false,System.currentTimeMillis(),0L,System.currentTimeMillis(),"declined"))
        }
        currentPeer?.let { signal("hangup", JSONObject()) }
        if (currentPeer == null) FamilyStore.incomingCall?.peerId?.let { id ->
            FamilyStore.find(id)?.let { peer ->
                io.launch { try { Tailnet.send(peer, "hangup") } catch (_: Exception) {} }
            }
        }
        clear(false)
    }

    private fun clear(notify: Boolean) {
        RecordingCoordinator.stop("Call ended")
        closing = true
        RingtonePlayer.stop()
        if (::context.isInitialized) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(41)
        }
        recordFinish()
        if (notify) currentPeer?.let { signal("hangup", JSONObject()) }
        try { camera?.stopCapture() } catch (_: Exception) {}
        camera?.dispose(); camera = null
        helper?.dispose(); helper = null
        localRenderer?.let { localVideo?.removeSink(it) }
        remoteRenderer?.let { remoteVideo?.removeSink(it) }
        localVideo?.dispose(); localVideo = null
        remoteVideo = null
        audioTrack?.dispose(); audioTrack = null
        source?.dispose(); source = null
        audioSource?.dispose(); audioSource = null
        connection?.close(); connection?.dispose(); connection = null
        restoreAudio()
        currentPeer = null
        hasRemoteDescription = false
        pendingIce.clear()
        connectedSinceMs = null
        FamilyStore.activeCall = null
        FamilyStore.incomingCall = null
    }
}
