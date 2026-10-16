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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class PeerService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listener: ServerSocket? = null
    private var listenJob: Job? = null
    private val channel = "familytouch_peer"

    override fun onCreate() {
        super.onCreate()
        FamilyStore.init(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channel, "Family Touch connections", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("familytouch_calls", "Incoming Family Touch calls", NotificationManager.IMPORTANCE_HIGH))
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Family Touch is ready")
            .setContentText("Listening privately through Tailscale")
            .setOngoing(true).setContentIntent(openApp()).build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(40, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(40, notification)
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

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 5, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showNotification(title: String, text: String, call: Boolean = false) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val c = if (call) "familytouch_calls" else channel
        val builder = NotificationCompat.Builder(this, c).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(text).setContentIntent(openApp()).setAutoCancel(true)
        if (call) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL).priority = NotificationCompat.PRIORITY_HIGH
        }
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 100, builder.build())
    }

    private suspend fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 8000
            try {
                val ip = s.inetAddress.hostAddress.orEmpty()
                require(Tailnet.isTailIp(ip)) { "Tailscale peers only" }
                val line = s.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                require(line != null && line.length <= 262144) { "Oversized request" }
                val json = JSONObject(line)
                val id = json.getString("id")
                val type = json.getString("type")
                val secret = json.getString("secret")
                val result = if (type == "pair") {
                    require(secret == FamilyStore.pairingSecret) { "Invitation secret is incorrect" }
                    require(id != FamilyStore.deviceId) { "Cannot pair device with itself" }
                    FamilyStore.upsert(Peer(id, json.optString("nick", "Family device").take(36), ip, secret))
                    JSONObject().put("id", FamilyStore.deviceId).put("nick", FamilyStore.nickname)
                } else {
                    val peer = FamilyStore.find(id)
                    require(peer != null && secret == peer.secret) { "Device is not paired" }
                    if (ip != peer.ip || json.optString("nick") != peer.name) {
                        FamilyStore.upsert(peer.copy(ip = ip, name = json.optString("nick", peer.name).take(36)))
                    }
                    when (type) {
                        "message" -> {
                            val txt = json.getString("text").take(6000)
                            val messageId = json.getString("messageId")
                            require(txt.isNotBlank() && messageId.length in 8..100) { "Invalid message" }
                            FamilyStore.addChat(ChatItem(messageId, id, false, txt, System.currentTimeMillis(), true))
                            showNotification(json.optString("nick", "Family Touch"), txt.take(100))
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

    override fun onDestroy() {
        listener?.close()
        listenJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
