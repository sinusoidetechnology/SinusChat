package pl.sinusoide.sinuschat.util

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import pl.sinusoide.sinuschat.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SafeMode {

    enum class Part(val id: String, val label: String) {
        TOR("tor", "Tor")
    }

    private const val PREFS = "safe_mode"
    private const val FAILS_TO_DISABLE = 2

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun begin(ctx: Context, part: Part) {
        prefs(ctx).edit().putLong("active_${part.id}", System.currentTimeMillis()).commit()
    }

    fun end(ctx: Context, part: Part) {
        prefs(ctx).edit().remove("active_${part.id}").commit()
    }

    fun stable(ctx: Context, part: Part) {
        prefs(ctx).edit().remove("active_${part.id}").remove("fails_${part.id}").apply()
    }

    fun isDisabled(ctx: Context, part: Part): Boolean = prefs(ctx).getBoolean("disabled_${part.id}", false)

    fun enable(ctx: Context, part: Part) {
        prefs(ctx).edit()
            .remove("disabled_${part.id}").remove("fails_${part.id}")
            .remove("notice_${part.id}").remove("report_${part.id}").remove("at_${part.id}")
            .commit()
    }

    fun checkOnStart(ctx: Context) {
        val p = prefs(ctx)
        for (part in Part.values()) {
            val since = p.getLong("active_${part.id}", 0L)
            if (since == 0L) continue
            val crashed = if (Build.VERSION.SDK_INT >= 30) crashedSince(ctx, since) else true
            val e = p.edit().remove("active_${part.id}")
            if (crashed) {
                val fails = p.getInt("fails_${part.id}", 0) + 1
                e.putInt("fails_${part.id}", fails)
                if (fails >= FAILS_TO_DISABLE) {
                    e.putBoolean("disabled_${part.id}", true)
                        .putBoolean("notice_${part.id}", true)
                        .putLong("at_${part.id}", System.currentTimeMillis())
                        .putString("report_${part.id}", AppExits.report(ctx))
                    LinkLog.add(ctx, "safe_engine", "${part.label} off after $fails crashes", sync = true)
                }
            }
            e.commit()
        }
    }

    @androidx.annotation.RequiresApi(30)
    private fun crashedSince(ctx: Context, since: Long): Boolean = try {
        val am = ctx.getSystemService(ActivityManager::class.java)
        am?.getHistoricalProcessExitReasons(ctx.packageName, 0, 8).orEmpty()
            .filter { it.processName == ctx.packageName && it.timestamp >= since }
            .any {
                it.reason == ApplicationExitInfo.REASON_CRASH ||
                    it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ||
                    (it.reason == ApplicationExitInfo.REASON_EXIT_SELF && it.status != 0)
            }
    } catch (_: Throwable) { false }

    fun pendingNotice(ctx: Context): Part? = Part.values().firstOrNull { prefs(ctx).getBoolean("notice_${it.id}", false) }

    private fun noticeShown(ctx: Context, part: Part) {
        prefs(ctx).edit().remove("notice_${part.id}").apply()
    }

    fun showPending(activity: Activity) {
        val part = pendingNotice(activity) ?: return
        noticeShown(activity, part)
        when (part) {
            Part.TOR -> showTor(activity)
        }
    }

    private fun showTor(activity: Activity) {
        val app = activity.applicationContext
        SafeEngine.notice(activity,
            activity.getString(R.string.safe_mode_tor_title),
            activity.getString(R.string.safe_mode_tor_msg),
            listOf(
                SafeEngine.Action(activity.getString(R.string.safe_mode_retry), filled = true) {
                    enable(app, Part.TOR)
                    pl.sinusoide.sinuschat.tor.TorManager.start(app)
                    pl.sinusoide.sinuschat.tor.TorManager.onReady {
                        AppExecutor.main { pl.sinusoide.sinuschat.network.WebSocketManager.connect(app) }
                    }
                },
                SafeEngine.Action(activity.getString(R.string.safe_mode_tor_off)) {
                    enable(app, Part.TOR)
                    PrivacySettings.setTorEnabled(app, false)
                    pl.sinusoide.sinuschat.tor.TorManager.stop()
                    pl.sinusoide.sinuschat.network.WebSocketManager.disconnect()
                    pl.sinusoide.sinuschat.network.WebSocketManager.connect(app)
                    PopupHelper.show(activity, activity.getString(R.string.tor_off))
                },
                SafeEngine.Action(activity.getString(R.string.safe_mode_copy), keepOpen = true) {
                    copyReport(activity, Part.TOR)
                }
            ))
    }

    fun copyReport(activity: Activity, part: Part) {
        val app = activity.applicationContext
        AppExecutor.io {
            val text = report(app, part)
            AppExecutor.main {
                if (activity.isFinishing || activity.isDestroyed) return@main
                val cm = activity.getSystemService(ClipboardManager::class.java) ?: return@main
                cm.setPrimaryClip(ClipData.newPlainText("SafeEngine", text))
                PopupHelper.show(activity, activity.getString(R.string.safe_mode_copied))
            }
        }
    }

    private fun report(ctx: Context, part: Part): String {
        val p = prefs(ctx)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val at = p.getLong("at_${part.id}", 0L)
        val version = try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
        } catch (_: Exception) { "?" }
        return buildString {
            appendLine("SafeEngine")
            appendLine("Moduł: ${part.label}")
            if (at > 0L) appendLine("Wyłączony: ${fmt.format(Date(at))}")
            appendLine("SinusChat $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            p.getString("report_${part.id}", null)?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Zakończenia procesu:")
                appendLine(it)
            }
            appendLine()
            append(try { DiagnosticsSummary.build(ctx) } catch (_: Throwable) { "" })
        }.trim()
    }
}
