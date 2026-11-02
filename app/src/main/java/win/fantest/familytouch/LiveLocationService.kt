package win.fantest.familytouch

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** Live sharing is started by an explicit foreground user action, never a remote request. */
internal object LiveLocationSharing {
    data class Active(val peerId: String, val sessionId: String, val expiresAt: Long)
    var active by mutableStateOf<Active?>(null)
        private set

    fun begin(context: Context, peer: Peer, duration: Long) {
        require(LiveLocationProtocol.mayStart(duration))
        require(FamilyStore.find(peer.id) != null && GroupStore.find(peer.id) == null)
        check(FamilyStore.appVisible) { "Open Family Touch before starting location sharing" }
        check(active == null) { "Stop the active location share first" }
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        check(fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            "Location permission is required"
        }
        // Require a visible Stop-sharing notification for the entire session.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            check(ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                "Enable Family Touch notifications to show the Stop sharing control"
            }
        }
        val expiry = System.currentTimeMillis() + duration
        val session = UUID.randomUUID().toString()
        val intent = Intent(context, LiveLocationService::class.java).setAction(LiveLocationService.START)
            .putExtra("peer", peer.id).putExtra("session", session).putExtra("expiry", expiry)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        if (active != null) context.startService(
            Intent(context, LiveLocationService::class.java).setAction(LiveLocationService.STOP)
        )
    }

    internal fun update(value: Active?) { active = value }
}

/** Receiver called ONLY from an already authenticated and paired PeerService request. */
internal object LiveLocationReceiver {
    fun receive(sender: String, json: JSONObject) {
        require(FamilyStore.find(sender) != null)
        val fix = LiveLocationProtocol.fromWire(json, System.currentTimeMillis())
        FamilyStore.upsertLiveLocation(fix, sender, outgoing = false)
    }
}

/** FGS uses only foreground-started while-in-use location. Has persistent Stop notification. */
internal class LiveLocationService : Service(), LocationListener {
    companion object {
        const val START = "win.fantest.familytouch.live.START"
        const val STOP = "win.fantest.familytouch.live.STOP"
        private const val CHANNEL = "ft_active_live_location_v1"
        private const val NOTIF = 4932
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listener: LocationManager? = null
    private var expiryJob: Job? = null
    private var peerId = ""
    private var sessionId = ""
    private var expiresAt = 0L
    private var lastSent = 0L
    private var lastFix: LiveLocationFix? = null
    private var stopping = true

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live location sharing",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Your location is being shared with a chosen family contact"
            setShowBadge(false)
        })
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, LiveLocationService::class.java)
            .setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val homeIntent = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val recipient = FamilyStore.find(peerId)?.name ?: "a family contact"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Live location sharing active")
            .setContentText("Sharing with " + recipient + " · Tap Stop to end")
            .setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(homeIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            endShare()
            return START_NOT_STICKY
        }
        if (intent?.action != START || !stopping) return START_NOT_STICKY
        try {
            val peer = intent.getStringExtra("peer").orEmpty()
            val session = UUID.fromString(intent.getStringExtra("session")).toString()
            val end = intent.getLongExtra("expiry", 0L)
            val now = System.currentTimeMillis()
            require(FamilyStore.find(peer) != null && GroupStore.find(peer) == null)
            require(end > now && end <= now + LiveLocationProtocol.MAX_DURATION_MS + 30_000)
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            check(fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED)
            peerId = peer
            sessionId = session
            expiresAt = end
            stopping = false
            startForeground(NOTIF, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            LiveLocationSharing.update(LiveLocationSharing.Active(peer, session, end))
            subscribe()
            expiryJob = scope.launch {
                delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
                main.post { endShare() }
            }
        } catch (e: Exception) {
            FamilyStore.lastError = "Could not start live location: " + (e.message ?: "Unavailable")
            endShare()
        }
        // No auto restart after crash/reboot; a new explicit user action is required.
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun subscribe() {
        val lm = getSystemService(LocationManager::class.java)
        listener = lm
        var subscribed = false
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 20_000L, 5f, this, Looper.getMainLooper())
                    subscribed = true
                }
            } catch (_: SecurityException) { }
        }
        check(subscribed) { "Enable Android Location services" }
    }

    override fun onLocationChanged(location: Location) {
        if (stopping || System.currentTimeMillis() >= expiresAt) {
            endShare()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastSent > 0 && now - lastSent < 20_000L) return
        if (!LiveLocationProtocol.validCoordinates(location.latitude, location.longitude)) return
        // Do not share stale or unusably inaccurate measurements.
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (age < 0 || age > 30_000_000_000L ||
            (location.hasAccuracy() && location.accuracy > 2500f)) return
        lastSent = now
        val fix = LiveLocationFix(sessionId, expiresAt, location.latitude, location.longitude, false)
        lastFix = fix
        FamilyStore.upsertLiveLocation(fix, peerId, outgoing = true)
        scope.launch { send(fix) }
    }

    private suspend fun send(fix: LiveLocationFix) {
        val peer = FamilyStore.find(peerId) ?: return
        try {
            Tailnet.send(peer, "live_location", LiveLocationProtocol.payload(fix))
            main.post { FamilyStore.delivered(fix.sessionId) }
        } catch (_: Exception) {
            // Best-effort update; next fix is allowed to retry.
        }
    }

    private fun endShare() {
        if (stopping) {
            stopSelf()
            return
        }
        stopping = true
        expiryJob?.cancel()
        listener?.removeUpdates(this)
        LiveLocationSharing.update(null)
        val last = lastFix
        if (last == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val ended = last.copy(ended = true)
        FamilyStore.upsertLiveLocation(ended, peerId, outgoing = true)
        // Give a single end update up to the network timeout; then always stop.
        scope.launch {
            try { send(ended) }
            finally {
                main.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        listener?.removeUpdates(this)
        expiryJob?.cancel()
        LiveLocationSharing.update(null)
        scope.cancel()
        super.onDestroy()
    }
}
