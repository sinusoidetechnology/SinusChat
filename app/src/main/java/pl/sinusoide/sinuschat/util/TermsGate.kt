package pl.sinusoide.sinuschat.util

import android.content.Context
import okhttp3.Request
import org.json.JSONObject
import pl.sinusoide.sinuschat.AppConfig
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.setup.SetupActivity
import java.util.concurrent.TimeUnit

object TermsGate {

    private const val PREFS = "sinuschat_terms_gate"
    private const val KEY_REMOTE = "remote_version"
    private const val KEY_LAST = "last_check"
    private const val CHECK_EVERY_MS = 6 * 3_600_000L
    private const val RETRY_MS = 15 * 60_000L
    private val VERSION = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    @Volatile private var checking = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun newest(ctx: Context): String {
        val remote = prefs(ctx).getString(KEY_REMOTE, null)
        return if (remote != null && remote > SetupActivity.TERMS_VERSION) remote else SetupActivity.TERMS_VERSION
    }

    fun required(ctx: Context): Boolean {
        if (!SetupActivity.isSetupDone(ctx) || SetupActivity.wizardStep(ctx) != null) return false
        val accepted = SetupActivity.acceptedTermsVersion(ctx) ?: return true
        return accepted < newest(ctx)
    }

    fun refresh(ctx: Context, onRequired: () -> Unit) {
        val app = ctx.applicationContext
        if (checking) return
        val p = prefs(app)
        val now = System.currentTimeMillis()
        if (now - p.getLong(KEY_LAST, 0L) < CHECK_EVERY_MS) return
        checking = true
        p.edit().putLong(KEY_LAST, now - CHECK_EVERY_MS + RETRY_MS).apply()
        AppExecutor.io {
            try {
                val remote = fetch(app) ?: return@io
                p.edit().putString(KEY_REMOTE, remote).putLong(KEY_LAST, System.currentTimeMillis()).apply()
                if (required(app)) AppExecutor.main { onRequired() }
            } finally {
                checking = false
            }
        }
    }

    private fun fetch(ctx: Context): String? = try {
        val tor = PrivacySettings.isTorEnabled(ctx)
        val client = ApiClient.httpClient(ctx).newBuilder()
            .callTimeout(if (tor) 60 else 15, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(AppConfig.TERMS_URL).header("Cache-Control", "no-cache").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string()?.take(4096) ?: return null
            JSONObject(body).optString("version").trim().takeIf { VERSION.matches(it) }
        }
    } catch (_: Exception) {
        null
    }
}
