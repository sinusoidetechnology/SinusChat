package pl.sinusoide.sinuschat.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.webrtc.VideoTrack
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.ui.AuroraBackgroundView
import pl.sinusoide.sinuschat.ui.CallButtonView
import pl.sinusoide.sinuschat.ui.LockPalette
import pl.sinusoide.sinuschat.ui.PulseAvatarView
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.PopupHelper
import kotlin.math.abs
import kotlin.math.min

class GroupCallActivity : AppCompatActivity() {

    companion object {
        const val MODE_START = 0
        const val MODE_JOIN = 1
        const val MODE_INCOMING = 2
        const val MODE_RESUME = 3
        @Volatile var visibleGroup: String? = null
        const val ACTION_RING_STOPPED = "pl.sinusoide.sinuschat.GROUP_RING_STOPPED"
        private const val REQ_PERMS = 41
        private const val REQ_CAMERA = 42
        private const val MAX_TILES = 4

        fun intent(context: Context, groupId: String, kind: String, mode: Int): Intent =
            Intent(context, GroupCallActivity::class.java)
                .putExtra("groupId", groupId)
                .putExtra("kind", kind)
                .putExtra("mode", mode)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }

    private val dp get() = resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private lateinit var groupId: String
    private lateinit var kind: String
    private var mode = MODE_START
    private var session: GroupCallSession? = null
    private val names = HashMap<String, String>()
    private val renderers = HashMap<String, Pair<TextureVideoRenderer, VideoTrack>>()
    private var groupTitle = ""
    private var groupColor = "#5288C1"
    private var groupAvatarUrl: String? = null

    private lateinit var palette: LockPalette
    private lateinit var videoPalette: LockPalette
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var topBox: LinearLayout
    private lateinit var tvName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var statusPill: FrameLayout
    private lateinit var focusContainer: FrameLayout
    private lateinit var focusHost: FrameLayout
    private lateinit var focusChip: LinearLayout
    private lateinit var scrimTop: View
    private lateinit var scrimBottom: View
    private var focused: String? = null
    private var uiHidden = false
    private val glassButtons = mutableListOf<CallButtonView>()
    private val buttonLabels = mutableListOf<TextView>()
    private lateinit var stage: FrameLayout
    private lateinit var waitAvatar: PulseAvatarView
    private var waitShown = true
    private lateinit var grid: TileGridLayout
    private lateinit var selfFrame: FrameLayout
    private var selfShown = false
    private var selfCorner = 1
    private lateinit var controls: LinearLayout
    private lateinit var panel: FrameLayout
    private lateinit var panelRow: LinearLayout
    private lateinit var btnMic: CallButtonView
    private lateinit var btnCam: CallButtonView
    private lateinit var btnScreen: CallButtonView
    private lateinit var btnSpk: CallButtonView
    private lateinit var flipColumn: View
    private lateinit var endButton: CallButtonView
    private var compactCall = false
    private var camWasOn = false
    private var bottomInset = 0

