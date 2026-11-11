package pl.sinusoide.sinuschat

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.sinusoide.sinuschat.util.FileUploader
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class VoiceHostingTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun voiceGoesThroughHostingAndBack() {
        val src = File(ctx.cacheDir, "vh_src.ogg").apply { writeBytes(Random(3).nextBytes(300_000)) }
        val dst = File(ctx.cacheDir, "vh_dst.ogg")
        try {
            val started = System.currentTimeMillis()
            val up = FileUploader.uploadVoiceBlocking(ctx, src, maxDownloads = 1)
            assertNotNull("wysłanie nieudane", up)
            val (id, key) = up!!
            assertTrue("głosówka nie trafiła na hosting: $id", FileUploader.isHostedVoice(id))
            assertTrue("pobranie nieudane", FileUploader.downloadVoiceToFile(ctx, id, key, dst))
            assertArrayEquals(src.readBytes(), dst.readBytes())
            Log.i("VoiceHostingTest", "OK w ${System.currentTimeMillis() - started} ms, id=${id.take(8)}")
        } finally {
            src.delete()
            dst.delete()
        }
    }
}
