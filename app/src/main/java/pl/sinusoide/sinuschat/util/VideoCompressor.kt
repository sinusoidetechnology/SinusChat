package pl.sinusoide.sinuschat.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(UnstableApi::class)
object VideoCompressor {

    private const val TAG = "VideoCompressor"
    private const val SHORT_SIDE = 720
    private const val VIDEO_BITRATE = 2_000_000
    private const val SKIP_BITRATE = 2_800_000
    private const val MIN_BYTES = 4L * 1024 * 1024
    private const val TIMEOUT_MIN = 30L
    private const val MAX_FPS = 30f

    private class Info(val shortSide: Int, val bitrate: Int)

    private fun info(file: File): Info? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                ?: run {
                    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (ms > 0) (file.length() * 8_000L / ms).toInt() else Int.MAX_VALUE
                }
            Info(minOf(w, h), bitrate)
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    fun worthIt(file: File): Boolean {
        if (file.length() < MIN_BYTES) return false
        val i = info(file) ?: return false
        return i.shortSide > SHORT_SIDE || i.bitrate > SKIP_BITRATE
    }

    fun compress(
        ctx: Context,
        src: File,
        dst: File,
        isCancelled: () -> Boolean
    ): Boolean {
        val i = info(src) ?: return false
        val app = ctx.applicationContext
        val thread = HandlerThread("video-compress").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        dst.delete()
        handler.post {
            try {
                val encoder = DefaultEncoderFactory.Builder(app)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(minOf(VIDEO_BITRATE, i.bitrate)).build()
                    )
                    .build()
                val transformer = Transformer.Builder(app)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoder)
                    .setUsePlatformDiagnostics(false)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            ok.set(true)
                            done.countDown()
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            Log.w(TAG, "eksport: ${exportException.errorCodeName} ${exportException.message}")
                            done.countDown()
                        }
                    })
                    .build()
                val video = listOfNotNull(
                    FrameDropEffect.createDefaultFrameDropEffect(MAX_FPS),
                    if (i.shortSide > SHORT_SIDE) Presentation.createForShortSide(SHORT_SIDE) else null
                )
                val effects = Effects(emptyList(), video)
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src))).setEffects(effects).build()
                val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                    .build()
                transformer.start(composition, dst.absolutePath)
                handler.post(object : Runnable {
                    override fun run() {
                        if (done.count == 0L) return
                        if (isCancelled()) {
                            transformer.cancel()
                            done.countDown()
                            return
                        }
                        handler.postDelayed(this, 300)
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "start: ${e.message}")
                done.countDown()
            }
        }
        val finished = done.await(TIMEOUT_MIN, TimeUnit.MINUTES)
        thread.quitSafely()
        val good = finished && ok.get() && !isCancelled() && dst.length() > 0
        if (!good) dst.delete()
        return good
    }
}
