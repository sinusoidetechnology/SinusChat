package pl.sinusoide.sinuschat.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pl.sinusoide.sinuschat.R

object TranslationNotifier {

    private const val CHANNEL_ID = "translation_download"
    private const val ACTION_CANCEL = "pl.sinusoide.sinuschat.CANCEL_TRANSLATION_DOWNLOAD"
    private const val EXTRA_LANG = "lang"

    private fun id(lang: String) = 7300 + (lang.hashCode() and 0x3ff)

    fun langName(ctx: Context, lang: String): String {
        val tag = if (lang == "zh") "zh-TW" else lang
        val loc = ctx.resources.configuration.locales[0]
        return java.util.Locale.forLanguageTag(tag).getDisplayLanguage(loc)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(loc) else it.toString() }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            ctx.getString(R.string.translate_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
    }

    fun progress(ctx: Context, lang: String, fraction: Float) {
        try {
            val app = ctx.applicationContext
            val w = LocaleManager.wrap(app)
            ensureChannel(app)
            val cancel = PendingIntent.getBroadcast(app, id(lang),
                Intent(app, CancelReceiver::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_LANG, lang),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val pct = (fraction.coerceIn(0f, 1f) * 100).toInt()
            val n = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(w.getString(R.string.translate_notif_title, langName(w, lang)))
                .setContentText("$pct%")
                .setProgress(100, pct, fraction <= 0f)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_stop, w.getString(R.string.download_stop), cancel)
                .build()
            NotificationManagerCompat.from(app).notify(id(lang), n)
        } catch (_: SecurityException) {
        } catch (_: Exception) {}
    }

    fun done(ctx: Context, lang: String) {
        try { NotificationManagerCompat.from(ctx.applicationContext).cancel(id(lang)) } catch (_: Exception) {}
    }

    class CancelReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CANCEL) return
            intent.getStringExtra(EXTRA_LANG)?.let { Translations.cancel(it) }
        }
    }
}
