package pl.sinusoide.sinuschat

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import pl.sinusoide.sinuschat.crypto.DbCrypto
import pl.sinusoide.sinuschat.crypto.DbKeyManager
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import pl.sinusoide.sinuschat.ui.AuroraBackgroundView
import pl.sinusoide.sinuschat.ui.GlassKeyView
import pl.sinusoide.sinuschat.ui.LockPalette
import pl.sinusoide.sinuschat.ui.LockRingView
import pl.sinusoide.sinuschat.ui.PinFieldView
import pl.sinusoide.sinuschat.ui.RetroUi
import pl.sinusoide.sinuschat.ui.finishWithFade
import pl.sinusoide.sinuschat.ui.startWithFade
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.PinStrength
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.RecoveryKey
import pl.sinusoide.sinuschat.util.ThemeManager

class ScreenLockActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    companion object {
        const val MODE_SET = 1
        const val MODE_UNLOCK = 2
        const val MODE_CONFIRM = 3
        const val MODE_SET_DURESS = 4
        const val MODE_CONFIRM_DURESS = 5
        const val MODE_SHUFFLE_TEST = 6
        private const val SHUFFLE_ROUNDS = 3

        const val EXTRA_MODE = "lock_mode"
        const val EXTRA_TITLE = "lock_title"
        const val EXTRA_OPEN_SETTINGS = "open_settings"
        const val EXTRA_NEXT_INTENT = "next_intent"
        const val EXTRA_RETURN_RESULT = "return_result"
        private const val EXTRA_FORCED_NEW_PIN = "forced_new_pin"
        private const val FORGOT_AFTER = 3

        fun start(context: Context, mode: Int, title: String? = null, openSettings: Boolean = false, nextIntent: Intent? = null) {
            if (isLockVisible && mode == MODE_UNLOCK) return

            val intent = Intent(context, ScreenLockActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_OPEN_SETTINGS, openSettings)
                title?.let { putExtra(EXTRA_TITLE, it) }
                nextIntent?.let { putExtra(EXTRA_NEXT_INTENT, it) }
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }

        fun cover(activity: Activity) {
            if (isLockVisible) return
            activity.startWithFade(Intent(activity, ScreenLockActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_UNLOCK)
                .putExtra(EXTRA_RETURN_RESULT, true), exit = 0)
        }

        @Volatile var isLockVisible = false

