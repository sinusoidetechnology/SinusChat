package pl.sinusoide.sinuschat.util

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

object TransferStats {

    private const val SAMPLE_MS = 700L
    private const val SMOOTHING = 0.3
    private const val MIN_BYTES = 1024L * 1024

    private class Stat {
        @Volatile var done = 0L
        @Volatile var total = 0L
        @Volatile var rate = 0.0
        var sampleAt = SystemClock.elapsedRealtime()
        var sampleBytes = 0L
    }

    private val stats = ConcurrentHashMap<String, Stat>()

    fun update(id: String, done: Long, total: Long) {
        val s = stats.getOrPut(id) { Stat() }
        synchronized(s) {
            if (done < s.sampleBytes) {
                s.sampleBytes = done
                s.sampleAt = SystemClock.elapsedRealtime()
            }
            s.done = done
            if (total > 0) s.total = total
            val now = SystemClock.elapsedRealtime()
            val dt = now - s.sampleAt
            if (dt < SAMPLE_MS) return
            val inst = (done - s.sampleBytes) * 1000.0 / dt
            s.rate = if (s.rate <= 0.0) inst else s.rate * (1 - SMOOTHING) + inst * SMOOTHING
            s.sampleAt = now
            s.sampleBytes = done
        }
    }

    fun updateFraction(id: String, fraction: Float, total: Long) {
        if (total <= 0) return
        update(id, (fraction.coerceIn(0f, 1f) * total).toLong(), total)
    }

    fun end(id: String) {
        stats.remove(id)
    }

    fun label(ctx: Context, id: String): String? {
        val s = stats[id] ?: return null
        if (s.total < MIN_BYTES || s.rate <= 0.0 || s.done >= s.total) return null
        val speed = MediaManager.formatSize(s.rate.toLong())
        val eta = VideoThumbs.formatDuration(((s.total - s.done) / s.rate * 1000).toLong())
        return ctx.getString(pl.sinusoide.sinuschat.R.string.transfer_speed_eta, speed, eta)
    }
}
