package pl.sinusoide.sinuschat

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RotateDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import pl.sinusoide.sinuschat.ui.ThemeUi
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.ThemeManager
import pl.sinusoide.sinuschat.util.UpdateChecker

class UpdateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "update_name"
        const val EXTRA_FOR_REPORT = "update_for_report"
        const val EXTRA_PAGE = "update_page"

        fun open(ctx: Context, latest: UpdateChecker.Latest? = null, forReport: Boolean = false) {
            ctx.startActivity(Intent(ctx, UpdateActivity::class.java).apply {
                if (latest != null) {
                    putExtra(UpdateChecker.EXTRA_CODE, latest.code)
                    putExtra(EXTRA_NAME, latest.name)
                    latest.page?.let { putExtra(EXTRA_PAGE, it) }
                }
                if (forReport) putExtra(EXTRA_FOR_REPORT, true)
                if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private val forReport by lazy { intent.getBooleanExtra(EXTRA_FOR_REPORT, false) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    private val dp by lazy { resources.displayMetrics.density }
    private var cPrimary = 0
    private var cSecondary = 0
    private var cAccent = 0
    private var cCard = 0
    private lateinit var body: LinearLayout
    private lateinit var iconTile: FrameLayout
    private lateinit var icon: ImageView
    private var spin: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        cPrimary = ThemeUi.textPrimary(this)
        cSecondary = ThemeUi.attrOrNull(this, R.attr.sinuschat_text_secondary) ?: 0xFF7F91A4.toInt()
        cAccent = ThemeUi.accent(this)
        cCard = ThemeUi.attrOrNull(this, R.attr.sinuschat_surface_elevated) ?: 0xFF1D2A38.toInt()

        val root = FrameLayout(this)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * dp).toInt(), (24 * dp).toInt(), (28 * dp).toInt(), (24 * dp).toInt())
        }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(cPrimary)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p)
            background = ContextCompat.getDrawable(this@UpdateActivity, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.updater_close)
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt(), Gravity.TOP or Gravity.START))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (close.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = b.top + (6 * dp).toInt(); leftMargin = b.left + (8 * dp).toInt()
                close.layoutParams = this
            }
            scroll.setPadding(b.left, b.top + (40 * dp).toInt(), b.right, b.bottom)
            insets
        }
        setContentView(root)

        iconTile = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((cAccent and 0x00FFFFFF) or 0x26000000)
            }
        }
        icon = ImageView(this)
        iconTile.addView(icon, FrameLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt(), Gravity.CENTER))

        val code = intent.getLongExtra(UpdateChecker.EXTRA_CODE, 0L)
        val name = intent.getStringExtra(EXTRA_NAME)
        if (code > 0L && !name.isNullOrBlank()) {
            render(UpdateChecker.Latest(code, name, intent.getStringExtra(EXTRA_PAGE)), animate = true)
        } else {
            showLoading()
            AppExecutor.io {
                val latest = UpdateChecker.fetchLatest(this)
                AppExecutor.main { if (!isFinishing && !isDestroyed) render(latest, animate = true) }
            }
        }
    }

    override fun onDestroy() {
        spin?.cancel()
        super.onDestroy()
    }

    private fun showLoading() {
        body.removeAllViews()
        body.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(cAccent)
        }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        body.addView(text(getString(R.string.updater_checking), 15f, cSecondary, false).apply {
            setPadding(0, (16 * dp).toInt(), 0, 0)
        })
    }

    private fun text(value: CharSequence, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(2 * dp, 1f)
    }

    private fun versionChip(label: String, version: String, highlight: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val ph = (16 * dp).toInt(); val pv = (12 * dp).toInt(); setPadding(ph, pv, ph, pv)
        background = GradientDrawable().apply {
            cornerRadius = 16 * dp
            setColor(if (highlight) (cAccent and 0x00FFFFFF) or 0x2E000000 else cCard)
            if (highlight) setStroke((1.5f * dp).toInt(), (cAccent and 0x00FFFFFF) or 0x99000000.toInt())
        }
        addView(text(label, 12f, cSecondary, false))
        addView(text(version, 20f, if (highlight) cAccent else cPrimary, true).apply {
            fontFeatureSettings = "tnum"
        })
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

    private fun render(latest: UpdateChecker.Latest?, animate: Boolean) {
        body.removeAllViews()
        val current = UpdateChecker.installedName(this)
        val outdated = latest != null && UpdateChecker.isOutdated(this, latest)

        (iconTile.parent as? android.view.ViewGroup)?.removeView(iconTile)
        body.addView(iconTile, LinearLayout.LayoutParams((104 * dp).toInt(), (104 * dp).toInt()))
        val title: String
        val desc: String
        when {
            outdated -> {
                setSpinningIcon()
                title = getString(if (forReport) R.string.updater_report_title else R.string.updater_title)
                desc = getString(if (forReport) R.string.updater_report_desc else R.string.updater_desc)
            }
            latest == null -> {
                icon.setImageResource(R.drawable.ic_warning); icon.setColorFilter(cSecondary)
                title = getString(R.string.updater_unknown_title)
                desc = getString(R.string.updater_unknown_desc)
            }
            else -> {
                icon.setImageResource(R.drawable.ic_check); icon.setColorFilter(cAccent)
                title = getString(R.string.updater_latest_title)
                desc = getString(R.string.updater_latest_desc, current)
            }
        }
        body.addView(text(title, 24f, cPrimary, true).apply { setPadding(0, (22 * dp).toInt(), 0, 0) })
        body.addView(text(desc, 15f, cSecondary, false).apply { setPadding(0, (10 * dp).toInt(), 0, 0) })

        if (outdated && latest != null) {
            val compare = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(versionChip(getString(R.string.updater_yours), current, false))
                addView(ImageView(this@UpdateActivity).apply {
                    setImageResource(R.drawable.ic_chevron_right); setColorFilter(cSecondary)
                }, LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply {
                    marginStart = (10 * dp).toInt(); marginEnd = (10 * dp).toInt()
                })
                addView(versionChip(getString(R.string.updater_new), latest.name, true))
            }
            body.addView(compare, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (26 * dp).toInt() })

            body.addView(text(getString(R.string.updater_whats_new, latest.name), 14.5f, cAccent, true).apply {
                val p = (10 * dp).toInt(); setPadding(p, p, p, p)
                background = ContextCompat.getDrawable(this@UpdateActivity, R.drawable.bg_menu_ripple)
                isClickable = true
                setOnClickListener {
                    val lang = if (LocaleManager.getEffective(this@UpdateActivity) == "pl") "pl" else "en"
                    pl.sinusoide.sinuschat.util.SafeEngine.openLink(this@UpdateActivity,
                        "${AppConfig.CHANGELOG_URL}?lang=$lang#v${latest.name}")
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * dp).toInt() })

            body.addView(button(getString(R.string.updater_update), true) {
                UpdateChecker.openPlay(this)
                finish()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (24 * dp).toInt() })
            body.addView(text(getString(R.string.updater_github), 13.5f, cSecondary, false).apply {
                val p = (10 * dp).toInt(); setPadding(p, p, p, p)
                background = ContextCompat.getDrawable(this@UpdateActivity, R.drawable.bg_menu_ripple)
                isClickable = true
                setOnClickListener {
                    pl.sinusoide.sinuschat.util.SafeEngine.openLink(this@UpdateActivity,
                        latest.page ?: UpdateChecker.RELEASES_URL)
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt() })
            if (!forReport) body.addView(button(getString(R.string.update_later), false) {
                UpdateChecker.snooze(this, latest.code)
                finish()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })
        } else {
            body.addView(button(getString(if (latest == null) R.string.updater_retry else R.string.updater_ok), latest != null) {
                if (latest == null) {
                    showLoading()
                    AppExecutor.io {
                        val again = UpdateChecker.fetchLatest(this)
                        AppExecutor.main { if (!isFinishing && !isDestroyed) render(again, animate = true) }
                    }
                } else finish()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (28 * dp).toInt() })
        }

        if (animate) {
            for (i in 0 until body.childCount) {
                val c = body.getChildAt(i)
                c.alpha = 0f; c.translationY = 18 * dp
                c.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 45L).setDuration(320)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            iconTile.scaleX = 0.6f; iconTile.scaleY = 0.6f
            iconTile.animate().scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(420)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
            if (outdated) iconTile.postDelayed({ startSpin() }, 260)
        }
    }

    private fun setSpinningIcon() {
        val rot = RotateDrawable().apply {
            drawable = ContextCompat.getDrawable(this@UpdateActivity, R.drawable.ic_update)?.mutate()
            fromDegrees = 0f
            toDegrees = 360f
            isPivotXRelative = true; pivotX = 0.5f
            isPivotYRelative = true; pivotY = 0.5f
            level = 0
        }
        icon.setImageDrawable(rot)
        icon.setColorFilter(cAccent)
    }

    private fun startSpin() {
        val rot = icon.drawable as? RotateDrawable ?: return
        spin?.cancel()
        spin = ValueAnimator.ofInt(0, 10000).apply {
            duration = 1100
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { rot.level = it.animatedValue as Int }
            start()
        }
        icon.setOnClickListener { startSpin() }
    }
}