        private const val MAX_PIN = 12
        private const val MIN_PIN = 4
    }

    private val dp get() = resources.displayMetrics.density

    private var mode = MODE_UNLOCK
    private var forcedNewPin = false
    private var forgotLink: TextView? = null
    private var inLockout = false
    private var weakAccepted: String? = null
    private val lockoutTick = Runnable { updateLockout() }
    private var currentPin = ""
    private var firstPin = ""
    private var isAutoConfirm = true
    private var silent = false
    private var biometricTriggered = false
    private var busy = false
    private var shuffle = false
    private var shuffling = false
    private var shufflePassed = 0
    private val shuffleSteps = mutableListOf<View>()
    private var shuffleOffLink: View? = null

    private var dark = true
    private lateinit var palette: LockPalette
    private var cPrimary = 0; private var cSecondary = 0; private var cAccent = 0
    private val cRed = 0xFFE5484D.toInt()
    private val cOrange = 0xFFE67E22.toInt()
    private val cGreen = 0xFF3FB97A.toInt()

    private lateinit var aurora: AuroraBackgroundView
    private lateinit var ring: LockRingView
    private lateinit var pinField: PinFieldView
    private lateinit var tvTitle: TextView
    private lateinit var tvDescription: TextView
    private lateinit var tvMessage: TextView
    private lateinit var keypad: LinearLayout
    private lateinit var btnNext: TextView
    private lateinit var noticeHost: LinearLayout
    private lateinit var content: LinearLayout
    private var autoConfirmRow: View? = null
    private var retroFields: Pair<PinFieldView, PinFieldView>? = null
    private var keyShrink = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        mode = intent.getIntExtra(EXTRA_MODE, MODE_UNLOCK)
        forcedNewPin = mode == MODE_SET && intent.getBooleanExtra(EXTRA_FORCED_NEW_PIN, false)
        isAutoConfirm = LockManager.isAutoConfirmEnabled(this)
        silent = LockManager.isSilentUnlock(this) && isAutoConfirm
        shuffle = mode == MODE_SHUFFLE_TEST || LockManager.isShuffleKeypad(this)

        palette = LockPalette.of(this)
        dark = palette.dark
        cPrimary = palette.text
        cSecondary = palette.textSecondary
        cAccent = palette.accent

        buildUi()
        setupTexts()
        updateNextButton()
        maybeShowBiometricRemovedNotice()
        playEntrance()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode != MODE_UNLOCK && !forcedNewPin) finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        isLockVisible = true
        updateLockout()
    }

    override fun onPause() {
        super.onPause()
        isLockVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isLockVisible = false
        if (::content.isInitialized) content.removeCallbacks(lockoutTick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && mode == MODE_UNLOCK && !biometricTriggered && !inLockout) {
            triggerBiometric(isAuto = true)
        }
    }

    private fun buildUi() {
        if (palette.retro) { buildRetroUi(); return }
        val root = FrameLayout(this)
        aurora = AuroraBackgroundView(this, palette)
        root.addView(aurora, FrameLayout.LayoutParams(-1, -1))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding((24 * dp).toInt(), (6 * dp).toInt(), (24 * dp).toInt(), (22 * dp).toInt())
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        content.addView(FrameLayout(this).apply {
            if (mode != MODE_UNLOCK && !forcedNewPin) addView(GlassKeyView(this@ScreenLockActivity, null, null, R.drawable.ic_back,
                glass = true, palette = palette).apply {
                contentDescription = getString(R.string.back)
                setOnClickListener { finish() }
            }, FrameLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt(), Gravity.START or Gravity.CENTER_VERTICAL))
        }, LinearLayout.LayoutParams(-1, (52 * dp).toInt()))

        ring = LockRingView(this, palette).apply {
            setDanger(mode == MODE_SET_DURESS || mode == MODE_CONFIRM_DURESS)
        }
        content.addView(ring, LinearLayout.LayoutParams((104 * dp).toInt(), (104 * dp).toInt()))

        tvTitle = TextView(this).apply {
            textSize = 26f; setTextColor(cPrimary); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = -0.01f
            setPadding(0, (14 * dp).toInt(), 0, 0)
        }
        content.addView(tvTitle, LinearLayout.LayoutParams(-1, -2))
        tvDescription = TextView(this).apply {
            textSize = 14.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), 0)
        }
        content.addView(tvDescription, LinearLayout.LayoutParams(-1, -2))

        if (mode == MODE_SHUFFLE_TEST) {
            val steps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            repeat(SHUFFLE_ROUNDS) { i ->
                val step = View(this).apply {
                    background = GradientDrawable().apply { cornerRadius = 3 * dp; setColor(stepIdle()) }
                }
                shuffleSteps += step
                steps.addView(step, LinearLayout.LayoutParams((34 * dp).toInt(), (6 * dp).toInt()).apply {
                    if (i > 0) marginStart = (8 * dp).toInt()
                })
            }
            content.addView(steps, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * dp).toInt() })
        }

        noticeHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(noticeHost, LinearLayout.LayoutParams(-1, -2))

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        pinField = PinFieldView(this, palette, getString(R.string.lock_pin_placeholder),
            showDots = LockManager.isShowPinDots(this))
        content.addView(pinField, LinearLayout.LayoutParams((264 * dp).toInt(), (58 * dp).toInt()).apply {
            topMargin = (26 * dp).toInt()
        })
        tvMessage = TextView(this).apply {
            textSize = 13.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = (20 * dp).toInt()
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(tvMessage, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = (12 * dp).toInt(); bottomMargin = (18 * dp).toInt()
        })
        if (mode == MODE_SET || mode == MODE_SET_DURESS) tvMessage.text = getString(R.string.min_4_digits)

        keypad = buildKeypad()
        content.addView(keypad, LinearLayout.LayoutParams(-2, -2))

        if (mode == MODE_SET) {
            autoConfirmRow = autoConfirmSwitch()
            content.addView(autoConfirmRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (22 * dp).toInt() })
        }

        btnNext = TextView(this).apply {
            text = getString(R.string.next)
            textSize = 16f; setTextColor(palette.onAccent); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(cAccent, palette.accent2)).apply { cornerRadius = 28 * dp }
            elevation = if (palette.flow) 8 * dp else 0f
            setPadding(0, (15 * dp).toInt(), 0, (15 * dp).toInt())
            isClickable = true
            setOnClickListener {
                it.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(2f)).start()
                }.start()
                handleNext()
            }
        }
        content.addView(btnNext, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * dp).toInt() })

        if (mode == MODE_SHUFFLE_TEST) {
            shuffleOffLink = TextView(this).apply {
                text = getString(R.string.lock_shuffle_test_off)
                textSize = 14.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, (12 * dp).toInt(), 0, (6 * dp).toInt())
                isClickable = true
                setOnClickListener {
                    if (busy) return@setOnClickListener
                    LockManager.setShuffleKeypad(this@ScreenLockActivity, false)
                    finish()
                }
            }
            content.addView(shuffleOffLink, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        }

        if (mode == MODE_UNLOCK) {
            forgotLink = TextView(this).apply {
                text = getString(R.string.lock_forgot)
                textSize = 14.5f; setTextColor(cAccent); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, (12 * dp).toInt(), 0, (6 * dp).toInt())
                visibility = View.GONE
                isClickable = true
                setOnClickListener { if (!busy) askRecoveryKey() }
            }
            content.addView(forgotLink, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
            updateForgotLink(animate = false)
        }

        setContentView(root)
        fitKeypad(scroll)
    }

    private fun xpText(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun xpButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f; setTextColor(RetroUi.NAVY); gravity = Gravity.CENTER
        background = RetroUi.button(this@ScreenLockActivity)
        minWidth = (84 * dp).toInt()
        setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun retroCommon() {
        aurora = AuroraBackgroundView(this, palette)
        ring = LockRingView(this, palette)
        tvTitle = TextView(this)
        tvDescription = TextView(this)
        noticeHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keypad = buildKeypad()
        tvMessage = TextView(this).apply {
            textSize = 13f; setTextColor(cSecondary); gravity = Gravity.CENTER_VERTICAL
            minHeight = (20 * dp).toInt()
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        pinField = PinFieldView(this, palette, "", showDots = LockManager.isShowPinDots(this))
    }

    private fun buildRetroUi() {
        retroCommon()
        if (mode == MODE_UNLOCK) buildRetroLogin() else buildRetroDialog()
    }

    private fun buildRetroLogin() {
        val root = FrameLayout(this).apply { background = pl.sinusoide.sinuschat.ui.Win7LogonDrawable() }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((22 * dp).toInt(), (24 * dp).toInt(), (22 * dp).toInt(), (16 * dp).toInt())
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val shadowText = { v: TextView -> v.setShadowLayer(5 * dp, 0f, 1 * dp, 0xA0001030.toInt()) }
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val name = pl.sinusoide.sinuschat.setup.SetupActivity.getUserDisplayName(this)
        val shown = name.ifEmpty { getString(R.string.app_name) }
        val tile = FrameLayout(this).apply {
            background = pl.sinusoide.sinuschat.ui.Win7TileDrawable(dp)
            elevation = 10 * dp
        }
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 4 * dp)
                }
            }
            clipToOutline = true
            foreground = pl.sinusoide.sinuschat.ui.Win7PictureFrame(dp)
        }
        tile.addView(avatar, FrameLayout.LayoutParams((84 * dp).toInt(), (84 * dp).toInt(), Gravity.CENTER))
        if (!pl.sinusoide.sinuschat.util.AvatarHelper.loadAvatarInto(this, avatar)) {
            pl.sinusoide.sinuschat.util.AvatarHelper.setLetterAvatar(this, avatar, shown,
                pl.sinusoide.sinuschat.util.AvatarHelper.getMyColor(this))
        }
        content.addView(tile, LinearLayout.LayoutParams((108 * dp).toInt(), (108 * dp).toInt()))

        content.addView(xpText(shown, 24f, Color.WHITE).apply {
            shadowText(this)
            gravity = Gravity.CENTER
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * dp).toInt() })
        content.addView(tvDescription.apply {
            textSize = 13.5f; setTextColor(0xFFDCEBFA.toInt()); gravity = Gravity.CENTER
            shadowText(this)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (2 * dp).toInt() })
        content.addView(noticeHost, LinearLayout.LayoutParams(-1, -2))

        val fieldRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fieldRow.addView(pinField, LinearLayout.LayoutParams((220 * dp).toInt(), (30 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
        btnNext = TextView(this).apply {
            contentDescription = getString(R.string.next)
            background = pl.sinusoide.sinuschat.ui.Win7GoDrawable(dp)
            isClickable = true
            setOnClickListener { handleNext() }
        }
        fieldRow.addView(btnNext, LinearLayout.LayoutParams((30 * dp).toInt(), (30 * dp).toInt()))
        content.addView(fieldRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * dp).toInt() })

        tvMessage.setTextColor(Color.WHITE)
        tvMessage.gravity = Gravity.CENTER
        tvMessage.setShadowLayer(4 * dp, 0f, 1 * dp, 0xA0001030.toInt())
        content.addView(tvMessage, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = (8 * dp).toInt(); bottomMargin = (10 * dp).toInt()
        })
        content.addView(keypad, LinearLayout.LayoutParams(-2, -2))

        forgotLink = TextView(this).apply {
            text = getString(R.string.lock_forgot)
            textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(4 * dp, 0f, 1 * dp, 0xA0001030.toInt())
            background = pl.sinusoide.sinuschat.ui.Win7GlassButtonDrawable(dp)
            setPadding((18 * dp).toInt(), 0, (18 * dp).toInt(), 0)
            minWidth = (150 * dp).toInt()
            visibility = View.GONE
            isClickable = true
            setOnClickListener { if (!busy) askRecoveryKey() }
        }
        content.addView(forgotLink, LinearLayout.LayoutParams(-2, (34 * dp).toInt()).apply { topMargin = (14 * dp).toInt() })
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
        brand.addView(xpText(getString(R.string.app_name), 17f, 0xEBFFFFFF.toInt(), bold = true).apply { shadowText(this) })
        content.addView(brand, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (18 * dp).toInt() })
        updateForgotLink(animate = false)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        fitKeypad(scroll)
        RetroUi.lightStatusIcons(this)
    }

    private fun buildRetroDialog() {
        val twoFields = mode == MODE_SET || mode == MODE_SET_DURESS
        val root = FrameLayout(this).apply { background = pl.sinusoide.sinuschat.ui.Win7LogonDrawable() }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }
        scroll.addView(holder)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pl.sinusoide.sinuschat.ui.AeroGlassDrawable(dp, line = false)
            foreground = GradientDrawable().apply {
                cornerRadius = 6 * dp
                setStroke((1 * dp).toInt(), 0xFF5A7AA6.toInt())
            }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 6 * dp)
                }
            }
            clipToOutline = true
            elevation = 8 * dp
            val p = (6 * dp).toInt(); setPadding(p, 0, p, p)
        }
        holder.addView(frame, LinearLayout.LayoutParams(-1, -2))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((2 * dp).toInt(), 0, 0, 0)
        }
        bar.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply { marginEnd = (7 * dp).toInt() })
        bar.addView(tvTitle.apply {
            textSize = 14f; setTextColor(RetroUi.NAVY); typeface = Typeface.DEFAULT
            setShadowLayer(6 * dp, 0f, 0f, 0xE6FFFFFF.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!forcedNewPin) bar.addView(View(this).apply {
            contentDescription = getString(R.string.cancel)
            background = pl.sinusoide.sinuschat.ui.Win7CloseDrawable(dp)
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((44 * dp).toInt(), (20 * dp).toInt()).apply { bottomMargin = (6 * dp).toInt() })
        frame.addView(bar, LinearLayout.LayoutParams(-1, (34 * dp).toInt()))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(RetroUi.PANEL)
                setStroke((1 * dp).toInt(), 0xFF8DA4C4.toInt())
            }
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
        }
        frame.addView(content, LinearLayout.LayoutParams(-1, -2))

        content.addView(tvDescription.apply {
            textSize = 13.5f; setTextColor(RetroUi.NAVY); setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(-1, -2))

        if (mode == MODE_SHUFFLE_TEST) {
            val steps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(SHUFFLE_ROUNDS) { i ->
                val step = View(this).apply {
                    background = GradientDrawable().apply { setColor(stepIdle()) }
                }
                shuffleSteps += step
                steps.addView(step, LinearLayout.LayoutParams((34 * dp).toInt(), (8 * dp).toInt()).apply {
                    if (i > 0) marginStart = (4 * dp).toInt()
                })
            }
            content.addView(steps, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })
        }
        content.addView(noticeHost, LinearLayout.LayoutParams(-1, -2))

        fun label(text: String) = xpText(text, 13.5f, RetroUi.NAVY).apply {
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        }
        if (twoFields) {
            val first = pinField
            val second = PinFieldView(this, palette, "", showDots = LockManager.isShowPinDots(this))
            retroFields = first to second
            content.addView(label(getString(R.string.retro_pin_new)))
            content.addView(first, LinearLayout.LayoutParams(-1, (32 * dp).toInt()))
            content.addView(label(getString(R.string.retro_pin_repeat)))
            content.addView(second, LinearLayout.LayoutParams(-1, (32 * dp).toInt()))
            first.setOnClickListener { restartRetroPin() }
            first.isSelected = true
        } else {
            content.addView(label(getString(R.string.retro_pin_label)))
            content.addView(pinField, LinearLayout.LayoutParams(-1, (32 * dp).toInt()))
            pinField.isSelected = true
        }
        content.addView(tvMessage, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })
        if (twoFields) tvMessage.text = getString(R.string.min_4_digits)

        if (mode == MODE_SET) {
            val sw = MaterialSwitch(this).apply {
                isChecked = isAutoConfirm
                setOnCheckedChangeListener { _, checked -> isAutoConfirm = checked; updateNextButton() }
            }
            autoConfirmRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { sw.isChecked = !sw.isChecked }
                addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * dp).toInt() })
                addView(LinearLayout(this@ScreenLockActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(xpText(getString(R.string.lock_auto_confirm), 13.5f, RetroUi.NAVY))
                    addView(xpText(getString(R.string.lock_auto_confirm_desc), 12f, cSecondary))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            content.addView(autoConfirmRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        }

        content.addView(keypad, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = (14 * dp).toInt()
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        btnNext = xpButton(getString(R.string.retro_ok)) { handleNext() }
        buttons.addView(btnNext)
        if (!forcedNewPin) buttons.addView(xpButton(getString(R.string.cancel)) { finish() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * dp).toInt() })
        content.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * dp).toInt() })

        if (mode == MODE_SHUFFLE_TEST) {
            shuffleOffLink = xpText(getString(R.string.lock_shuffle_test_off), 13.5f, RetroUi.ACCENT).apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setPadding(0, (12 * dp).toInt(), 0, 0)
                isClickable = true
                setOnClickListener {
                    if (busy) return@setOnClickListener
                    LockManager.setShuffleKeypad(this@ScreenLockActivity, false)
                    finish()
                }
            }
            content.addView(shuffleOffLink, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        fitKeypad(scroll)
        RetroUi.lightStatusIcons(this)
    }

    private fun advanceRetroField(): Boolean {
        val fields = retroFields ?: return false
        fields.first.isSelected = false
        fields.second.isSelected = true
        pinField = fields.second
        return true
    }

    private fun restartRetroPin() {
        val fields = retroFields ?: return
        if (busy || firstPin.isEmpty()) return
        firstPin = ""
        currentPin = ""
        fields.first.clear(); fields.second.clear()
        fields.second.isSelected = false
        fields.first.isSelected = true
        pinField = fields.first
        setupTexts()
        autoConfirmRow?.visibility = View.VISIBLE
        autoConfirmRow?.alpha = 1f; autoConfirmRow?.translationY = 0f
        updateNextButton()
    }

    private fun buildKeypad(): LinearLayout {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        fillKeypad(grid)
        return grid
    }

    private fun digitOrder(): List<String> {
        val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        return if (shuffle) digits.shuffled(java.security.SecureRandom()) else digits
    }

    private fun keyDims(): Triple<Int, Int, Int> {
        if (palette.retro) {
            val h = (46 * dp).toInt() - keyShrink
            return Triple((84 * dp).toInt(), h, ((6 * dp).toInt() - keyShrink / 4).coerceAtLeast((3 * dp).toInt()))
        }
        val k = (78 * dp).toInt() - keyShrink
        return Triple(k, k, ((14 * dp).toInt() - keyShrink / 2).coerceAtLeast((6 * dp).toInt()))
    }

    private fun fitKeypad(scroll: ScrollView) {
        val maxShrink = ((if (palette.retro) 12 else 24) * dp).toInt()
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val child = scroll.getChildAt(0) ?: return@addOnLayoutChangeListener
            val avail = scroll.height - scroll.paddingTop - scroll.paddingBottom
            val over = child.height - avail
            if (avail <= 0 || over <= 0 || keyShrink >= maxShrink || keypad.visibility != View.VISIBLE) return@addOnLayoutChangeListener
            keyShrink = (keyShrink + over / 4 + (2 * dp).toInt()).coerceAtMost(maxShrink)
            scroll.post { resizeKeys() }
        }
    }

    private fun resizeKeys() {
        val (keyW, keyH, vGap) = keyDims()
        for (r in 0 until keypad.childCount) {
            val row = keypad.getChildAt(r) as? LinearLayout ?: continue
            for (c in 0 until row.childCount) {
                val key = row.getChildAt(c)
                val lp = key.layoutParams as LinearLayout.LayoutParams
                lp.width = keyW; lp.height = keyH
                if (r > 0) lp.topMargin = vGap
                key.layoutParams = lp
            }
        }
    }

    private fun fillKeypad(grid: LinearLayout) {
        grid.removeAllViews()
        val bioAvailable = (mode == MODE_UNLOCK || mode == MODE_CONFIRM) &&
            LockManager.isBiometricEnabled(this) && LockManager.isBiometricAvailable(this)
        val letters = mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
            "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ")
        val order = digitOrder()
        val rows = listOf(order.subList(0, 3), order.subList(3, 6), order.subList(6, 9), listOf("BIO", order[9], "DEL"))
        val (keyW, keyH, vGap) = keyDims()
        val hGap = if (palette.retro) (6 * dp).toInt() else (22 * dp).toInt()
        rows.forEachIndexed { r, keys ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false }
            keys.forEachIndexed { c, key ->
                val view: View = when (key) {
                    "BIO" -> if (bioAvailable) GlassKeyView(this, null, null, R.drawable.ic_fingerprint, glass = false,
                        palette = palette, sparkle = true).apply {
                        contentDescription = getString(R.string.lock_use_fingerprint)
                    } else View(this)
                    "DEL" -> GlassKeyView(this, null, null, R.drawable.ic_backspace, glass = false, palette = palette).apply {
                        contentDescription = getString(R.string.clear_word)
                        setOnLongClickListener {
                            if (currentPin.isNotEmpty() && !busy) {
                                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                currentPin = ""
                                pinField.clear()
                                updateNextButton()
                            }
                            true
                        }
                    }
                    else -> GlassKeyView(this, key, letters[key], null, glass = true, palette = palette)
                }
                if (view is GlassKeyView) view.setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onKey(key)
                }
                row.addView(view, LinearLayout.LayoutParams(keyW, keyH).apply {
                    if (c > 0) marginStart = hGap
                    if (r > 0) topMargin = vGap
                })
            }
            grid.addView(row)
        }
    }

    private fun popKeys(baseDelay: Long) {
        for (r in 0 until keypad.childCount) {
            val row = keypad.getChildAt(r) as LinearLayout
            for (c in 0 until row.childCount) {
                val key = row.getChildAt(c)
                key.alpha = 0f; key.scaleX = 0.7f; key.scaleY = 0.7f
                key.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(baseDelay + (r * 45L) + (c * 25L)).setDuration(360)
                    .setInterpolator(OvershootInterpolator(1.6f)).start()
            }
        }
    }

    private fun reshuffleKeypad() {
        if (!shuffle || isFinishing) return
        shuffling = true
        keypad.animate().cancel()
        keypad.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(140)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                fillKeypad(keypad)
                keypad.alpha = 1f; keypad.scaleX = 1f; keypad.scaleY = 1f
                popKeys(0L)
                shuffling = false
            }.start()
    }

    private fun stepIdle() = (cSecondary and 0x00FFFFFF) or 0x40000000

    private fun markShuffleStep(index: Int) {
        val step = shuffleSteps.getOrNull(index) ?: return
        val bg = step.background as? GradientDrawable ?: return
        android.animation.ValueAnimator.ofArgb(stepIdle(), cGreen).apply {
            duration = 260
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
        step.scaleY = 1f
        step.animate().scaleY(1.8f).setDuration(120).withEndAction {
            step.animate().scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
        }.start()
    }

    private fun shuffleRoundPassed() {
        busy = true
        window.decorView.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        pinField.flash(cGreen)
        tvMessage.animate().cancel()
        tvMessage.alpha = 1f
        tvMessage.setTextColor(cGreen)
        tvMessage.text = getString(R.string.lock_shuffle_test_step, shufflePassed, SHUFFLE_ROUNDS)
        content.postDelayed({
            if (isFinishing) return@postDelayed
            currentPin = ""
            pinField.clear()
            updateNextButton()
            busy = false
            reshuffleKeypad()
        }, 420)
    }

    private fun autoConfirmSwitch(): View {
        val sw = MaterialSwitch(this).apply {
            isChecked = isAutoConfirm
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, 0xFF8AA0B8.toInt()))
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(cAccent, if (dark) 0x33FFFFFF else 0x33000000))
            setOnCheckedChangeListener { _, checked -> isAutoConfirm = checked; updateNextButton() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassCard(20f)
            setPadding((16 * dp).toInt(), (11 * dp).toInt(), (12 * dp).toInt(), (11 * dp).toInt())
            isClickable = true
            setOnClickListener { sw.isChecked = !sw.isChecked }
            addView(LinearLayout(this@ScreenLockActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@ScreenLockActivity).apply {
                    text = getString(R.string.lock_auto_confirm); textSize = 15f; setTextColor(cPrimary)
                })
                addView(TextView(this@ScreenLockActivity).apply {
                    text = getString(R.string.lock_auto_confirm_desc); textSize = 12.5f; setTextColor(cSecondary)
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * dp).toInt() })
            addView(sw)
        }
    }

    private fun glassCard(radiusDp: Float, tint: Int? = null) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(tint?.let { (it and 0x00FFFFFF) or 0x26000000 } ?: palette.fill)
        setStroke((1 * dp).toInt(), tint?.let { (it and 0x00FFFFFF) or 0x66000000 } ?: palette.rimTop)
    }

    private fun setupTexts() {
        when (mode) {
            MODE_SET -> {
                tvTitle.text = getString(if (forcedNewPin) R.string.lock_forgot_new_title else R.string.lock_set_pin_title)
                tvDescription.text = getString(if (forcedNewPin) R.string.lock_forgot_new_desc else R.string.lock_set_pin_desc)
            }
            MODE_UNLOCK -> {
                tvTitle.text = getString(R.string.lock_unlock_title)
                tvDescription.text = getString(R.string.lock_unlock_desc)
            }
            MODE_CONFIRM -> {
                tvTitle.text = getString(R.string.lock_confirm_pin_title)
                tvDescription.text = getString(R.string.lock_confirm_pin_desc)
            }
            MODE_SET_DURESS -> {
                tvTitle.text = getString(R.string.lock_set_duress_title)
                tvDescription.text = getString(R.string.lock_set_duress_desc)
            }
            MODE_CONFIRM_DURESS -> {
                tvTitle.text = getString(R.string.lock_confirm_duress_title)
                tvDescription.text = getString(R.string.lock_confirm_duress_desc)
            }
            MODE_SHUFFLE_TEST -> {
                tvTitle.text = getString(R.string.lock_shuffle_test_title)
                tvDescription.text = getString(R.string.lock_shuffle_test_desc)
            }
        }
        intent.getStringExtra(EXTRA_TITLE)?.let { tvTitle.text = it }
        if (palette.retro && mode == MODE_UNLOCK) tvDescription.text = getString(R.string.retro_login_hint)
    }

    private fun playEntrance() {
        if (palette.retro) return
        ring.scaleX = 0.6f; ring.scaleY = 0.6f; ring.alpha = 0f
        ring.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520)
            .setInterpolator(OvershootInterpolator(1.8f)).start()
        listOf(tvTitle, tvDescription, pinField, tvMessage).forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = 18 * dp
            v.animate().alpha(1f).translationY(0f).setStartDelay(90L + i * 50L).setDuration(380)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        popKeys(220L)
        listOfNotNull(autoConfirmRow, btnNext, shuffleOffLink).forEachIndexed { i, v ->
            val target = v.alpha
            v.alpha = 0f
            v.animate().alpha(target).setStartDelay(420L + i * 60L).setDuration(300).start()
        }
    }

    private fun maybeShowBiometricRemovedNotice() {
        if (mode != MODE_UNLOCK || !LockManager.shouldShowBiometricRemovedNotice(this)) return
        LockManager.markBiometricRemovedNoticeShown(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glassCard(20f, cOrange)
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (13 * dp).toInt())
            addView(ImageView(this@ScreenLockActivity).apply {
                setImageResource(R.drawable.ic_fingerprint); setColorFilter(cOrange)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((cOrange and 0x00FFFFFF) or 0x2E000000) }
                val p = (7 * dp).toInt(); setPadding(p, p, p, p)
            }, LinearLayout.LayoutParams((38 * dp).toInt(), (38 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
            addView(LinearLayout(this@ScreenLockActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@ScreenLockActivity).apply {
                    text = getString(R.string.lock_bio_removed_title); textSize = 15f
                    setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@ScreenLockActivity).apply {
                    text = getString(R.string.lock_bio_removed_desc); textSize = 13f; setTextColor(cSecondary)
                    setLineSpacing(0f, 1.15f)
                    setPadding(0, (3 * dp).toInt(), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        noticeHost.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (18 * dp).toInt() })
        card.alpha = 0f; card.translationY = -14 * dp; card.scaleX = 0.96f; card.scaleY = 0.96f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(450).setDuration(420)
            .setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    private fun onKey(key: String) {
        if (busy || inLockout || shuffling) return
        when (key) {
            "DEL" -> if (currentPin.isNotEmpty()) {
                currentPin = currentPin.dropLast(1)
                pinField.onDelete(currentPin.isEmpty())
                updateNextButton()
            }
            "BIO" -> if (LockManager.isBiometricEnabled(this)) {
                biometricTriggered = false
                if (mode == MODE_UNLOCK) triggerBiometric(isAuto = false) else confirmWithBiometric()
            }
            else -> if (currentPin.length < MAX_PIN) {
                resetMessage()
                currentPin += key
                pinField.onDigit()
                updateNextButton()
                if (isAutoConfirm || silentChecks()) autoConfirm()
            }
        }
    }

    private fun silentChecks(): Boolean =
        silent && (mode == MODE_UNLOCK || mode == MODE_CONFIRM || mode == MODE_CONFIRM_DURESS || mode == MODE_SHUFFLE_TEST)

    private fun submitLength(): Int {
        if (silent || !isAutoConfirm) return 0
        val pinLen = LockManager.pinLength(this)
        return when (mode) {
            MODE_UNLOCK -> {
                if (pinLen == 0) return 0
                if (!LockManager.isDuressEnabled(this)) return pinLen
                val duressLen = LockManager.duressLength(this)
                if (duressLen == 0) 0 else maxOf(pinLen, duressLen)
            }
            MODE_CONFIRM, MODE_SHUFFLE_TEST -> pinLen
            MODE_CONFIRM_DURESS -> LockManager.duressLength(this)
            else -> 0
        }
    }

    private fun autoConfirm() {
        val reached = submitLength().let { it > 0 && currentPin.length >= it }
        when (mode) {
            MODE_UNLOCK -> if (LockManager.verifyPin(this, currentPin) || LockManager.verifyDuressPin(this, currentPin) || reached)
                checkPin(currentPin)
            MODE_CONFIRM, MODE_SHUFFLE_TEST -> if (LockManager.verifyPin(this, currentPin) || reached) handleNext()
            MODE_CONFIRM_DURESS -> if (LockManager.verifyDuressPin(this, currentPin) || reached) handleNext()
            MODE_SET, MODE_SET_DURESS -> if (firstPin.isNotEmpty() && currentPin.length == firstPin.length) handleNext()
        }
    }

    private fun autoConfirmCoversStep(): Boolean = when (mode) {
        MODE_UNLOCK, MODE_CONFIRM, MODE_CONFIRM_DURESS, MODE_SHUFFLE_TEST -> silent || submitLength() > 0
        MODE_SET, MODE_SET_DURESS -> isAutoConfirm && firstPin.isNotEmpty()
        else -> false
    }

    private fun confirmWithBiometric() {
        if (!LockManager.isBiometricAvailable(this)) return
        LockManager.showBiometricPrompt(this, onSuccess = {
            playSuccess { setResult(RESULT_OK); finish() }
        }, onFailed = {})
    }

    private fun updateNextButton() {
        val needsNext = (palette.retro || !autoConfirmCoversStep()) && !inLockout
        btnNext.visibility = if (needsNext) View.VISIBLE else View.GONE
        if (!palette.retro) btnNext.text = getString(if ((mode == MODE_SET || mode == MODE_SET_DURESS) && firstPin.isNotEmpty()) R.string.lock_save else R.string.next)
        val ready = currentPin.length >= MIN_PIN
        btnNext.isEnabled = ready
        btnNext.animate().alpha(if (ready) 1f else 0.4f).setDuration(160).start()
    }

    private fun resetMessage() {
        if (tvMessage.currentTextColor == cRed) {
            tvMessage.animate().alpha(0f).setDuration(140).withEndAction {
                tvMessage.setTextColor(cSecondary)
                tvMessage.text = if ((mode == MODE_SET || mode == MODE_SET_DURESS)) getString(R.string.min_4_digits) else ""
                tvMessage.animate().alpha(1f).setDuration(140).start()
            }.start()
        }
    }

    private fun showError(message: String) {
        busy = true
        tvMessage.animate().cancel()
        tvMessage.alpha = 1f
        tvMessage.setTextColor(cRed)
        tvMessage.text = message
        window.decorView.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        pinField.flash(cRed)
        ring.shake()
        android.animation.ObjectAnimator.ofFloat(pinField, "translationX",
            0f, -20 * dp, 17 * dp, -12 * dp, 8 * dp, -4 * dp, 0f).apply {
            duration = 460
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    currentPin = ""
                    pinField.clear()
                    updateNextButton()
                    busy = false
                    reshuffleKeypad()
                }
            })
            start()
        }
    }

    private fun playSuccess(then: () -> Unit) {
        busy = true
        window.decorView.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        ring.playSuccess()
        pinField.holdColor(cGreen)
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener { aurora.glow = it.animatedValue as Float }
            start()
        }
        keypad.animate().alpha(0f).translationY(40 * dp).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        btnNext.animate().alpha(0f).setDuration(200).start()
        autoConfirmRow?.animate()?.alpha(0f)?.setDuration(200)?.start()
        shuffleOffLink?.animate()?.alpha(0f)?.setDuration(200)?.start()
        content.postDelayed({ if (!isFinishing) then() }, 430)
    }

    private fun handleNext() {
        if (busy) return
        if (currentPin.length < MIN_PIN) {
            showError(getString(R.string.min_4_digits))
            return
        }

        when (mode) {
            MODE_SET -> {
                if (firstPin.isEmpty()) {
                    if (LockManager.verifyDuressPin(this, currentPin)) {
                        showError(getString(R.string.lock_pin_same_as_duress))
                        return
                    }
                    if (currentPin != weakAccepted && PinStrength.isWeak(currentPin)) {
                        askAboutWeakPin()
                        return
                    }
                    firstPin = currentPin
                    currentPin = ""
                    if (!advanceRetroField()) pinField.clear()
                    tvTitle.text = getString(R.string.lock_repeat_pin_title)
                    tvDescription.text = getString(R.string.lock_repeat_pin_desc)
                    autoConfirmRow?.animate()?.alpha(0f)?.translationY(10 * dp)?.setDuration(180)
                        ?.withEndAction { autoConfirmRow?.visibility = View.GONE }?.start()
                    animateStep()
                    updateNextButton()
                } else {
                    if (currentPin == firstPin) {
                        LockManager.setPin(this, currentPin)
                        LockManager.setAutoConfirm(this, isAutoConfirm)
                        LockManager.sessionUnlocked = true

                        val appCtx = applicationContext
                        val pinCopy = currentPin
                        AppExecutor.io {
                            val enabled = DbKeyManager.isEnabled(appCtx)
                            val dbEnc = DbCrypto.isDbEncrypted(appCtx) || !DbCrypto.dbExists(appCtx)
                            if (enabled && dbEnc) {
                                if (DbKeyManager.cachedKey() == null && DbKeyManager.isTransparent(appCtx)) {
                                    DbKeyManager.initTransparent(appCtx)
                                }
                                DbKeyManager.rewrapWithCachedKey(appCtx, pinCopy)
                            } else {
                                DbKeyManager.setMigrating(appCtx, true)
                                val existingKey = DbKeyManager.cachedKey()
                                if (existingKey != null && dbEnc) {
                                    DbKeyManager.rewrapWithCachedKey(appCtx, pinCopy)
                                } else {
                                    val rk = pl.sinusoide.sinuschat.util.BackupPrefs.recoveryKey(appCtx)
                                    val rkAt = pl.sinusoide.sinuschat.util.BackupPrefs.recoveryKeyCreatedMs(appCtx)
                                    DbKeyManager.wipe(appCtx)
                                    SinusChatDatabase.reset()
                                    val dbk = DbKeyManager.setUp(appCtx, pinCopy)
                                    if (dbk != null) {
                                        val ok = DbCrypto.encryptExisting(appCtx, dbk)
                                        if (ok) {
                                            DbKeyManager.cacheKey(dbk)
                                            rk?.let { pl.sinusoide.sinuschat.util.BackupPrefs.setRecoveryKey(appCtx, it, rkAt) }
                                            DbKeyManager.syncUnlockCopies(appCtx)
                                            SinusChatDatabase.reset()
                                        } else {
                                            val error = DbKeyManager.getMigrationError(appCtx)
                                            DbKeyManager.wipe(appCtx)
                                            DbKeyManager.setMigrationError(appCtx, error ?: "Nieznany błąd szyfrowania")
                                            SinusChatDatabase.reset()
                                        }
                                    }
                                }
                                DbKeyManager.setMigrating(appCtx, false)
                            }
                        }
                        playSuccess {
                            if (forcedNewPin) {
                                PopupHelper.showSuccess(this, getString(R.string.lock_forgot_done))
                                goToApp()
                                return@playSuccess
                            }
                            PopupHelper.showSuccess(this, getString(R.string.lock_set_success))
                            if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
                                startActivity(Intent(this, ScreenLockSettingsActivity::class.java))
                            }
                            finish()
                        }
                    } else {
                        showError(getString(R.string.lock_match_error))
                    }
                }
            }
            MODE_UNLOCK -> checkPin(currentPin)
            MODE_CONFIRM -> {
                if (LockManager.verifyPin(this, currentPin)) {
                    LockManager.rememberPinLength(this, currentPin.length)
                    playSuccess { setResult(RESULT_OK); finish() }
                } else {
                    showError(getString(R.string.lock_incorrect_pin))
                }
            }
            MODE_SET_DURESS -> {
                if (firstPin.isEmpty()) {
                    if (LockManager.verifyPin(this, currentPin)) {
                        showError(getString(R.string.lock_duress_same_error))
                        return
                    }
                    firstPin = currentPin
                    currentPin = ""
                    if (!advanceRetroField()) pinField.clear()
                    tvTitle.text = getString(R.string.lock_repeat_duress_title)
                    animateStep()
                    updateNextButton()
                } else {
                    if (currentPin == firstPin) {
                        LockManager.setDuressPin(this, currentPin)
                        playSuccess {
                            PopupHelper.showSuccess(this, getString(R.string.lock_duress_set_success))
                            finish()
                        }
                    } else {
                        showError(getString(R.string.lock_match_error))
                    }
                }
            }
            MODE_CONFIRM_DURESS -> {
                if (LockManager.verifyDuressPin(this, currentPin)) {
                    playSuccess { setResult(RESULT_OK); finish() }
                } else {
                    showError(getString(R.string.lock_incorrect_duress))
                }
            }
            MODE_SHUFFLE_TEST -> {
                if (!LockManager.verifyPin(this, currentPin)) {
                    showError(getString(R.string.lock_incorrect_pin))
                    return
                }
                LockManager.rememberPinLength(this, currentPin.length)
                markShuffleStep(shufflePassed)
                shufflePassed++
                if (shufflePassed < SHUFFLE_ROUNDS) {
                    shuffleRoundPassed()
                    return
                }
                LockManager.setShuffleKeypad(this, true)
                playSuccess {
                    PopupHelper.showSuccess(this, getString(R.string.lock_shuffle_enabled))
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun askAboutWeakPin() {
        val ui = pl.sinusoide.sinuschat.ui.ThemeUi
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
        }
        root.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = 3 * dp; setColor(ui.handle(this@ScreenLockActivity)) }
        }, LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply { bottomMargin = (18 * dp).toInt() })
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_warning)
            setColorFilter(cOrange)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((cOrange and 0x00FFFFFF) or 0x26000000) }
            val p = (13 * dp).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()))
        root.addView(TextView(this).apply {
            text = getString(R.string.lock_weak_title)
            textSize = 19f; setTextColor(ui.textPrimary(this@ScreenLockActivity)); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.lock_weak_desc)
            textSize = 14.5f; setTextColor(ui.textSecondary(this@ScreenLockActivity)); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(0, (8 * dp).toInt(), 0, (20 * dp).toInt())
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.lock_weak_change)
            textSize = 16f; setTextColor(ui.onAccent(this@ScreenLockActivity)); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 26 * dp; setColor(ui.accent(this@ScreenLockActivity)) }
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            isClickable = true
            setOnClickListener {
                sheet.dismiss()
                currentPin = ""
                pinField.clear()
                updateNextButton()
                tvMessage.setTextColor(cSecondary)
                tvMessage.text = getString(R.string.lock_weak_hint)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = getString(R.string.lock_weak_keep)
            textSize = 15f; setTextColor(ui.textSecondary(this@ScreenLockActivity)); gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
            isClickable = true
            setOnClickListener {
                sheet.dismiss()
                weakAccepted = currentPin
                handleNext()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        sheet.setContentView(root)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun animateStep() {
        if (palette.retro) { reshuffleKeypad(); return }
        for (v in listOf(tvTitle, tvDescription)) {
            v.alpha = 0f; v.translationX = 28 * dp
            v.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        ring.animate().rotationBy(360f).setDuration(650).setInterpolator(DecelerateInterpolator()).start()
        reshuffleKeypad()
    }

    private fun goToApp() {
        LockManager.sessionUnlocked = true
        SinusChatDatabase.warmUp(this)
        if (intent.getBooleanExtra(EXTRA_RETURN_RESULT, false)) {
            setResult(RESULT_OK)
            finishWithFade()
            return
        }
        val nextIntent = androidx.core.content.IntentCompat.getParcelableExtra(intent, EXTRA_NEXT_INTENT, Intent::class.java)
        if (nextIntent != null) {
            startWithFade(nextIntent)
        } else {
            startWithFade(Intent(this, MainActivity::class.java).apply {
                putExtra("unlocked", true)
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
        }
        finish()
    }

    private fun checkPin(pin: String) {
        if (LockManager.verifyDuressPin(this, pin)) {
            LockManager.wipeAllChats(this)
            DbKeyManager.wipe(this)
            finishAffinity()
            return
        }

        if (LockManager.verifyPin(this, pin)) {
            LockManager.resetFailCount(this)
            LockManager.rememberPinLength(this, pin.length)

            if (DbKeyManager.isEnabled(this)) {
                busy = true
                val appCtx = applicationContext
                val pinCopy = pin
                val wasOpen = DbKeyManager.cachedKey() != null
                AppExecutor.io {
                    var ok = DbKeyManager.unlockSession(appCtx, pinCopy)
                    if (!ok && DbKeyManager.isTransparent(appCtx)) {
                        ok = DbKeyManager.initTransparent(appCtx) != null &&
                            DbKeyManager.rewrapWithCachedKey(appCtx, pinCopy)
                    }
                    if (ok) {
                        if (!wasOpen) SinusChatDatabase.reset()
                        runOnUiThread { playSuccess { goToApp() } }
                    } else {
                        runOnUiThread {
                            busy = false
                            showError(getString(R.string.lock_incorrect_pin))
                        }
                    }
                }
            } else {
                playSuccess { goToApp() }
            }
        } else {
            onWrongSecret(null)
        }
    }

    private fun onWrongSecret(message: String?) {
        val attempts = LockManager.incrementFailCount(this)
        if (LockManager.isWipeOnFailEnabled(this) && attempts >= LockManager.maxAttempts()) {
            LockManager.wipeAllChats(this)
            DbKeyManager.wipe(this)
            PopupHelper.showError(this, getString(R.string.lock_wipe_exceeded))
            finishAffinity()
            return
        }
        val lockout = LockManager.lockoutFor(attempts)
        if (lockout > 0) LockManager.startLockout(this, lockout)
        showError(message ?: if (LockManager.isWipeOnFailEnabled(this))
            getString(R.string.lock_attempt_error_wipe, attempts, LockManager.maxAttempts())
        else getString(R.string.lock_attempt_error, attempts))
        updateForgotLink(animate = true)
        if (lockout > 0) content.postDelayed({ if (!isFinishing) updateLockout() }, 520)
    }

    private fun updateLockout() {
        if (mode != MODE_UNLOCK || !::content.isInitialized) return
        content.removeCallbacks(lockoutTick)
        val remaining = LockManager.lockoutRemainingMs(this)
        if (remaining > 0L) {
            if (!inLockout) enterLockoutUi()
            tvDescription.text = getString(R.string.lock_lockout_desc, formatWait(remaining))
            val next = (remaining % 1000L).let { if (it < 50L) 1000L else it }
            content.postDelayed(lockoutTick, next)
        } else if (inLockout) {
            exitLockoutUi()
        }
    }

    private fun enterLockoutUi() {
        inLockout = true
        busy = false
        currentPin = ""
        pinField.clear()
        for (v in listOf(pinField, tvMessage, keypad)) v.visibility = View.GONE
        updateNextButton()
        ring.setDanger(true)
        tvTitle.text = getString(R.string.lock_lockout_title)
        updateForgotLink(animate = true)
    }

    private fun exitLockoutUi() {
        inLockout = false
        for (v in listOf(pinField, tvMessage, keypad)) v.visibility = View.VISIBLE
        tvMessage.setTextColor(cSecondary)
        tvMessage.text = ""
        ring.setDanger(false)
        setupTexts()
        updateNextButton()
        updateForgotLink(animate = false)
    }

    private fun formatWait(ms: Long): String {
        val total = (ms + 999L) / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val sec = total % 60L
        return when {
            h > 0 -> getString(R.string.lock_wait_hm, h, m)
            m > 0 -> getString(R.string.lock_wait_ms, m, sec)
            else -> getString(R.string.lock_wait_s, sec)
        }
    }

    private fun updateForgotLink(animate: Boolean) {
        val link = forgotLink ?: return
        val show = mode == MODE_UNLOCK && !silent && DbKeyManager.hasRecoveryUnlock(this) &&
            (LockManager.getFailCount(this) >= FORGOT_AFTER || inLockout)
        if (show == (link.visibility == View.VISIBLE)) return
        link.visibility = if (show) View.VISIBLE else View.GONE
        if (show && animate) {
            link.alpha = 0f; link.translationY = 8 * dp
            link.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private val recoveryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        RecoveryKeyActivity.keyFrom(r.data)?.let { unlockWithRecovery(it) }
    }

    private fun askRecoveryKey() {
        recoveryLauncher.launch(RecoveryKeyActivity.unlockIntent(this, DbKeyManager.recoveryUnlockId(this)))
    }

    private fun unlockWithRecovery(key: RecoveryKey) {
        busy = true
        val appCtx = applicationContext
        val wasOpen = DbKeyManager.cachedKey() != null
        AppExecutor.io {
            val ok = DbKeyManager.unlockWithRecoveryKey(appCtx, key)
            runOnUiThread {
                busy = false
                if (!ok) {
                    onWrongSecret(getString(R.string.lock_forgot_failed))
                    return@runOnUiThread
                }
                LockManager.resetFailCount(appCtx)
                if (!wasOpen) SinusChatDatabase.reset()
                intent.putExtra(EXTRA_MODE, MODE_SET).putExtra(EXTRA_FORCED_NEW_PIN, true)
                recreate()
            }
        }
    }

    private fun triggerBiometric(isAuto: Boolean = false) {
        if (inLockout) return
        val kgm = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (kgm.isKeyguardLocked) return

        if (!LockManager.isBiometricEnabled(this) || !LockManager.isBiometricAvailable(this)) return
        val appCtx = applicationContext

        if (DbKeyManager.cachedKey() != null || !DbKeyManager.isEnabled(appCtx)) {
            biometricTriggered = true
            LockManager.showBiometricPrompt(this, {
                if (DbKeyManager.cachedKey() == null) SinusChatDatabase.reset()
                LockManager.resetFailCount(appCtx)
                playSuccess { goToApp() }
            }, {
                if (!isAuto) biometricTriggered = false
            })
            return
        }

        val cipher = DbKeyManager.biometricCipher(appCtx)
        if (cipher == null) {
            if (!isAuto) {
                tvMessage.setTextColor(cSecondary)
                tvMessage.text = getString(R.string.lock_bio_after_restart)
            }
            return
        }
        biometricTriggered = true
        LockManager.showBiometricCryptoPrompt(this, cipher, onSuccess = { unlocked ->
            AppExecutor.io {
                val ok = DbKeyManager.unlockWithBiometricCipher(appCtx, unlocked)
                runOnUiThread {
                    if (ok) {
                        LockManager.resetFailCount(appCtx)
                        SinusChatDatabase.reset()
                        playSuccess { goToApp() }
                    } else if (!isAuto) {
                        tvMessage.setTextColor(cSecondary)
                        tvMessage.text = getString(R.string.lock_bio_after_restart)
                    }
                }
            }
        }, onFailed = {
            if (!isAuto) biometricTriggered = false
        })
    }
}
