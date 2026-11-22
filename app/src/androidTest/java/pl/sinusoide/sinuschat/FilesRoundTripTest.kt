package pl.sinusoide.sinuschat

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.sinusoide.sinuschat.util.FileUploader
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class FilesRoundTripTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sha(f: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(f.readBytes())

    private fun roundTrip(size: Int) {
        val src = File(ctx.cacheDir, "rt_src_$size.bin").apply { writeBytes(Random.nextBytes(size)) }
        val back = File(ctx.cacheDir, "rt_back_$size.bin")
        try {
            val start = System.currentTimeMillis()
            val up = FileUploader.uploadEncryptedFileBlocking(ctx, src, "test.bin", 1)
            assertNotNull("wysyłka nieudana", up)
            Log.i("RTTEST", "size=$size upload ${System.currentTimeMillis() - start} ms id=${up!!.fileId}")
            var gone = false
            assertTrue("pobranie nieudane", FileUploader.downloadDecryptToFile(ctx, up.fileId, up.fileKey, back))
            assertArrayEquals("plik po pobraniu inny", sha(src), sha(back))
            back.delete()
            Thread.sleep(1500)
            assertFalse("plik nie zniknął po pobraniu",
                FileUploader.downloadDecryptToFile(ctx, up.fileId, up.fileKey, back, onMissing = { gone = true }))
            assertTrue("drugie pobranie nie dało 404", gone)
            Log.i("RTTEST", "size=$size OK")
        } finally {
            src.delete()
            back.delete()
        }
    }

    @Test
    fun smallFileRoundTrip() = roundTrip(700_000)

    @Test
    fun chunkedFileRoundTrip() = roundTrip(37_000_000)
}
