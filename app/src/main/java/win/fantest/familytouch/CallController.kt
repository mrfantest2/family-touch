package win.fantest.familytouch

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaAudioDeviceModule
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
    private var hasRemoteDescription = false
    private var currentPeer: Peer? = null

    val eglContext get() = egl.eglBaseContext

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        context = ctx.applicationContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        egl = EglBase.create()
        val adm = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
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
        currentPeer = peer
        pendingIce.clear()
        hasRemoteDescription = false
        val config = PeerConnection.RTCConfiguration(emptyList())
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        connection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    FamilyStore.lastError = "Call network interrupted. Confirm both devices are connected to Tailscale."
                }
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
        val stream = factory.createLocalMediaStream("FamilyTouch")
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("FTAudio", audioSource)
        stream.addTrack(audioTrack)
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
                stream.addTrack(localVideo)
                localRenderer?.let { localVideo?.addSink(it) }
            }
        }
        connection?.addStream(stream)
    }

    private fun constraints(video: Boolean) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", video.toString()))
    }

    fun start(peer: Peer, video: Boolean) {
        try {
            prepare(peer, video)
            FamilyStore.activeCall = CallInvite(peer.id, video, outgoing = true)
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
            prepare(peer, invitation.isVideo)
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
        io.launch { try { Tailnet.send(peer, type, body) } catch (e: Exception) { FamilyStore.lastError = "Call signaling: " + e.message } }
    }

    fun receive(type: String, peerId: String, data: JSONObject, alert: (String) -> Unit) {
        when (type) {
            "offer" -> {
                if (FamilyStore.activeCall != null) return
                val peer = FamilyStore.find(peerId) ?: return
                FamilyStore.incomingCall = CallInvite(peerId, data.optBoolean("video"), data.getString("sdp"))
                alert(peer.name + " is calling")
            }
            "answer" -> if (currentPeer?.id == peerId && ready) {
                setRemote(SessionDescription(SessionDescription.Type.ANSWER, data.getString("sdp")))
            }
            "ice" -> if (currentPeer?.id == peerId && ready) {
                val candidate = IceCandidate(data.optString("mid"), data.getInt("mLine"), data.getString("candidate"))
                if (hasRemoteDescription) connection?.addIceCandidate(candidate) else pendingIce.add(candidate)
            }
            "hangup" -> if (currentPeer?.id == peerId || FamilyStore.incomingCall?.peerId == peerId) clear(false)
        }
    }

    fun hangup() {
        currentPeer?.let { signal("hangup", JSONObject()) }
        if (currentPeer == null) FamilyStore.incomingCall?.peerId?.let { id ->
            FamilyStore.find(id)?.let { peer ->
                io.launch { try { Tailnet.send(peer, "hangup") } catch (_: Exception) {} }
            }
        }
        clear(false)
    }

    private fun clear(notify: Boolean) {
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
        currentPeer = null
        hasRemoteDescription = false
        pendingIce.clear()
        FamilyStore.activeCall = null
        FamilyStore.incomingCall = null
    }
}
