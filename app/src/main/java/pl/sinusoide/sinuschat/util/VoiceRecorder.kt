package pl.sinusoide.sinuschat.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class VoiceRecorder(private val context: Context) {

    private val TAG = "VoiceRecorder"
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs = 0L

    val durationSeconds: Int
        get() = if (startTimeMs > 0) ((System.currentTimeMillis() - startTimeMs) / 1000).toInt() else 0

    var lastDurationSeconds: Int = 0
        private set

    @Volatile var limitReached = false
        private set

    companion object {
        const val MAX_BYTES = 149L * 1024 * 1024
    }

    fun start(): Boolean {
        return try {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.ogg")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setMaxFileSize(MAX_BYTES)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) limitReached = true
                }
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            outputFile = file
            limitReached = false
            startTimeMs = System.currentTimeMillis()
            Log.d(TAG, "Recording started: ${file.path}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Start error", e)
            false
        }
    }

    fun getAmplitude(): Int {
        return try {
            val max = recorder?.maxAmplitude ?: 0
            (max / 327).coerceIn(0, 100)
        } catch (_: Exception) { 0 }
    }

    fun stop(): File? {
        if (limitReached) {
            lastDurationSeconds = durationSeconds
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            startTimeMs = 0
            val file = outputFile
            outputFile = null
            return file?.takeIf { it.exists() && it.length() > 0 }
        }
        return try {
            val duration = durationSeconds
            lastDurationSeconds = duration
            recorder?.stop()
            recorder?.release()
            recorder = null
            startTimeMs = 0
            if (duration < 1) {
                outputFile?.delete()
                Log.d(TAG, "Recording too short, discarded")
                null
            } else {
                val file = outputFile
                outputFile = null
                Log.d(TAG, "Recording stopped, file: ${file?.length()} bytes, ${duration}s")
                file
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
            recorder?.release(); recorder = null
            outputFile?.delete(); outputFile = null
            null
        }
    }

    fun cancel() {
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
        startTimeMs = 0
        Log.d(TAG, "Recording cancelled")
    }
}
