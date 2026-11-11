package pl.sinusoide.sinuschat.pclink

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import pl.sinusoide.sinuschat.LockManager
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.ScreenLockActivity
import pl.sinusoide.sinuschat.link.PairingCode
import pl.sinusoide.sinuschat.messenger.QrScanActivity
import pl.sinusoide.sinuschat.ui.ThemeUi
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.ThemeManager

class PcLinkActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }

    private val dp get() = resources.displayMetrics.density

    private var cBg = 0; private var cSurface = 0; private var cCard = 0
    private var cPrimary = 0; private var cSecondary = 0; private var cAccent = 0
    private var cGreen = 0; private var cRed = 0; private var cOrange = 0

    private lateinit var listCard: LinearLayout
    private lateinit var wifiWarning: TextView
    private lateinit var waitingRow: LinearLayout
    private lateinit var addButton: TextView
    private lateinit var addressBox: LinearLayout
    private lateinit var addressText: TextView
    private lateinit var batteryCard: LinearLayout
    private lateinit var lanCard: LinearLayout
    private val lanLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        PcLinkServer.retryLocalNetwork()
        listCard.postDelayed({
            if (!isFinishing && LocalNetworkAccess.blocked(this)) openAppSettings()
            refresh()
        }, 600L)
    }

    private var waitingFor: ByteArray? = null
    private val waitTimeout = Runnable { onPairingTimeout() }
    private val changed: () -> Unit = { refresh() }

    private var onAuthSuccess: (() -> Unit)? = null
    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = onAuthSuccess
        onAuthSuccess = null
        if (result.resultCode == RESULT_OK) action?.invoke()
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringExtra("qr_result")
        if (result.resultCode == RESULT_OK && text != null) onScanned(text)
    }

    private val nearbyLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        PcProximity.refresh(this)
    }

    private fun askNearby(i: Intent?) {
        if (i?.getBooleanExtra(PcProximity.EXTRA_ASK, false) != true) return
        i.removeExtra(PcProximity.EXTRA_ASK)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !PcProximity.hasPermission(this)) {
            nearbyLauncher.launch(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        askNearby(intent)
    }

    private fun attr(id: Int, fallback: Int): Int = ThemeUi.attrOrNull(this, id) ?: fallback

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        cBg = attr(R.attr.sinuschat_background, 0xFF0E1621.toInt())
        cSurface = attr(R.attr.sinuschat_surface, 0xFF17212B.toInt())
        cCard = attr(R.attr.sinuschat_surface_elevated, 0xFF1D2A38.toInt())
        cPrimary = attr(R.attr.sinuschat_text_primary, Color.WHITE)
        cSecondary = attr(R.attr.sinuschat_text_secondary, 0xFF7F91A4.toInt())
        cAccent = ThemeUi.accent(this)
        cGreen = ContextCompat.getColor(this, R.color.sinuschat_success)
        cRed = ContextCompat.getColor(this, R.color.sinuschat_error)
        cOrange = 0xFFE67E22.toInt()

        PcLinkServer.init(this)
        buildUi()
        askNearby(intent)
    }

    override fun onStart() {
        super.onStart()
        PcLinkServer.addListener(changed)
        PcLinkServer.resume()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (LocalNetworkAccess.applies()) PcLinkServer.retryLocalNetwork()
        if (ignoringBattery()) PcLinkService.update(this)
        refresh()
    }

    override fun onStop() {
        PcLinkServer.removeListener(changed)
        super.onStop()
    }

    override fun onDestroy() {
        listCard.removeCallbacks(waitTimeout)
        super.onDestroy()
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
            background = ContextCompat.getDrawable(this@PcLinkActivity, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        header.addView(TextView(this).apply {
            text = getString(R.string.pclink_title); textSize = 20f
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
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom); insets
        }

        content.addView(heroCard())

        wifiWarning = TextView(this).apply {
            text = getString(R.string.pclink_no_wifi)
            textSize = 13f; setTextColor(cOrange)
            setPadding((6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt(), 0)
            visibility = View.GONE
        }
        content.addView(wifiWarning)

        lanCard = localNetworkHint()
        content.addView(lanCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() })

        batteryCard = batteryHint()
        content.addView(batteryCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() })

        content.addView(sectionTitle(getString(R.string.pclink_section)))
        listCard = card()
        content.addView(listCard)

        setContentView(outer)
    }

    private fun heroCard(): View {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(cCard, 20f)
            setPadding((20 * dp).toInt(), (22 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
        }
        hero.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_qr_code)
            setColorFilter(cAccent)
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((cAccent and 0x00FFFFFF) or 0x26000000)
            }
        }, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply {
            bottomMargin = (12 * dp).toInt()
        })
        hero.addView(TextView(this).apply {
            text = getString(R.string.pclink_hero_title)
            textSize = 17f; setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        hero.addView(TextView(this).apply {
            text = getString(R.string.pclink_hero_desc)
            textSize = 13f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, (16 * dp).toInt())
        })
        addButton = TextView(this).apply {
            text = getString(R.string.pclink_add)
            textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(cAccent, 14f)
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            isClickable = true
            setOnClickListener { requireAuth { startScan() } }
        }
        hero.addView(addButton, LinearLayout.LayoutParams(-1, -2))
        hero.addView(TextView(this).apply {
            text = getString(R.string.pclink_add_hint)
            textSize = 12f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setPadding(0, (10 * dp).toInt(), 0, 0)
        })
        waitingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, 0)
            visibility = View.GONE
        }
        waitingRow.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(cAccent)
        }, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).apply { marginEnd = (10 * dp).toInt() })
        waitingRow.addView(TextView(this).apply {
            text = getString(R.string.pclink_waiting)
            textSize = 14f; setTextColor(cPrimary)
        })
        hero.addView(waitingRow)
        addressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (16 * dp).toInt(), 0, 0)
            visibility = View.GONE
        }
        addressText = TextView(this).apply {
            textSize = 14f; setTextColor(cPrimary); gravity = Gravity.CENTER
            setTextIsSelectable(true)
        }
        addressBox.addView(addressText)
        addressBox.addView(TextView(this).apply {
            text = getString(R.string.pclink_address_hint)
            textSize = 12f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        hero.addView(addressBox, LinearLayout.LayoutParams(-1, -2))
        return hero
    }

    private fun batteryHint(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 18f)
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            visibility = View.GONE
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(iconTile(R.drawable.ic_timer, cOrange))
        head.addView(TextView(this).apply {
            text = getString(R.string.pclink_battery_title)
            textSize = 15f; setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(head)
        box.addView(TextView(this).apply {
            text = getString(R.string.pclink_battery_desc)
            textSize = 13f; setTextColor(cSecondary)
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.pclink_battery_allow)
            textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(cAccent, 12f)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            isClickable = true
            setOnClickListener { askBatteryExemption() }
        }, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun localNetworkHint(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 18f)
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            visibility = View.GONE
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(iconTile(R.drawable.ic_nearby, cRed))
        head.addView(TextView(this).apply {
            text = getString(R.string.pclink_lan_title)
            textSize = 15f; setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(head)
        box.addView(TextView(this).apply {
            text = getString(R.string.pclink_lan_desc)
            textSize = 13f; setTextColor(cSecondary)
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.pclink_lan_allow)
            textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(cAccent, 12f)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            isClickable = true
            setOnClickListener { askLocalNetwork() }
        }, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun askLocalNetwork() {
        if (ContextCompat.checkSelfPermission(this, LocalNetworkAccess.PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            lanLauncher.launch(LocalNetworkAccess.PERMISSION)
        } else {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
        }
    }

    private fun ignoringBattery(): Boolean =
        getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    private fun askBatteryExemption() {
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun refresh() {
        if (isFinishing || isDestroyed) return
        wifiWarning.visibility = if (PcLinkServer.isOnWifi()) View.GONE else View.VISIBLE
        val address = PcLinkServer.listenAddress()
        addressBox.visibility = if (address != null) View.VISIBLE else View.GONE
        if (address != null) addressText.text = getString(R.string.pclink_address, address)
        val needsBattery = PcLinkStore.pcs(this).isNotEmpty() && !ignoringBattery()
        batteryCard.visibility = if (needsBattery) View.VISIBLE else View.GONE
        lanCard.visibility = if (LocalNetworkAccess.blocked(this)) View.VISIBLE else View.GONE

        val waiting = waitingFor
        if (waiting != null && PcLinkStore.find(this, waiting) != null) {
            waitingFor = null
            listCard.removeCallbacks(waitTimeout)
            PopupHelper.showSuccess(this, getString(R.string.pclink_paired))
        }
        waitingRow.visibility = if (waitingFor != null) View.VISIBLE else View.GONE
        addButton.isEnabled = waitingFor == null
        addButton.alpha = if (waitingFor == null) 1f else 0.5f

        listCard.removeAllViews()
        val pcs = PcLinkStore.pcs(this).sortedByDescending { it.lastSeen }
        if (pcs.isEmpty()) {
            listCard.addView(TextView(this).apply {
                text = getString(R.string.pclink_empty)
                textSize = 14f; setTextColor(cSecondary); gravity = Gravity.CENTER
                setPadding((16 * dp).toInt(), (22 * dp).toInt(), (16 * dp).toInt(), (22 * dp).toInt())
            })
            return
        }
        pcs.forEachIndexed { i, pc ->
            if (i > 0) listCard.addView(divider())
            listCard.addView(pcRow(pc))
        }
    }

    private fun pcRow(pc: PcLinkStore.Pc): View {
        val connected = PcLinkServer.isConnected(pc.key)
        val name = pc.name.ifBlank { getString(if (pc.isWatch) R.string.pclink_default_watch else R.string.pclink_default_name) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt())
        }
        row.addView(iconTile(if (pc.isWatch) R.drawable.ic_watch else R.drawable.ic_pc, if (connected) cGreen else cSecondary))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = name; textSize = 16f; setTextColor(cPrimary)
        })
        texts.addView(TextView(this).apply {
            text = if (connected) getString(R.string.pclink_connected)
            else getString(R.string.pclink_last_seen,
                DateUtils.getRelativeTimeSpanString(pc.lastSeen, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
            textSize = 13f; setTextColor(if (connected) cGreen else cSecondary)
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(cSecondary)
            val p = (9 * dp).toInt(); setPadding(p, p, p, p)
            background = ContextCompat.getDrawable(this@PcLinkActivity, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.pclink_remove)
            setOnClickListener { confirmRemove(pc, name) }
        }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
        return row
    }

    private fun confirmRemove(pc: PcLinkStore.Pc, name: String) {
        pl.sinusoide.sinuschat.ui.ConfirmSheet.show(this,
            icon = if (pc.isWatch) R.drawable.ic_watch else R.drawable.ic_pc,
            color = cRed,
            title = getString(R.string.pclink_remove_title),
            message = getString(R.string.pclink_remove_msg, name),
            confirm = getString(R.string.pclink_remove),
            confirmIcon = R.drawable.ic_close,
            onConfirm = { PcLinkServer.unpair(pc.key) })
    }

    private fun startScan() {
        scanLauncher.launch(Intent(this, QrScanActivity::class.java))
    }

    private fun onScanned(text: String) {
        val code = PairingCode.decode(text)
        if (code == null) {
            PopupHelper.showError(this, getString(R.string.pclink_bad_code))
            return
        }
        PcLinkStore.startPairing(code)
        waitingFor = code.pcKey
        PcLinkServer.refresh()
        listCard.removeCallbacks(waitTimeout)
        listCard.postDelayed(waitTimeout, PAIR_WAIT_MS)
        refresh()
    }

    private fun onPairingTimeout() {
        if (waitingFor == null) return
        waitingFor = null
        PcLinkStore.cancelPairing()
        PcLinkServer.refresh()
        PopupHelper.showError(this, getString(R.string.pclink_timeout))
        refresh()
    }

    private fun rounded(color: Int, radiusDp: Float) = ThemeUi.rounded(this, color, radiusDp)

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 18f)
        clipToOutline = true
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(ThemeUi.divider(context))
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

    private fun iconTile(icon: Int, color: Int) = ImageView(this).apply {
        setImageResource(icon); setColorFilter(color)
        background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor((color and 0x00FFFFFF) or 0x24000000)
        }
        val p = (9 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
            marginEnd = (14 * dp).toInt()
        }
    }

    private fun requireAuth(onSuccess: () -> Unit) {
        if (!LockManager.hasPin(this)) { onSuccess(); return }
        onAuthSuccess = onSuccess
        val askPin = {
            authLauncher.launch(Intent(this, ScreenLockActivity::class.java)
                .putExtra(ScreenLockActivity.EXTRA_MODE, ScreenLockActivity.MODE_CONFIRM))
        }
        if (LockManager.isBiometricEnabled(this) && LockManager.isBiometricAvailable(this)) {
            LockManager.showBiometricPrompt(this,
                onSuccess = { onAuthSuccess = null; onSuccess() },
                onFailed = { askPin() })
        } else {
            askPin()
        }
    }

    companion object {
        private const val PAIR_WAIT_MS = 90_000L
    }
}
