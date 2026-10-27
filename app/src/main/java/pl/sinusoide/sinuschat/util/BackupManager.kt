package pl.sinusoide.sinuschat.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pl.sinusoide.sinuschat.data.CallEntity
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import pl.sinusoide.sinuschat.setup.SetupActivity
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object BackupManager {

    private const val MANIFEST = "manifest.json"
    private const val MESSAGES = "messages.json"
    private const val CALLS = "calls.json"
    private const val PREFS = "prefs.json"
    internal const val MEDIA_DIR = "media"
    private const val FORMAT = "sinuschat-backup"

    const val EXTENSION = "sinuschat"

    private val PREFS_INCLUDED = listOf(
        "sinuschat_prefs",
        "sinuschat_conv_cache",
        "sinuschat_contact_cache",
        "sinuschat_last_msg_cache",
        "sinuschat_accepted_convs",
        "sinuschat_avatar_prefs",
        "sinuschat_blocked",
        "sinuschat_mute",
        "sinuschat_disappearing",
        "sinuschat_notifications",
        "sinuschat_privacy",
        "sinuschat_theme_prefs",
        "sinuschat_locale_prefs",
        "sinuschat_network",
        "sinuschat_auto_delete",
        "sinuschat_experimental",
        "sinuschat_reactions",
        "sinia_conversations"
    )

    internal val PREFS_RESTORED = PREFS_INCLUDED - "sinuschat_prefs"

    data class Stats(val conversations: Int, val messages: Int, val files: Int, val skipped: Int)

    data class Manifest(
        val accountHash: String,
        val accountEmail: String,
        val displayName: String,
        val createdAt: Long,
        val withMedia: Boolean,
        val messages: Int,
        val conversations: Int,
        val files: Int,
        val calls: Int,
        val appVersion: String,
        val profileKey: String = "",
        val contactKeys: JSONObject? = null
    )

    fun fileName(atMs: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US)
        return "SinusChat-${f.format(java.util.Date(atMs))}.$EXTENSION"
    }

    fun mediaBytes(ctx: Context): Long =
        File(ctx.filesDir, MEDIA_DIR).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun create(
        ctx: Context,
        out: OutputStream,
        key: RecoveryKey,
        withMedia: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Stats {
        val app = ctx.applicationContext
        val db = SinusChatDatabase.getInstance(app)

        val all = db.messageDao().getAllSync()
        val messages = all.filter { it.expiresAtMs == null }
        val skipped = all.size - messages.size
        val conversations = messages.map { it.conversationId }.distinct().size
        val calls = db.callDao().getAllSync()

        val mediaFiles: List<File> =
            if (withMedia) File(app.filesDir, MEDIA_DIR).walkTopDown().filter { it.isFile }.toList()
            else emptyList()

        val flags = if (withMedia) BackupCrypto.FLAG_MEDIA else 0
        val header = BackupCrypto.Header.create(flags, key)

        val total = messages.size + mediaFiles.size + 3
        var done = 0

        out.write(header.bytes())
        BackupArchive.Writer(BackupCrypto.encryptingStream(out, key, header)).use { archive ->
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", BackupCrypto.VERSION)
                .put("createdAt", System.currentTimeMillis())
                .put("accountHash", SetupActivity.getUserPhoneHash(app))
                .put("accountEmail", SetupActivity.getUserEmail(app))
                .put("displayName", SetupActivity.getUserDisplayName(app))
                .put("profileKey", pl.sinusoide.sinuschat.crypto.ProfileCrypto.myKeyB64(app))
                .put("contactKeys", pl.sinusoide.sinuschat.crypto.ProfileCrypto.exportContactKeys(app))
                .put("appVersion", appVersion(app))
                .put("device", android.os.Build.MODEL ?: "")
                .put("withMedia", withMedia)
                .put("messages", messages.size)
                .put("conversations", conversations)
                .put("files", mediaFiles.size)
                .put("calls", calls.size)
                .put("skippedDisappearing", skipped)
            archive.bytes(BackupArchive.MANIFEST, MANIFEST, manifest.toString(2).toByteArray())
            onProgress(++done, total)

            val arr = JSONArray()
            for (m in messages) {
                arr.put(messageJson(m))
                done++
                if (done % 200 == 0) onProgress(done, total)
            }
            archive.bytes(BackupArchive.MESSAGES, MESSAGES, arr.toString().toByteArray())
            onProgress(done, total)

            val callArr = JSONArray()
            for (c in calls) callArr.put(callJson(c))
            archive.bytes(BackupArchive.CALLS, CALLS, callArr.toString().toByteArray())
            onProgress(++done, total)

            archive.bytes(BackupArchive.PREFS, PREFS, prefsJson(app).toString().toByteArray())
            onProgress(++done, total)

            if (mediaFiles.isNotEmpty()) {
                val root = File(app.filesDir, MEDIA_DIR).absolutePath
                for (f in mediaFiles) {
                    val rel = f.absolutePath.removePrefix(root).trimStart(File.separatorChar)
                    archive.file("$MEDIA_DIR/${rel.replace(File.separatorChar, '/')}", f)
                    onProgress(++done, total)
                }
            }
        }
        return Stats(conversations, messages.size, mediaFiles.size, skipped)
    }

    fun readHeader(inp: InputStream): BackupCrypto.Header = BackupCrypto.Header.read(inp)

    fun open(inp: InputStream, key: RecoveryKey): BackupArchive.Reader {
        val header = BackupCrypto.Header.read(inp)
        return BackupArchive.Reader(BackupCrypto.decryptingStream(inp, key, header))
    }

    fun readManifest(inp: InputStream, key: RecoveryKey): Manifest {
        open(inp, key).use { archive ->
            val e = archive.next()
            if (e == null || e.type != BackupArchive.MANIFEST) throw java.io.IOException("Kopia nie zawiera manifestu")
            return parseManifest(archive.readBytes())
        }
    }

    internal fun parseManifest(bytes: ByteArray): Manifest {
        val o = JSONObject(String(bytes))
        return Manifest(
            accountHash = o.optString("accountHash"),
            accountEmail = o.optString("accountEmail"),
            displayName = o.optString("displayName"),
            createdAt = o.optLong("createdAt"),
            withMedia = o.optBoolean("withMedia"),
            messages = o.optInt("messages"),
            conversations = o.optInt("conversations"),
            files = o.optInt("files"),
            calls = o.optInt("calls"),
            appVersion = o.optString("appVersion"),
            profileKey = o.optString("profileKey"),
            contactKeys = o.optJSONObject("contactKeys")
        )
    }

    fun belongsToMe(ctx: Context, m: Manifest): Boolean {
        val mine = SetupActivity.getUserPhoneHash(ctx.applicationContext)
        return mine.isNotEmpty() && mine == m.accountHash
    }

    private fun messageJson(m: MessageEntity): JSONObject {
        val reactions = JSONObject()
        for ((k, v) in m.reactions) reactions.put(k, v)
        return JSONObject()
            .put("id", m.id)
            .put("conversationId", m.conversationId)
            .put("senderHash", m.senderHash)
            .put("text", m.text)
            .put("timestampMs", m.timestampMs)
            .put("status", m.status)
            .put("type", m.type)
            .put("fileId", m.fileId)
            .put("fileKey", m.fileKey)
            .put("attachmentName", m.attachmentName)
            .put("pinned", m.pinned)
            .put("reactions", reactions)
            .put("replyToId", m.replyToId)
            .put("replyToText", m.replyToText)
            .put("edited", m.edited)
            .put("viewOnce", m.viewOnce)
            .put("viewed", m.viewed)
            .put("isSystemMessage", m.isSystemMessage)
            .put("albumId", m.albumId)
            .put("trOrig", m.trOrig)
            .put("trLocal", m.trLocal)
            .put("trFrom", m.trFrom)
            .put("trTo", m.trTo)
    }

    internal fun messageFrom(o: JSONObject): MessageEntity {
        val reactions = LinkedHashMap<String, String>()
        o.optJSONObject("reactions")?.let { r -> for (k in r.keys()) reactions[k] = r.optString(k) }
        fun str(k: String): String? = if (o.isNull(k)) null else o.optString(k)
        return MessageEntity(
            id = o.getString("id"),
            conversationId = o.getString("conversationId"),
            senderHash = o.optString("senderHash"),
            text = o.optString("text"),
            timestampMs = o.optLong("timestampMs"),
            status = o.optString("status", MessageEntity.STATUS_SENT),
            type = o.optString("type", MessageEntity.TYPE_TEXT),
            fileId = str("fileId"),
            fileKey = str("fileKey"),
            attachmentName = str("attachmentName"),
            pinned = o.optBoolean("pinned"),
            reactions = reactions,
            replyToId = str("replyToId"),
            replyToText = str("replyToText"),
            edited = o.optBoolean("edited"),
            viewOnce = o.optBoolean("viewOnce"),
            viewed = o.optBoolean("viewed"),
            isSystemMessage = o.optBoolean("isSystemMessage"),
            albumId = str("albumId"),
            trOrig = str("trOrig"),
            trLocal = str("trLocal"),
            trFrom = str("trFrom"),
            trTo = str("trTo")
        )
    }

    private fun callJson(c: CallEntity): JSONObject = JSONObject()
        .put("id", c.id)
        .put("otherUserHash", c.otherUserHash)
        .put("otherUserName", c.otherUserName)
        .put("conversationId", c.conversationId)
        .put("isVideo", c.isVideo)
        .put("direction", c.direction)
        .put("durationSec", c.durationSec)
        .put("startedAtMs", c.startedAtMs)

    internal fun callFrom(o: JSONObject): CallEntity = CallEntity(
        id = o.getString("id"),
        otherUserHash = o.optString("otherUserHash"),
        otherUserName = o.optString("otherUserName"),
        conversationId = o.optString("conversationId"),
        isVideo = o.optBoolean("isVideo"),
        direction = o.optString("direction"),
        durationSec = o.optInt("durationSec"),
        startedAtMs = o.optLong("startedAtMs")
    )

    private fun prefsJson(ctx: Context): JSONObject {
        val out = JSONObject()
        for (name in PREFS_INCLUDED) {
            val values = try {
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all
            } catch (_: Exception) { continue }
            if (values.isEmpty()) continue
            val o = JSONObject()
            for ((k, v) in values) {
                val typed = when (v) {
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> null
                } ?: continue
                o.put(k, typed)
            }
            out.put(name, o)
        }
        return out
    }

    private fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }
}
