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
import java.util.UUID

class PeerService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listener: ServerSocket? = null
    private var listenJob: Job? = null
    private val channel = "familytouch_status_silent_v3"
    private val messageChannel = "familytouch_messages_v3"
    private val callChannel = "familytouch_calls_v3"

    override fun onCreate() {
        super.onCreate()
        FamilyStore.init(this)
        Tailnet.init(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channel, "Family Touch background connection",
            NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(messageChannel, "Family Touch messages",
            NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(callChannel, "Family Touch incoming calls",
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null,null); enableVibration(false)
        })
        val notification = connectionNotification()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(40, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(40, notification)
        scope.launch {
            var wasConnected = Tailnet.vpnIp(this@PeerService) != null
            while (isActive) {
                delay(60_000)
                val connected = Tailnet.vpnIp(this@PeerService) != null
                if (wasConnected != connected) {
                    wasConnected = connected
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
            try {
                ServerSocket(Tailnet.PORT).use { server ->
                    listener = server
                    while (isActive) {
                        val socket = server.accept()
                        launch { handle(socket) }
                    }
                }
            } catch (ex: Exception) {
                if (isActive) FamilyStore.lastError = "Peer listener unavailable: " + ex.message
            }
        }
    }

    private fun connectionNotification(): Notification {
        val connected = Tailnet.vpnIp(this) != null
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (connected) "Family Touch connected" else "Family Touch needs Tailscale")
            .setContentText(if (connected) "Connected through Tailscale • Messages and calls ready"
                else "Connect Tailscale to receive private messages and calls")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 5, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showNotification(title: String, text: String, call: Boolean = false, peerId: String? = null) {
        if (!call && FamilyStore.appVisible && FamilyStore.selectedPeer == peerId) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val c = if (call) callChannel else messageChannel
        val builder = NotificationCompat.Builder(this, c).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(text).setContentIntent(openApp()).setAutoCancel(true)
        if (call) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH)
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
                require(Tailnet.isTailIp(ip)) { "Tailscale peers only" }
                check(Tailnet.vpnIp(this@PeerService) != null) { "Connect Tailscale before accepting peer traffic" }
                val input = s.getInputStream().buffered()
                val line = Tailnet.readWireLine(input)
                val json = JSONObject(line)
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
                    when (type) {
                        "attachment" -> {
                            s.soTimeout = 90_000
                            val item = AttachmentTransport.receive(this@PeerService, input, json, id)
                            if (FamilyStore.messages.none { it.id == item.id }) {
                                FamilyStore.addChat(item)
                                showNotification(json.optString("nick", "Family Touch"),
                                    if (item.text.contains("|image/")) "Sent a photo" else "Sent a video", peerId = id)
                            }
                            JSONObject()
                        }
                        "profile_get" -> JSONObject().put("avatar", FamilyStore.profilePhoto)
                        "profile_update" -> {
                            FamilyStore.setPeerPhoto(id, json.optString("avatar"))
                            JSONObject()
                        }
                        "presence" -> JSONObject().put("status", when {
                            FamilyStore.activeCall != null -> "on_call"
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
                        }
                        "offer", "answer", "ice", "hangup" -> CallController.receive(type, id, json) { who ->
                            showNotification("Incoming Family Touch call", who, true)
                        }
                        else -> error("Unsupported request")
                    }
                    JSONObject()
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
