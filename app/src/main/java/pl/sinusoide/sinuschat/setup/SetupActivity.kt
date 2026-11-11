package pl.sinusoide.sinuschat.setup

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import pl.sinusoide.sinuschat.MainActivity
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.ui.startWithFade

class SetupActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }


    companion object {
        private const val PREFS = "sinuschat_prefs"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_USER_HASH = "user_phone_hash"
        private const val KEY_USER_DISPLAY_NAME = "user_display_name"
        private const val KEY_USER_AVATAR_URI = "user_avatar_uri"
        private const val KEY_USER_EMAIL = "user_email"

        private const val KEY_WIZARD_STEP = "wizard_step"

        fun wizardStep(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WIZARD_STEP, null)

        fun setWizardStep(context: Context, step: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (step == null) remove(KEY_WIZARD_STEP) else putString(KEY_WIZARD_STEP, step)
            }.apply()
        }

        const val TERMS_VERSION = "2026-09-23"
        private const val KEY_TERMS_VERSION = "terms_version"
        private const val KEY_TERMS_AT = "terms_accepted_at"

        fun acceptedTermsVersion(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TERMS_VERSION, null)

        fun termsAccepted(context: Context): Boolean {
            val accepted = acceptedTermsVersion(context) ?: return false
            return accepted >= pl.sinusoide.sinuschat.util.TermsGate.newest(context)
        }

        fun acceptTerms(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TERMS_VERSION, pl.sinusoide.sinuschat.util.TermsGate.newest(context))
                .putLong(KEY_TERMS_AT, System.currentTimeMillis())
                .apply()
        }

        fun revokeTerms(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_TERMS_VERSION).remove(KEY_TERMS_AT).apply()
        }

        private const val KEY_RESTORE = "restore_after_login"

        fun restoreWanted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RESTORE, false)

        fun setRestoreWanted(context: Context, wanted: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RESTORE, wanted).apply()
        }

        private const val KEY_PENDING_EMAIL = "pending_reg_email"
        private const val KEY_PENDING_NAME = "pending_reg_name"

        class PendingRegistration(val email: String, val displayName: String)

        fun pendingRegistration(context: Context): PendingRegistration? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val email = p.getString(KEY_PENDING_EMAIL, null)?.takeIf { it.isNotEmpty() } ?: return null
            return PendingRegistration(email, p.getString(KEY_PENDING_NAME, "") ?: "")
        }

        fun setPendingRegistration(context: Context, email: String, displayName: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_EMAIL, email)
                .putString(KEY_PENDING_NAME, displayName)
                .apply()
        }

        fun clearPendingRegistration(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_PENDING_EMAIL).remove(KEY_PENDING_NAME).apply()
        }

        fun isSetupDone(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SETUP_DONE, false)

        fun markSetupDone(context: Context, userHash: String, displayName: String, email: String = "") {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SETUP_DONE, true)
                .putString(KEY_USER_HASH, userHash)
                .putString(KEY_USER_DISPLAY_NAME, displayName)
                .putString(KEY_USER_EMAIL, email)
                .apply()
        }

        fun getUserPhoneHash(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_USER_HASH, "") ?: ""

        fun getUserDisplayName(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_USER_DISPLAY_NAME, "") ?: ""

        fun setUserDisplayName(context: Context, name: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER_DISPLAY_NAME, name).apply()
        }

        fun updateEmailAndHash(context: Context, newEmail: String, newHash: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER_EMAIL, newEmail)
                .putString(KEY_USER_HASH, newHash)
                .apply()
        }

        fun setUserAvatarUri(context: Context, uri: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER_AVATAR_URI, uri).apply()
        }

        fun getUserEmail(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_USER_EMAIL, "") ?: ""

        fun accountHashOf(emailOrAnon: String): String =
            if (AnonAccount.isAnonId(emailOrAnon)) emailOrAnon.removePrefix(AnonAccount.PREFIX)
            else pl.sinusoide.sinuschat.crypto.E2EManager.sha256(emailOrAnon)

        fun displayEmail(context: Context): String {
            val e = getUserEmail(context)
            return if (AnonAccount.isAnonId(e)) context.getString(pl.sinusoide.sinuschat.R.string.account_no_email) else e
        }

        fun resetSetup(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }

        const val EXTRA_REVERIFY = "reverify"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        pl.sinusoide.sinuschat.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val reverify = intent.getBooleanExtra(EXTRA_REVERIFY, false)
        val wizard = wizardStep(this)
        if (isSetupDone(this) && !reverify && wizard == null) { goToMain(); return }
        setContentView(R.layout.activity_setup)
        applyThemeChrome()
        supportActionBar?.hide()
        if (savedInstanceState == null) {
            val savedEmail = getUserEmail(this)
            if (reverify && savedEmail.isNotEmpty()) {
                navigateTo(VerifyCodeFragment().apply {
                    arguments = Bundle().apply {
                        putString("email", savedEmail)
                        putBoolean("reverify", true)
                    }
                }, addToBackStack = false)
            } else {
                val step = SetupStepFragment.lastStep
                    ?: wizard?.let { runCatching { SetupStepFragment.Step.valueOf(it) }.getOrNull() }
                    ?: SetupStepFragment.Step.WELCOME
                navigateTo(SetupStepFragment.of(step), addToBackStack = false)
            }
        }
    }

    private fun applyThemeChrome() {
        val flow = pl.sinusoide.sinuschat.util.ThemeManager.isFlow(this)
        val bar = if (flow) Color.TRANSPARENT
        else pl.sinusoide.sinuschat.ui.ThemeUi.backgroundSolid(this)
        val style = if (pl.sinusoide.sinuschat.ui.ThemeUi.isDark(this)) androidx.activity.SystemBarStyle.dark(bar)
        else androidx.activity.SystemBarStyle.light(bar, bar)
        enableEdgeToEdge(style, style)
        if (flow) findViewById<View>(R.id.setup_container)?.setBackgroundColor(Color.TRANSPARENT)
    }

    fun navigateTo(fragment: Fragment, addToBackStack: Boolean = true) {
        val tx = supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out,
                android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.setup_container, fragment)
        if (addToBackStack) tx.addToBackStack(null)
        tx.commit()
    }

    fun startWizard(step: SetupStepFragment.Step) {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        navigateTo(SetupStepFragment.of(step), addToBackStack = false)
    }

    fun finishSetup(from: View? = null) {
        setWizardStep(this, null)
        if (from == null || from.width == 0) {
            goToMain(fade = true)
            return
        }
        pl.sinusoide.sinuschat.ui.CircleReveal.capture(this, from) { ok ->
            val main = Intent(this, MainActivity::class.java).apply {
                putExtra("unlocked", true)
            }
            if (ok) startWithFade(main, 0, 0) else startWithFade(main)
            finishAffinity()
        }
    }

    fun goToMain(fade: Boolean = false) {
        val main = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("unlocked", true)
        }
        if (fade) startWithFade(main) else startActivity(main)
        finish()
    }
}
