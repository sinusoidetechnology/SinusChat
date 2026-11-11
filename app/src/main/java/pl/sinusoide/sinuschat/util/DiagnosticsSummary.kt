package pl.sinusoide.sinuschat.util

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

object DiagnosticsSummary {

    fun build(context: Context): String {
        val sb = StringBuilder()
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        sb.appendLine("SinusChat $version")
        sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Lang: ${LocaleManager.getEffective(context)} · Theme: ${ThemeManager.getCurrent(context)} · " +
            "Tor: ${onOff(PrivacySettings.isTorEnabled(context))} · Wi-Fi only: ${onOff(NetworkSettings.isWifiOnly(context))}")
        sb.appendLine("Network: ${NetworkSettings.connection(context).name.lowercase()}")
        sb.appendLine()

        fun line(ok: Boolean, name: String) = sb.appendLine("[${if (ok) "OK" else "PROBLEM"}] $name")

        val svc = pl.sinusoide.sinuschat.messenger.MessageListenerService
        val onDemand = svc.onDemand(context)
        line(svc.isRunning || onDemand, if (onDemand) "background service (on demand, FCM)" else "background service")
        val live = pl.sinusoide.sinuschat.network.WebSocketManager.isConnected()
        line(live || onDemand, if (onDemand && !live) "live connection (on demand, FCM)" else "live connection")
        line(NotificationManagerCompat.from(context).areNotificationsEnabled(), "notifications")
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        if (onDemand) line(true, "battery optimization (not needed with FCM)")
        else line(pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true, "battery optimization off")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(android.app.AlarmManager::class.java)
            if (onDemand) line(true, "exact alarms (not needed with FCM)")
            else line(am?.canScheduleExactAlarms() ?: true, "exact alarms")
        }
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm != null) {
            line(cm.restrictBackgroundStatus != android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                "background data allowed")
        }
        val dbError = pl.sinusoide.sinuschat.crypto.DbKeyManager.getMigrationError(context)
        val dbEncrypted = pl.sinusoide.sinuschat.crypto.DbCrypto.isDbEncrypted(context) ||
            !pl.sinusoide.sinuschat.crypto.DbCrypto.dbExists(context)
        line(dbError == null && dbEncrypted, "database encryption" + (dbError?.let { " ($it)" }.orEmpty()))
        LinkLog.lastCrash(context).takeIf { it.isNotEmpty() }?.let {
            sb.appendLine()
            sb.appendLine("Last app crash:")
            sb.appendLine(it)
        }
        AppExits.report(context).takeIf { it.isNotEmpty() }?.let {
            sb.appendLine()
            sb.appendLine("Recent app exits (Android):")
            sb.appendLine(it)
        }
        LinkLog.report(context, (REPORT_LIMIT - sb.length - 64).coerceAtLeast(1000)).takeIf { it.isNotEmpty() }?.let {
            sb.appendLine()
            sb.appendLine("Events (last 3 days):")
            sb.appendLine(it)
        }
        return sb.toString().trim()
    }

    private const val REPORT_LIMIT = 15_500

    private fun onOff(b: Boolean) = if (b) "on" else "off"
}
