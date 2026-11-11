package pl.sinusoide.sinuschat.messenger

import android.content.Context
import android.util.Log
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.FileUploader
import pl.sinusoide.sinuschat.util.MediaManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object AttachmentDownloader {

    private const val TAG = "AttachmentDownloader"
    private const val AUTO_RETRY_MS = 5 * 60_000L

    private class State {
        @Volatile var progress: Float? = null
        val waiting = mutableListOf<(File?) -> Unit>()
    }

    private val states = HashMap<String, State>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(2)

    @Volatile var onChanged: ((conversationId: String) -> Unit)? = null

    fun progressOf(id: String): Float? = synchronized(states) { states[id]?.progress }

    fun isActive(id: String): Boolean = synchronized(states) { states.containsKey(id) }

    fun fetch(ctx: Context, msg: MessageEntity, auto: Boolean = false, done: ((File?) -> Unit)? = null) {
        val app = ctx.applicationContext
        val name = msg.attachmentName ?: "plik"
        val local = MediaManager.getMediaFile(app, msg.conversationId, msg.id, name)
        if (local.exists() && local.length() > 0) {
            done?.let { AppExecutor.main { it(local) } }
            return
        }
        val fileId = msg.fileId
        val key = msg.fileKey
        if (fileId.isNullOrEmpty() || key.isNullOrEmpty() || pl.sinusoide.sinuschat.util.MediaGone.isGone(app, msg.id) ||
            (auto && System.currentTimeMillis() - (failedAt[msg.id] ?: 0L) < AUTO_RETRY_MS)) {
            done?.let { AppExecutor.main { it(null) } }
            return
        }
        val st: State
        val start: Boolean
        synchronized(states) {
            val existing = states[msg.id]
            start = existing == null
            st = existing ?: State().also { states[msg.id] = it }
            if (done != null) st.waiting += done
        }
        if (!start) return
        onChanged?.invoke(msg.conversationId)
        pool.execute {
            var file: File? = null
            try {
                val ok = FileUploader.downloadDecryptToFile(app, fileId, key, local,
                    onProgress = { p -> st.progress = p },
                    onMissing = { pl.sinusoide.sinuschat.util.MediaGone.mark(app, msg.id) },
                    onBytes = { done, total -> pl.sinusoide.sinuschat.util.TransferStats.update(msg.id, done, total) })
                if (ok && local.exists() && local.length() > 0) {
                    file = local
                    pl.sinusoide.sinuschat.util.VideoThumbs.ensure(app, msg.conversationId, msg.id, name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pobieranie ${msg.id.take(8)}: ${e.message}")
            }
            pl.sinusoide.sinuschat.util.TransferStats.end(msg.id)
            if (file == null) failedAt[msg.id] = System.currentTimeMillis() else failedAt.remove(msg.id)
            val callbacks = synchronized(states) {
                states.remove(msg.id)
                st.waiting.toList()
            }
            val result = file
            AppExecutor.main { callbacks.forEach { it(result) } }
            onChanged?.invoke(msg.conversationId)
        }
    }
}
