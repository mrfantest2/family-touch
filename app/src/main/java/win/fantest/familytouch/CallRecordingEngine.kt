package win.fantest.familytouch

import android.content.Context
import android.media.*
import android.os.StatFs
import kotlinx.coroutines.*
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Records WebRTC's actual microphone plus mixed playback PCM, gated by unanimous consent. */
internal object CallRecordingEngine {
    @Volatile private var active: Recorder? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun init(context: Context) {
        RecordingLibrary.init(context)
        RecordingCoordinator.startCapture = { request, call ->
            check(active == null) { "Already recording" }
            val recorder = Recorder(context.applicationContext, request, call)
            active = recorder
            scope.launch { recorder.run() }
        }
        RecordingCoordinator.stopCapture = { val old = active; active = null; old?.stopping = true; old?.detach() }
    }
    fun samples(local: Boolean, samples: JavaAudioDeviceModule.AudioSamples) {
        val current = active ?: return
        if (!RecordingCoordinator.captureAuthorized || RecordingCoordinator.request?.id != current.request.id) return
        try {
            require(samples.audioFormat == android.media.AudioFormat.ENCODING_PCM_16BIT)
            var pcm = RecordingAudio.mono(samples.data, samples.channelCount, samples.sampleRate)
            if (local && (CallController.microphoneMuted || GroupCallController.micMuted)) pcm.fill(0)
            current.audio(local, pcm)
        } catch (_: Exception) {
            scope.launch(Dispatchers.Main) { if (RecordingCoordinator.request?.id == current.request.id) RecordingCoordinator.stop("Unsupported call audio format") }
        }
    }
    private class Recorder(val context: Context, val request: RecordingRequest, call: RecordingCall) {
        @Volatile var stopping = false
        private var startNs = 0L
        private val ringSize = RecordingAudio.RATE * 2
        private val mic = ShortArray(ringSize)
        private val playback = ShortArray(ringSize)
        private val micPositions = LongArray(ringSize) { -1 }
        private val playbackPositions = LongArray(ringSize) { -1 }
        private var writtenSamples = 0L
        private var lastSample = 0L
        private var microphonePackets = 0
        private var playbackPackets = 0
        private val order = request.participants.sorted()
        private val audioEncoder: MediaCodec
        private val videoEncoder: MediaCodec?
        private val temp: File
        private val muxer: MediaMuxer
        private var audioTrack = -1
        private var videoTrack = -1
        private var muxStarted = false
        private var nextVideoUs = 0L
        private var videoFrames = 0
        private val tiles = ConcurrentHashMap<String, ByteArray>()
        private val tileTimes = ConcurrentHashMap<String, Long>()
        private val sinks = mutableListOf<Pair<VideoTrack, VideoSink>>()
        private data class Encoded(val video: Boolean, val bytes: ByteArray, val info: MediaCodec.BufferInfo)
        private val waiting = mutableListOf<Encoded>()
        private var waitingBytes = 0
        init {
            val folder = RecordingLibrary.folder(context)
            require(StatFs(folder.absolutePath).availableBytes > 20L * 1024 * 1024) { "Not enough free storage" }
            temp = File(folder, request.id + ".partial.mp4")
            var audioCodec: MediaCodec? = null
            var videoCodec: MediaCodec? = null
            var createdMuxer: MediaMuxer? = null
            try {
                audioCodec = MediaCodec.createEncoderByType("audio/mp4a-latm")
                audioCodec.configure(MediaFormat.createAudioFormat("audio/mp4a-latm", RecordingAudio.RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 96000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioCodec.start()
                if (request.video) {
                    require(call.tracks.isNotEmpty()) { "No video available" }
                    videoCodec = MediaCodec.createEncoderByType("video/avc")
                    videoCodec.configure(MediaFormat.createVideoFormat("video/avc", 640, 480).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                        setInteger(MediaFormat.KEY_BIT_RATE, 1200000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    videoCodec.start()
                }
                createdMuxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                audioEncoder = audioCodec; videoEncoder = videoCodec; muxer = createdMuxer
                RecordingLibrary.pending(context, request)
                call.tracks.forEach { (id, track) ->
                    val last = AtomicLong(0)
                    val sink = VideoSink { frame ->
                        val now = System.nanoTime()
                        if (!stopping && RecordingCoordinator.captureAuthorized && now - last.get() > 100_000_000 && last.getAndSet(now) < now) {
                            runCatching { tiles[id] = tile(frame); tileTimes[id] = now }
                        }
                    }
                    track.addSink(sink); sinks.add(track to sink)
                }
                // Codec startup can be slow. Capture timestamps begin only when inputs can arrive.
                startNs = System.nanoTime()
            } catch (e: Exception) {
                detach()
                runCatching { audioCodec?.stop() }; audioCodec?.release()
                runCatching { videoCodec?.stop() }; videoCodec?.release()
                runCatching { createdMuxer?.release() }; temp.delete(); throw e
            }
        }
        fun detach() { sinks.forEach { (track, sink) -> runCatching { track.removeSink(sink) } } }
        @Synchronized fun audio(local: Boolean, samples: ShortArray) {
            if (stopping || samples.size > RecordingAudio.RATE) return
            val end = (System.nanoTime() - startNs) * RecordingAudio.RATE / 1_000_000_000
            val first = maxOf(0, end - samples.size)
            if (end - writtenSamples >= ringSize) { stopping = true; return }
            val destination = if (local) mic else playback
            val positions = if (local) micPositions else playbackPositions
            samples.forEachIndexed { index, sample ->
                val position = first + index
                if (position >= writtenSamples) { val slot = (position % ringSize).toInt(); destination[slot] = sample; positions[slot] = position }
            }
            lastSample = maxOf(lastSample, first + samples.size)
            if (local) microphonePackets++ else playbackPackets++
        }
        private fun feedAudio(finish: Boolean = false): Boolean {
            val target = synchronized(this) { if (finish) lastSample else maxOf(0, (System.nanoTime() - startNs) * RecordingAudio.RATE / 1_000_000_000 - 9600) }
            if (writtenSamples + 1024 > target && !finish) return false
            if (writtenSamples >= target) return false
            val inputIndex = audioEncoder.dequeueInputBuffer(10000)
            if (inputIndex < 0) { drain(audioEncoder, false); return false }
            val count = minOf(1024L, target - writtenSamples).toInt()
            val local = ShortArray(count); val remote = ShortArray(count)
            synchronized(this) {
                for (i in 0 until count) {
                    val position = writtenSamples + i; val slot = (position % ringSize).toInt()
                    if (micPositions[slot] == position) local[i] = mic[slot]
                    if (playbackPositions[slot] == position) remote[i] = playback[slot]
                }
            }
            val mixed = RecordingAudio.mix(local, remote)
            val input = requireNotNull(audioEncoder.getInputBuffer(inputIndex)); input.clear()
            mixed.forEach { sample -> input.put((sample.toInt() and 255).toByte()); input.put((sample.toInt() shr 8).toByte()) }
            audioEncoder.queueInputBuffer(inputIndex, 0, count * 2, writtenSamples * 1_000_000 / RecordingAudio.RATE, 0)
            synchronized(this) { writtenSamples += count }
            drain(audioEncoder, false); return true
        }
        private fun feedVideo(us: Long) {
            val encoder = videoEncoder ?: return
            if (us < nextVideoUs) return
            val index = encoder.dequeueInputBuffer(10000)
            if (index < 0) { drain(encoder, true); return }
            val frame = ByteArray(640 * 480 * 3 / 2).apply { fill(16, 0, 640 * 480); fill(128.toByte(), 640 * 480, size) }
            order.forEachIndexed { slot, id ->
                val tile = tiles[id]
                if (tile != null && System.nanoTime() - (tileTimes[id] ?: 0) < 1_000_000_000) {
                    val left = (slot % 2) * 320
                    val top = if (order.size <= 2) 120 else (slot / 2) * 240
                    copyPlane(tile, 0, 320, 240, frame, 0, 640, left, top)
                    copyPlane(tile, 320 * 240, 160, 120, frame, 640 * 480, 320, left / 2, top / 2)
                    copyPlane(tile, 320 * 240 * 5 / 4, 160, 120, frame, 640 * 480 * 5 / 4, 320, left / 2, top / 2)
                }
            }
            val image = requireNotNull(encoder.getInputImage(index)) { "Video encoder does not support image input" }
            val offsets = intArrayOf(0, 640 * 480, 640 * 480 * 5 / 4)
            image.planes.forEachIndexed { planeIndex, plane ->
                val width = if (planeIndex == 0) 640 else 320; val height = if (planeIndex == 0) 480 else 240
                for (row in 0 until height) for (col in 0 until width)
                    plane.buffer.put(row * plane.rowStride + col * plane.pixelStride, frame[offsets[planeIndex] + row * width + col])
            }
            encoder.queueInputBuffer(index, 0, frame.size, us, 0)
            videoFrames++; nextVideoUs = us + 100000; drain(encoder, true)
        }
        private fun copyPlane(source: ByteArray, sourceOffset: Int, width: Int, height: Int,
            target: ByteArray, targetOffset: Int, targetStride: Int, left: Int, top: Int) {
            for (row in 0 until height) System.arraycopy(source, sourceOffset + row * width, target,
                targetOffset + (top + row) * targetStride + left, width)
        }
        private fun tile(frame: VideoFrame): ByteArray {
            val rotated = frame.rotation == 90 || frame.rotation == 270
            val ratio = minOf(320.0 / frame.rotatedWidth, 240.0 / frame.rotatedHeight)
            val width = maxOf(2, ((frame.rotatedWidth * ratio).toInt() / 2) * 2)
            val height = maxOf(2, ((frame.rotatedHeight * ratio).toInt() / 2) * 2)
            val nativeWidth = if (rotated) height else width
            val nativeHeight = if (rotated) width else height
            val scaled = frame.buffer.cropAndScale(0, 0, frame.buffer.width, frame.buffer.height, nativeWidth, nativeHeight)
            val i420 = requireNotNull(scaled.toI420())
            try {
                val result = ByteArray(320 * 240 * 3 / 2).apply { fill(16, 0, 320 * 240); fill(128.toByte(), 320 * 240, size) }
                val buffers = arrayOf(i420.dataY, i420.dataU, i420.dataV)
                val strides = intArrayOf(i420.strideY, i420.strideU, i420.strideV)
                val offsets = intArrayOf(0, 320 * 240, 320 * 240 * 5 / 4)
                for (plane in 0..2) {
                    val divisor = if (plane == 0) 1 else 2
                    val sw = nativeWidth / divisor; val sh = nativeHeight / divisor
                    val stride = 320 / divisor
                    val left = (320 - width) / 2 / divisor; val top = (240 - height) / 2 / divisor
                    for (y in 0 until sh) for (x in 0 until sw) {
                        val dx: Int; val dy: Int
                        when(frame.rotation) {
                            90 -> { dx = sh - 1 - y; dy = x }
                            180 -> { dx = sw - 1 - x; dy = sh - 1 - y }
                            270 -> { dx = y; dy = sw - 1 - x }
                            else -> { dx = x; dy = y }
                        }
                        result[offsets[plane] + (top + dy) * stride + left + dx] = buffers[plane].get(y * strides[plane] + x)
                    }
                }
                return result
            } finally { i420.release(); scaled.release() }
        }
        private fun write(packet: Encoded) {
            muxer.writeSampleData(if (packet.video) videoTrack else audioTrack, ByteBuffer.wrap(packet.bytes), packet.info)
        }
        private fun drain(codec: MediaCodec, video: Boolean): Boolean {
            val info = MediaCodec.BufferInfo()
            var eos = false
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (video) videoTrack = muxer.addTrack(codec.outputFormat) else audioTrack = muxer.addTrack(codec.outputFormat)
                    if (!muxStarted && audioTrack >= 0 && (!request.video || videoTrack >= 0)) {
                        muxer.start(); muxStarted = true; waiting.forEach { write(it) }; waiting.clear(); waitingBytes = 0
                    }
                    continue
                }
                if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = requireNotNull(codec.getOutputBuffer(index)); buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size); buffer.get(bytes)
                        val copied = MediaCodec.BufferInfo().apply { set(0, bytes.size, info.presentationTimeUs, info.flags) }
                        val packet = Encoded(video, bytes, copied)
                        if (muxStarted) write(packet) else {
                            waitingBytes += bytes.size; require(waitingBytes <= 4 * 1024 * 1024) { "Encoder startup stalled" }; waiting.add(packet)
                        }
                    }
                    eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) break
                }
            }
            return eos
        }
        suspend fun run() {
            var success = false
            try {
                while (!stopping) {
                    while (feedAudio()) { /* Keep audio and video timestamps on one monotonic timeline. */ }
                    feedVideo((System.nanoTime() - startNs) / 1000)
                    if (temp.length() > 512L * 1024 * 1024 || StatFs(temp.parent!!).availableBytes < 10L * 1024 * 1024) {
                        withContext(Dispatchers.Main) { if (RecordingCoordinator.request?.id == request.id) RecordingCoordinator.stop("Recording stopped: storage limit") }
                    }
                    delay(10)
                }
                withContext(Dispatchers.Main) { if (RecordingCoordinator.request?.id == request.id) RecordingCoordinator.stop("Recording ended") }
                val audioEnd = synchronized(this) { lastSample }
                var attempts = 0
                while (writtenSamples < audioEnd && attempts++ < 500) { feedAudio(true); drain(audioEncoder, false) }
                for ((codec, isVideo) in listOfNotNull(audioEncoder to false, videoEncoder?.let { it to true })) {
                    var index = codec.dequeueInputBuffer(100000)
                    var retry = 0
                    while (index < 0 && retry++ < 20) { drain(codec, isVideo); index = codec.dequeueInputBuffer(10000) }
                    require(index >= 0)
                    codec.queueInputBuffer(index, 0, 0, (System.nanoTime() - startNs) / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    var done = false
                    repeat(100) { if (!done) { done = drain(codec, isVideo); if (!done) delay(10) } }
                    require(done) { "Encoder finalization timed out" }
                }
                require(muxStarted && microphonePackets > 0 && playbackPackets > 0 && (!request.video || videoFrames > 0)) { "Both sides of call audio were not captured" }
                muxer.stop(); success = true
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (RecordingCoordinator.request?.id == request.id) RecordingCoordinator.stop("Recording interrupted")
                    FamilyStore.lastError = "Recording could not be finalized. Check the recordings library for interrupted files."
                }
            } finally {
                detach()
                runCatching { audioEncoder.stop() }; audioEncoder.release()
                runCatching { videoEncoder?.stop() }; videoEncoder?.release()
                runCatching { muxer.release() }
                if (success) runCatching { RecordingLibrary.complete(context, request, temp, (System.nanoTime() - startNs) / 1_000_000) }
                    .onFailure { RecordingLibrary.refresh(context) }
                else RecordingLibrary.refresh(context)
            }
        }
    }
}