    private class TileFrame(context: Context, val avatar: ImageView) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val d = resources.displayMetrics.density
            val size = (min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec)) * 0.42f).toInt()
                .coerceIn((40 * d).toInt(), (128 * d).toInt())
            avatar.layoutParams.width = size
            avatar.layoutParams.height = size
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private class Tile(val hash: String, val frame: TileFrame, val videoHost: FrameLayout, val avatar: ImageView, val label: TextView) {
        var avatarKey: String? = null
    }

    private class MoreTile(val frame: FrameLayout, val count: TextView, val faces: LinearLayout) {
        var faceKeys: List<String> = emptyList()
    }

    private val gridTiles = HashMap<String, Tile>()
    private var moreTile: MoreTile? = null
    private val sheetTiles = LinkedHashMap<String, Tile>()
    private var sheet: FrameLayout? = null
    private var sheetBox: LinearLayout? = null
    private var sheetRows: LinearLayout? = null
    private var sheetTitle: TextView? = null
    private var sheetOrder: List<String> = emptyList()
    private var pinned: String? = null
    private val videoSince = HashMap<String, Long>()
    private val screenSince = HashMap<String, Long>()
    private var incoming: FrameLayout? = null
    private var incomingBounce: Runnable? = null
    private var incomingAvatar: ImageView? = null

    private val screenCapture = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val s = session ?: return@registerForActivityResult
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        if (OngoingCallService.allowScreenCapture() && s.startScreenShare(data)) PopupHelper.show(this, getString(R.string.call_screen_on))
        else PopupHelper.showError(this, getString(R.string.call_screen_failed))
    }

    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { if (sheet != null) closeSheet() else exitFocus() }
    }

    private fun updateBack() { backCallback.isEnabled = sheet != null || focused != null }

    private val sessionListener: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            main.postDelayed(this, 1000)
        }
    }
    private val ringReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.getStringExtra("groupId") == groupId && mode == MODE_INCOMING && session == null) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        groupId = intent.getStringExtra("groupId").orEmpty()
        kind = intent.getStringExtra("kind") ?: GroupCallHub.KIND_AUDIO
        mode = intent.getIntExtra("mode", MODE_START)
        if (!groupId.startsWith("g_")) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (mode == MODE_INCOMING) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
            else @Suppress("DEPRECATION") window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        groupTitle = GroupCallRing.groupTitle(this, groupId)
        GroupCallRing.groupAvatar(this, groupId).let { (c, u) -> groupColor = c; groupAvatarUrl = u }
        onBackPressedDispatcher.addCallback(this, backCallback)
        buildUi()
        loadNames()
        ContextCompat.registerReceiver(this, ringReceiver, IntentFilter(ACTION_RING_STOPPED), ContextCompat.RECEIVER_NOT_EXPORTED)

        val existing = GroupCallSession.current
        if (existing != null && !existing.ended && existing.groupId != groupId) {
            PopupHelper.showError(this, getString(R.string.gcall_other_active))
            finish(); return
        }
        if (existing != null && existing.groupId == groupId) {
            kind = existing.kind
            attach(existing)
            return
        }
        when (mode) {
            MODE_INCOMING -> showIncoming()
            MODE_RESUME -> finish()
            else -> ensurePermissions()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val m = intent.getIntExtra("mode", MODE_START)
        if (session == null && m == MODE_JOIN && intent.getStringExtra("groupId") == groupId) {
            mode = MODE_JOIN
            ensurePermissions()
        }
    }

    private fun neededPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (kind == GroupCallHub.KIND_VIDEO) list += Manifest.permission.CAMERA
        return list.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun ensurePermissions() {
        val missing = neededPermissions()
        if (missing.isEmpty()) startSession() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) session?.toggleCamera()
            return
        }
        if (requestCode != REQ_PERMS) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSession()
        } else {
            PopupHelper.showError(this, getString(R.string.gcall_need_mic))
            finish()
        }
    }

    private var offlineShown = false

    private var idleBar: View? = null
    private var idleRing: pl.sinusoide.sinuschat.ui.CountdownRingView? = null
    private var idleShownAt = 0L

    override fun onResume() {
        super.onResume()
        if (::groupId.isInitialized) visibleGroup = groupId
        session?.let { if (it.idlePromptAt != 0L) GroupCallIdle.cancelNotice(this) }
    }

    override fun onPause() {
        if (::groupId.isInitialized && visibleGroup == groupId) visibleGroup = null
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) session?.noteActivity()
        return super.dispatchTouchEvent(ev)
    }

    private fun buildIdleBar(): View {
        val textColor = pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this)
        val ring = pl.sinusoide.sinuschat.ui.CountdownRingView(this)
        idleRing = ring
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(this@GroupCallActivity, R.attr.sinuschat_surface_variant)
                    ?: 0xFF162431.toInt())
            }
            elevation = 8 * dp
            visibility = View.GONE
            isClickable = true
        }
        bar.addView(FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()))
            addView(android.widget.ImageView(this@GroupCallActivity).apply {
                setImageResource(R.drawable.ic_timer)
                setColorFilter(textColor)
            }, FrameLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt(), Gravity.CENTER))
        }, LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()))
        bar.addView(TextView(this).apply {
            text = getString(R.string.gcall_idle_prompt)
            textSize = 15f
            setTextColor(textColor)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12 * dp).toInt() })
        bar.addView(TextView(this).apply {
            text = getString(R.string.gcall_idle_button)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.accent(this@GroupCallActivity))
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            val ripple = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { session?.confirmActive() }
        })
        column.addView(bar, column.indexOfChild(controls), LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = (12 * dp).toInt(); marginEnd = (12 * dp).toInt(); bottomMargin = (12 * dp).toInt()
        })
        return bar
    }

    private fun updateIdleBar(s: GroupCallSession) {
        val at = s.idlePromptAt
        if (at == 0L) {
            if (idleShownAt != 0L) {
                idleShownAt = 0L
                idleRing?.cancel()
                idleBar?.let { b -> b.animate().alpha(0f).setDuration(150L).withEndAction { b.visibility = View.GONE }.start() }
            }
            return
        }
        if (idleShownAt == at) return
        idleShownAt = at
        val bar = idleBar ?: buildIdleBar().also { idleBar = it }
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        bar.animate().alpha(1f).setDuration(150L).start()
        val left = (GroupCallSession.PROMPT_MS - (android.os.SystemClock.elapsedRealtime() - at)).coerceAtLeast(1000L)
        idleRing?.start(left) {}
        bar.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun showLeft(res: Int) {
        if (offlineShown || isFinishing || isDestroyed) return
        offlineShown = true
        idleRing?.cancel()
        idleBar?.visibility = View.GONE
        tvStatus.text = getString(res)
        PopupHelper.show(this, getString(res))
        main.postDelayed({ if (!isFinishing && !isDestroyed) finish() }, 3500)
    }

    private fun showOffline() {
        if (offlineShown || isFinishing || isDestroyed) return
        offlineShown = true
        GroupCallRing.stop(applicationContext, groupId)
        hideIncoming()
        val wifi = CallNetwork.onWifi(this)
        tvStatus.text = getString(if (wifi) R.string.call_no_net_wifi else R.string.call_no_net)
        PopupHelper.showError(this, getString(if (wifi) R.string.call_no_net_wifi_hint else R.string.call_no_net_hint))
        main.postDelayed({ if (!isFinishing && !isDestroyed) finish() }, 3500)
    }

    private fun startSession() {
        pl.sinusoide.sinuschat.util.SafeEngine.guardTor(this, pl.sinusoide.sinuschat.util.SafeEngine.Risk.TOR_CALL,
            onCancel = {
                GroupCallRing.stop(applicationContext, groupId)
                finish()
            }
        ) { if (!isFinishing && !isDestroyed && session == null) startSessionConfirmed() }
    }

    private fun startSessionConfirmed() {
        if (!CallNetwork.online(this)) { showOffline(); return }
        GroupCallRing.stop(applicationContext, groupId)
        hideIncoming()
        WalkieTalkie.session?.stop()
        val s = GroupCallSession.start(this, groupId, kind, groupTitle, starting = mode == MODE_START)
        attach(s)
    }

    private fun attach(s: GroupCallSession) {
        session = s
        s.addListener(sessionListener)
        main.removeCallbacks(ticker)
        main.post(ticker)
        if (controls.visibility != View.VISIBLE) {
            controls.visibility = View.VISIBLE
            controls.translationY = 60 * dp; controls.alpha = 0f
            controls.animate().translationY(0f).alpha(1f).setStartDelay(120).setDuration(480)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
        render()
    }

    private fun loadNames() {
        pl.sinusoide.sinuschat.messenger.GroupRepository.getGroupInfo(this, groupId) { info ->
            if (info == null || isFinishing || isDestroyed) return@getGroupInfo
            info.members.forEach { m -> if (m.displayName.isNotBlank() && !m.displayName.startsWith("p1:")) names[m.userHash] = m.displayName }
            if (info.name.isNotBlank()) groupTitle = info.name
            groupColor = info.avatarColor.ifBlank { groupColor }
            groupAvatarUrl = info.avatarUrl?.takeIf { it.startsWith("http") || it.startsWith("/") } ?: groupAvatarUrl
            tvName.text = groupTitle
            loadGroupPhoto(waitAvatar.imageView(), (160 * dp).toInt())
            incomingAvatar?.let { loadGroupPhoto(it, (150 * dp).toInt()) }
            gridTiles.values.forEach { it.avatarKey = null }
            sheetTiles.values.forEach { it.avatarKey = null }
            moreTile?.faceKeys = emptyList()
            render()
        }
    }

    private fun nameOf(hash: String): String =
        if (hash == session?.me) getString(R.string.you)
        else ContactCache.getNick(this, hash) ?: names[hash] ?: ContactCache.getName(this, hash) ?: getString(R.string.caller_unknown)

    private fun loadGroupPhoto(img: ImageView, size: Int) {
        AvatarHelper.loadGroupAvatar(this, img, groupTitle, groupColor, groupAvatarUrl, size, groupId)
    }

    private fun loadPersonPhoto(img: ImageView, hash: String, size: Int) {
        val letter = AvatarHelper.createLetterBitmap(AvatarHelper.initialOf(nameOf(hash)), AvatarHelper.getContactColor(this, hash), size)
        val url = ContactCache.getAvatarUrl(this, hash)?.takeIf { it.isNotEmpty() }
        if (url == null) {
            com.bumptech.glide.Glide.with(this).clear(img)
            img.setImageBitmap(letter)
        } else {
            com.bumptech.glide.Glide.with(this).load(url).circleCrop()
                .placeholder(android.graphics.drawable.BitmapDrawable(resources, letter))
                .error(android.graphics.drawable.BitmapDrawable(resources, letter))
                .into(img)
        }
    }

    private fun buildUi() {
        palette = LockPalette.of(this)
        videoPalette = LockPalette.glass(palette.accent)
        root = FrameLayout(this).apply { setBackgroundColor(palette.background) }
        root.addView(AuroraBackgroundView(this, palette), FrameLayout.LayoutParams(-1, -1))
        focusContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            setOnClickListener { setUiHidden(!uiHidden) }
        }
        focusHost = FrameLayout(this)
        focusContainer.addView(focusHost, FrameLayout.LayoutParams(-1, -1))
        root.addView(focusContainer, FrameLayout.LayoutParams(-1, -1))
        scrimTop = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0x00000000))
            visibility = View.GONE
        }
        root.addView(scrimTop, FrameLayout.LayoutParams(-1, (200 * dp).toInt(), Gravity.TOP))
        scrimBottom = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
            visibility = View.GONE
        }
        root.addView(scrimBottom, FrameLayout.LayoutParams(-1, (300 * dp).toInt(), Gravity.BOTTOM))
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
        }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        topBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        tvName = TextView(this).apply {
            text = groupTitle
            textSize = 28f
            setTextColor(palette.text)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            if (palette.dark) setShadowLayer(8f, 0f, 2f, 0x66000000)
        }
        topBox.addView(tvName, LinearLayout.LayoutParams(-1, -2).apply { marginStart = (24 * dp).toInt(); marginEnd = (24 * dp).toInt() })
        val pill = FrameLayout(this).apply {
            background = pillBackground(palette)
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
        }
        statusPill = pill
        tvStatus = TextView(this).apply {
            text = getString(R.string.gcall_connecting)
            textSize = 14f
            setTextColor(if (palette.flow) 0xE6FFFFFF.toInt() else palette.textSecondary)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        pill.addView(tvStatus)
        topBox.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (10 * dp).toInt() })
        focusChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBackground(videoPalette)
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            isClickable = true
            visibility = View.GONE
            setOnClickListener { exitFocus() }
            addView(ImageView(this@GroupCallActivity).apply {
                setImageResource(R.drawable.ic_group); setColorFilter(Color.WHITE)
            }, LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply { marginEnd = (6 * dp).toInt() })
            addView(TextView(this@GroupCallActivity).apply {
                text = getString(R.string.gcall_all)
                textSize = 14f; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }
        topBox.addView(focusChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (10 * dp).toInt() })
        column.addView(topBox, LinearLayout.LayoutParams(-1, -2))

        stage = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        column.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = (18 * dp).toInt(); bottomMargin = (18 * dp).toInt()
            marginStart = (12 * dp).toInt(); marginEnd = (12 * dp).toInt()
        })
        waitAvatar = PulseAvatarView(this, palette)
        stage.addView(waitAvatar, FrameLayout.LayoutParams((260 * dp).toInt(), (260 * dp).toInt(), Gravity.CENTER))
        loadGroupPhoto(waitAvatar.imageView(), (160 * dp).toInt())
        waitAvatar.scaleX = 0.7f; waitAvatar.scaleY = 0.7f; waitAvatar.alpha = 0f
        waitAvatar.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(600).setInterpolator(OvershootInterpolator(1.4f)).start()
        grid = TileGridLayout(this)
        stage.addView(grid, FrameLayout.LayoutParams(-1, -1))
        buildSelfFrame()
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) main.post { sizeWaitAvatar(); placeSelf(animate = false) }
        }

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false; clipToPadding = false
            visibility = View.INVISIBLE
        }
        column.addView(controls, LinearLayout.LayoutParams(-1, -2))
        endButton = CallButtonView(this, R.drawable.ic_call_end, 0xFFE5484D.toInt(), palette).apply {
            contentDescription = getString(R.string.call_btn_end)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                session?.leave() ?: finish()
            }
        }
        buildPanel()
        applyOrientation()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val gap = ((if (compactCall) 12 else 28) * dp).toInt()
            column.setPadding(bars.left, bars.top + gap, bars.right, bars.bottom + gap)
            bottomInset = bars.bottom
            sheetBox?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, (16 * dp).toInt() + bottomInset) }
            insets
        }
        setContentView(root)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.dark
            isAppearanceLightNavigationBars = !palette.dark
        }
    }

    private fun pillBackground(pal: LockPalette) = GradientDrawable().apply {
        cornerRadius = 16 * dp
        if (pal.flow) { setColor(0x1FFFFFFF); setStroke((1 * dp).toInt(), 0x33FFFFFF) }
        else { setColor(pal.fill); setStroke((1 * dp).toInt(), pal.rimTop) }
    }

    private fun panelBackground(pal: LockPalette) = GradientDrawable().apply {
        cornerRadius = 34 * dp
        if (pal.flow) { setColor(0x14FFFFFF); setStroke((1 * dp).toInt(), 0x29FFFFFF) }
        else { setColor(pal.fill); setStroke((1 * dp).toInt(), pal.rimTop) }
    }

    private fun applyChrome(overVideo: Boolean) {
        val pal = if (overVideo) videoPalette else palette
        glassButtons.forEach { it.palette = pal }
        endButton.palette = pal
        tvName.setTextColor(pal.text)
        if (pal.dark) tvName.setShadowLayer(8f, 0f, 2f, 0x66000000) else tvName.setShadowLayer(0f, 0f, 0f, 0)
        tvStatus.setTextColor(if (pal.flow) 0xE6FFFFFF.toInt() else pal.textSecondary)
        buttonLabels.forEach { it.setTextColor(pal.textSecondary) }
        statusPill.background = pillBackground(pal)
        panel.background = panelBackground(pal)
        scrimTop.visibility = if (overVideo) View.VISIBLE else View.GONE
        scrimBottom.visibility = if (overVideo) View.VISIBLE else View.GONE
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !pal.dark
            isAppearanceLightNavigationBars = !pal.dark
        }
    }

    private fun setUiHidden(hide: Boolean) {
        if (uiHidden == hide) return
        if (hide && focused == null) return
        uiHidden = hide
        listOf<View>(topBox, controls, scrimTop, scrimBottom).forEach { v ->
            v.animate().cancel()
            if (hide) v.animate().alpha(0f).setDuration(220).withEndAction { if (uiHidden) v.visibility = View.INVISIBLE }.start()
            else {
                if ((v !== scrimTop && v !== scrimBottom) || focused != null) v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(220).start()
            }
        }
    }

    private fun enterFocus(hash: String) {
        if (focused == hash) return
        val wasFocused = focused != null
        focused = hash
        if (sheet != null) closeSheet()
        updateBack()
        if (!wasFocused) {
            focusContainer.animate().cancel()
            focusContainer.visibility = View.VISIBLE
            focusContainer.alpha = 0f
            focusContainer.animate().alpha(1f).setDuration(260).start()
            grid.animate().cancel()
            grid.animate().alpha(0f).setDuration(200).withEndAction { if (focused != null) grid.visibility = View.INVISIBLE }.start()
            focusChip.visibility = View.VISIBLE
            applyChrome(true)
        }
        render()
    }

    private fun exitFocus(rerender: Boolean = true) {
        if (focused == null) return
        setUiHidden(false)
        focused = null
        updateBack()
        focusContainer.animate().cancel()
        focusContainer.animate().alpha(0f).setDuration(220).withEndAction {
            if (focused == null) { focusContainer.visibility = View.GONE; focusHost.removeAllViews() }
        }.start()
        grid.animate().cancel()
        grid.visibility = View.VISIBLE
        grid.animate().alpha(1f).setDuration(220).start()
        focusChip.visibility = View.GONE
        applyChrome(false)
        if (rerender) render()
    }

    private fun onTileTap(hash: String) {
        val p = session?.peers?.get(hash) ?: return
        if (p.videoOn && p.remoteVideo != null) enterFocus(hash)
    }

    private fun buttonSize(): Int =
        minOf(62 * dp, maxOf(52 * dp, (resources.displayMetrics.widthPixels - 52 * dp) / 5f - 6 * dp)).toInt()

    private fun buildPanel() {
        panel = FrameLayout(this).apply {
            clipChildren = false; clipToPadding = false
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            background = panelBackground(palette)
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength((24 * dp).toInt())
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        panelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false; clipToPadding = false
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        scroller.addView(panelRow, FrameLayout.LayoutParams(-2, -2))
        panel.addView(scroller, FrameLayout.LayoutParams(-1, -2))

        btnMic = CallButtonView(this, R.drawable.ic_mic, palette = palette).apply { setOnClickListener { session?.toggleMic() } }
        panelRow.addView(buttonColumn(btnMic, getString(R.string.call_btn_mic)), LinearLayout.LayoutParams(0, -2, 1f))
        btnCam = CallButtonView(this, R.drawable.ic_call_video_on, palette = palette).apply { setOnClickListener { onCamera() } }
        panelRow.addView(buttonColumn(btnCam, getString(R.string.call_btn_camera)), LinearLayout.LayoutParams(0, -2, 1f))
        btnScreen = CallButtonView(this, R.drawable.ic_screen_share, palette = palette).apply {
            setOnClickListener {
                val s = session ?: return@setOnClickListener
                if (s.sharing) s.stopScreenShare(toCamera = false) else requestScreenShare()
            }
        }
        panelRow.addView(buttonColumn(btnScreen, getString(R.string.call_btn_screen)), LinearLayout.LayoutParams(0, -2, 1f))
        val btnFlip = CallButtonView(this, R.drawable.ic_call_switch_camera, palette = palette).apply {
            setOnClickListener { session?.switchCamera() }
        }
        flipColumn = buttonColumn(btnFlip, getString(R.string.call_btn_flip)).apply { visibility = View.GONE }
        panelRow.addView(flipColumn, LinearLayout.LayoutParams(0, -2, 1f))
        btnSpk = CallButtonView(this, R.drawable.ic_call_speaker_off, palette = palette).apply { setOnClickListener { session?.toggleSpeaker() } }
        panelRow.addView(buttonColumn(btnSpk, getString(R.string.call_btn_speaker)), LinearLayout.LayoutParams(0, -2, 1f))

        controls.addView(panel, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = (16 * dp).toInt(); marginEnd = (16 * dp).toInt()
        })
    }

    private fun buttonColumn(button: CallButtonView, label: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        setPadding((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
        button.contentDescription = label
        glassButtons.add(button)
        val size = buttonSize()
        addView(button, LinearLayout.LayoutParams(size, size))
        addView(TextView(this@GroupCallActivity).apply {
            text = label
            textSize = 12f; setTextColor(palette.textSecondary); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            buttonLabels.add(this)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * dp).toInt() })
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::controls.isInitialized) return
        applyOrientation()
        if (incoming != null) showIncoming()
    }

    private fun applyOrientation() {
        val cfg = resources.configuration
        compactCall = cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenHeightDp in 1..479
        tvName.textSize = if (compactCall) 22f else 28f
        val size = buttonSize()
        for (i in 0 until panelRow.childCount) {
            val b = (panelRow.getChildAt(i) as? LinearLayout)?.getChildAt(0) ?: continue
            val lp = b.layoutParams
            if (lp.width != size) { lp.width = size; lp.height = size; b.layoutParams = lp }
        }
        val target: ViewGroup = if (compactCall) panelRow else controls
        if (endButton.parent !== target) {
            (endButton.parent as? ViewGroup)?.removeView(endButton)
            if (compactCall) {
                val size = (62 * dp).toInt()
                panelRow.addView(endButton, LinearLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = (6 * dp).toInt(); marginEnd = (4 * dp).toInt()
                })
            } else {
                controls.addView(endButton, LinearLayout.LayoutParams((80 * dp).toInt(), (80 * dp).toInt()).apply {
                    topMargin = (22 * dp).toInt()
                })
            }
        }
        val landscape = cfg.screenWidthDp > cfg.screenHeightDp
        (selfFrame.layoutParams as FrameLayout.LayoutParams).apply {
            width = ((if (landscape) 132 else 92) * dp).toInt()
            height = ((if (landscape) 92 else 132) * dp).toInt()
        }
        selfFrame.requestLayout()
        findViewById<View>(android.R.id.content)?.let { ViewCompat.requestApplyInsets(it) }
        ViewCompat.requestApplyInsets(root)
    }

    private fun sizeWaitAvatar() {
        val size = minOf((260 * dp).toInt(), stage.width, stage.height).coerceAtLeast((96 * dp).toInt())
        val lp = waitAvatar.layoutParams
        if (lp.width != size) { lp.width = size; lp.height = size; waitAvatar.layoutParams = lp }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildSelfFrame() {
        val corner = 22 * dp
        selfFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = corner; setColor(0xFF141B24.toInt()) }
            foreground = GradientDrawable().apply { cornerRadius = corner; setStroke((2 * dp).toInt(), 0x59FFFFFF) }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = 6 * dp
            visibility = View.GONE
        }
        stage.addView(selfFrame, FrameLayout.LayoutParams((92 * dp).toInt(), (132 * dp).toInt(), Gravity.TOP or Gravity.START))
        var offX = 0f; var offY = 0f; var downX = 0f; var downY = 0f; var dragging = false
        selfFrame.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    offX = v.translationX - e.rawX; offY = v.translationY - e.rawY
                    downX = e.rawX; downY = e.rawY; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(e.rawX - downX) + abs(e.rawY - downY) > 8 * dp) dragging = true
                    if (dragging) {
                        v.translationX = (e.rawX + offX).coerceIn(0f, (stage.width - v.width).toFloat().coerceAtLeast(0f))
                        v.translationY = (e.rawY + offY).coerceIn(0f, (stage.height - v.height).toFloat().coerceAtLeast(0f))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    val right = v.translationX + v.width / 2f > stage.width / 2f
                    val bottom = v.translationY + v.height / 2f > stage.height / 2f
                    selfCorner = (if (bottom) 2 else 0) + (if (right) 1 else 0)
                    placeSelf(animate = true)
                }
            }
            true
        }
    }

    private fun placeSelf(animate: Boolean) {
        val lp = selfFrame.layoutParams
        if (stage.width <= 0) return
        val pad = 10 * dp
        val x = if (selfCorner % 2 == 1) stage.width - lp.width - pad else pad
        val y = if (selfCorner >= 2) stage.height - lp.height - pad else pad
        if (animate) selfFrame.animate().translationX(x).translationY(y).setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        else { selfFrame.translationX = x; selfFrame.translationY = y }
    }

    private fun showIncoming() {
        incomingBounce?.let { main.removeCallbacks(it) }
        incoming?.let { root.removeView(it) }
        val state = GroupCallHub.state(groupId)
        val starter = state?.startedBy?.let { nameOf(it) } ?: getString(R.string.caller_unknown)
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val video = kind == GroupCallHub.KIND_VIDEO
        val initial = AvatarHelper.initialOf(groupTitle).takeIf { it != "?" } ?: "G"
        val letter = AvatarHelper.createLetterBitmap(initial, groupColor, (150 * dp).toInt())
        val built = IncomingCallScreen.build(
            this, main, locked, video,
            getString(if (video) R.string.gcall_type_video else R.string.gcall_type_audio),
            groupTitle,
            getString(if (video) R.string.gcall_incoming_video else R.string.gcall_incoming_audio, starter),
            letter,
            loadAvatar = { img -> incomingAvatar = img; loadGroupPhoto(img, (150 * dp).toInt()) },
            onAnswer = {
                mode = MODE_JOIN
                ensurePermissions()
            },
            onDecline = {
                GroupCallRing.stop(applicationContext, groupId)
                finish()
            })
        built.root.isClickable = true
        incoming = built.root
        incomingBounce = built.bounce
        root.addView(built.root, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.requestApplyInsets(root)
    }

    private fun hideIncoming() {
        val view = incoming ?: return
        incoming = null
        incomingAvatar = null
        incomingBounce?.let { main.removeCallbacks(it) }
        incomingBounce = null
        view.animate().alpha(0f).setDuration(260).withEndAction { root.removeView(view) }.start()
    }

    private fun updateStatus() {
        val s = session ?: return
        val others = s.members.count { it != s.me }
        val now = System.currentTimeMillis()
        tvStatus.text = when {
            s.ended -> getString(R.string.call_ended)
            others == 0 -> getString(R.string.gcall_waiting)
            s.connectedSince > 0L -> {
                val sec = (now - s.connectedSince) / 1000
                getString(R.string.gcall_status, String.format("%02d:%02d", sec / 60, sec % 60), s.members.size)
            }
            else -> getString(R.string.gcall_connecting)
        }
    }

    private fun onCamera() {
        val s = session ?: return
        if (!s.cameraOn && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        else s.toggleCamera()
    }

    private fun updateControls(s: GroupCallSession) {
        if (s.cameraOn) camWasOn = true
        btnMic.setIcon(if (s.micOn) R.drawable.ic_mic else R.drawable.ic_mic_off)
        btnMic.active = !s.micOn
        val camOffMarked = !s.cameraOn && (s.isVideo || camWasOn || s.sharing)
        btnCam.setIcon(if (camOffMarked) R.drawable.ic_call_video_off else R.drawable.ic_call_video_on)
        btnCam.active = camOffMarked
        btnScreen.active = s.sharing
        flipColumn.visibility = if (s.cameraOn && !s.sharing) View.VISIBLE else View.GONE
        btnSpk.setIcon(if (s.speakerOn) R.drawable.ic_call_speaker_on else R.drawable.ic_call_speaker_off)
        btnSpk.active = s.speakerOn
    }

    private fun trackPriority(s: GroupCallSession, others: List<String>) {
        val now = System.currentTimeMillis()
        for (h in others) {
            val p = s.peers[h]
            if (p != null && p.screenOn) screenSince.getOrPut(h) { now } else screenSince.remove(h)
            if (p != null && p.videoOn && !p.screenOn) videoSince.getOrPut(h) { now } else videoSince.remove(h)
        }
        screenSince.keys.retainAll(others.toSet())
        videoSince.keys.retainAll(others.toSet())
    }

    private fun rank(hash: String): Int = when {
        hash == pinned -> 0
        screenSince.containsKey(hash) -> 1
        videoSince.containsKey(hash) -> 2
        else -> 3
    }

    private fun orderPeople(others: List<String>): List<String> {
        val base = others.withIndex().associate { it.value to it.index }
        return others.sortedWith(compareBy<String>({ rank(it) }, { -(screenSince[it] ?: videoSince[it] ?: 0L) }, { base[it] ?: 0 }))
    }

    private fun render() {
        val s = session ?: return
        if (isDestroyed) return
        if (s.ended) {
            if (s.error == CallNetwork.OFFLINE) {
                s.removeListener(sessionListener)
                main.removeCallbacks(ticker)
                showOffline()
                return
            }
            if (s.error == GroupCallSession.LEFT_IDLE || s.error == GroupCallSession.LEFT_ALONE) {
                s.removeListener(sessionListener)
                main.removeCallbacks(ticker)
                showLeft(if (s.error == GroupCallSession.LEFT_IDLE) R.string.gcall_left_idle else R.string.gcall_left_alone)
                return
            }
            if (s.error == "group_call_forbidden") PopupHelper.showError(this, getString(R.string.group_cant_call))
            finish()
            return
        }
        updateStatus()
        updateControls(s)
        updateIdleBar(s)
        val others = s.members.filter { it != s.me }
        if (pinned != null && pinned !in others) pinned = null
        focused?.let { f ->
            val fp = s.peers[f]
            if (f !in others || fp == null || !fp.videoOn || fp.remoteVideo == null) exitFocus(rerender = false)
        }
        trackPriority(s, others)
        val ordered = orderPeople(others)
        val visible = if (ordered.size <= MAX_TILES) ordered else ordered.take(MAX_TILES - 1)
        val overflow = if (ordered.size <= MAX_TILES) emptyList() else ordered.drop(MAX_TILES - 1)

        for (gone in gridTiles.keys.filter { it !in visible }) gridTiles.remove(gone)?.let { detachVideo(it) }
        val views = ArrayList<View>()
        for (hash in visible) views += gridTiles.getOrPut(hash) { makeTile(hash) }.frame
        if (overflow.isNotEmpty()) {
            val m = moreTile ?: makeMoreTile().also { moreTile = it }
            updateMoreTile(m, overflow)
            views += m.frame
        } else moreTile = null
        grid.update(views)

        if (sheet != null) {
            if (overflow.isEmpty()) closeSheet() else updateSheet(overflow)
        }

        for (hash in visible) gridTiles[hash]?.let { bindTile(s, it) }
        sheetTiles.values.forEach { bindTile(s, it) }
        focused?.let { f -> bindFocus(s, f) }
        val shown = HashSet<String>(visible).apply { addAll(sheetTiles.keys); add(s.me); focused?.let { add(it) } }
        renderers.keys.filter { it !in shown }.forEach { releaseRenderer(it) }
        renderSelf(s)
        showWaitAvatar(others.isEmpty())
    }

    private fun showWaitAvatar(show: Boolean) {
        waitAvatar.ringing = show
        if (show == waitShown) return
        waitShown = show
        waitAvatar.animate().cancel()
        if (show) {
            waitAvatar.visibility = View.VISIBLE
            waitAvatar.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(OvershootInterpolator(1.2f)).start()
        } else {
            waitAvatar.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(250)
                .withEndAction { if (!waitShown) waitAvatar.visibility = View.GONE }.start()
        }
    }

    private fun tileBackground() = GradientDrawable().apply {
        cornerRadius = 24 * dp
        if (palette.flow) { setColor(0x17FFFFFF); setStroke((1 * dp).toInt(), 0x33FFFFFF) }
        else { setColor(palette.fill); setStroke((1 * dp).toInt(), palette.rimTop) }
    }

    private fun makeTile(hash: String): Tile {
        val avatar = ImageView(this)
        val frame = TileFrame(this, avatar).apply {
            background = tileBackground()
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val host = FrameLayout(this)
        frame.addView(host, FrameLayout.LayoutParams(-1, -1))
        frame.addView(avatar, FrameLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt(), Gravity.CENTER))
        val label = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            background = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(0x8C000000.toInt()) }
            setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
            compoundDrawablePadding = (4 * dp).toInt()
            gravity = Gravity.CENTER_VERTICAL
        }
        frame.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            val m = (8 * dp).toInt(); setMargins(m, m, m, m)
        })
        attachTileGestures(frame, hash)
        return Tile(hash, frame, host, avatar, label)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTileGestures(view: View, hash: String) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { togglePin(hash); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTileTap(hash); return true }
        })
        view.setOnTouchListener { _, e -> detector.onTouchEvent(e); true }
    }

    private fun togglePin(hash: String) {
        pinned = if (pinned == hash) null else hash
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        PopupHelper.show(this, getString(if (pinned == hash) R.string.gcall_pinned else R.string.gcall_unpinned, nameOf(hash)))
        if (pinned == hash && sheet != null) closeSheet()
        render()
    }

    private fun bindFocus(s: GroupCallSession, hash: String) {
        val peer = s.peers[hash] ?: return
        val track = peer.remoteVideo?.takeIf { peer.videoOn } ?: return
        val r = rendererFor(s, hash, track)
        r.setMirror(false)
        r.setFit(peer.screenOn)
        if (r.parent !== focusHost) {
            (r.parent as? ViewGroup)?.removeView(r)
            focusHost.removeAllViews()
            focusHost.addView(r, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun bindTile(s: GroupCallSession, tile: Tile) {
        val peer = s.peers[tile.hash]
        val track = peer?.remoteVideo?.takeIf { peer.videoOn && tile.hash != focused }
        if (track != null) {
            val r = rendererFor(s, tile.hash, track)
            r.setMirror(false)
            r.setFit(peer.screenOn)
            if (r.parent !== tile.videoHost) {
                (r.parent as? ViewGroup)?.removeView(r)
                tile.videoHost.removeAllViews()
                tile.videoHost.addView(r, FrameLayout.LayoutParams(-1, -1))
            }
            tile.avatar.visibility = View.GONE
        } else {
            if (tile.hash != focused) releaseRenderer(tile.hash)
            if (tile.videoHost.childCount > 0) tile.videoHost.removeAllViews()
            tile.avatar.visibility = View.VISIBLE
        }
        val name = nameOf(tile.hash)
        val key = name + "|" + ContactCache.getAvatarUrl(this, tile.hash)
        if (tile.avatarKey != key) {
            tile.avatarKey = key
            loadPersonPhoto(tile.avatar, tile.hash, (128 * dp).toInt())
        }
        tile.label.text = when {
            peer?.connected != true -> name + " · " + getString(R.string.gcall_connecting)
            peer.screenOn -> name + " · " + getString(R.string.gcall_sharing)
            else -> name
        }
        val pin = if (tile.hash == pinned) ContextCompat.getDrawable(this, R.drawable.ic_pin)?.mutate()?.apply {
            setTint(Color.WHITE)
            val sz = (14 * dp).toInt(); setBounds(0, 0, sz, sz)
        } else null
        tile.label.setCompoundDrawablesRelative(pin, null, null, null)
    }

    private fun detachVideo(tile: Tile) {
        tile.videoHost.removeAllViews()
        tile.avatar.visibility = View.VISIBLE
    }

    private fun rendererFor(s: GroupCallSession, hash: String, track: VideoTrack): TextureVideoRenderer {
        val existing = renderers[hash]
        if (existing != null && existing.second === track) return existing.first
        releaseRenderer(hash)
        return TextureVideoRenderer(this, "g_$hash").also { r ->
            r.init(s.eglBase.eglBaseContext)
            track.addSink(r)
            renderers[hash] = r to track
        }
    }

    private fun releaseRenderer(hash: String) {
        val (r, t) = renderers.remove(hash) ?: return
        try { t.removeSink(r) } catch (_: Exception) {}
        (r.parent as? ViewGroup)?.removeView(r)
        r.release()
    }

    private fun renderSelf(s: GroupCallSession) {
        val track = s.localVideo?.takeIf { s.cameraOn && !s.sharing }
        if (track != null) {
            val r = rendererFor(s, s.me, track)
            r.setMirror(s.frontCamera)
            r.setFit(false)
            if (r.parent !== selfFrame) {
                (r.parent as? ViewGroup)?.removeView(r)
                selfFrame.addView(r, FrameLayout.LayoutParams(-1, -1))
            }
            if (!selfShown) {
                selfShown = true
                selfFrame.visibility = View.VISIBLE
                placeSelf(animate = false)
                selfFrame.animate().cancel()
                selfFrame.scaleX = 0.6f; selfFrame.scaleY = 0.6f; selfFrame.alpha = 0f
                selfFrame.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420)
                    .setInterpolator(OvershootInterpolator(1.6f)).start()
            }
        } else {
            releaseRenderer(s.me)
            if (selfShown) {
                selfShown = false
                selfFrame.animate().cancel()
                selfFrame.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(200)
                    .withEndAction { if (!selfShown) selfFrame.visibility = View.GONE }.start()
            }
        }
    }

    private fun makeMoreTile(): MoreTile {
        val frame = FrameLayout(this).apply {
            background = tileBackground()
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, GradientDrawable().apply { cornerRadius = 24 * dp; setColor(Color.WHITE) })
            setOnClickListener { openSheet() }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val faces = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        box.addView(faces, LinearLayout.LayoutParams(-2, -2))
        val count = TextView(this).apply {
            textSize = 30f; setTextColor(palette.text); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        box.addView(count, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt() })
        box.addView(TextView(this).apply {
            text = getString(R.string.gcall_show_all)
            textSize = 12.5f; setTextColor(palette.textSecondary); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (2 * dp).toInt() })
        frame.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        return MoreTile(frame, count, faces)
    }

    private fun updateMoreTile(m: MoreTile, overflow: List<String>) {
        m.count.text = "+" + overflow.size
        val keys = overflow.take(3)
        if (keys == m.faceKeys) return
        m.faceKeys = keys
        m.faces.removeAllViews()
        val size = (30 * dp).toInt()
        keys.forEachIndexed { i, hash ->
            val img = ImageView(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (palette.flow) 0xFF1B2430.toInt() else palette.background) }
                val p = (2 * dp).toInt(); setPadding(p, p, p, p)
            }
            m.faces.addView(img, LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = (-9 * dp).toInt() })
            loadPersonPhoto(img, hash, size)
        }
    }

    private fun openSheet() {
        if (sheet != null || session == null) return
        val overlay = FrameLayout(this)
        val scrim = View(this).apply {
            setBackgroundColor(0x80000000.toInt())
            alpha = 0f
            setOnClickListener { closeSheet() }
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                val r = 28 * dp
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(if (palette.flow) 0xF2101820.toInt() else palette.background)
                setStroke((1 * dp).toInt(), if (palette.flow) 0x33FFFFFF else palette.rimTop)
            }
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt() + bottomInset)
        }
        box.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = 2 * dp; setColor(palette.textSecondary) }
            alpha = 0.5f
        }, LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val title = TextView(this).apply {
            textSize = 17f; setTextColor(palette.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        box.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })
        box.addView(TextView(this).apply {
            text = getString(R.string.gcall_pin_hint)
            textSize = 12.5f; setTextColor(palette.textSecondary)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (2 * dp).toInt(); bottomMargin = (12 * dp).toInt() })
        val maxH = (root.height * 0.62f).toInt()
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows, FrameLayout.LayoutParams(-1, -2))
        box.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        sheet = overlay
        sheetBox = box
        sheetRows = rows
        sheetTitle = title
        updateBack()
        render()
        box.visibility = View.INVISIBLE
        box.post {
            box.translationY = box.height.toFloat()
            box.visibility = View.VISIBLE
            box.animate().translationY(0f).setDuration(340).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
        scrim.animate().alpha(1f).setDuration(260).start()
    }

    private fun updateSheet(overflow: List<String>) {
        val rows = sheetRows ?: return
        sheetTitle?.text = getString(R.string.gcall_more_title, overflow.size)
        for (gone in sheetTiles.keys.filter { it !in overflow }) sheetTiles.remove(gone)?.let { detachVideo(it) }
        for (h in overflow) if (!sheetTiles.containsKey(h)) sheetTiles[h] = makeTile(h)
        if (overflow == sheetOrder) return
        sheetOrder = overflow.toList()
        sheetTiles.values.forEach { (it.frame.parent as? ViewGroup)?.removeView(it.frame) }
        rows.removeAllViews()
        val gap = (10 * dp).toInt()
        val cellW = (resources.displayMetrics.widthPixels - 32 * dp - gap) / 2f
        val cellH = (cellW * 1.1f).toInt()
        overflow.chunked(2).forEachIndexed { i, pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { j, h ->
                val frame = sheetTiles[h]?.frame ?: return@forEachIndexed
                row.addView(frame, LinearLayout.LayoutParams(0, cellH, 1f).apply { if (j == 1) marginStart = gap })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, cellH, 1f).apply { marginStart = gap })
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = gap })
        }
    }

    private fun closeSheet() {
        val overlay = sheet ?: return
        val box = sheetBox
        sheet = null
        sheetBox = null
        sheetRows = null
        sheetTitle = null
        sheetOrder = emptyList()
        updateBack()
        sheetTiles.keys.toList().forEach { releaseRenderer(it) }
        sheetTiles.values.forEach { detachVideo(it) }
        sheetTiles.clear()
        overlay.getChildAt(0)?.animate()?.alpha(0f)?.setDuration(220)?.start()
        if (box != null) box.animate().translationY(box.height.toFloat()).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).withEndAction { root.removeView(overlay) }.start()
        else root.removeView(overlay)
    }

    private fun requestScreenShare() {
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java) ?: return
        try { screenCapture.launch(mpm.createScreenCaptureIntent()) }
        catch (_: Exception) { PopupHelper.showError(this, getString(R.string.call_screen_failed)) }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        session?.removeListener(sessionListener)
        try { unregisterReceiver(ringReceiver) } catch (_: Exception) {}
        renderers.keys.toList().forEach { releaseRenderer(it) }
    }
}
