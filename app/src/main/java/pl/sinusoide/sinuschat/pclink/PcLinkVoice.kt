package pl.sinusoide.sinuschat.pclink

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File

object PcLinkVoice {

    private const val TAG = "PcLinkVoice"
    private const val BIT_RATE = 32_000
    private const val FRAME_BYTES = 640
    private const val FRAMES_PER_BUFFER = 16
    private const val IDLE_LIMIT = 3_000
    private val RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)

    class Pcm(val data: ByteArray, val offset: Int, val length: Int, val rate: Int)

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)

    fun readWav(file: File): Pcm? {
        val b = file.readBytes()
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32(b, pos + 4)
            val body = pos + 8
            if (size < 0) return null
            when (id) {
                "fmt " -> {
                    if (size < 16 || body + 16 > b.size) return null
                    format = le16(b, body)
                    channels = le16(b, body + 2)
                    rate = le32(b, body + 4)
                    bits = le16(b, body + 14)
                }
                "data" -> {
                    if (format != 1 || bits != 16 || channels != 1 || rate !in RATES) return null
                    val len = minOf(size, b.size - body) and 1.inv()
                    return if (len > 0) Pcm(b, body, len, rate) else null
                }
            }
            pos = body + size + (size and 1)
        }
        return null
    }

    fun durationMs(pcm: Pcm): Long = pcm.length * 1000L / (pcm.rate * 2L)

    fun encodeOgg(pcm: Pcm, out: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, pcm.rate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * FRAMES_PER_BUFFER)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            val info = MediaCodec.BufferInfo()
            val bytesPerSecond = pcm.rate * 2L
            var track = -1
            var read = 0
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone && idle < IDLE_LIMIT) {
                var progressed = false
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i) ?: continue
                        buf.clear()
                        val n = minOf(buf.remaining(), FRAME_BYTES * FRAMES_PER_BUFFER, pcm.length - read) and 1.inv()
                        val pts = read * 1_000_000L / bytesPerSecond
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm.data, pcm.offset + read, n)
                            read += n
                            codec.queueInputBuffer(i, 0, n, pts, 0)
                        }
                        progressed = true
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                        progressed = true
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && started && buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        progressed = true
                    }
                }
                idle = if (progressed) 0 else idle + 1
            }
            if (!outputDone || !started) return false
            muxer.stop()
            started = false
            return out.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "opus: ${e.message}")
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
