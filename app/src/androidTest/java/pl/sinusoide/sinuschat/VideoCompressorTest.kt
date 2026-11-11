package pl.sinusoide.sinuschat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.sinusoide.sinuschat.util.VideoCompressor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoCompressorTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun makeSource(out: File): Boolean {
        val png = File(ctx.cacheDir, "vc_frame.png")
        val bmp = Bitmap.createBitmap(3840, 2160, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        val rnd = Random(7)
        for (i in 0 until 4000) {
            p.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            val x = rnd.nextFloat() * 3840
            val y = rnd.nextFloat() * 2160
            c.drawRect(x, y, x + 40 + rnd.nextFloat() * 160, y + 40 + rnd.nextFloat() * 160, p)
        }
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val thread = HandlerThread("vc-src").apply { start() }
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        Handler(thread.looper).post {
            val t = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(40_000_000).build()).build())
                .setUsePlatformDiagnostics(false)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { ok.set(true); done.countDown() }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        Log.e("VideoCompressorTest", "źródło: ${exportException.message}"); done.countDown()
                    }
                }).build()
            val item = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(Uri.fromFile(png)).setImageDurationMs(8_000).build()
            ).setFrameRate(30).build()
            t.start(item, out.absolutePath)
        }
        done.await(5, TimeUnit.MINUTES)
        thread.quitSafely()
        png.delete()
        return ok.get() && out.length() > 0
    }

    @Test
    fun shrinks4kVideoTo720p() {
        val src = File(ctx.cacheDir, "vc_src.mp4")
        val dst = File(ctx.cacheDir, "vc_dst.mp4")
        try {
            assertTrue("nie powstał film testowy", makeSource(src))
            assertTrue("kompresja uznana za zbędną", VideoCompressor.worthIt(src))
            val started = System.currentTimeMillis()
            var last = 0f
            val ok = VideoCompressor.compress(ctx, src, dst, { false }) { last = it }
            val took = System.currentTimeMillis() - started
            assertTrue("kompresja nieudana", ok)
            val r = MediaMetadataRetriever()
            r.setDataSource(dst.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            r.release()
            Log.i("VideoCompressorTest", "źródło ${src.length() / 1024} KB -> ${dst.length() / 1024} KB, ${w}x$h, $took ms, postęp $last")
            assertTrue("krótszy bok $w x $h", minOf(w, h) <= 720)
            assertTrue("plik nie zmalał", dst.length() * 3 < src.length())
        } finally {
            src.delete()
            dst.delete()
        }
    }
}
