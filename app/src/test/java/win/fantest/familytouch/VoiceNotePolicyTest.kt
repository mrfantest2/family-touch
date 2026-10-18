package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test

class VoiceNotePolicyTest {
    @Test fun limitIsFiveMinutesAndEmptyIsRejected() {
        assertTrue(VoiceNotePolicy.canSend(1000, 100))
        assertTrue(VoiceNotePolicy.canSend(300_000, 100))
        assertFalse(VoiceNotePolicy.canSend(300_001, 100))
        assertFalse(VoiceNotePolicy.canSend(0, 100))
        assertFalse(VoiceNotePolicy.canSend(1000, 0))
    }
    @Test fun onlyKnownAudioFormatsAccepted() {
        assertTrue(VoiceNotePolicy.audioMime("audio/mp4"))
        assertTrue(VoiceNotePolicy.audioMime("audio/wav"))
        assertFalse(VoiceNotePolicy.audioMime("audio/unknown"))
        assertFalse(VoiceNotePolicy.audioMime("application/octet-stream"))
    }
}
