package win.fantest.familytouch

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Short local confirmation only after a message has been accepted into the outbox. */
internal object ChatSendSound {
    fun play() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35) }.getOrNull() ?: return
        if (!tone.startTone(ToneGenerator.TONE_PROP_ACK, 85)) {
            tone.release()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 180)
    }
}
