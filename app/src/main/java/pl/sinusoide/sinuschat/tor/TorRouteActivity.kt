package pl.sinusoide.sinuschat.tor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.PrivacySettings
import pl.sinusoide.sinuschat.util.ThemeManager
import java.util.Locale

class TorRouteActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    companion object {
        fun countryName(ctx: Context, cc: String?): String {
            if (cc.isNullOrEmpty()) return ctx.getString(R.string.tor_route_unknown_country)
            val name = Locale("", cc).getDisplayCountry(Locale.forLanguageTag(LocaleManager.getEffective(ctx)))
            return name.ifBlank { cc }
        }

        fun summary(ctx: Context, route: TorCircuits.Route?): String? =
            route?.hops?.takeIf { it.isNotEmpty() }?.joinToString(" → ") { countryName(ctx, it.country) }
    }

    private val dp get() = resources.displayMetrics.density
    private var cBg = 0; private var cSurface = 0; private var cCard = 0
    private var cPrimary = 0; private var cSecondary = 0; private var cAccent = 0
    private var cGreen = 0; private var cOrange = 0; private var cPurple = 0

    private lateinit var routeCard: LinearLayout
    private lateinit var warning: TextView
    private lateinit var newButton: View
    private val main = Handler(Looper.getMainLooper())
    private val refreshSoon = Runnable { refresh() }
    private val changed: () -> Unit = {
        main.removeCallbacks(refreshSoon)
        main.postDelayed(refreshSoon, 600L)
    }

    private fun attr(id: Int, fallback: Int): Int = pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(this, id) ?: fallback

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        cBg = attr(R.attr.sinuschat_background, 0xFF0E1621.toInt())
        cSurface = attr(R.attr.sinuschat_surface, 0xFF17212B.toInt())
        cCard = attr(R.attr.sinuschat_surface_elevated, 0xFF1D2A38.toInt())
        cPrimary = attr(R.attr.sinuschat_text_primary, Color.WHITE)
        cSecondary = attr(R.attr.sinuschat_text_secondary, 0xFF7F91A4.toInt())
        cAccent = pl.sinusoide.sinuschat.ui.ThemeUi.accent(this)
        cGreen = ContextCompat.getColor(this, R.color.sinuschat_success)
        cOrange = 0xFFE67E22.toInt()
        cPurple = 0xFF9B6BE0.toInt()
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        TorCircuits.addListener(changed)
        refresh()
    }

    override fun onStop() {
        TorCircuits.removeListener(changed)
        main.removeCallbacks(refreshSoon)
        super.onStop()
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * dp).toInt()
            setBackgroundColor(cSurface)
            elevation = 2 * dp
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(cPrimary)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p)
            background = ContextCompat.getDrawable(this@TorRouteActivity, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        header.addView(TextView(this).apply {
            text = getString(R.string.tor_route_title); textSize = 20f
            setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * dp).toInt(), 0, 0, 0)
        })
        outer.addView(header)
        ViewCompat.setOnApplyWindowInsetsListener(header) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding((12 * dp).toInt(), bars.top, (12 * dp).toInt(), 0)
            insets
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (28 * dp).toInt())
        }
        val scroll = ScrollView(this).apply { addView(content) }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom); insets
        }

        content.addView(sectionTitle(getString(R.string.tor_route_section)))
        routeCard = card().apply { setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt()) }
        content.addView(routeCard)

        warning = TextView(this).apply {
            textSize = 13f; setTextColor(cOrange)
            setLineSpacing(0f, 1.15f)
            setPadding((6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt(), 0)
            visibility = View.GONE
        }
        content.addView(warning)

        newButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 24 * dp; setColor(cAccent) }
            setPadding(0, (13 * dp).toInt(), 0, (13 * dp).toInt())
            isClickable = true
            addView(ImageView(this@TorRouteActivity).apply {
                setImageResource(R.drawable.ic_refresh)
                setColorFilter(pl.sinusoide.sinuschat.ui.ThemeUi.onAccent(this@TorRouteActivity))
            }, LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
            addView(TextView(this@TorRouteActivity).apply {
                text = getString(R.string.tor_route_new)
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.onAccent(this@TorRouteActivity))
            })
            setOnClickListener { requestNewRoute() }
        }
        content.addView(newButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() })

        content.addView(sectionTitle(getString(R.string.tor_route_security)))
        content.addView(card().apply { addView(distinctRow()) })

        content.addView(TextView(this).apply {
            text = getString(R.string.tor_route_footer)
            textSize = 12.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding((12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt(), 0)
        })

        setContentView(outer)
    }

    private var building = false
    private var shownRouteId: String? = null
    private var buildingText: TextView? = null

    private fun refresh() {
        if (isFinishing || isDestroyed || building) return
        if (!PrivacySettings.isTorEnabled(this)) {
            showMessage(getString(R.string.tor_route_off))
            newButton.visibility = View.GONE
            return
        }
        if (TorManager.viaOrbot) {
            showMessage(getString(R.string.tor_route_orbot))
            newButton.visibility = View.GONE
            warning.visibility = View.GONE
            return
        }
        newButton.visibility = View.VISIBLE
        if (routeCard.childCount == 0) showBuilding(getString(R.string.tor_route_loading))
        TorCircuits.route { r ->
            if (isFinishing || isDestroyed || building) return@route
            if (r == null) {
                val p = TorCircuits.lastProgress
                val text = if (p != null && p < 100) getString(R.string.tor_startup_progress, p)
                    else getString(R.string.tor_route_none)
                if (buildingText != null && routeCard.childCount > 0) buildingText?.text = text
                else showBuilding(text)
                warning.visibility = View.GONE
            } else if (r.id != shownRouteId || buildingText != null) {
                render(r)
            }
        }
    }

    private fun showBuilding(text: String) {
        routeCard.removeAllViews()
        warning.visibility = View.GONE
        shownRouteId = null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (18 * dp).toInt(), 0, (18 * dp).toInt())
        }
        box.addView(android.widget.FrameLayout(this).apply {
            addView(pl.sinusoide.sinuschat.ui.SineProgressRing(this@TorRouteActivity).apply {
                color = cAccent
                disc = 0
                showCancel = false
                isClickable = false
                progress = { null }
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(ImageView(this@TorRouteActivity).apply {
                setImageResource(R.drawable.ic_shield); setColorFilter(cAccent)
            }, android.widget.FrameLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt(), Gravity.CENTER))
        }, LinearLayout.LayoutParams((84 * dp).toInt(), (84 * dp).toInt()))
        buildingText = TextView(this).apply {
            this.text = text; textSize = 14f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding((8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt(), 0)
        }
        box.addView(buildingText)
        routeCard.addView(box, LinearLayout.LayoutParams(-1, -2))
        box.alpha = 0f
        box.animate().alpha(1f).setDuration(200L).start()
    }

    private fun showMessage(text: String) {
        buildingText = null
        shownRouteId = null
        routeCard.removeAllViews()
        routeCard.addView(TextView(this).apply {
            this.text = text; textSize = 14f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding((8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt())
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun render(r: TorCircuits.Route) {
        buildingText = null
        shownRouteId = r.id
        routeCard.removeAllViews()
        routeCard.alpha = 0f
        routeCard.animate().alpha(1f).setDuration(250L).start()
        val roles = listOf(R.string.tor_route_guard, R.string.tor_route_middle, R.string.tor_route_exit)
        val colors = listOf(cGreen, cAccent, cOrange)
        val icons = listOf(R.drawable.ic_shield, R.drawable.ic_internet, R.drawable.ic_location)
        val dup = r.duplicateCountry
        stop(R.drawable.ic_new_phone, cSecondary, getString(R.string.tor_route_you), null, null, false)
        r.hops.forEachIndexed { i, h ->
            val k = when {
                i == 0 -> 0
                i == r.hops.size - 1 -> 2
                else -> 1
            }
            connector()
            stop(icons[k], colors[k], countryName(this, h.country), getString(roles[k]),
                listOfNotNull(h.country, h.nickname.takeIf { it.isNotBlank() }).joinToString(" · "),
                h.country != null && h.country == dup)
        }
        connector()
        stop(R.drawable.ic_server, cPurple, getString(R.string.tor_route_server), null, null, false)
        if (dup != null && !TorCircuits.distinct(this)) {
            warning.text = getString(R.string.tor_route_same, countryName(this, dup))
            warning.visibility = View.VISIBLE
        } else warning.visibility = View.GONE
    }

    private fun connector() {
        routeCard.addView(View(this).apply {
            setBackgroundColor((cSecondary and 0x00FFFFFF) or 0x55000000)
        }, LinearLayout.LayoutParams((2 * dp).toInt(), (14 * dp).toInt()).apply {
            marginStart = (19 * dp).toInt()
            topMargin = (2 * dp).toInt(); bottomMargin = (2 * dp).toInt()
        })
    }

    private fun stop(icon: Int, color: Int, title: String, role: String?, detail: String?, flagged: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon); setColorFilter(color)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((color and 0x00FFFFFF) or 0x26000000) }
            val p = (9 * dp).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            role?.let {
                addView(TextView(this@TorRouteActivity).apply {
                    text = it.uppercase(); textSize = 10.5f; letterSpacing = 0.06f
                    typeface = Typeface.DEFAULT_BOLD; setTextColor(color)
                })
            }
            addView(TextView(this@TorRouteActivity).apply {
                text = title; textSize = 15.5f
                setTextColor(if (flagged) cOrange else cPrimary)
                if (role != null) typeface = Typeface.DEFAULT_BOLD
            })
            detail?.takeIf { it.isNotBlank() }?.let {
                addView(TextView(this@TorRouteActivity).apply {
                    text = it; textSize = 12f; setTextColor(cSecondary)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        routeCard.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun requestNewRoute() {
        if (!PrivacySettings.isTorEnabled(this) || building) return
        val old = shownRouteId
        building = true
        newButton.isEnabled = false
        newButton.alpha = 0.5f
        showBuilding(getString(R.string.tor_route_new_done))
        TorCircuits.newRoute { ok ->
            if (isFinishing || isDestroyed) return@newRoute
            if (!ok) {
                PopupHelper.showError(this, getString(R.string.tor_route_none))
                finishBuilding()
                return@newRoute
            }
            waitForNewRoute(old, android.os.SystemClock.elapsedRealtime() + 20_000L)
        }
    }

    private fun waitForNewRoute(old: String?, deadline: Long) {
        main.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            TorCircuits.route { r ->
                if (isFinishing || isDestroyed) return@route
                when {
                    r != null && r.id != old -> { finishBuilding(); render(r) }
                    android.os.SystemClock.elapsedRealtime() > deadline -> finishBuilding()
                    else -> waitForNewRoute(old, deadline)
                }
            }
        }, 1000L)
    }

    private fun finishBuilding() {
        building = false
        newButton.isEnabled = true
        newButton.alpha = 1f
        refresh()
    }

    private fun distinctRow(): View {
        val sw = MaterialSwitch(this).apply {
            isChecked = TorCircuits.distinct(this@TorRouteActivity)
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, 0xFF8AA0B8.toInt()))
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(cAccent, 0xFF2A3A4A.toInt()))
            setOnCheckedChangeListener { _, v ->
                TorCircuits.setDistinct(this@TorRouteActivity, v)
                changed()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt())
            background = ContextCompat.getDrawable(this@TorRouteActivity, R.drawable.bg_menu_ripple)
            isClickable = true
            setOnClickListener { sw.toggle() }
            addView(ImageView(this@TorRouteActivity).apply {
                setImageResource(R.drawable.ic_shield); setColorFilter(cGreen)
                background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor((cGreen and 0x00FFFFFF) or 0x24000000) }
                val p = (9 * dp).toInt(); setPadding(p, p, p, p)
            }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
            addView(LinearLayout(this@TorRouteActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@TorRouteActivity).apply {
                    text = getString(R.string.tor_route_distinct); textSize = 15.5f; setTextColor(cPrimary)
                })
                addView(TextView(this@TorRouteActivity).apply {
                    text = getString(R.string.tor_route_distinct_desc); textSize = 12.5f; setTextColor(cSecondary)
                    setLineSpacing(0f, 1.1f)
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * dp).toInt() })
            addView(sw)
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = pl.sinusoide.sinuschat.ui.ThemeUi.rounded(this@TorRouteActivity, cCard, 18f)
        clipToOutline = true
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f; setTextColor(cAccent); typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        setPadding((6 * dp).toInt(), (22 * dp).toInt(), 0, (8 * dp).toInt())
    }
}
