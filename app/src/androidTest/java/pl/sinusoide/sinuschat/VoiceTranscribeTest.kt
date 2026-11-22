package pl.sinusoide.sinuschat

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.sinusoide.sinuschat.util.VoiceTranscriber
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VoiceTranscribeTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun speak(text: String, out: File): Boolean {
        val ready = CountDownLatch(1)
        var tts: TextToSpeech? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tts = TextToSpeech(ctx) { ready.countDown() }
        }
        ready.await(20, TimeUnit.SECONDS)
        val engine = tts ?: return false
        engine.language = Locale("pl", "PL")
        val done = CountDownLatch(1)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done.countDown() }
        })
        engine.synthesizeToFile(text, Bundle(), out, "t")
        done.await(30, TimeUnit.SECONDS)
        engine.shutdown()
        return out.length() > 1000
    }

    @Test
    fun transcribesOggVoiceNote() {
        val wav = File(ctx.cacheDir, "transcribe_test2.wav")
        assertTrue(speak("Spotkajmy się jutro o siedemnastej przy dworcu.", wav))
        val pcm = pl.sinusoide.sinuschat.pclink.PcLinkVoice.readWav(wav)!!
        val ogg = File(ctx.cacheDir, "transcribe_test2.ogg")
        assertTrue("brak kodowania ogg", pl.sinusoide.sinuschat.pclink.PcLinkVoice.encodeOgg(pcm, ogg))
        wav.delete()
        val latch = CountDownLatch(1)
        var outcome: VoiceTranscriber.Outcome? = null
        var text: String? = null
        val start = System.currentTimeMillis()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            VoiceTranscriber.transcribe(ctx, "ogg-" + start, ogg) { o, t -> outcome = o; text = t; latch.countDown() }
        }
        latch.await(120, TimeUnit.SECONDS)
        Log.i("VTTEST", "ogg outcome=$outcome in ${System.currentTimeMillis() - start} ms text=[$text]")
        ogg.delete()
        assertEquals(VoiceTranscriber.Outcome.OK, outcome)
        assertTrue(text!!.lowercase().contains("jutro"))
    }

    @Test
    fun transcribesPolishSpeech() {
        assertTrue("brak rozpoznawania offline", VoiceTranscriber.supported(ctx))
        val wav = File(ctx.cacheDir, "transcribe_test.wav")
        assertTrue("TTS nie zapisał pliku", speak("Dzień dobry, to jest próba zamiany wiadomości głosowej na tekst.", wav))
        val latch = CountDownLatch(1)
        var outcome: VoiceTranscriber.Outcome? = null
        var text: String? = null
        val start = System.currentTimeMillis()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            VoiceTranscriber.transcribe(ctx, "test-" + start, wav) { o, t -> outcome = o; text = t; latch.countDown() }
        }
        latch.await(120, TimeUnit.SECONDS)
        Log.i("VTTEST", "outcome=$outcome in ${System.currentTimeMillis() - start} ms text=[$text]")
        wav.delete()
        assertEquals(VoiceTranscriber.Outcome.OK, outcome)
        assertTrue(text!!.lowercase().contains("dzień dobry"))
    }
}
