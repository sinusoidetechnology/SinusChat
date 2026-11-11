package pl.sinusoide.sinuschat

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.DiagnosticsSummary
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.ThemeManager
import java.text.DateFormat
import java.util.Date

class ReportProblemActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }

    private val dp get() = resources.displayMetrics.density

    private var cBg = 0; private var cSurface = 0; private var cCard = 0
    private var cPrimary = 0; private var cSecondary = 0
    private var cAccent = 0; private var cGreen = 0; private var cRed = 0; private var cOrange = 0; private var cPurple = 0

    private val maxDesc = 4000
    private val minDesc = 10

    private var topic = "bug"
    private val topicTiles = mutableMapOf<String, LinearLayout>()
    private lateinit var descInput: EditText
    private lateinit var counter: TextView
    private lateinit var emailInput: EditText
    private lateinit var diagSwitch: MaterialSwitch
    private lateinit var sendButton: LinearLayout
    private lateinit var sendLabel: TextView
    private lateinit var sendProgress: ProgressBar
    private lateinit var mineContainer: LinearLayout
    private var sending = false
    private var outdatedLatest: pl.sinusoide.sinuschat.util.UpdateChecker.Latest? = null

    companion object {
        const val EXTRA_FROM_NOTIFICATION = "report_from_notification"
        const val EXTRA_DESKTOP = "report_desktop"
        private const val PLATFORM_ANDROID = "SinusChatA"
        private const val PLATFORM_DESKTOP = "SinusChat-Desktop"
        private const val APP_NAME_ANDROID = "SinusChat"
        private const val APP_NAME_DESKTOP = "SinusChat-Desktop"
        private const val DIAG_WAIT_MS = 20_000L
    }

    private var desktop = false

    private fun askToUpdate(latest: pl.sinusoide.sinuschat.util.UpdateChecker.Latest) {
        UpdateActivity.open(this, latest, forReport = true)
    }

    private fun attr(id: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(this, id) ?: fallback
    }

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
        cRed = ContextCompat.getColor(this, R.color.sinuschat_error)
        cOrange = 0xFFE67E22.toInt()
        cPurple = 0xFF9B6BE0.toInt()
        desktop = intent.getBooleanExtra(EXTRA_DESKTOP, false)

        if (desktop) {
            buildUi()
            selectTopic("bug")
            refreshSendState()
            return
        }

        val updates = pl.sinusoide.sinuschat.util.UpdateChecker
        val fromNotification = intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)
        val cached = updates.cachedLatest(this)?.takeIf { updates.isOutdated(this, it) }
        if (cached != null && !fromNotification) {
            askToUpdate(cached)
            finish()
            return
        }
        outdatedLatest = cached

        buildUi()
        selectTopic("bug")
        refreshSendState()
        loadMine()

        pl.sinusoide.sinuschat.util.AppExecutor.io {
            val latest = updates.fetchLatest(applicationContext) ?: return@io
            pl.sinusoide.sinuschat.util.AppExecutor.main {
                if (isFinishing || isDestroyed) return@main
                if (!updates.isOutdated(this, latest)) { outdatedLatest = null; return@main }
                outdatedLatest = latest
                if (!fromNotification && !sending) {
                    askToUpdate(latest)
                    finish()
                }
            }
        }
    }

    private val reportUpdatedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (!isFinishing && !isDestroyed && !desktop) loadMine()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter(pl.sinusoide.sinuschat.util.ReportNotifier.ACTION_REPORT_UPDATED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(reportUpdatedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(reportUpdatedReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(reportUpdatedReceiver) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (!desktop) loadMine()
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
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
            background = ContextCompat.getDrawable(this@ReportProblemActivity, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        header.addView(TextView(this).apply {
            text = getString(if (desktop) R.string.report_title_desktop else R.string.report_title); textSize = 20f
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
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (28 * dp).toInt())
        }
        val scroll = ScrollView(this).apply { addView(content) }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bottom = maxOf(insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            v.setPadding(0, 0, 0, bottom); insets
        }

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(cCard, 20f)
            setPadding((20 * dp).toInt(), (22 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            addView(ImageView(this@ReportProblemActivity).apply {
                setImageResource(R.drawable.ic_report); setColorFilter(cAccent)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(tint(cAccent, 0x26))
                }
                val p = (16 * dp).toInt(); setPadding(p, p, p, p)
            }, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply {
                bottomMargin = (12 * dp).toInt()
            })
            addView(TextView(this@ReportProblemActivity).apply {
                text = getString(if (desktop) R.string.report_hero_title_desktop else R.string.report_hero_title); textSize = 17f
                setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            })
            addView(TextView(this@ReportProblemActivity).apply {
                text = getString(R.string.report_hero_desc); textSize = 13f
                setTextColor(cSecondary); gravity = Gravity.CENTER
                setLineSpacing(0f, 1.15f)
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })
        })

        content.addView(sectionTitle(getString(R.string.report_section_topic)))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false; clipToPadding = false
            listOf(
                Triple("bug", R.drawable.ic_bug, R.string.report_topic_bug_short) to cRed,
                Triple("feature", R.drawable.ic_lightbulb, R.string.report_topic_feature_short) to cOrange,
                Triple("other", R.drawable.ic_chat, R.string.report_topic_other_short) to cPurple
            ).forEachIndexed { i, (t, color) ->
                val tile = topicTile(t.first, t.second, getString(t.third), color)
                topicTiles[t.first] = tile
                addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (i > 0) marginStart = (10 * dp).toInt()
                })
            }
        })

        content.addView(sectionTitle(getString(R.string.report_section_desc)))
        content.addView(card().apply {
            descInput = EditText(this@ReportProblemActivity).apply {
                background = null
                minLines = 5; gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                filters = arrayOf(InputFilter.LengthFilter(maxDesc))
                textSize = 15f
                setTextColor(cPrimary); setHintTextColor(tint(cSecondary, 0xB0))
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { refreshSendState() }
                })
            }
            addView(descInput)
            counter = TextView(this@ReportProblemActivity).apply {
                textSize = 12f; setTextColor(cSecondary); gravity = Gravity.END
                setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), (10 * dp).toInt())
            }
            addView(counter)
        })

        content.addView(sectionTitle(getString(R.string.report_section_contact)))
        content.addView(card().apply {
            addView(LinearLayout(this@ReportProblemActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                addView(tile(R.drawable.ic_mail, cAccent))
                emailInput = EditText(this@ReportProblemActivity).apply {
                    background = null
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    hint = getString(R.string.report_email_hint)
                    textSize = 15f; maxLines = 1
                    filters = arrayOf(InputFilter.LengthFilter(254))
                    setTextColor(cPrimary); setHintTextColor(tint(cSecondary, 0xB0))
                }
                addView(emailInput, LinearLayout.LayoutParams(0, -2, 1f))
            })
            val myEmail = SetupActivity.getUserEmail(this@ReportProblemActivity)
                .takeUnless { pl.sinusoide.sinuschat.setup.AnonAccount.isAnonId(it) }
            if (!myEmail.isNullOrBlank()) {
                addView(TextView(this@ReportProblemActivity).apply {
                    text = getString(R.string.report_use_my_email)
                    textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cAccent)
                    background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(tint(cAccent, 0x24)) }
                    setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                    isClickable = true
                    setOnClickListener { emailInput.setText(myEmail); emailInput.setSelection(emailInput.text.length) }
                }, LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = (68 * dp).toInt(); bottomMargin = (4 * dp).toInt()
                })
            }
            addView(TextView(this@ReportProblemActivity).apply {
                text = getString(R.string.report_email_desc)
                textSize = 12f; setTextColor(cSecondary); setLineSpacing(0f, 1.1f)
                setPadding((68 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            })
        })

        content.addView(card().apply {
            addView(diagRow())
            addView(divider())
            addView(TextView(this@ReportProblemActivity).apply {
                text = getString(R.string.report_diag_preview)
                textSize = 14f; setTextColor(cAccent); typeface = Typeface.DEFAULT_BOLD
                setPadding((68 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                background = ContextCompat.getDrawable(this@ReportProblemActivity, R.drawable.bg_menu_ripple)
                isClickable = true
                setOnClickListener { showDiagnosticsPreview() }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })

        sendLabel = TextView(this).apply {
            text = getString(R.string.report_send)
            textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        sendProgress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        sendButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(cAccent, 26f)
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            isClickable = true
            addView(sendProgress, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).apply {
                marginEnd = (10 * dp).toInt()
            })
            addView(sendLabel)
            setOnClickListener { submit() }
        }
        content.addView(sendButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * dp).toInt() })

        mineContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!desktop) {
            content.addView(sectionTitle(getString(R.string.report_section_mine)))
            content.addView(mineContainer)
            content.addView(TextView(this).apply {
                text = getString(R.string.report_retention_note)
                textSize = 12.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
                setPadding((12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt(), 0)
            })
        }

        setContentView(outer)
    }

    private fun topicTile(key: String, icon: Int, label: String, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt())
        isClickable = true
        tag = color
        clipChildren = false; clipToPadding = false
        setOnClickListener { selectTopic(key, animate = true) }
        addView(FrameLayout(this@ReportProblemActivity).apply {
            clipChildren = false; clipToPadding = false
            addView(ImageView(this@ReportProblemActivity).apply {
                tag = "topicIcon"
                setImageResource(icon)
                val p = (10 * dp).toInt(); setPadding(p, p, p, p)
            }, FrameLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt(), Gravity.CENTER))
        }, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply {
            bottomMargin = (2 * dp).toInt()
        })
        addView(TextView(this@ReportProblemActivity).apply {
            tag = "topicLabel"
            text = label; textSize = 14f; gravity = Gravity.CENTER
        })
    }

    private fun selectTopic(key: String, animate: Boolean = false) {
        if (animate && key == topic) { animateTopicIcon(key); return }
        topic = key
        for ((k, tile) in topicTiles) {
            val color = tile.tag as Int
            val on = k == key
            val iconView = tile.findViewWithTag<ImageView>("topicIcon")
            val labelView = tile.findViewWithTag<TextView>("topicLabel")
            if (on) {
                tile.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(shade(color, 0.72f), shade(color, 1.12f))).apply {
                    cornerRadius = 18 * dp
                    setStroke((2.5f * dp).toInt(), Color.WHITE)
                }
                tile.elevation = 6 * dp
                iconView.setColorFilter(Color.WHITE)
                iconView.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
                labelView.setTextColor(Color.WHITE)
                labelView.typeface = Typeface.DEFAULT_BOLD
            } else {
                tile.background = GradientDrawable().apply {
                    cornerRadius = 18 * dp
                    setColor(cCard)
                    setStroke((1 * dp).toInt(), pl.sinusoide.sinuschat.ui.ThemeUi.divider(this@ReportProblemActivity))
                }
                tile.elevation = 0f
                iconView.setColorFilter(color)
                iconView.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tint(color, 0x26)) }
                labelView.setTextColor(cPrimary)
                labelView.typeface = Typeface.DEFAULT
            }
        }
        if (animate) {
            topicTiles[key]?.let { tile ->
                tile.animate().cancel()
                tile.scaleX = 0.92f; tile.scaleY = 0.92f
                tile.animate().scaleX(1f).scaleY(1f).setDuration(320)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
            }
            animateTopicIcon(key)
        }
        descInput.hint = getString(when (key) {
            "bug" -> R.string.report_hint_bug
            "feature" -> R.string.report_hint_feature
            else -> R.string.report_hint_other
        })
    }

    private fun animateTopicIcon(key: String) {
        val icon = topicTiles[key]?.findViewWithTag<ImageView>("topicIcon") ?: return
        icon.animate().cancel()
        icon.rotation = 0f; icon.scaleX = 1f; icon.scaleY = 1f; icon.translationY = 0f
        when (key) {
            "bug" -> android.animation.ObjectAnimator.ofFloat(icon, "rotation",
                0f, -18f, 16f, -12f, 8f, -4f, 0f).apply {
                duration = 560
                interpolator = android.view.animation.DecelerateInterpolator()
                start()
            }
            "feature" -> {
                val sx = android.animation.ObjectAnimator.ofFloat(icon, "scaleX", 1f, 1.22f, 0.94f, 1.06f, 1f)
                val sy = android.animation.ObjectAnimator.ofFloat(icon, "scaleY", 1f, 1.22f, 0.94f, 1.06f, 1f)
                android.animation.AnimatorSet().apply {
                    playTogether(sx, sy); duration = 600; start()
                }
            }
            else -> icon.animate().translationY(-12f * dp).setDuration(170)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    icon.animate().translationY(0f).setDuration(320)
                        .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                }.start()
        }
    }

    private fun shade(color: Int, factor: Float): Int {
        fun ch(shift: Int) = (((color shr shift) and 0xFF) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    private fun diagRow(): View {
        diagSwitch = MaterialSwitch(this).apply {
            isChecked = true
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, 0xFF8AA0B8.toInt()))
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(cAccent, 0xFF2A3A4A.toInt()))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt())
            background = ContextCompat.getDrawable(this@ReportProblemActivity, R.drawable.bg_menu_ripple)
            isClickable = true
            setOnClickListener { diagSwitch.isChecked = !diagSwitch.isChecked }
            addView(tile(R.drawable.ic_question_exchange, cGreen))
            addView(LinearLayout(this@ReportProblemActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@ReportProblemActivity).apply {
                    text = getString(R.string.report_diag_title); textSize = 15.5f; setTextColor(cPrimary)
                })
                addView(TextView(this@ReportProblemActivity).apply {
                    text = getString(if (desktop) R.string.report_diag_desc_desktop else R.string.report_diag_desc)
                    textSize = 12.5f; setTextColor(cSecondary)
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * dp).toInt() })
            addView(diagSwitch)
        }
    }

    private fun refreshSendState() {
        val len = descInput.text.trim().length
        counter.text = getString(R.string.report_counter, descInput.text.length, maxDesc)
        counter.setTextColor(if (descInput.text.length >= maxDesc) cRed else cSecondary)
        val enabled = len >= minDesc && !sending
        sendButton.alpha = if (enabled || sending) 1f else 0.5f
    }

    private fun submit() {
        if (sending) return
        outdatedLatest?.let { askToUpdate(it); return }
        val desc = descInput.text.toString().trim()
        val email = emailInput.text.toString().trim()
        if (desc.length < minDesc) {
            PopupHelper.showError(this, getString(R.string.report_desc_too_short)); return
        }
        if (email.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            PopupHelper.showError(this, getString(R.string.report_email_invalid)); return
        }

        setSending(true)
        val withDiag = diagSwitch.isChecked
        val sendingText = getString(R.string.report_sending_app, if (desktop) APP_NAME_DESKTOP else APP_NAME_ANDROID)
        val (waiting, waitingLabel) = showDiagWaiting(if (desktop && withDiag) getString(R.string.report_diag_waiting) else sendingText)
        AppExecutor.io {
            val pc = if (desktop) {
                if (withDiag) pl.sinusoide.sinuschat.pclink.PcLinkHub.requestDesktopDiag(DIAG_WAIT_MS)
                else pl.sinusoide.sinuschat.pclink.PcLinkHub.cachedDesktopDiag()
                    ?: pl.sinusoide.sinuschat.pclink.PcLinkHub.requestDesktopDiag(5_000L)
            } else null
            if (desktop && withDiag && pc == null) {
                AppExecutor.main {
                    waiting.dismiss()
                    if (isFinishing || isDestroyed) return@main
                    setSending(false)
                    PopupHelper.showError(this, getString(R.string.report_diag_pc_failed))
                }
                return@io
            }
            val body = JSONObject().apply {
                put("topic", topic)
                put("description", desc)
                if (email.isNotEmpty()) put("email", email)
                if (desktop) {
                    put("platform", PLATFORM_DESKTOP)
                    put("appVersion", pc?.appVersion?.ifBlank { null } ?: "?")
                    put("device", listOfNotNull(pc?.device?.ifBlank { null }, pc?.os?.ifBlank { null }).joinToString(" · ").ifEmpty { "?" })
                    if (withDiag && pc != null) put("diagnostics", pc.text)
                } else {
                    put("platform", PLATFORM_ANDROID)
                    put("appVersion", try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" })
                    put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    put("android", android.os.Build.VERSION.RELEASE)
                    if (withDiag) put("diagnostics", DiagnosticsSummary.build(this@ReportProblemActivity))
                }
            }
            AppExecutor.main { waitingLabel.text = sendingText }
            val (code, json) = postWithCode("/api/report", body)
            AppExecutor.main {
                waiting.dismiss()
                if (isFinishing || isDestroyed) return@main
                setSending(false)
                when {
                    code in 200..299 && json?.optBoolean("ok") == true -> {
                        descInput.setText("")
                        showSentSheet(json.optInt("id"))
                        if (!desktop) loadMine()
                    }
                    code == 429 -> PopupHelper.showError(this, getString(R.string.report_too_many))
                    code == 400 && json?.optString("code") == "invalid_email" ->
                        PopupHelper.showError(this, getString(R.string.report_email_invalid))
                    code == 400 && json?.optString("code") == "description_too_short" ->
                        PopupHelper.showError(this, getString(R.string.report_desc_too_short))
                    else -> PopupHelper.showError(this, getString(R.string.report_failed))
                }
            }
        }
    }

    private fun setSending(on: Boolean) {
        sending = on
        sendProgress.visibility = if (on) View.VISIBLE else View.GONE
        sendLabel.text = getString(if (on) R.string.report_sending else R.string.report_send)
        refreshSendState()
    }

    private fun postWithCode(path: String, body: JSONObject): Pair<Int, JSONObject?> = try {
        val req = okhttp3.Request.Builder()
            .url(ApiClient.BASE_URL + path)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer ${ApiClient.getToken(this) ?: ""}")
            .build()
        ApiClient.httpClient(this).newCall(req).execute().use { resp ->
            val str = resp.body?.string()
            resp.code to str?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
    } catch (_: Exception) { -1 to null }

    private fun loadMine() {
        mineContainer.removeAllViews()
        mineContainer.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(cAccent)
        }, LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = (12 * dp).toInt()
        })
        AppExecutor.io {
            val json = ApiClient.getObject("/api/reports/mine", this)
            AppExecutor.main {
                if (isFinishing || isDestroyed) return@main
                mineContainer.removeAllViews()
                val arr = json?.optJSONArray("reports")
                when {
                    json == null -> mineContainer.addView(emptyText(getString(R.string.report_mine_error)))
                    arr == null || arr.length() == 0 -> mineContainer.addView(emptyText(getString(R.string.report_mine_empty)))
                    else -> mineContainer.addView(card().apply {
                        for (i in 0 until arr.length()) {
                            val r = arr.optJSONObject(i) ?: continue
                            if (i > 0) addView(divider())
                            addView(reportRow(r))
                        }
                    })
                }
            }
        }
    }

    private fun reportRow(r: JSONObject): View {
        val t = r.optString("topic")
        val (icon, color) = when (t) {
            "bug" -> R.drawable.ic_bug to cRed
            "feature" -> R.drawable.ic_lightbulb to cOrange
            else -> R.drawable.ic_chat to cPurple
        }
        val status = r.optString("status")
        val decided = status == "confirmed" || status == "rejected"
        val (statusRes, statusColor) = when (status) {
            "confirmed" -> R.string.report_status_confirmed to cGreen
            "rejected" -> R.string.report_status_rejected to cRed
            else -> R.string.report_status_pending to cOrange
        }
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(r.optLong("createdAt")))

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((14 * dp).toInt(), (13 * dp).toInt(), (14 * dp).toInt(), (13 * dp).toInt())
            addView(tile(icon, color))
            addView(LinearLayout(this@ReportProblemActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@ReportProblemActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    val pcLabel = if (r.optString("platform") == PLATFORM_DESKTOP) " · " + getString(R.string.report_platform_desktop) else ""
                    addView(TextView(this@ReportProblemActivity).apply {
                        text = "#${r.optInt("id")} · $date$pcLabel"; textSize = 12.5f; setTextColor(cSecondary)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(this@ReportProblemActivity).apply {
                        text = getString(statusRes); textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
                        setTextColor(statusColor)
                        background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(tint(statusColor, 0x24)) }
                        setPadding((10 * dp).toInt(), (3 * dp).toInt(), (10 * dp).toInt(), (3 * dp).toInt())
                    })
                })
                addView(TextView(this@ReportProblemActivity).apply {
                    text = r.optString("description"); textSize = 14.5f; setTextColor(cPrimary)
                    maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                })
                val deleteAt = r.optLong("deleteAt", 0L)
                if (deleteAt > 0L) {
                    val days = kotlin.math.ceil((deleteAt - System.currentTimeMillis()) / 86_400_000.0).toInt()
                    addView(TextView(this@ReportProblemActivity).apply {
                        text = if (days <= 1) getString(R.string.report_deletes_today)
                               else resources.getQuantityString(R.plurals.report_deletes_in, days, days)
                        textSize = 11.5f; setTextColor(tint(cSecondary, 0xCC))
                        setPadding(0, (4 * dp).toInt(), 0, 0)
                    })
                }
                val replyText = r.optString("reply").takeIf { !r.isNull("reply") && it.isNotBlank() }
                if (replyText != null) {
                    addView(LinearLayout(this@ReportProblemActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(tint(cAccent, 0x1C)) }
                        setPadding((12 * dp).toInt(), (9 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                        addView(TextView(this@ReportProblemActivity).apply {
                            text = getString(R.string.report_reply_label); textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD; setTextColor(cAccent)
                        })
                        addView(TextView(this@ReportProblemActivity).apply {
                            text = replyText; textSize = 14f; setTextColor(cPrimary)
                            setTextIsSelectable(true)
                            setPadding(0, (3 * dp).toInt(), 0, 0)
                        })
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() })
                } else if (decided && r.optBoolean("hasEmail", false)) {
                    addView(LinearLayout(this@ReportProblemActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(tint(statusColor, 0x1A)) }
                        setPadding((10 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                        addView(ImageView(this@ReportProblemActivity).apply {
                            setImageResource(R.drawable.ic_mail); setColorFilter(statusColor)
                        }, LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply {
                            marginEnd = (8 * dp).toInt()
                        })
                        addView(TextView(this@ReportProblemActivity).apply {
                            text = getString(R.string.report_decision_email); textSize = 13f; setTextColor(cPrimary)
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() })
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun showDiagWaiting(label: String = getString(R.string.report_diag_waiting)): Pair<BottomSheetDialog, TextView> {
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        sheet.setCancelable(false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (28 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())
        }
        val ring = pl.sinusoide.sinuschat.ui.SineProgressRing(this).apply {
            color = cAccent; disc = 0; showCancel = false; isClickable = false
            progress = { null }
        }
        root.addView(ring, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply { bottomMargin = (18 * dp).toInt() })
        val labelView = TextView(this).apply {
            text = label; textSize = 16f
            setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        root.addView(labelView)
        sheet.setContentView(root)
        val tick = object : Runnable {
            override fun run() {
                ring.invalidate()
                if (sheet.isShowing) ring.postOnAnimation(this)
            }
        }
        sheet.setOnShowListener { ring.postOnAnimation(tick) }
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
        return sheet to labelView
    }

    private fun showDiagnosticsPreview() {
        if (desktop) {
            val cached = pl.sinusoide.sinuschat.pclink.PcLinkHub.cachedDesktopDiag()
            if (cached != null) { showDiagnosticsText(cached.text); return }
            val waiting = showDiagWaiting().first
            AppExecutor.io {
                val pc = pl.sinusoide.sinuschat.pclink.PcLinkHub.requestDesktopDiag(DIAG_WAIT_MS)
                AppExecutor.main {
                    waiting.dismiss()
                    if (isFinishing || isDestroyed) return@main
                    if (pc == null) PopupHelper.showError(this, getString(R.string.report_diag_pc_failed))
                    else showDiagnosticsText(pc.text)
                }
            }
            return
        }
        showDiagnosticsText(DiagnosticsSummary.build(this))
    }

    private fun showDiagnosticsText(summary: String) {
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dp).toInt(), (12 * dp).toInt(), (22 * dp).toInt(), (22 * dp).toInt())
        }
        root.addView(View(this).apply { background = rounded(pl.sinusoide.sinuschat.ui.ThemeUi.handle(context), 3f) },
            LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (18 * dp).toInt()
            })
        root.addView(TextView(this).apply {
            text = getString(R.string.report_diag_preview_title); textSize = 18f
            setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = getString(if (desktop) R.string.report_diag_desc_desktop else R.string.report_diag_desc)
            textSize = 13f; setTextColor(cSecondary)
            setPadding(0, (4 * dp).toInt(), 0, (14 * dp).toInt())
        })
        root.addView(TextView(this).apply {
            text = summary; textSize = 12.5f; setTextColor(cPrimary)
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            background = rounded(pl.sinusoide.sinuschat.ui.ThemeUi.inset(this@ReportProblemActivity), 12f)
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        })
        sheet.setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun showSentSheet(id: Int) {
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
        }
        root.addView(View(this).apply { background = rounded(pl.sinusoide.sinuschat.ui.ThemeUi.handle(context), 3f) },
            LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply { bottomMargin = (20 * dp).toInt() })
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_check); setColorFilter(cGreen)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tint(cGreen, 0x26)) }
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply { bottomMargin = (16 * dp).toInt() })
        root.addView(TextView(this).apply {
            text = getString(R.string.report_sent_title); textSize = 20f
            setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.report_sent_desc, id); textSize = 14f
            setTextColor(cSecondary); gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (22 * dp).toInt())
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.report_ok); textSize = 16f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = rounded(cAccent, 26f)
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))
        sheet.setContentView(root)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun tint(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun rounded(color: Int, radiusDp: Float) = pl.sinusoide.sinuschat.ui.ThemeUi.rounded(this, color, radiusDp)

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 18f)
        clipToOutline = true
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(pl.sinusoide.sinuschat.ui.ThemeUi.divider(context))
        layoutParams = LinearLayout.LayoutParams(-1, (1 * dp).toInt().coerceAtLeast(1)).apply {
            marginStart = (68 * dp).toInt()
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f; setTextColor(cAccent); typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        setPadding((6 * dp).toInt(), (22 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun tile(icon: Int, color: Int) = ImageView(this).apply {
        setImageResource(icon); setColorFilter(color)
        background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(tint(color, 0x24)) }
        val p = (9 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
            marginEnd = (14 * dp).toInt()
        }
    }

    private fun emptyText(t: String) = TextView(this).apply {
        text = t; textSize = 13.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
        background = rounded(cCard, 18f)
        setPadding((16 * dp).toInt(), (22 * dp).toInt(), (16 * dp).toInt(), (22 * dp).toInt())
    }
}
