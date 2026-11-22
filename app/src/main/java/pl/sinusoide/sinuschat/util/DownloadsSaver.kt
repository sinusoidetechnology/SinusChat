package pl.sinusoide.sinuschat.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

object DownloadsSaver {

    private const val TAG = "DownloadsSaver"
    const val FOLDER = "SinusChat"

    private val lock = Any()

    fun save(ctx: Context, src: File, name: String, mime: String): String? = synchronized(lock) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveViaMediaStore(ctx, src, name, mime)
            else saveLegacy(ctx, src, name, mime)
        } catch (e: Throwable) {
            Log.e(TAG, "Zapis do folderu SinusChat nie wyszedł", e)
            null
        }
    }

    private fun numbered(name: String, n: Int): String {
        if (n == 0) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        return if (ext.isEmpty() || base == name) "$name ($n)" else "$base ($n).$ext"
    }

    private fun taken(ctx: Context, collection: Uri, name: String, path: String): Boolean = try {
        ctx.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "$path%"), null)?.use { it.count > 0 } ?: false
    } catch (_: Exception) { false }

    fun locationFor(ctx: Context, mime: String): String {
        val res = when {
            mime.startsWith("image/") -> pl.sinusoide.sinuschat.R.string.folder_pictures
            mime.startsWith("video/") -> pl.sinusoide.sinuschat.R.string.folder_movies
            else -> pl.sinusoide.sinuschat.R.string.folder_downloads
        }
        return ctx.getString(res) + "/" + FOLDER
    }

    private fun publicDir(mime: String): String = when {
        mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
        mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        else -> Environment.DIRECTORY_DOWNLOADS
    }

    private fun saveViaMediaStore(ctx: Context, src: File, name: String, mime: String): String? {
        val collection: Uri = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val path = publicDir(mime) + "/" + FOLDER
        val resolver = ctx.contentResolver
        var n = 0
        while (n < 500 && taken(ctx, collection, numbered(name, n), path)) n++
        var uri: Uri? = null
        var finalName = name
        for (attempt in 0 until 5) {
            finalName = numbered(name, n + attempt)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = try { resolver.insert(collection, values) } catch (e: Exception) {
                Log.w(TAG, "insert: ${e.javaClass.simpleName}")
                null
            }
            if (uri != null) break
        }
        val target = uri ?: return null
        val ok = try {
            resolver.openOutputStream(target)?.use { out -> src.inputStream().use { it.copyTo(out) }; true } ?: false
        } catch (e: Exception) { false }
        if (!ok) { resolver.delete(target, null, null); return null }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(target, done, null, null)
        return finalName
    }

    private fun saveLegacy(ctx: Context, src: File, name: String, mime: String): String? {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(publicDir(mime)), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return null
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        var out = File(dir, name); var n = 1
        while (out.exists()) out = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext").also { n++ }
        src.copyTo(out)
        MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), arrayOf(mime), null)
        return out.name
    }
}
