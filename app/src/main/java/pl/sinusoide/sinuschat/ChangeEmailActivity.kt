package pl.sinusoide.sinuschat

import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.json.JSONObject
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.ui.CodeInputView
import pl.sinusoide.sinuschat.ui.ThemeUi
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.ThemeManager
import java.net.URLEncoder

class ChangeEmailActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }

    private val authUrl = pl.sinusoide.sinuschat.AppConfig.AUTH_URL

    private lateinit var viewInput: View
    private lateinit var viewVerify: View
    private lateinit var codeInput: CodeInputView
    private lateinit var btnVerify: Button
    private var ticket = ""
    private var rejected: String? = null
    private var clipStampAtEntry: Long? = null
    private lateinit var tvResend: TextView
    private lateinit var tvCodeError: TextView
    private lateinit var etNew: EditText
    private lateinit var btnNext: Button
    private lateinit var scroll: ScrollView
    private lateinit var inputRow: View

    private var newEmail = ""
    private var authProof: String? = null
    private var resendCount = 0
    private var resendTimer: CountDownTimer? = null
    private var busy = false

    companion object {
        private const val RESEND_COOLDOWN_MS = 30_000L
        private const val RESEND_MAX = 5
        private const val RESEND_LOCKOUT_MS = 10 * 60_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_change_email)

        viewInput = findViewById(R.id.view_input_email)
        viewVerify = findViewById(R.id.view_verify_code)
        scroll = findViewById(R.id.scroll_change_email)
        inputRow = findViewById(R.id.layout_new_email)
        codeInput = findViewById(R.id.code_input)
        btnVerify = findViewById(R.id.btn_verify)
        tvResend = findViewById(R.id.tv_resend)
        tvCodeError = findViewById(R.id.tv_code_error)
        etNew = findViewById(R.id.et_new_email)
        btnNext = findViewById(R.id.btn_next)

        val header = findViewById<View>(R.id.layout_header)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_change_email)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.updatePadding(top = bars.top)
            v.updatePadding(bottom = maxOf(bars.bottom, ime.bottom))
            if (ime.bottom > 0) scroll.post { revealBottom() }
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewVerify.visibility == View.VISIBLE && !busy) backToInput() else finish()
            }
        })
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        styleViews()

        findViewById<TextView>(R.id.tv_current_email_intro).text =
            SetupActivity.getUserEmail(this).ifEmpty { getString(R.string.not_set) }

        etNew.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { validateInputs() }
        })
        etNew.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                onNextClicked(); true
            } else false
        }
        etNew.setOnFocusChangeListener { _, focused -> styleInputRow(focused) }
        inputRow.setOnClickListener { etNew.requestFocus(); showKeyboard(etNew) }
        validateInputs()

        btnNext.setOnClickListener { onNextClicked() }
        findViewById<View>(R.id.btn_fix_email).setOnClickListener { if (!busy) backToInput() }
        codeInput.onEdit = { if (tvCodeError.visibility == View.VISIBLE) tvCodeError.visibility = View.GONE }
        codeInput.onComplete = { verifyCode() }
        codeInput.onFullCode = { verifyCode(it) }
        btnVerify.setOnClickListener {
            if (codeInput.code.length < CodeInputView.LENGTH) {
                codeInput.shake()
                showCodeError(getString(R.string.setup_code_incomplete))
                codeInput.focus()
            } else verifyCode()
        }
        tvResend.setOnClickListener { onResendClicked() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && viewVerify.visibility == View.VISIBLE) checkClipboard()
    }

    private fun clipStamp(): Long {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return 0L
        return try { cm.primaryClipDescription?.timestamp ?: 0L } catch (_: Throwable) { 0L }
    }

    private fun checkClipboard() {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        val stamp = clipStamp()
        val entry = clipStampAtEntry ?: run { clipStampAtEntry = stamp; return }
        if (stamp == entry || codeInput.code.isNotEmpty() || busy) return
        clipStampAtEntry = stamp
        val clip = try { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty() } catch (_: Throwable) { "" }
        val code = Regex("""(?<!\d)\d{6}(?!\d)""").find(clip)?.value ?: return
        if (code == rejected) return
        codeInput.typeIn(code)
    }

    private fun showCodeError(message: String) {
        tvCodeError.text = message
        if (tvCodeError.visibility != View.VISIBLE) {
            tvCodeError.alpha = 0f
            tvCodeError.visibility = View.VISIBLE
            tvCodeError.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun styleViews() {
        val accent = ThemeUi.accent(this)
        val tint = (accent and 0x00FFFFFF) or 0x26000000
        for (id in intArrayOf(R.id.iv_input_icon, R.id.iv_verify_icon)) {
            findViewById<ImageView>(id).apply {
                setColorFilter(accent)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(tint)
                }
            }
        }
        findViewById<View>(R.id.layout_current_email).background = ThemeUi.rounded(this, ThemeUi.card(this), 16f)
        findViewById<ImageView>(R.id.iv_fix_email).setColorFilter(accent)
        findViewById<TextView>(R.id.tv_fix_email).setTextColor(accent)
        findViewById<View>(R.id.btn_fix_email).background = RippleDrawable(
            ColorStateList.valueOf(tint),
            ThemeUi.rounded(this, (accent and 0x00FFFFFF) or 0x14000000, 18f), null)
        for (b in listOf(btnNext, btnVerify)) {
            b.backgroundTintList = null
            b.stateListAnimator = null
            b.background = GradientDrawable().apply {
                cornerRadius = dp(27f)
                setColor(accent)
            }
            b.setTextColor(ThemeUi.onAccent(this))
        }
        styleInputRow(false)
    }

    private fun styleInputRow(focused: Boolean) {
        val accent = ThemeUi.accent(this)
        inputRow.background = GradientDrawable().apply {
            cornerRadius = dp(16f)
            setColor(ThemeUi.surface(this@ChangeEmailActivity))
            setStroke(dp(if (focused) 1.5f else 1f).toInt(),
                if (focused) accent else ThemeUi.border(this@ChangeEmailActivity))
        }
        findViewById<ImageView>(R.id.iv_new_email_icon).setColorFilter(
            if (focused) accent else ThemeUi.textSecondary(this))
    }

    private fun revealBottom() {
        val target = if (viewVerify.visibility == View.VISIBLE) tvResend else btnNext
        val rect = Rect()
        target.getDrawingRect(rect)
        scroll.offsetDescendantRectToMyCoords(target, rect)
        val bottom = rect.bottom + dp(16f).toInt() - scroll.height
        if (bottom > scroll.scrollY) scroll.smoothScrollTo(0, bottom)
    }

    private fun showKeyboard(v: View) {
        getSystemService(InputMethodManager::class.java)?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun normalized(et: EditText) = et.text.toString().trim().lowercase()

    private fun validateInputs() {
        val email = normalized(etNew)
        val valid = Patterns.EMAIL_ADDRESS.matcher(email).matches()
        val current = SetupActivity.getUserEmail(this).lowercase()
        if (valid && email == current) showEmailError(getString(R.string.email_already_current))
        else findViewById<TextView>(R.id.tv_email_error).visibility = View.GONE
        btnNext.isEnabled = !busy && valid && email != current
        btnNext.alpha = if (btnNext.isEnabled) 1f else 0.5f
    }

    private fun onNextClicked() {
        val email = normalized(etNew)
        if (busy) return
        validateInputs()
        if (!btnNext.isEnabled) {
            if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                showEmailError(getString(R.string.invalid_email))
            }
            return
        }

        val progress = findViewById<ProgressBar>(R.id.progress_email)
        setBusy(true)
        progress.visibility = View.VISIBLE
        hideKeyboard()

        Thread {
            val taken = isEmailTaken(email)
            runOnUiThread {
                if (taken) {
                    progress.visibility = View.GONE
                    setBusy(false)
                    showEmailError(getString(R.string.email_already_in_use))
                } else {
                    sendCode(email) { ok, error ->
                        progress.visibility = View.GONE
                        setBusy(false)
                        if (ok) {
                            newEmail = email
                            goToVerify()
                        } else {
                            showEmailError(error ?: getString(R.string.code_send_failed_retry))
                        }
                    }
                }
            }
        }.start()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        etNew.isEnabled = !value
        validateInputs()
    }

    private fun showEmailError(msg: String) {
        val err = findViewById<TextView>(R.id.tv_email_error)
        err.text = msg
        err.visibility = View.VISIBLE
    }

    private fun isEmailTaken(email: String): Boolean {
        return try {
            val hash = E2EManager.sha256(email)
            val user = pl.sinusoide.sinuschat.network.ApiClient.getObject("/api/user/$hash", this)
            user != null && user.optString("email_hash").isNotEmpty()
        } catch (_: Exception) { false }
    }

    private fun goToVerify() {
        viewInput.visibility = View.GONE
        viewVerify.visibility = View.VISIBLE
        tvCodeError.visibility = View.GONE
        rejected = null
        clipStampAtEntry = clipStamp()
        codeInput.setLocked(false)
        codeInput.clear()
        btnVerify.isEnabled = true
        findViewById<TextView>(R.id.tv_toolbar_title).text = getString(R.string.confirm_code)
        findViewById<TextView>(R.id.tv_code_email).text = newEmail
        resendCount = 1
        startResendCooldown(RESEND_COOLDOWN_MS)
        scroll.scrollTo(0, 0)
        codeInput.post { codeInput.focus() }
    }

    private fun backToInput() {
        resendTimer?.cancel()
        authProof = null
        viewVerify.visibility = View.GONE
        viewInput.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_toolbar_title).text = getString(R.string.change_email_title)
        validateInputs()
        scroll.scrollTo(0, 0)
        etNew.requestFocus()
        etNew.setSelection(etNew.text.length)
        etNew.post { showKeyboard(etNew) }
    }

    private fun requestVerify(code: String): JSONObject? {
        if (ticket.isNotEmpty()) {
            try {
                val body = JSONObject().put("email", newEmail).put("code", code).put("ticket", ticket).toString()
                val r = pl.sinusoide.sinuschat.network.ApiClient.rawPost(this,
                    "${AppConfig.API_BASE}/api/auth/verify", body.toByteArray(Charsets.UTF_8),
                    "application/json; charset=utf-8")
                if (r != null && r.first == 200) return JSONObject(r.second?.toString(Charsets.UTF_8) ?: "")
            } catch (_: Exception) { }
        }
        return postForm("verify", "action=verify&email=${URLEncoder.encode(newEmail, "UTF-8")}&code=$code" +
            (if (ticket.isNotEmpty()) "&ticket=${URLEncoder.encode(ticket, "UTF-8")}" else ""))
    }

    private fun verifyCode(code: String = codeInput.code.trim()) {
        if (code.length != CodeInputView.LENGTH || busy) return
        tvCodeError.visibility = View.GONE
        val progress = findViewById<ProgressBar>(R.id.progress_code)
        progress.visibility = View.VISIBLE
        busy = true
        btnVerify.isEnabled = false
        codeInput.setLocked(true)

        Thread {
            val json = requestVerify(code)
            runOnUiThread { codeInput.afterTyping {
                progress.visibility = View.GONE
                when {
                    json == null -> {
                        busy = false
                        btnVerify.isEnabled = true
                        codeInput.setLocked(false)
                        showCodeError(getString(R.string.error_connection))
                    }
                    json.optBoolean("success", false) -> {
                        authProof = json.optString("authProof").takeIf { it.isNotEmpty() }
                        authProof?.let {
                            pl.sinusoide.sinuschat.network.ApiClient.saveAuthProof(this, it, json.optLong("expiresAt", 0L))
                        }
                        codeInput.showSuccess()
                        codeInput.postDelayed({ onCodeVerified() }, 550)
                    }
                    else -> {
                        busy = false
                        btnVerify.isEnabled = true
                        rejected = code
                        codeInput.setLocked(false)
                        codeInput.showError()
                        showCodeError(json.optString("error").ifEmpty { getString(R.string.wrong_code) })
                        codeInput.postDelayed({ codeInput.focus() }, 560)
                    }
                }
            } }
        }.start()
    }

    private fun onCodeVerified() {
        val progress = findViewById<ProgressBar>(R.id.progress_code)
        progress.visibility = View.VISIBLE
        busy = true
        val newHash = E2EManager.sha256(newEmail)

        Thread {
            val resp = try {
                val body = JSONObject().put("newHash", newHash)
                authProof?.let { body.put("authProof", it) }
                pl.sinusoide.sinuschat.network.ApiClient.post("/api/user/change-email", body, this)
            } catch (_: Exception) { null }
            val movedOk = resp != null && resp.optBoolean("ok", false)
            val oldHash = SetupActivity.getUserPhoneHash(this)
            val movedContacts = if (movedOk) {
                try { pl.sinusoide.sinuschat.util.AccountMove.moveLocally(applicationContext, oldHash, newHash) }
                catch (_: Exception) { emptyList() }
            } else emptyList()
            runOnUiThread {
                progress.visibility = View.GONE
                busy = false
                if (movedOk && resp != null) {
                    val token = resp.optString("token")
                    if (token.isNotEmpty()) pl.sinusoide.sinuschat.network.ApiClient.saveToken(this, token)
                    SetupActivity.updateEmailAndHash(this, newEmail, newHash)
                    val app = applicationContext
                    pl.sinusoide.sinuschat.util.AppExecutor.io {
                        pl.sinusoide.sinuschat.util.AccountMove.announceMove(app, oldHash, newHash, movedContacts)
                    }
                    hideKeyboard()
                    showChangedSheet()
                } else {
                    btnVerify.isEnabled = true
                    codeInput.setLocked(false)
                    codeInput.clear()
                    showCodeError(resp?.optString("error")?.takeIf { it.isNotEmpty() }
                        ?: getString(R.string.server_connect_failed_account))
                }
            }
        }.start()
    }

    private fun showChangedSheet() {
        val d = resources.displayMetrics.density
        val green = androidx.core.content.ContextCompat.getColor(this, R.color.sinuschat_success)
        val accent = ThemeUi.accent(this)
        val secondary = ThemeUi.textSecondary(this)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (28 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
        }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_check); setColorFilter(green)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((green and 0x00FFFFFF) or 0x26000000)
            }
            val p = (16 * d).toInt(); setPadding(p, p, p, p)
        }, android.widget.LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()).apply {
            bottomMargin = (16 * d).toInt()
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.email_changed_title)
            textSize = 20f; setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = newEmail; textSize = 15f; setTextColor(accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, (4 * d).toInt(), 0, (12 * d).toInt())
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.email_changed_note)
            textSize = 13.5f; setTextColor(secondary)
            gravity = android.view.Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, (22 * d).toInt())
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.change_email_done)
            textSize = 16f; setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 26 * d; setColor(accent)
            }
            setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, android.widget.LinearLayout.LayoutParams(-1, -2))
        sheet.setCancelable(true)
        sheet.setOnDismissListener {
            setResult(RESULT_OK)
            finish()
        }
        sheet.setContentView(root)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun onResendClicked() {
        if (busy) return
        if (resendCount >= RESEND_MAX) {
            startResendCooldown(RESEND_LOCKOUT_MS, lockout = true)
            return
        }
        val progress = findViewById<ProgressBar>(R.id.progress_code)
        progress.visibility = View.VISIBLE
        sendCode(newEmail) { ok, error ->
            progress.visibility = View.GONE
            if (ok) {
                resendCount++
                tvCodeError.visibility = View.GONE
                rejected = null
                codeInput.setLocked(false)
                codeInput.clear()
                codeInput.focus()
                PopupHelper.showSuccess(this, getString(R.string.code_resent))
                if (resendCount >= RESEND_MAX) startResendCooldown(RESEND_LOCKOUT_MS, lockout = true)
                else startResendCooldown(RESEND_COOLDOWN_MS)
            } else {
                PopupHelper.showError(this, error ?: getString(R.string.code_send_failed))
            }
        }
    }

    private fun startResendCooldown(durationMs: Long, lockout: Boolean = false) {
        tvResend.isClickable = false
        tvResend.setTextColor(ThemeUi.textMuted(tvResend.context))
        resendTimer?.cancel()
        resendTimer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                val total = ms / 1000
                val m = total / 60
                val s = total % 60
                tvResend.text = if (lockout)
                    getString(R.string.too_many_attempts_timer, m, s)
                else
                    getString(R.string.resend_timer_format, m, s)
            }
            override fun onFinish() {
                if (lockout) resendCount = 0
                tvResend.text = getString(R.string.resend_code)
                tvResend.isClickable = true
                tvResend.setTextColor(ThemeUi.accent(tvResend.context))
            }
        }.start()
    }

    private fun postForm(action: String, body: String): JSONObject? = try {
        val text = pl.sinusoide.sinuschat.network.ApiClient.rawPost(this, "$authUrl?action=$action", body.toByteArray(),
            "application/x-www-form-urlencoded")?.second?.toString(Charsets.UTF_8) ?: ""
        runCatching { JSONObject(text) }.getOrNull()
    } catch (_: Exception) { null }

    private fun sendCode(email: String, onResult: (Boolean, String?) -> Unit) {
        Thread {
            val json = postForm("send", "action=send&purpose=change_email" +
                    "&email=${URLEncoder.encode(email, "UTF-8")}")
            val ok = json?.optBoolean("success", false) == true
            if (ok) json?.optString("ticket")?.takeIf { it.isNotEmpty() }?.let { ticket = it }
            val error = json?.optString("error")?.takeIf { it.isNotEmpty() }
            runOnUiThread { onResult(ok, if (json == null) getString(R.string.error_connection) else error) }
        }.start()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(etNew.windowToken, 0)
    }

    override fun onDestroy() {
        resendTimer?.cancel()
        super.onDestroy()
    }
}
