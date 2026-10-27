package pl.sinusoide.sinuschat.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import java.io.File
import java.io.IOException

object BackupRestore {

    class ForeignAccountException(val email: String) : IOException("Kopia z innego konta")

    data class Result(val conversations: Int, val messages: Int, val files: Int,
                      val accountEmail: String, val displayName: String)

    private const val BATCH = 500

    fun restore(
        ctx: Context,
        archive: BackupArchive.Reader,
        accountHash: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result {
        val app = ctx.applicationContext
        val first = archive.next() ?: throw IOException("Pusta kopia")
        if (first.type != BackupArchive.MANIFEST) throw IOException("Kopia nie zaczyna się od manifestu")
        val manifest = BackupManager.parseManifest(archive.readBytes())
        if (accountHash.isNotEmpty() && manifest.accountHash.isNotEmpty() && manifest.accountHash != accountHash)
            throw ForeignAccountException(manifest.accountEmail)

        val total = manifest.messages + manifest.files + 3
        var done = 1
        onProgress(done, total)

        val db = SinusChatDatabase.getInstance(app)
        val conversations = HashSet<String>()
        var messages = 0
        var files = 0
        val mediaRoot = File(app.filesDir, BackupManager.MEDIA_DIR).canonicalFile

        while (true) {
            val e = archive.next() ?: break
            when (e.type) {
                BackupArchive.MESSAGES -> {
                    val arr = JSONArray(String(archive.readBytes()))
                    val batch = ArrayList<MessageEntity>(BATCH)
                    for (i in 0 until arr.length()) {
                        var m = BackupManager.messageFrom(arr.getJSONObject(i))
                        if (m.status == MessageEntity.STATUS_PENDING) m = m.copy(status = MessageEntity.STATUS_FAILED)
                        batch.add(m)
                        conversations.add(m.conversationId)
                        if (batch.size == BATCH) { db.messageDao().insertAll(batch); batch.clear() }
                        messages++
                        done++
                        if (done % 200 == 0) onProgress(done, total)
                    }
                    if (batch.isNotEmpty()) db.messageDao().insertAll(batch)
                    onProgress(done, total)
                }
                BackupArchive.CALLS -> {
                    val arr = JSONArray(String(archive.readBytes()))
                    for (i in 0 until arr.length()) db.callDao().insert(BackupManager.callFrom(arr.getJSONObject(i)))
                    onProgress(++done, total)
                }
                BackupArchive.PREFS -> {
                    restorePrefs(app, JSONObject(String(archive.readBytes())))
                    onProgress(++done, total)
                }
                BackupArchive.FILE -> {
                    val target = File(app.filesDir, e.name).canonicalFile
                    if (!target.path.startsWith(mediaRoot.path + File.separator)) continue
                    target.parentFile?.mkdirs()
                    val part = File(target.path + ".part")
                    archive.stream().use { src -> part.outputStream().use { src.copyTo(it) } }
                    if (!part.renameTo(target)) {
                        target.delete()
                        if (!part.renameTo(target)) throw IOException("Nie udało się zapisać ${e.name}")
                    }
                    files++
                    onProgress(++done, total)
                }
                else -> Unit
            }
        }
        if (manifest.profileKey.isNotEmpty()) pl.sinusoide.sinuschat.crypto.ProfileCrypto.setMyKey(app, manifest.profileKey)
        manifest.contactKeys?.let { pl.sinusoide.sinuschat.crypto.ProfileCrypto.importContactKeys(app, it) }
        return Result(conversations.size, messages, files, manifest.accountEmail, manifest.displayName)
    }

    private fun restorePrefs(ctx: Context, all: JSONObject) {
        for (name in BackupManager.PREFS_RESTORED) {
            val o = all.optJSONObject(name) ?: continue
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for (k in o.keys()) {
                val t = o.optJSONObject(k) ?: continue
                when (t.optString("t")) {
                    "s" -> ed.putString(k, t.optString("v"))
                    "b" -> ed.putBoolean(k, t.optBoolean("v"))
                    "i" -> ed.putInt(k, t.optInt("v"))
                    "l" -> ed.putLong(k, t.optLong("v"))
                    "f" -> ed.putFloat(k, t.optDouble("v").toFloat())
                    "set" -> {
                        val arr = t.optJSONArray("v") ?: continue
                        ed.putStringSet(k, (0 until arr.length()).map { arr.optString(it) }.toSet())
                    }
                }
            }
            ed.commit()
        }
    }
}
