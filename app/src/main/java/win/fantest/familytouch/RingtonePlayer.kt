package win.fantest.familytouch

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/** Original Family Touch melody. Sound is played only while an incoming call is ringing. */
object RingtonePlayer {
    private var player: MediaPlayer? = null

    @Synchronized
    fun start(context: Context) {
        if (player?.isPlaying == true) return
        stop()
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            player = MediaPlayer.create(context.applicationContext, R.raw.family_touch_ring, attributes, 0)?.apply {
                setWakeMode(context.applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                isLooping = true
                setVolume(0.8f, 0.8f)
                start()
            }
        } catch (_: Exception) { stop() }
    }

    @Synchronized
    fun stop() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }
}
