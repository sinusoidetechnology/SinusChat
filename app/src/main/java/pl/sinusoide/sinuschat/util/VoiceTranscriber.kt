package pl.sinusoide.sinuschat.util

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object VoiceTranscriber {

    enum class Outcome { OK, EMPTY, UNSUPPORTED, NO_LANGUAGE, FAILED }

    private const val TAG = "VoiceTranscriber"
    private const val RATE = 16000
    private const val MAX_SECONDS = 30 * 60

    val cache = ConcurrentHashMap<String, String>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())

    fun supported(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)

    fun isBusy(id: String) = id in busy

    private fun languageTag(ctx: Context): String = when (Translations.appLang(ctx)) {
        "pl" -> "pl-PL"
        "cs" -> "cs-CZ"
        "uk" -> "uk-UA"
        "de" -> "de-DE"
        "zh" -> "zh-TW"
        else -> "en-US"
    }

    fun transcribe(ctx: Context, id: String, file: File, done: (Outcome, String?) -> Unit) {
        val app = ctx.applicationContext
        if (!supported(app)) { done(Outcome.UNSUPPORTED, null); return }
        cache[id]?.let { done(Outcome.OK, it); return }
        if (!busy.add(id)) return
        val finish: (Outcome, String?) -> Unit = { o, t ->
            busy.remove(id)
            if (o == Outcome.OK && t != null) cache[id] = t
            main.post { done(o, t) }
        }
        AppExecutor.io {
            val pcm = try { decode(file) } catch (t: Throwable) {
                Log.w(TAG, "decode: ${t.javaClass.simpleName}")
                null
            }
            if (pcm == null || pcm.isEmpty()) { finish(Outcome.FAILED, null); return@io }
            main.post { recognize(app, pcm, finish) }
        }
    }

    private fun recognize(app: Context, pcm: ByteArray, finish: (Outcome, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { finish(Outcome.UNSUPPORTED, null); return }
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (t: Throwable) { finish(Outcome.FAILED, null); return }
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val recognizer = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(app) } catch (t: Throwable) {
            readEnd.close(); writeEnd.close(); finish(Outcome.UNSUPPORTED, null); return
        }
        val parts = mutableListOf<String>()
        var ended = false
        val seconds = pcm.size / (RATE * 2)
        val tag = languageTag(app)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
        lateinit var timeout: Runnable
        fun end(o: Outcome, text: String?) {
            if (ended) return
            ended = true
            main.removeCallbacks(timeout)
            runCatching { recognizer.destroy() }
            runCatching { readEnd.close() }
            runCatching { writeEnd.close() }
            finish(o, text)
        }
        fun collect(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        fun joined(): String? = parts.joinToString(" ").trim().takeIf { it.isNotEmpty() }
        timeout = Runnable { val t = joined(); end(if (t != null) Outcome.OK else Outcome.FAILED, t) }
        main.postDelayed(timeout, (seconds * 3_000L + 30_000L))
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onSegmentResults(segmentResults: Bundle) { collect(segmentResults) }
            override fun onEndOfSegmentedSession() {
                val t = joined()
                end(if (t != null) Outcome.OK else Outcome.EMPTY, t)
            }
            override fun onResults(results: Bundle?) {
                collect(results)
                val t = joined()
                end(if (t != null) Outcome.OK else Outcome.EMPTY, t)
            }
            override fun onError(error: Int) {
                Log.w(TAG, "error $error")
                val t = joined()
                when {
                    t != null -> end(Outcome.OK, t)
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                        runCatching { recognizer.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                        }) }
                        end(Outcome.NO_LANGUAGE, null)
                    }
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> end(Outcome.EMPTY, null)
                    else -> end(Outcome.FAILED, null)
                }
            }
        })
        try {
            recognizer.startListening(intent)
        } catch (t: Throwable) {
            end(Outcome.FAILED, null)
            return
        }
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    var off = 0
                    val chunk = RATE * 2 / 5
                    while (off < pcm.size) {
                        val n = minOf(chunk, pcm.size - off)
                        out.write(pcm, off, n)
                        off += n
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "pipe: ${t.javaClass.simpleName}")
            }
        }, "voice-transcribe").start()
    }

    private fun decode(file: File): ByteArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            ex.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pos = 0.0
            val limit = MAX_SECONDS.toLong() * RATE * 2
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val buf = codec.getInputBuffer(ii)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        oi >= 0 -> {
                            val buf = codec.getOutputBuffer(oi)
                            if (buf != null && info.size > 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val shorts = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val frames = shorts.remaining() / channels.coerceAtLeast(1)
                                val step = rate.toDouble() / RATE
                                val mono = ShortArray(frames)
                                for (fi in 0 until frames) {
                                    var sum = 0
                                    for (c in 0 until channels) sum += shorts.get(fi * channels + c)
                                    mono[fi] = (sum / channels.coerceAtLeast(1)).toShort()
                                }
                                while (pos < frames) {
                                    val s = mono[pos.toInt()].toInt()
                                    out.write(s and 0xFF)
                                    out.write((s shr 8) and 0xFF)
                                    pos += step
                                }
                                pos -= frames
                            }
                            codec.releaseOutputBuffer(oi, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (out.size() >= limit) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            return out.toByteArray()
        } finally {
            ex.release()
        }
    }
}
