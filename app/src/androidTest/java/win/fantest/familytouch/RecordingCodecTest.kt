package win.fantest.familytouch

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.math.sin

/** Uses an isolated QA package and synthetic PCM; never records the owner's microphone. */
class RecordingCodecTest {
    @Test fun mixedAudioFinalizesAsPlayableLocalM4a() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.Main) { FamilyStore.init(context) }
        val request = RecordingRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            FamilyStore.deviceId, setOf(FamilyStore.deviceId, "synthetic-remote"), false)
        val type = Class.forName("win.fantest.familytouch.CallRecordingEngine\$Recorder")
        val constructor = type.getDeclaredConstructor(Context::class.java, RecordingRequest::class.java, RecordingCall::class.java)
            .apply { isAccessible = true }
        val constructionStarted = System.nanoTime()
        val recorder = constructor.newInstance(context, request, RecordingCall(request.sessionId, request.participants, false, emptyMap()))
        val constructionFinished = System.nanoTime()
        val startedAt = type.getDeclaredField("startNs").apply { isAccessible = true }.getLong(recorder)
        val startupMs = (System.nanoTime() - startedAt) / 1_000_000
        val audio = type.getDeclaredMethod("audio", Boolean::class.javaPrimitiveType, ShortArray::class.java).apply { isAccessible = true }
        val run = type.getDeclaredMethod("run", kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
        val stop = type.getDeclaredField("stopping").apply { isAccessible = true }
        val job = async(Dispatchers.IO) {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation -> run.invoke(recorder, continuation) }
        }
        try {
            assertTrue("Capture clock includes codec initialization", startedAt >= constructionStarted + (constructionFinished - constructionStarted) / 2)
            repeat(120) { packet ->
                val local = ShortArray(480) { (sin((packet * 480 + it) * 2 * Math.PI * 440 / 48000) * 6000).toInt().toShort() }
                val remote = ShortArray(480) { (sin((packet * 480 + it) * 2 * Math.PI * 660 / 48000) * 6000).toInt().toShort() }
                audio.invoke(recorder, true, local); audio.invoke(recorder, false, remote)
                delay(10)
            }
        } finally { stop.setBoolean(recorder, true) }
        withTimeout(15000) { job.await() }
        val file = java.io.File(RecordingLibrary.folder(context), request.id + ".m4a")
        try {
            val counts = listOf("microphonePackets", "playbackPackets", "writtenSamples", "lastSample").joinToString {
                "$it=${type.getDeclaredField(it).apply { isAccessible = true }.get(recorder)}"
            }
            assertTrue("Recorder must finalize a local M4A (startup=${startupMs}ms, $counts)", file.isFile && file.length() > 1000)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals(1, extractor.trackCount)
                assertEquals("audio/mp4a-latm", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                extractor.selectTrack(0)
                assertTrue(extractor.readSampleData(java.nio.ByteBuffer.allocate(65536), 0) > 0)
            } finally { extractor.release() }
        } finally {
            // Only this test's UUID files inside the separate QA package are removed.
            listOf(".m4a", ".partial.mp4", ".json").forEach {
                java.io.File(RecordingLibrary.folder(context), request.id + it).delete()
            }
        }
    }
}
