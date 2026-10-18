package win.fantest.familytouch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Started from a visible, permission-authorized call action before the app backgrounds. */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("family_call_capture", "Family Touch calls", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2702, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "family_call_capture").setSmallIcon(R.drawable.ic_family_touch)
            .setContentTitle("Family Touch call").setContentText("Private microphone / camera in use")
            .setContentIntent(open).setOngoing(true).build()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (intent?.getBooleanExtra("video", false) == true) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (android.os.Build.VERSION.SDK_INT >= 30) startForeground(2702, notification, types)
        else startForeground(2702, notification)
        return START_NOT_STICKY
    }
    companion object {
        fun begin(context: Context, video: Boolean) {
            ContextCompat.startForegroundService(context, Intent(context, CallForegroundService::class.java).putExtra("video", video))
        }
        fun end(context: Context) { context.stopService(Intent(context, CallForegroundService::class.java)) }
    }
}

