package pl.sinusoide.sinuschat.pclink

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.link.LinkFrame
import pl.sinusoide.sinuschat.ui.ConnectedHopView
import pl.sinusoide.sinuschat.ui.DistanceWaveView
import pl.sinusoide.sinuschat.ui.IconDrawView
import pl.sinusoide.sinuschat.ui.LogoDrawView
import pl.sinusoide.sinuschat.ui.ThemeUi
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.ThemeManager

class ProximitySetupActivity : AppCompatActivity() {

    private companion object {
        const val BLUETOOTH_PAGE = "android.settings.BLUETOOTH_DASHBOARD_SETTINGS"
    }

    private enum class Step { CHECK, BLUETOOTH, NEARBY, WAIT, CONNECTED, TEST, LOCKED, DISTANCE, DONE }

    private val dp by lazy { resources.displayMetrics.density }
    private var cPrimary = 0
    private var cSecondary = 0
    private var cAccent = 0
    private var ink = Color.WHITE
    private lateinit var body: LinearLayout
    private var step = Step.CHECK
    private var reported: String? = null
    private var wave: DistanceWaveView? = null
    private var askedNearby = false

    private val nearbyLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_ADVERTISE) && askedNearby) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
        askedNearby = true
        evaluate()
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { evaluate() }
    }

    private val listener = PcProximitySetup.Listener { s, rssi, dm -> onDesktop(s, rssi, dm) }
    private var distanceText: TextView? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        if (!PcProximitySetup.active()) {
            finish()
            return
        }
        cPrimary = ThemeUi.textPrimary(this)
        cSecondary = ThemeUi.attrOrNull(this, R.attr.sinuschat_text_secondary) ?: 0xFF7F91A4.toInt()
        cAccent = ThemeUi.accent(this)
        ink = IconDrawView.ink(this)

        val root = FrameLayout(this)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * dp).toInt(), (16 * dp).toInt(), (28 * dp).toInt(), (24 * dp).toInt())
        }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(cPrimary)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p)
            background = ContextCompat.getDrawable(this@ProximitySetupActivity, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.prox_cancel)
            setOnClickListener { cancelSetup() }
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step == Step.DONE) finish() else cancelSetup()
            }
        })
        PcProximitySetup.attach(listener)
        ContextCompat.registerReceiver(this, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val restored = PcProximitySetup.phoneStep?.let { n -> Step.entries.firstOrNull { it.name == n } }
        when {
            restored != null && restored != Step.CHECK && restored != Step.BLUETOOTH && restored != Step.NEARBY -> show(restored)
            else -> when (PcProximitySetup.lastDesktop) {
            LinkFrame.SETUP_CONNECTED -> show(Step.CONNECTED)
            LinkFrame.SETUP_TEST -> show(Step.TEST)
            LinkFrame.SETUP_LOCKED, LinkFrame.SETUP_DISTANCE -> show(Step.DISTANCE)
            LinkFrame.SETUP_DONE, LinkFrame.SETUP_SKIP -> show(Step.DONE)
            else -> evaluate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (step == Step.CHECK || step == Step.BLUETOOTH || step == Step.NEARBY) evaluate()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(btReceiver) }
        PcProximitySetup.attach(null)
        super.onDestroy()
    }

    private fun bluetoothOn(): Boolean =
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)

    private fun evaluate() {
        if (isFinishing || isDestroyed) return
        if (step != Step.CHECK && step != Step.BLUETOOTH && step != Step.NEARBY) return
        when {
            !bluetoothOn() -> {
                report(LinkFrame.SETUP_BLUETOOTH)
                show(Step.BLUETOOTH)
            }
            !PcProximity.hasPermission(this) -> {
                report(LinkFrame.SETUP_NEARBY)
                show(Step.NEARBY)
            }
            else -> {
                reported = LinkFrame.SETUP_READY
                PcProximitySetup.ready(this)
                show(Step.WAIT)
            }
        }
    }

    private fun report(s: String) {
        if (reported == s) return
        reported = s
        PcProximitySetup.send(s)
    }

    private fun onDesktop(s: String, rssi: Int, decimeters: Int) {
        if (isFinishing || isDestroyed) return
        when (s) {
            LinkFrame.SETUP_CONNECTED -> if (step == Step.WAIT) show(Step.CONNECTED)
            LinkFrame.SETUP_TEST -> if (step == Step.CONNECTED || step == Step.WAIT) show(Step.TEST)
            LinkFrame.SETUP_SKIP, LinkFrame.SETUP_DONE -> show(Step.DONE)
            LinkFrame.SETUP_SIGNAL -> {
                wave?.setStrength(strength(rssi))
                distanceText?.let { t ->
                    t.visibility = if (rssi > -100) View.VISIBLE else View.INVISIBLE
                    if (decimeters > 0) t.text = getString(R.string.prox_test_distance, String.format(resources.configuration.locales[0], "%.1f", decimeters / 10f))
                    else t.text = getString(R.string.prox_test_signal, rssi)
                }
            }
            LinkFrame.SETUP_LOCKED -> if (step == Step.TEST || step == Step.CONNECTED) {
                show(Step.LOCKED)
                body.postDelayed({ if (step == Step.LOCKED) show(Step.DISTANCE) }, 2_600L)
            }
            LinkFrame.SETUP_DISTANCE -> if (step != Step.DONE) show(Step.DISTANCE)
            LinkFrame.SETUP_CANCEL -> finish()
            LinkFrame.SETUP_START -> {
                reported = null
                when (step) {
                    Step.WAIT -> {
                        reported = LinkFrame.SETUP_READY
                        PcProximitySetup.ready(this)
                    }
                    Step.BLUETOOTH -> report(LinkFrame.SETUP_BLUETOOTH)
                    Step.NEARBY -> report(LinkFrame.SETUP_NEARBY)
                    Step.CHECK -> evaluate()
                    else -> Unit
                }
            }
        }
    }

    private fun strength(rssi: Int): Float = ((rssi + 95f) / 45f).coerceIn(0f, 1f)

    private fun cancelSetup() {
        PcProximitySetup.cancel(this)
        finish()
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

    private fun art(drawing: IconDrawView.Drawing) = IconDrawView(this).apply {
        setColors(ink, ink)
        setDrawing(drawing)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun artKey(s: Step): Step = when (s) {
        Step.CHECK, Step.BLUETOOTH, Step.WAIT -> Step.BLUETOOTH
        Step.TEST, Step.LOCKED -> Step.TEST
        else -> s
    }

    private fun show(next: Step) {
        if (next == step && body.childCount > 0) return
        val keepArt = body.childCount > 0 && artKey(next) == artKey(step)
        val oldArt = if (keepArt) body.getChildAt(0) else null
        step = next
        PcProximitySetup.phoneStep = next.name
        distanceText = null
        if (oldArt == null) wave = null
        body.removeAllViews()
        val artHeight = (200 * dp).toInt()
        val (artView, title, desc) = if (oldArt != null) Triple(oldArt, titleOf(next), descOf(next)) else when (next) {
            Step.CHECK, Step.BLUETOOTH -> Triple(art(IconDrawView.bluetooth(this)), R.string.prox_bt_title, R.string.prox_bt_desc)
            Step.NEARBY -> Triple(art(IconDrawView.nearby(this)), R.string.prox_nearby_title, R.string.prox_nearby_desc)
            Step.WAIT -> Triple(art(IconDrawView.bluetooth(this)), R.string.prox_wait_title, R.string.prox_wait_desc)
            Step.CONNECTED -> Triple(ConnectedHopView(this).apply {
                setColors(ink, cAccent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, R.string.prox_connected_title, R.string.prox_connected_desc)
            Step.TEST, Step.LOCKED -> {
                val w = DistanceWaveView(this).apply {
                    setColors(ink, cAccent)
                    setLocked(next == Step.LOCKED)
                }
                wave = w
                Triple(w, if (next == Step.LOCKED) R.string.prox_locked_title else R.string.prox_test_title,
                    if (next == Step.LOCKED) R.string.prox_locked_desc else R.string.prox_test_desc)
            }
            Step.DISTANCE -> Triple(art(IconDrawView.distance(this)), R.string.prox_distance_title, R.string.prox_distance_desc)
            Step.DONE -> Triple(LogoDrawView(this), R.string.prox_done_title, R.string.prox_done_desc)
        }
        body.addView(artView, LinearLayout.LayoutParams(-1, artHeight))
        if (next == Step.LOCKED) wave?.setLocked(true)
        body.addView(text(getString(title), 24f, cPrimary, true).apply { setPadding(0, (14 * dp).toInt(), 0, 0) })
        body.addView(text(getString(desc), 15f, cSecondary, false).apply { setPadding(0, (10 * dp).toInt(), 0, 0) })
        if (next == Step.TEST) {
            val chip = text("", 13f, cAccent, true).apply {
                val ph = (14 * dp).toInt(); val pv = (6 * dp).toInt(); setPadding(ph, pv, ph, pv)
                background = GradientDrawable().apply {
                    cornerRadius = 14 * dp
                    setColor((cAccent and 0x00FFFFFF) or 0x22000000)
                }
                visibility = View.INVISIBLE
            }
            distanceText = chip
            body.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * dp).toInt() })
        }

        fun add(label: Int, primary: Boolean, top: Int, onClick: () -> Unit) {
            body.addView(button(getString(label), primary, onClick), LinearLayout.LayoutParams(-1, -2).apply { topMargin = (top * dp).toInt() })
        }
        when (next) {
            Step.BLUETOOTH -> add(R.string.prox_bt_button, true, 28) { enableBluetooth() }
            Step.NEARBY -> add(R.string.prox_nearby_button, true, 28) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) nearbyLauncher.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
                else evaluate()
            }
            Step.CONNECTED -> {
                add(R.string.prox_test_yes, true, 28) {
                    PcProximitySetup.send(LinkFrame.SETUP_TEST)
                    show(Step.TEST)
                }
                add(R.string.prox_test_no, false, 6) {
                    PcProximitySetup.send(LinkFrame.SETUP_SKIP)
                    show(Step.DONE)
                }
            }
            Step.DISTANCE -> add(R.string.prox_finish_button, true, 28) {
                PcProximitySetup.send(LinkFrame.SETUP_DONE)
                show(Step.DONE)
            }
            Step.DONE -> {
                PcProximitySetup.complete(this)
                add(R.string.prox_done_button, true, 28) { finish() }
            }
            else -> Unit
        }
        for (i in (if (oldArt != null) 1 else 0) until body.childCount) {
            val c = body.getChildAt(i)
            c.alpha = 0f; c.translationY = 18 * dp
            c.animate().alpha(1f).translationY(0f).setStartDelay(40L + i * 45L).setDuration(320)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun titleOf(s: Step): Int = when (s) {
        Step.CHECK, Step.BLUETOOTH -> R.string.prox_bt_title
        Step.NEARBY -> R.string.prox_nearby_title
        Step.WAIT -> R.string.prox_wait_title
        Step.CONNECTED -> R.string.prox_connected_title
        Step.TEST -> R.string.prox_test_title
        Step.LOCKED -> R.string.prox_locked_title
        Step.DISTANCE -> R.string.prox_distance_title
        Step.DONE -> R.string.prox_done_title
    }

    private fun descOf(s: Step): Int = when (s) {
        Step.CHECK, Step.BLUETOOTH -> R.string.prox_bt_desc
        Step.NEARBY -> R.string.prox_nearby_desc
        Step.WAIT -> R.string.prox_wait_desc
        Step.CONNECTED -> R.string.prox_connected_desc
        Step.TEST -> R.string.prox_test_desc
        Step.LOCKED -> R.string.prox_locked_desc
        Step.DISTANCE -> R.string.prox_distance_desc
        Step.DONE -> R.string.prox_done_desc
    }

    private fun enableBluetooth() {
        for (action in listOf(BLUETOOTH_PAGE, Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)) {
            if (runCatching { startActivity(Intent(action)) }.isSuccess) return
        }
    }
}
