package pl.sinusoide.sinuschat.util

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.Request
import org.json.JSONObject
import pl.sinusoide.sinuschat.AppConfig
import pl.sinusoide.sinuschat.network.ApiClient
import java.util.concurrent.TimeUnit

object UpdateChecker {

    const val PLAY_URL = "https://play.google.com/store/apps/details?id=pl.sinusoide.sinuschat"
    const val EXTRA_CODE = "update_code"
    private const val TAG = "UpdateChecker"
    private const val PREFS = "sinuschat_update_check"
    private const val KEY_LAST = "last_check"
    private const val LEGACY_NOTIFIED = "notified_code"
    private const val KEY_SNOOZE_CODE = "snooze_code"
    private const val KEY_SNOOZE_UNTIL = "snooze_until"
    private const val KEY_LATEST_CODE = "latest_code"
    private const val KEY_LATEST_NAME = "latest_name"
    private const val KEY_LATEST_PAGE = "latest_page"
    private const val LEGACY_CHANNEL = "sinuschat_updates"
    private const val LEGACY_NOTIF_ID = 9300
    private const val CHECK_EVERY_MS = 24 * 3_600_000L
    private const val RETRY_MS = 3_600_000L
    private const val SNOOZE_MS = 3 * 24 * 3_600_000L

    const val RELEASES_URL = "https://github.com/sinusoidetechnology/SinusChat/releases/latest"

    class Latest(val code: Long, val name: String, val page: String? = null)

    @Volatile private var checking = false
    @Volatile private var pending: Latest? = null
    @Volatile private var legacyCleared = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedCode(ctx: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (_: Exception) { 0L }

    fun installedName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) { "" }

    fun isOutdated(ctx: Context, latest: Latest): Boolean = latest.code > installedCode(ctx)

    fun cachedLatest(ctx: Context): Latest? {
        val p = prefs(ctx)
        val code = p.getLong(KEY_LATEST_CODE, 0L)
        val name = p.getString(KEY_LATEST_NAME, null)
        return if (code > 0L && !name.isNullOrBlank()) Latest(code, name, p.getString(KEY_LATEST_PAGE, null)) else null
    }

    fun fetchLatest(ctx: Context): Latest? {
        return try {
            val tor = PrivacySettings.isTorEnabled(ctx)
            val client = ApiClient.httpClient(ctx).newBuilder()
                .callTimeout(if (tor) 60 else 15, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(AppConfig.VERSION_URL).header("Cache-Control", "no-cache").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()?.take(4096) ?: return null
                val json = JSONObject(body)
                val code = json.optLong("versionCode", 0L)
                val name = json.optString("versionName").trim().take(32)
                val page = json.optString("releaseUrl").trim().takeIf { it.startsWith("https://github.com/sinusoidetechnology/") && it.length < 300 }
                if (code <= 0L || name.isEmpty()) null else Latest(code, name, page).also {
                    prefs(ctx).edit().putLong(KEY_LATEST_CODE, code).putString(KEY_LATEST_NAME, name)
                        .putString(KEY_LATEST_PAGE, page).apply()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nie sprawdzono wersji: ${e.javaClass.simpleName}")
            null
        }
    }

    fun check(ctx: Context, onFound: () -> Unit) {
        val app = ctx.applicationContext
        removeLegacyNotification(app)
        if (!NotificationPrefs.updateNotices(app) || checking) return
        val p = prefs(app)
        val now = System.currentTimeMillis()
        if (now - p.getLong(KEY_LAST, 0L) < CHECK_EVERY_MS) return
        checking = true
        p.edit().putLong(KEY_LAST, now - CHECK_EVERY_MS + RETRY_MS).apply()
        AppExecutor.io {
            try {
                val latest = fetchLatest(app) ?: return@io
                p.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                if (!isOutdated(app, latest) || isSnoozed(app, latest)) return@io
                pending = latest
                AppExecutor.main { onFound() }
            } finally {
                checking = false
            }
        }
    }

    fun takePending(ctx: Context): Latest? {
        val latest = pending ?: return null
        pending = null
        return latest.takeIf {
            NotificationPrefs.updateNotices(ctx) && isOutdated(ctx, it) && !isSnoozed(ctx, it)
        }
    }

    private fun isSnoozed(ctx: Context, latest: Latest): Boolean {
        val p = prefs(ctx)
        return p.getLong(KEY_SNOOZE_CODE, -1L) == latest.code &&
            System.currentTimeMillis() < p.getLong(KEY_SNOOZE_UNTIL, 0L)
    }

    fun snooze(ctx: Context, code: Long) {
        prefs(ctx).edit()
            .putLong(KEY_SNOOZE_CODE, code)
            .putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS)
            .apply()
    }

    fun removeLegacyNotification(ctx: Context) {
        if (legacyCleared) return
        legacyCleared = true
        try {
            NotificationManagerCompat.from(ctx).cancel(LEGACY_NOTIF_ID)
            ctx.getSystemService(NotificationManager::class.java)?.let { mgr ->
                if (mgr.getNotificationChannel(LEGACY_CHANNEL) != null) mgr.deleteNotificationChannel(LEGACY_CHANNEL)
            }
            prefs(ctx).edit().remove(LEGACY_NOTIFIED).apply()
        } catch (_: Exception) {}
    }

    fun openPlay(ctx: Context) {
        val uri = Uri.parse(PLAY_URL)
        val market = Uri.parse("market://details?id=" + ctx.packageName)
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"),
            Intent(Intent.ACTION_VIEW, market),
            Intent(Intent.ACTION_VIEW, uri)
        )
        for (intent in attempts) {
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {}
        }
    }
}
