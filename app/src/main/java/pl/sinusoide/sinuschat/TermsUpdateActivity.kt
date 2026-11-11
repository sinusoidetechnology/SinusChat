package pl.sinusoide.sinuschat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.checkbox.MaterialCheckBox
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.ui.DocumentIconView
import pl.sinusoide.sinuschat.ui.IconDrawView
import pl.sinusoide.sinuschat.ui.ThemeUi
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.SafeEngine
import pl.sinusoide.sinuschat.util.TermsGate
import pl.sinusoide.sinuschat.util.ThemeManager

class TermsUpdateActivity : AppCompatActivity() {

    companion object {
        @Volatile var showing = false
            private set

        fun open(a: Activity) {
            if (showing || a is TermsUpdateActivity) return
            showing = true
            try {
                a.startActivity(Intent(a, TermsUpdateActivity::class.java))
            } catch (_: Exception) {
                showing = false
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    private val dp by lazy { resources.displayMetrics.density }
    private var cPrimary = 0
    private var cSecondary = 0
    private var cAccent = 0
    private var cCard = 0
    private var cBorder = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showing = true
        supportActionBar?.hide()

        cPrimary = ThemeUi.textPrimary(this)
        cSecondary = ThemeUi.attrOrNull(this, R.attr.sinuschat_text_secondary) ?: 0xFF7F91A4.toInt()
        cAccent = ThemeUi.accent(this)
        cCard = ThemeUi.attrOrNull(this, R.attr.sinuschat_surface_elevated) ?: 0xFF1D2A38.toInt()
        cBorder = ThemeUi.border(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })

        val root = FrameLayout(this)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            scroll.setPadding(b.left, b.top + (12 * dp).toInt(), b.right, b.bottom)
            insets
        }
        setContentView(root)

        body.addView(DocumentIconView(this).apply {
            setColor(IconDrawView.ink(this@TermsUpdateActivity))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, (180 * dp).toInt()))

        body.addView(text(getString(R.string.terms_update_title), 24f, cPrimary, true).apply {
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        body.addView(text(getString(R.string.terms_update_desc), 15f, cSecondary, false).apply {
            setPadding(0, (10 * dp).toInt(), 0, 0)
        })
        body.addView(text(getString(R.string.terms_update_date, date(TermsGate.newest(this))), 13f, cAccent, true).apply {
            val ph = (14 * dp).toInt(); val pv = (6 * dp).toInt(); setPadding(ph, pv, ph, pv)
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor((cAccent and 0x00FFFFFF) or 0x22000000)
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * dp).toInt() })

        var agreed = false
        val box = MaterialCheckBox(this).apply {
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(cAccent, cSecondary))
            isClickable = false
            isFocusable = false
        }
        val agreeText = TextView(this).apply {
            text = getString(R.string.setup_terms_agree)
            textSize = 15f
            setTextColor(cPrimary)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }
        val link = getString(R.string.setup_terms_docs_link)
        val full = getString(R.string.setup_terms_docs, link)
        val docsText = TextView(this).apply {
            val span = android.text.SpannableString(full)
            val at = full.indexOf(link)
            if (at >= 0) {
                span.setSpan(object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) { openDocs() }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.color = cAccent
                        ds.isUnderlineText = true
                        ds.isFakeBoldText = true
                    }
                }, at, at + link.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = span
            textSize = 13.5f
            setTextColor(cSecondary)
            setLineSpacing(0f, 1.2f)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        val cardBg = GradientDrawable().apply {
            cornerRadius = 18 * dp
            setColor(cCard)
            setStroke((1 * dp).toInt(), cBorder)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding((12 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            background = cardBg
            isClickable = true
            addView(box, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * dp).toInt() })
            addView(LinearLayout(this@TermsUpdateActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (8 * dp).toInt(), 0, 0)
                addView(agreeText)
                addView(docsText)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (22 * dp).toInt() })

        val declined = text(getString(R.string.terms_update_declined), 14f, cSecondary, false).apply {
            visibility = View.GONE
            setPadding(0, (14 * dp).toInt(), 0, 0)
        }
        body.addView(declined)

        val accept = button(getString(R.string.terms_update_accept), true) {
            if (!agreed) return@button
            SetupActivity.acceptTerms(this)
            showing = false
            finish()
        }
        accept.isEnabled = false
        accept.alpha = 0.45f
        body.addView(accept, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * dp).toInt() })

        lateinit var decline: TextView
        decline = button(getString(R.string.terms_update_decline), false) {
            if (declined.visibility == View.VISIBLE) {
                showing = false
                finishAffinity()
            } else {
                declined.visibility = View.VISIBLE
                declined.alpha = 0f
                declined.animate().alpha(1f).setDuration(220).start()
                decline.text = getString(R.string.terms_update_close)
            }
        }
        body.addView(decline, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })

        row.setOnClickListener {
            agreed = !agreed
            box.isChecked = agreed
            cardBg.setStroke((if (agreed) 2 * dp else 1 * dp).toInt(), if (agreed) cAccent else cBorder)
            accept.isEnabled = agreed
            accept.animate().alpha(if (agreed) 1f else 0.45f).setDuration(180).start()
        }

        for (i in 0 until body.childCount) {
            val c = body.getChildAt(i)
            c.alpha = 0f; c.translationY = 18 * dp
            c.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 45L).setDuration(320)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        declined.animate().cancel()
        declined.alpha = 1f
        declined.translationY = 0f
    }

    override fun onDestroy() {
        if (isFinishing) showing = false
        super.onDestroy()
    }

    private fun openDocs() {
        val lang = if (LocaleManager.getEffective(this) == "pl") "pl" else "en"
        SafeEngine.openLink(this, "${AppConfig.DOCS_URL}?lang=$lang")
    }

    private fun date(version: String): String = try {
        val d = java.time.LocalDate.parse(version)
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)
            .withLocale(resources.configuration.locales[0]).format(d)
    } catch (_: Exception) {
        version
    }

    private fun text(value: CharSequence, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(2 * dp, 1f)
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 16f; gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Color.WHITE else cPrimary)
        val pv = (15 * dp).toInt(); setPadding((20 * dp).toInt(), pv, (20 * dp).toInt(), pv)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(if (primary) 0x40FFFFFF else (cPrimary and 0x00FFFFFF) or 0x22000000),
            GradientDrawable().apply {
                cornerRadius = 26 * dp
                setColor(if (primary) cAccent else Color.TRANSPARENT)
            }, null)
        isClickable = true
        setOnClickListener { onClick() }
    }
}
