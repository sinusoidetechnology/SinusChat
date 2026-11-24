package pl.sinusoide.sinuschat.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import pl.sinusoide.sinuschat.util.BackupCrypto
import pl.sinusoide.sinuschat.util.BackupManager
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object ExternalBackup {

    const val ACTION_EXPORT = "pl.sinusoide.sinuschat.action.EXPORT_BACKUP"
    const val EXTRA_KEY_ID = "backup_key_id"
    const val EXTRA_WITH_MEDIA = "with_media"
    const val EXTRA_ERROR = "error"
    const val ERROR_CANCELLED = "cancelled"
    const val ERROR_NO_CALLER = "no_caller"
    const val ERROR_NOT_READY = "not_ready"
    const val ERROR_NO_KEY = "no_recovery_key"
    const val ERROR_FAILED = "failed"
    const val MIME = "application/octet-stream"

    const val EXPORT_DIR = "external_export"
    const val KEEP_MS = 10 * 60 * 1000L
    private const val RESTORE_NAME = "restore.sinuschat"
    private const val INSPECT_NAME = "inspect.sinuschat"
    private const val CLEANUP_WORK = "sinuschat_external_export_cleanup"
    private const val PREFS = "external_backup"
    private const val KEY_SOURCE = "source"

    fun isExportRequest(action: String?): Boolean = action == ACTION_EXPORT

    fun isBackupName(name: String?): Boolean =
        name?.trim()?.lowercase()?.endsWith("." + BackupManager.EXTENSION) == true

    fun importSource(action: String?, data: String?, stream: String?): String? {
        val raw = when (action) {
            Intent.ACTION_VIEW -> data
            Intent.ACTION_SEND -> stream ?: data
            else -> null
        } ?: return null
        val scheme = raw.substringBefore(':', "").lowercase()
        return raw.takeIf { scheme == "content" }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun callerFromReferrer(referrer: String?): String? {
        if (referrer.isNullOrEmpty() || !referrer.startsWith("android-app://")) return null
        return referrer.removePrefix("android-app://").substringBefore('/').takeIf { it.isNotEmpty() }
    }

    fun exportDir(ctx: Context): File = File(ctx.applicationContext.cacheDir, EXPORT_DIR)

    fun clearExports(ctx: Context) {
        exportDir(ctx).listFiles()?.forEach { it.delete() }
    }

    fun scheduleCleanup(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInitialDelay(KEEP_MS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork(CLEANUP_WORK, ExistingWorkPolicy.REPLACE, req)
    }

    class CleanupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            clearExports(applicationContext)
            return Result.success()
        }
    }

    private fun restoreFile(ctx: Context) = File(ctx.applicationContext.cacheDir, RESTORE_NAME)

    private fun inspectFile(ctx: Context) = File(ctx.applicationContext.cacheDir, INSPECT_NAME)

    fun pendingRestore(ctx: Context): File? = restoreFile(ctx).takeIf { it.isFile && it.length() > 0 }

    fun sourceLabel(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SOURCE, null)

    fun clearPending(ctx: Context) {
        restoreFile(ctx).delete()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SOURCE).apply()
    }

    fun clearInspect(ctx: Context) {
        inspectFile(ctx).delete()
    }

    fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun sizeOf(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }

    class NotBackupException : IOException("To nie jest kopia SinusChat")
    class NoSpaceException : IOException("Za mało miejsca")

    fun stage(ctx: Context, uri: Uri, forRestore: Boolean, source: String?): BackupCrypto.Header {
        val app = ctx.applicationContext
        val target = if (forRestore) restoreFile(app) else inspectFile(app)
        val size = sizeOf(app, uri)
        if (size > 0 && app.cacheDir.usableSpace < size + 64L * 1024 * 1024) throw NoSpaceException()
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.delete()
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Brak pliku")
            input.use { inp -> tmp.outputStream().buffered(256 * 1024).use { out -> inp.copyTo(out, 256 * 1024) } }
            val header = try {
                tmp.inputStream().use { BackupManager.readHeader(it) }
            } catch (e: BackupCrypto.LegacyFormatException) {
                throw e
            } catch (e: Throwable) {
                throw NotBackupException()
            }
            target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            if (forRestore) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SOURCE, source).apply()
            }
            return header
        } finally {
            tmp.delete()
        }
    }

    fun inspectFileIfReady(ctx: Context): File? = inspectFile(ctx).takeIf { it.isFile && it.length() > 0 }
}
