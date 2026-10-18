package win.fantest.familytouch

internal object RecordingAudio {
    const val RATE = 48000
    fun mix(local: ShortArray, remote: ShortArray): ShortArray {
        require(local.size == remote.size)
        return ShortArray(local.size) { (local[it].toInt() + remote[it].toInt()).coerceIn(-32768, 32767).toShort() }
    }
    fun mono(bytes: ByteArray, channels: Int, rate: Int): ShortArray {
        require(channels in 1..2 && rate in 8000..192000 && bytes.isNotEmpty() && bytes.size % (2 * channels) == 0)
        val frames = bytes.size / (2 * channels)
        val mono = ShortArray(frames) { frame ->
            var sum = 0
            for (channel in 0 until channels) {
                val index = (frame * channels + channel) * 2
                sum += ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
            }
            (sum / channels).toShort()
        }
        if (rate == RATE) return mono
        val size = maxOf(1, (frames.toLong() * RATE / rate).toInt())
        return ShortArray(size) { index ->
            val position = index.toDouble() * rate / RATE
            val first = position.toInt().coerceAtMost(mono.lastIndex)
            val second = (first + 1).coerceAtMost(mono.lastIndex)
            (mono[first] + (mono[second] - mono[first]) * (position - first)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
