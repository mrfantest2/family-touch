package win.fantest.familytouch
import org.junit.Assert.*
import org.junit.Test
class RecordingAudioTest {
    @Test fun mixesBothDirectionsAndClipsWithoutOverflow() {
        assertArrayEquals(shortArrayOf(1000, -1000, 32767, -32768),
            RecordingAudio.mix(shortArrayOf(500, -400, 30000, -30000), shortArrayOf(500, -600, 30000, -30000)))
    }
    @Test fun decodesStereoAndResamplesToMono() {
        val bytes = byteArrayOf(0x10, 0, 0x30, 0, 0x20, 0, 0x40, 0)
        assertArrayEquals(shortArrayOf(0x20, 0x30), RecordingAudio.mono(bytes, 2, 48000))
        assertEquals(4, RecordingAudio.mono(bytes, 2, 24000).size)
    }
    @Test fun malformedPcmIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { RecordingAudio.mono(byteArrayOf(1), 2, 48000) }
        assertThrows(IllegalArgumentException::class.java) { RecordingAudio.mono(byteArrayOf(1, 2), 0, 48000) }
    }
}
