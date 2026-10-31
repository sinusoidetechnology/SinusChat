package pl.sinusoide.sinuschat

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ModelDownloadService : Service() {

    companion object {
        const val CHANNEL_ID     = "sinia_download"
        const val NOTIF_ID       = 9201
        const val ACTION_START   = "START_DOWNLOAD"
        const val ACTION_CANCEL  = "CANCEL_DOWNLOAD"
        const val EXTRA_URL      = "model_url"
        const val EXTRA_FILENAME = "model_filename"
        const val EXTRA_LABEL    = "model_label"

        const val ERR_CANCELLED = "CANCELLED"

        private const val CHUNK = 32L * 1024 * 1024
        private const val WORKERS = 4
        private const val CHUNK_RETRIES = 5
        private const val TAG = "SinusDownload"
    }

    private class HttpError(message: String) : IOException(message)

    data class Job(val url: String, val filename: String, val label: String)

    inner class DownloadBinder : Binder() {
        fun getService(): ModelDownloadService = this@ModelDownloadService
    }

    private val binder = DownloadBinder()
    override fun onBind(intent: Intent): IBinder = binder

    private val uiHandler = Handler(Looper.getMainLooper())
    private val executor  = Executors.newSingleThreadExecutor()
    private var task      : Future<*>? = null
    private lateinit var notifManager: NotificationManager

    private val lock    = Any()
    private val pending = ArrayList<Job>()
    @Volatile private var current    : Job? = null
    @Volatile private var cancelFlag = false
    private var completed = 0

    var onProgress     : ((Job, Long, Long, Double, String) -> Unit)? = null
    var onFinished     : ((Job, Boolean, String?) -> Unit)? = null
    var onQueueChanged : (() -> Unit)? = null

    val isDownloading: Boolean get() = current != null
    fun currentJob(): Job? = current
    fun pendingJobs(): List<Job> = synchronized(lock) { pending.toList() }
    fun totalCount(): Int = completed + (if (current != null) 1 else 0) + synchronized(lock) { pending.size }

    fun isQueued(filename: String): Boolean =
        current?.filename == filename || synchronized(lock) { pending.any { it.filename == filename } }

    override fun onCreate() {
        super.onCreate()
        notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelAll()
            ACTION_START  -> {
                val url      = intent.getStringExtra(EXTRA_URL)      ?: return START_NOT_STICKY
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: return START_NOT_STICKY
                val label    = intent.getStringExtra(EXTRA_LABEL)    ?: filename
                enqueue(Job(url, filename, label))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        synchronized(lock) { pending.clear() }
        cancelFlag = true
        task?.cancel(true)
        executor.shutdown()
    }

    fun enqueue(job: Job) {
        if (isQueued(job.filename)) return
        synchronized(lock) { pending.add(job) }
        try {
            androidx.core.app.ServiceCompat.startForeground(this, NOTIF_ID,
                buildNotification(job.label, getString(R.string.sinia_queue_waiting), indet = true),
                if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            android.util.Log.w("ModelDownload", "Nie można uruchomić pobierania w tle: ${e.javaClass.simpleName}")
            synchronized(lock) { pending.remove(job) }
            stopSelf()
            return
        }
        notifyQueue()
        pump()
    }

    fun dequeue(filename: String) {
        val removed = synchronized(lock) { pending.removeAll { it.filename == filename } }
        if (removed) notifyQueue()
    }

    fun cancelCurrent() {
        if (current == null) { stopEverything(); return }
        cancelFlag = true
        task?.cancel(true)
    }

    fun cancelAll() {
        synchronized(lock) { pending.clear() }
        notifyQueue()
        if (current == null) stopEverything() else { cancelFlag = true; task?.cancel(true) }
    }

    private fun pump() {
        if (current != null) return
        val next = synchronized(lock) { if (pending.isEmpty()) null else pending.removeAt(0) }
        if (next == null) { stopEverything(); return }
        current = next; cancelFlag = false
        notifyQueue()
        task = executor.submit { download(next) }
    }

    private fun notifyQueue() { uiHandler.post { onQueueChanged?.invoke() } }

    private fun stopEverything() {
        current = null
        completed = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyQueue()
        stopSelf()
    }

    private fun jobDone(job: Job, ok: Boolean, error: String?) {
        uiHandler.post {
            current = null
            if (ok) completed++
            onFinished?.invoke(job, ok, error)
            pump()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.sinia_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.sinia_notif_channel_desc)
                setShowBadge(false)
            }
            notifManager.createNotificationChannel(ch)
        }
    }

    private fun notifTitle(label: String): String {
        val total = totalCount()
        return if (total > 1) getString(R.string.sinia_notif_title_queue, label, completed + 1, total)
        else getString(R.string.sinia_notif_title_one, label)
    }

    private fun buildNotification(label: String, text: String, progress: Int = 0, maxProg: Int = 100, indet: Boolean = false): Notification {
        val cancelPi = PendingIntent.getService(this, 0,
            Intent(this, ModelDownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sinia)
            .setContentTitle(notifTitle(label)).setContentText(text)
            .setProgress(maxProg, progress, indet)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_close, getString(R.string.cancel), cancelPi)
            .setColor(0xFF33b5e5.toInt())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun download(job: Job) {
        val modelFile = File(filesDir, job.filename)
        val tmpFile   = File(filesDir, job.filename + ".part")
        val metaFile  = File(filesDir, job.filename + ".part.meta")
        val locks = holdLocks()
        try {
            val probe = probe(job.url)
            if (probe.ranges && probe.total > 0) downloadChunked(job, probe.url, probe.total, tmpFile, metaFile)
            else downloadWhole(job, probe.url, tmpFile, metaFile)

            if (cancelFlag) { cancelled(job, tmpFile, metaFile); return }
            metaFile.delete()
            modelFile.delete()
            if (!tmpFile.renameTo(modelFile)) { fail(job, tmpFile, getString(R.string.sinia_save_error)); return }

            val stillQueued = synchronized(lock) { pending.size }
            if (stillQueued == 0) {
                notifManager.notify(NOTIF_ID, NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_sinia)
                    .setContentTitle(getString(R.string.sinia_notif_ready_title))
                    .setContentText(getString(R.string.sinia_notif_ready_text, job.label))
                    .setAutoCancel(true).setColor(0xFF00C851.toInt())
                    .setContentIntent(PendingIntent.getActivity(this, 0,
                        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    .build())
            }
            jobDone(job, true, null)
        } catch (e: Exception) {
            if (cancelFlag || e is InterruptedException) cancelled(job, tmpFile, metaFile)
            else fail(job, tmpFile, e.message ?: getString(R.string.error))
        } finally {
            locks()
        }
    }

    private fun cancelled(job: Job, tmp: File, meta: File) {
        tmp.delete(); meta.delete()
        jobDone(job, false, ERR_CANCELLED)
    }

    @Suppress("DEPRECATION")
    private fun holdLocks(): () -> Unit {
        val wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SinusChat:modelDownload")
            .apply { setReferenceCounted(false); acquire(4 * 3600_000L) }
        val wifi = try {
            (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SinusChat:modelDownload")
                .apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { null }
        return {
            try { if (wake.isHeld) wake.release() } catch (_: Exception) {}
            try { if (wifi?.isHeld == true) wifi.release() } catch (_: Exception) {}
        }
    }

    private fun open(start: String, range: String?): Pair<HttpURLConnection, String> {
        var url = start
        repeat(10) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout    = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SinusChat-Android/1.0")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "close")
                if (range != null) setRequestProperty("Range", range)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) throw HttpError(getString(R.string.sinia_http_error, code, ""))
                url = URL(URL(url), loc).toString()
                return@repeat
            }
            return conn to url
        }
        throw HttpError(getString(R.string.sinia_http_error, 310, "redirects"))
    }

    private class Probe(val url: String, val total: Long, val ranges: Boolean)

    private fun probe(url: String): Probe {
        val (conn, finalUrl) = open(url, "bytes=0-0")
        try {
            val code = conn.responseCode
            Log.d(TAG, "HTTP $code from $finalUrl")
            return when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                    Probe(finalUrl, total, total > 0)
                }
                HttpURLConnection.HTTP_OK -> Probe(finalUrl, conn.contentLengthLong, false)
                else -> throw HttpError(getString(R.string.sinia_http_error, code, conn.responseMessage ?: ""))
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadChunked(job: Job, url: String, total: Long, tmp: File, meta: File) {
        var done = startOffset(total, tmp, meta)
        if (done >= total) return
        writeMeta(meta, total, done)

        val progress = AtomicLong(done)
        val error = AtomicReference<String?>(null)
        val nextChunk = AtomicLong(done)
        val finished = java.util.TreeSet<Long>()
        val meter = Meter(job, total, done)

        RandomAccessFile(tmp, "rw").use { raf ->
            if (raf.length() > total) raf.setLength(total)
            val channel = raf.channel
            val worker = Runnable {
                while (!cancelFlag && error.get() == null) {
                    val start = nextChunk.getAndAdd(CHUNK)
                    if (start >= total) break
                    val end = minOf(start + CHUNK, total) - 1
                    val problem = fetchChunk(url, start, end, channel, progress)
                    if (cancelFlag) break
                    if (problem != null) { error.compareAndSet(null, problem); break }
                    synchronized(finished) {
                        finished.add(start)
                        var moved = false
                        while (finished.remove(done)) { done = minOf(done + CHUNK, total); moved = true }
                        if (moved) {
                            try { channel.force(false); writeMeta(meta, total, done) } catch (e: IOException) {
                                error.compareAndSet(null, e.message ?: getString(R.string.sinia_save_error))
                            }
                        }
                    }
                }
            }
            val pool = Executors.newFixedThreadPool(WORKERS)
            try {
                repeat(WORKERS) { pool.execute(worker) }
                pool.shutdown()
                while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) meter.tick(progress.get())
            } finally {
                pool.shutdownNow()
            }
        }
        if (cancelFlag) return
        error.get()?.let { throw IOException(it) }
        if (done < total || tmp.length() < total) throw IOException(getString(R.string.sinia_incomplete_error))
    }

    private fun fetchChunk(url: String, start: Long, end: Long, channel: FileChannel, progress: AtomicLong): String? {
        var pos = start
        var attempt = 0
        val buf = ByteArray(64 * 1024)
        while (pos <= end) {
            if (cancelFlag) return null
            var conn: HttpURLConnection? = null
            try {
                conn = open(url, "bytes=$pos-$end").first
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_PARTIAL) {
                    throw HttpError(getString(R.string.sinia_http_error, code, conn.responseMessage ?: ""))
                }
                conn.inputStream.use { input ->
                    while (pos <= end) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (cancelFlag) return null
                        val len = minOf(n.toLong(), end - pos + 1).toInt()
                        val bb = ByteBuffer.wrap(buf, 0, len)
                        var at = pos
                        while (bb.hasRemaining()) at += channel.write(bb, at)
                        pos += len
                        progress.addAndGet(len.toLong())
                    }
                }
                if (pos <= end) throw IOException(getString(R.string.sinia_incomplete_error))
            } catch (e: IOException) {
                if (cancelFlag) return null
                if (e is java.nio.channels.ClosedChannelException) return e.message ?: getString(R.string.sinia_save_error)
                if (++attempt > CHUNK_RETRIES) return e.message ?: getString(R.string.error)
                Log.w(TAG, "Kawałek od $pos: ${e.message} — ponawiam ($attempt/$CHUNK_RETRIES)")
                try { Thread.sleep(1000L * attempt) } catch (_: InterruptedException) { return null }
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun startOffset(total: Long, tmp: File, meta: File): Long {
        if (!tmp.exists()) { meta.delete(); return 0L }
        if (meta.exists()) {
            val parts = try { meta.readText().trim().split(' ') } catch (_: Exception) { emptyList() }
            val savedTotal = parts.getOrNull(0)?.toLongOrNull()
            val savedDone = parts.getOrNull(1)?.toLongOrNull()
            if (savedTotal == total && savedDone != null && savedDone in 0..total) return savedDone
        } else if (tmp.length() <= total) {
            return tmp.length()
        }
        tmp.delete(); meta.delete()
        return 0L
    }

    private fun writeMeta(meta: File, total: Long, done: Long) {
        val next = File(meta.path + ".new")
        next.writeText("$total $done")
        if (!next.renameTo(meta)) { meta.delete(); next.renameTo(meta) }
    }

    private fun downloadWhole(job: Job, url: String, tmp: File, meta: File) {
        tmp.delete(); meta.delete()
        val (conn, _) = open(url, null)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw HttpError(getString(R.string.sinia_http_error, code, conn.responseMessage ?: ""))
            }
            val total = conn.contentLengthLong
            val meter = Meter(job, total, 0L)
            var downloaded = 0L
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (cancelFlag) return
                        out.write(buf, 0, n)
                        downloaded += n
                        meter.tick(downloaded)
                    }
                }
            }
            if (total > 0 && downloaded < total) throw IOException(getString(R.string.sinia_incomplete_error))
        } finally {
            conn.disconnect()
        }
    }

    private inner class Meter(private val job: Job, private val total: Long, private var lastBytes: Long) {
        private val sizes = ArrayDeque<Long>()
        private val times = ArrayDeque<Long>()
        private var lastAt = System.currentTimeMillis()

        fun tick(downloaded: Long) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastAt
            if (elapsed < 1000) return
            if (sizes.size == 5) { sizes.removeFirst(); times.removeFirst() }
            sizes.addLast(downloaded - lastBytes); times.addLast(elapsed)
            lastBytes = downloaded; lastAt = now
            val avgBytes = sizes.sum(); val avgMs = times.sum().coerceAtLeast(1)
            val spd  = (avgBytes.toDouble() / avgMs * 1000.0) / (1024.0 * 1024.0)
            val dlMB = downloaded / 1_048_576L
            val totMB = if (total > 0) total / 1_048_576L else 0L
            val pct  = if (total > 0) (downloaded * 100L / total).toInt() else 0
            val eta  = calcEta(downloaded, total, avgBytes, avgMs)
            val txt = if (total > 0) "${dlMB} MB / ${totMB} MB • %.1f MB/s • ~$eta".format(spd)
                      else "${dlMB} MB • %.1f MB/s".format(spd)
            if (!cancelFlag) notifManager.notify(NOTIF_ID, buildNotification(job.label, txt, pct, 100, total <= 0))
            uiHandler.post { onProgress?.invoke(job, downloaded, total, spd, eta) }
        }
    }

    private fun fail(job: Job, tmp: File, error: String) {
        val resumable = tmp.exists() && tmp.length() > 0
        notifManager.notify(NOTIF_ID + 1, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sinia)
            .setContentTitle(getString(R.string.sinia_notif_fail_title, job.label))
            .setContentText(if (resumable) getString(R.string.sinia_notif_fail_resume, error) else error)
            .setAutoCancel(true).setColor(0xFFff4444.toInt()).build())
        jobDone(job, false, error)
    }

    private fun calcEta(downloaded: Long, total: Long, speedBytes: Long, elapsed: Long): String {
        if (speedBytes <= 0 || total <= 0) return "..."
        val s = ((total - downloaded).toDouble() / speedBytes * elapsed / 1000.0).toLong()
        return when { s > 3600 -> "${s/3600}h ${(s%3600)/60}m"; s > 60 -> "${s/60}m ${s%60}s"; else -> "${s}s" }
    }
}
