package win.fantest.familytouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.UUID

class PeerService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listener: ServerSocket? = null
    private var listenJob: Job? = null
    private val channel = "familytouch_status_silent_v3"
    private val messageChannel = "familytouch_messages_heads_up_v4"
    private val callChannel = "familytouch_calls_v3"

    override fun onCreate() {
        super.onCreate()
        FamilyStore.init(this)
        GroupStore.init(this)
        Tailnet.init(this)
        PeerOutbox.init(this)
        MessageReactions.init(this)
        MessageMutations.init(this)
        CallController.init(this)
        GroupCallController.init(this)
        CallRecordingEngine.init(this)
        scope.launch {
            while (isActive) {
                FamilyDirectory.refresh(this@PeerService)
                delay(20000)
            }
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channel, "Family Touch background connection",
            NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(messageChannel, "Family Touch message pop-ups",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Show new message banners, sounds and vibration outside the app"
            enableVibration(true)
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
        nm.createNotificationChannel(NotificationChannel(callChannel, "Family Touch incoming calls",
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null,null); enableVibration(false)
        })
        val notification = connectionNotification()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(40, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(40, notification)
        scope.launch {
            var previous = EmbeddedTailnet.status
            while (isActive) {
                delay(5_000)
                val current = EmbeddedTailnet.status
                if (previous != current) {
                    previous = current
                    nm.notify(NotificationIds.CONNECTION, connectionNotification())
                }
            }
        }
        scope.launch {
            while (isActive) {
                val contacts = FamilyStore.contacts.toList()
                for (peer in contacts) {
                    FamilyStore.peerPresence[peer.id] = try {
                        Tailnet.send(peer, "presence").optString("status", "away")
                    } catch (_: Exception) { "offline" }
                }
                delay(45_000L)
            }
        }
        scope.launch {
            while (isActive) {
                for (peer in FamilyStore.contacts.toList()) {
                    try {
                        val result = Tailnet.send(peer, "profile_get")
                        val encoded = result.optString("avatar")
                        if (encoded.isNotEmpty() && FamilyStore.peerPhotos[peer.id] != encoded)
                            FamilyStore.setPeerPhoto(peer.id, encoded)
                    } catch (_: Exception) { }
                }
                delay(300_000L)
            }
        }
        listenJob = scope.launch {
            while (isActive) {
                val address = EmbeddedTailnet.status.takeIf { it.connected }?.address
                if (address == null) { delay(1000); continue }
                try {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(address, Tailnet.PORT))
                    server.soTimeout = 1000
                    listener = server
                    while (isActive && EmbeddedTailnet.status.let { it.connected && it.address == address }) {
                        try {
                            val socket = server.accept()
                            launch { handle(socket) }
                        } catch (_: SocketTimeoutException) { /* Recheck VPN address and ownership. */ }
                    }
                }
                } catch (ex: Exception) {
                    if (isActive) { FamilyStore.lastError = "Peer listener unavailable: " + ex.message; delay(2000) }
                } finally { listener = null }
            }
        }
    }

    private fun connectionNotification(): Notification {
        val connected = EmbeddedTailnet.status.connected
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Family Touch")
            .setContentText(if (connected) "Private connection active" else "Private connection unavailable")
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun openApp(peerId: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (peerId != null) intent.putExtra("open_peer_id", peerId)
        return PendingIntent.getActivity(this, peerId?.hashCode() ?: 5, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun callAction(action: String, sessionId: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(action)
            .putExtra(NotificationIds.EXTRA_CALL_SESSION, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val code = sessionId.hashCode() xor action.hashCode()
        return PendingIntent.getActivity(this, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showNotification(title: String, text: String, call: Boolean = false, peerId: String? = null) {
        // A locked chat must still alert even if it is the selected conversation.
        val isLockedConversation = !call && peerId != null &&
            runCatching { ChatSecurity.enabled(this, peerId) }.getOrDefault(true)
        if (!call && FamilyStore.appVisible && FamilyStore.selectedPeer == peerId &&
            !isLockedConversation) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val c = if (call) callChannel else messageChannel
        // Hide message text in notifications when this conversation has its own PIN.
        val builder = NotificationCompat.Builder(this, c).setSmallIcon(R.drawable.ic_family_touch)
            .setContentTitle(if (isLockedConversation) "Locked conversation" else title)
            .setContentText(if (isLockedConversation) "New private family message" else text)
            .setContentIntent(openApp(peerId)).setAutoCancel(!call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (call) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(if (isLockedConversation) NotificationCompat.VISIBILITY_SECRET
                else NotificationCompat.VISIBILITY_PRIVATE)
        if (call) {
            val session = FamilyStore.incomingCall?.sessionId ?: GroupCallController.incoming?.sessionId
            if (session != null) {
                val decline = callAction(NotificationIds.ACTION_DECLINE_CALL, session)
                val answer = callAction(NotificationIds.ACTION_ANSWER_CALL, session)
                // CallStyle requires a qualifying call foreground service or full-screen intent
                // on newer Android versions; without it NotificationManager.notify() throws,
                // interrupting incoming signaling. Use compatible actionable call alerts.
                builder.setOngoing(true)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline)
                    .addAction(android.R.drawable.sym_action_call, "Answer", answer)
                    .setContentText(text + " · Tap Answer or Decline")
            }
        }
        nm.notify(if (call) NotificationIds.INCOMING_CALL else NotificationIds.messageForPeer(requireNotNull(peerId)),
            builder.build())
        if (call) RingtonePlayer.start(this)
    }

    private suspend fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 8000
            try {
                val ip = s.inetAddress.hostAddress.orEmpty()
                require(Tailnet.isTailIp(ip)) { "private network peers only" }
                check(Tailnet.vpnIp(this@PeerService) != null) { "Connect private network before accepting peer traffic" }
                val input = s.getInputStream().buffered()
                val line = Tailnet.readWireLine(input)
                val json = JSONObject(line)
                if (json.optString("type") == "identity_probe") {
                    check(EmbeddedTailnet.status.connected) { "Private connection is not ready" }
                    val response = JSONObject().put("ok", true).put("device", FamilyIdentity.fingerprint())
                        .put("signature", FamilyIdentity.probe(json.getString("challenge")))
                    s.getOutputStream().write((response.toString() + "\n").toByteArray(Charsets.UTF_8))
                    return
                }
                val id = json.getString("id")
                val type = json.getString("type")
                val secret = json.getString("secret")
                val result = if (type == "pair") {
                    require(secret == FamilyStore.pairingSecret) { "Invitation secret is incorrect" }
                    require(id != FamilyStore.deviceId) { "Cannot pair device with itself" }
                    val newToken = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                        .joinToString("") { "%02x".format(it) }
                    FamilyStore.upsert(Peer(id, json.optString("nick", "Family device").take(36), ip, newToken))
                    JSONObject().put("id", FamilyStore.deviceId).put("nick", FamilyStore.nickname).put("secret", newToken)
                } else {
                    val peer = FamilyStore.find(id)
                    require(peer != null && secret == peer.secret) { "Device is not paired" }
                    if (ip != peer.ip || json.optString("nick") != peer.name) {
                        FamilyStore.upsert(peer.copy(ip = ip, name = json.optString("nick", peer.name).take(36)))
                    }
                    val reply: Any = when (type) {
                        "attachment" -> {
                            s.soTimeout = 90_000
                            val group = if (json.has("groupId")) GroupStore.validateSender(id, json) else null
                            val received = AttachmentTransport.receive(this@PeerService, input, json, id)
                            val item = received.copy(peerId = group?.id ?: id, senderId = id)
                            if (FamilyStore.messages.none { it.id == item.id }) {
                                FamilyStore.addChat(item)
                                showNotification(json.optString("nick", "Family Touch"),
                                    if (item.text.contains("|image/")) "Sent a photo" else "Sent media", peerId = item.peerId)
                            }
                            JSONObject()
                        }
                        "profile_get" -> JSONObject().put("avatar", FamilyStore.profilePhoto)
                        "profile_update" -> {
                            FamilyStore.setPeerPhoto(id, json.optString("avatar"))
                            JSONObject()
                        }
                        "group_update" -> { GroupStore.receiveUpdate(id, json); JSONObject() }
                        "group_snapshot" -> GroupStore.snapshot(id, json)
                        "group_leave" -> { GroupStore.receiveLeave(id, json); JSONObject() }
                        "record_check" -> kotlinx.coroutines.withContext(Dispatchers.Main) { RecordingCoordinator.proof(id, json) }
                        "record_request", "record_vote", "record_start", "record_stop" -> {
                            kotlinx.coroutines.withContext(Dispatchers.Main) { RecordingCoordinator.receive(type, id, json) }
                            JSONObject()
                        }
                        "group_message" -> {
                            val group = GroupStore.validateSender(id, json)
                            val messageId = json.getString("messageId")
                            UUID.fromString(messageId)
                            val text = json.getString("text")
                            require(text.isNotBlank() && text.length <= 6000)
                            if (FamilyStore.messages.none { it.id == messageId }) {
                                FamilyStore.addChat(ChatItem(messageId, group.id, false, text, System.currentTimeMillis(), true, id))
                                showNotification(group.title, peer.name + ": " + text.take(80), peerId = group.id)
                            }
                            JSONObject()
                        }
                        "presence" -> JSONObject().put("protocol", 5).put("status", when {
                            FamilyStore.activeCall != null || GroupCallController.session != null -> "on_call"
                            FamilyStore.appVisible -> "available"
                            else -> "away"
                        })
                        "message" -> {
                            val txt = json.getString("text").take(6000)
                            val messageId = json.getString("messageId")
                            require(txt.isNotBlank() && messageId.length in 8..100) { "Invalid message" }
                            val exists = FamilyStore.messages.any { it.id == messageId }
                            if (!exists) {
                                FamilyStore.addChat(ChatItem(messageId, id, false, txt, System.currentTimeMillis(), true))
                                showNotification(json.optString("nick", "Family Touch"), txt.take(100), peerId = id)
                            }
                            JSONObject()
                        }
                        "read_receipt" -> {
                            val array = json.getJSONArray("messageIds")
                            require(array.length() <= 100)
                            val ids = (0 until array.length()).map { array.getString(it) }.toSet()
                            kotlinx.coroutines.withContext(Dispatchers.Main) { FamilyStore.markRead(id, ids) }
                            JSONObject()
                        }
                        "message_reaction" -> {
                            kotlinx.coroutines.withContext(Dispatchers.Main) { MessageReactions.receive(id, json) }
                            JSONObject()
                        }
                        "message_mutation" -> {
                            kotlinx.coroutines.withContext(Dispatchers.Main) { MessageMutations.receive(id, json) }
                            JSONObject()
                        }
                        "group_call_invite", "group_call_join", "group_call_join_ack", "group_call_offer", "group_call_answer", "group_call_ice", "group_call_leave" -> {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                GroupCallController.receive(type, id, json) { who -> showNotification("Incoming Family Touch group call", who, true) }
                            }
                            JSONObject()
                        }
                        "offer", "answer", "ice", "hangup" -> CallController.receive(type, id, json) { who ->
                            showNotification("Incoming Family Touch call", who, true)
                        }
                        else -> error("Unsupported request")
                    }
                    if (reply is JSONObject) reply else JSONObject()
                }
                val body = result.put("ok", true).toString() + "\n"
                s.getOutputStream().write(body.toByteArray(Charsets.UTF_8))
            } catch (ex: Exception) {
                try {
                    val body = JSONObject().put("ok", false).put("error", ex.message ?: "Invalid request").toString() + "\n"
                    s.getOutputStream().write(body.toByteArray(Charsets.UTF_8))
                } catch (_: Exception) { }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        RingtonePlayer.stop()
        listener?.close()
        listenJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
