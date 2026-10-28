package pl.sinusoide.sinuschat.messenger

import pl.sinusoide.sinuschat.util.optStringOrNull

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.content.pm.PackageManager
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.ScreenLockActivity
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.FileUploader
import pl.sinusoide.sinuschat.util.MediaManager
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.FileProvider
import org.json.JSONObject
import pl.sinusoide.sinuschat.crypto.DbKeyManager
import pl.sinusoide.sinuschat.network.WebSocketManager
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.ui.DustEffect
import pl.sinusoide.sinuschat.util.BlockList
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.DisappearingCoordinator
import pl.sinusoide.sinuschat.util.DisappearingMessages
import pl.sinusoide.sinuschat.util.PrivacySettings
import pl.sinusoide.sinuschat.util.SecurityUtils
import pl.sinusoide.sinuschat.util.Translations
import pl.sinusoide.sinuschat.util.ThemeManager
import pl.sinusoide.sinuschat.util.ZoomableImageView
import java.text.SimpleDateFormat
import java.util.*

open class ChatActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }


    private lateinit var recycler: RecyclerView
    private lateinit var inputMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnAttach: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var toolbarName: TextView
    private lateinit var toolbarStatus: TextView
    private lateinit var toolbarAvatar: TextView
    private var toolbarAvatarImage: ImageView? = null
    private lateinit var btnBack: ImageButton
    private lateinit var btnCall: ImageButton
    private lateinit var btnWalkie: ImageButton
    private lateinit var btnVideo: ImageButton

    private lateinit var searchContainer: View
    private lateinit var searchInput: EditText
    private lateinit var btnSearchClose: ImageButton

    private lateinit var replyBar: View
    private lateinit var replyBarText: TextView
    private lateinit var attachPreviewScroll: HorizontalScrollView
    private lateinit var attachPreviewBar: LinearLayout

    private lateinit var linkPreviewDraft: View
    private lateinit var linkDraftImage: ImageView
    private lateinit var linkDraftTitle: TextView
    private lateinit var linkDraftDesc: TextView
    private lateinit var linkDraftHost: TextView
    private lateinit var linkDraftFavicon: ImageView
    private lateinit var btnCloseLinkPreview: ImageButton
    private var draftLinkData: pl.sinusoide.sinuschat.util.LinkPreviewHelper.PreviewData? = null
    private var isLinkPreviewClosedByUser = false

    private lateinit var undoBar: View
    private lateinit var undoRing: pl.sinusoide.sinuschat.ui.CountdownRingView
    private lateinit var undoButton: TextView
    private lateinit var undoText: TextView
    private lateinit var dustOverlay: pl.sinusoide.sinuschat.ui.DisintegrateView
    private var pendingDeleteIds: List<String> = emptyList()
    private val UNDO_MS = 5000L
    private lateinit var btnCancelReply: ImageButton

    private lateinit var chatBlockedBar: View
    private lateinit var chatRequestBar: View
    private lateinit var chatDisappearingBar: View
    private lateinit var chatInputContainer: View

    private lateinit var adapter: MessageAdapter

    private var conversationId: String = ""
    private var otherUserName: String = ""
    private var otherUserHash: String = ""
    private var myPhoneHash: String = ""
    private var sharedSecret: ByteArray? = null

    private var isShowingSend = false
    private var allMessages: List<MessageEntity> = emptyList()
    private var isSearching = false
    private var replyingTo: MessageEntity? = null
    private var lastPublishedReadMs = 0L
    private var disappearingTtlMs: Long = 0L
    private var disappearingTrigger: String = DisappearingMessages.TRIGGER_READ
    private var amIGroupAdmin: Boolean = false
    private var groupPerms = GroupPerms()
    private var groupReadOnlyBar: TextView? = null
    private var conversationChecked = false

    private var lastTypingSentMs = 0L

    private fun sendStopTyping() {
        if (pl.sinusoide.sinuschat.util.PrivacySettings.isTypingStatusDisabled(this@ChatActivity)) return
        lastTypingSentMs = 0L
        try {
            val stopMsg = org.json.JSONObject()
                .put("type", "typing_stop")
                .put("conversationId", conversationId)
            if (!GroupRepository.isGroup(conversationId)) {
                stopMsg.put("recipientHash", otherUserHash)
                if (pl.sinusoide.sinuschat.crypto.SealedSender.sendTyping(this, conversationId, otherUserHash, false)) return
            }
            pl.sinusoide.sinuschat.network.WebSocketManager.send(stopMsg)
        } catch (_: Exception) {}
    }
    private val typingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var typingBubbleView: android.view.ViewGroup? = null
    private var typingAvatarBox: android.widget.LinearLayout? = null
    private var sinewaveView: pl.sinusoide.sinuschat.util.SinewaveView? = null
    private val hideTypingRunnable = Runnable { hideTypingBubble() }

    private fun setupTypingBubble() {
        val dp = resources.displayMetrics.density
        val isDark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bubbleColor = if (isDark) "#2C2C2E" else "#E0E0E0"

        val content = window.decorView.rootView
            .findViewById<android.widget.FrameLayout>(android.R.id.content) ?: return

        val bubble = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((14*dp).toInt(), (10*dp).toInt(), (14*dp).toInt(), (10*dp).toInt())
            val bg = android.graphics.drawable.GradientDrawable()
            bg.setColor(android.graphics.Color.parseColor(bubbleColor))
            bg.cornerRadii = floatArrayOf(20*dp,20*dp, 20*dp,20*dp, 20*dp,20*dp, 6*dp,6*dp)
            background = bg
            visibility = android.view.View.GONE
            elevation = 6f * dp
        }

        val avatarBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = android.view.View.GONE
        }
        bubble.addView(avatarBox)
        typingAvatarBox = avatarBox

        val wave = pl.sinusoide.sinuschat.util.SinewaveView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (72*dp).toInt(), (26*dp).toInt()
            )
        }
        bubble.addView(wave)
        sinewaveView = wave

        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            leftMargin = (16*dp).toInt()
            bottomMargin = (80*dp).toInt()
        }
        content.addView(bubble, params)
        typingBubbleView = bubble

        content.viewTreeObserver.addOnGlobalLayoutListener {
            val contentLoc = IntArray(2)
            content.getLocationInWindow(contentLoc)
            val fromBottom = (content.height - (composerTopInWindow() - contentLoc[1])) + (10*dp).toInt()
            (bubble.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
                val newMargin = fromBottom.coerceAtLeast((60*dp).toInt())
                if (it.bottomMargin != newMargin) {
                    it.bottomMargin = newMargin
                    bubble.requestLayout()
                }
            }
        }
    }

    private var scrollDownWrap: FrameLayout? = null
    private var scrollDownBadge: TextView? = null
    private var scrollDownShown = false
    private var stickToBottom = true
    private var unseenWhileUp = 0
    private var lastNewestId: String? = null
    private var firstListShown = false

    private fun setupScrollDownButton() {
        val dp = resources.displayMetrics.density
        val content = window.decorView.rootView.findViewById<FrameLayout>(android.R.id.content) ?: return
        val size = (44 * dp).toInt()
        val wrap = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }
        val btn = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(pl.sinusoide.sinuschat.ui.ThemeUi.card(this@ChatActivity))
            }
            elevation = 6 * dp
            isClickable = true
            contentDescription = getString(R.string.scroll_to_latest)
            setOnClickListener { scrollToLatest() }
            addView(ImageView(this@ChatActivity).apply {
                setImageResource(R.drawable.ic_arrow_down)
                setColorFilter(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this@ChatActivity))
            }, FrameLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt(), Gravity.CENTER))
        }
        wrap.addView(btn, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (10 * dp).toInt()
        })
        val badge = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = (20 * dp).toInt()
            setPadding((5 * dp).toInt(), 0, (5 * dp).toInt(), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(pl.sinusoide.sinuschat.ui.ThemeUi.accent(this@ChatActivity))
            }
            elevation = 7 * dp
            visibility = View.GONE
        }
        wrap.addView(badge, FrameLayout.LayoutParams(-2, (20 * dp).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        content.addView(wrap, FrameLayout.LayoutParams(size, size + (10 * dp).toInt(), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = (14 * dp).toInt()
            bottomMargin = (80 * dp).toInt()
        })
        scrollDownWrap = wrap
        scrollDownBadge = badge

        content.viewTreeObserver.addOnGlobalLayoutListener {
            val contentLoc = IntArray(2)
            content.getLocationInWindow(contentLoc)
            val fromBottom = (content.height - (composerTopInWindow() - contentLoc[1])) + (12 * dp).toInt()
            (wrap.layoutParams as? FrameLayout.LayoutParams)?.let {
                val newMargin = fromBottom.coerceAtLeast((60 * dp).toInt())
                if (it.bottomMargin != newMargin) {
                    it.bottomMargin = newMargin
                    wrap.requestLayout()
                }
            }
        }
        recycler.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                updateStickToBottom()
            }
        })
    }

    private fun distanceFromBottom(): Int {
        if (!recycler.canScrollVertically(1)) return 0
        return (recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollOffset() -
            recycler.computeVerticalScrollExtent()).coerceAtLeast(0)
    }

    private var stickRecheckPending = false

    private fun updateStickToBottom() {
        if (isSearching) { showScrollDown(false); return }
        val animator = recycler.itemAnimator
        if (animator != null && recycler.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE &&
            animator.isRunning) {
            if (!stickRecheckPending) {
                stickRecheckPending = true
                animator.isRunning { recycler.post { stickRecheckPending = false; updateStickToBottom() } }
            }
            return
        }
        val near = distanceFromBottom() <= (160 * resources.displayMetrics.density).toInt()
        if (near) {
            stickToBottom = true
            if (unseenWhileUp != 0) { unseenWhileUp = 0; updateScrollDownBadge() }
        } else stickToBottom = false
        showScrollDown(!near)
    }

    private fun showScrollDown(show: Boolean) {
        val wrap = scrollDownWrap ?: return
        if (show == scrollDownShown) return
        scrollDownShown = show
        wrap.animate().cancel()
        if (show) {
            wrap.visibility = View.VISIBLE
            wrap.alpha = 0f; wrap.scaleX = 0.6f; wrap.scaleY = 0.6f
            wrap.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else {
            wrap.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(180)
                .withEndAction { if (!scrollDownShown) wrap.visibility = View.GONE }.start()
        }
    }

    private fun updateScrollDownBadge() {
        val badge = scrollDownBadge ?: return
        if (unseenWhileUp <= 0) { badge.visibility = View.GONE; return }
        badge.text = if (unseenWhileUp > 99) "99+" else unseenWhileUp.toString()
        badge.visibility = View.VISIBLE
    }

    private fun scrollToLatest() {
        val last = adapter.itemCount - 1
        if (last < 0) return
        val lm = recycler.layoutManager as? LinearLayoutManager
        val visible = lm?.findLastVisibleItemPosition() ?: last
        if (last - visible > 25) recycler.scrollToPosition(last - 12)
        recycler.post { recycler.smoothScrollToPosition(last) }
        unseenWhileUp = 0
        updateScrollDownBadge()
    }

    private fun composerTopInWindow(): Int {
        val loc = IntArray(2)
        inputMessage.getLocationInWindow(loc)
        var top = loc[1]
        val bars = mutableListOf<View?>(deletedBar)
        if (::undoBar.isInitialized) bars += undoBar
        if (::replyBar.isInitialized) bars += replyBar
        if (::attachPreviewScroll.isInitialized) bars += attachPreviewScroll
        if (::linkPreviewDraft.isInitialized) bars += linkPreviewDraft
        if (::chatBlockedBar.isInitialized) bars += chatBlockedBar
        if (::chatRequestBar.isInitialized) bars += chatRequestBar
        if (::chatDisappearingBar.isInitialized) bars += chatDisappearingBar
        if (::chatInputContainer.isInitialized) bars += chatInputContainer
        for (bar in bars) {
            if (bar == null || !bar.isShown || bar.height == 0) continue
            bar.getLocationInWindow(loc)
            if (loc[1] < top) top = loc[1]
        }
        return top
    }

    private val typingReserveDp = 56

    private fun setTypingSpaceReserved(reserved: Boolean) {
        val dp = resources.displayMetrics.density
        val extra = if (reserved) (typingReserveDp * dp).toInt() else 0
        if (typingExtra == extra && recycler.paddingBottom == basePaddingBottom + extra + flowInsetBottom) return
        typingExtra = extra
        recycler.clipToPadding = false
        applyRecyclerPadding()
        if (reserved && stickToBottom) {
            recycler.post {
                val last = (adapter.itemCount - 1)
                if (last >= 0) recycler.scrollToPosition(last)
            }
        }
    }

    private var basePaddingBottom = 0
    private var basePaddingTop = 0
    private var typingExtra = 0
    private var flowInsetTop = 0
    private var flowInsetBottom = 0

    private fun applyRecyclerPadding() {
        val top = basePaddingTop + flowInsetTop
        val bottom = basePaddingBottom + typingExtra + flowInsetBottom
        if (recycler.paddingTop == top && recycler.paddingBottom == bottom) return
        val atBottom = !recycler.canScrollVertically(1)
        recycler.setPadding(recycler.paddingLeft, top, recycler.paddingRight, bottom)
        if (atBottom && adapter.itemCount > 0) recycler.scrollToPosition(adapter.itemCount - 1)
    }

    private fun setupRetro() {
        if (!ThemeManager.isRetro(this)) return
        val retro = pl.sinusoide.sinuschat.ui.RetroUi
        findViewById<View>(R.id.status_bar_spacer).background = retro.statusArea()
        retro.styleTitleBar(findViewById(R.id.chat_toolbar))
        findViewById<View>(R.id.chat_input_container).background = retro.bottomBar(this)
        findViewById<View>(R.id.nav_bar_spacer).setBackgroundColor(retro.CHROME)
        findViewById<View>(R.id.chat_input).background = retro.sunken(this)
        findViewById<android.widget.ImageButton>(R.id.btn_send_message).apply {
            backgroundTintList = null
            background = retro.button(this@ChatActivity)
            imageTintList = android.content.res.ColorStateList.valueOf(retro.TITLE_MID)
            elevation = 0f
        }
        retro.lightStatusIcons(this)
    }

    private fun setupFlowGlass() {
        if (!ThemeManager.isFlow(this)) return
        val dp = resources.displayMetrics.density
        val chatRoot = findViewById<LinearLayout>(R.id.chat_root)
        val glass = pl.sinusoide.sinuschat.ui.FlowGlass

        fun floating(v: View, radiusDp: Float, vMargin: Float = 4f) {
            glass.glass(v, radiusDp, press = false)
            (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = (8 * dp).toInt(); it.marginEnd = (8 * dp).toInt()
                it.topMargin = (vMargin * dp).toInt(); it.bottomMargin = (vMargin * dp).toInt()
                v.layoutParams = it
            }
        }
        chatRoot.background = null
        findViewById<View>(R.id.status_bar_spacer).background = null
        findViewById<View>(R.id.nav_bar_spacer).background = null
        floating(chatToolbar, 30f)
        floating(selectionToolbar, 30f)
        floating(searchContainer, 26f)
        chatRoot.getChildAt(chatRoot.indexOfChild(selectionToolbar) + 1)
            ?.takeIf { it !is ViewGroup }?.visibility = View.GONE
        for (bar in listOf(replyBar, attachPreviewScroll, linkPreviewDraft, chatBlockedBar, chatRequestBar, chatDisappearingBar)) {
            floating(bar, 22f)
            ((bar as? ViewGroup)?.getChildAt(0))?.takeIf { it !is ViewGroup && it.layoutParams.height <= (2 * dp) }
                ?.visibility = View.GONE
        }
        chatInputContainer.background = null
        glass.glass(btnAttach, 22f, oval = true)
        glass.glass(inputMessage, 22f, press = false)

        if (!pl.sinusoide.sinuschat.ui.FlowLens.supported(this)) return
        val screen = chatRoot.parent as? FrameLayout ?: return
        basePaddingTop = recycler.paddingTop
        val slot = View(this)
        val index = chatRoot.indexOfChild(recycler)
        chatRoot.removeView(recycler)
        chatRoot.addView(slot, index, LinearLayout.LayoutParams(-1, 0, 1f))
        screen.addView(recycler, 0, FrameLayout.LayoutParams(-1, -1))
        chatRoot.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (isDestroyed) { chatRoot.viewTreeObserver.removeOnPreDrawListener(this); return true }
                if (slot.height == 0 && slot.top == 0) return true
                val top = chatRoot.top + slot.top
                val bottom = (screen.height - (chatRoot.top + slot.bottom)).coerceAtLeast(0)
                if (top == flowInsetTop && bottom == flowInsetBottom) return true
                flowInsetTop = top; flowInsetBottom = bottom
                applyRecyclerPadding()
                return false
            }
        })
        val L = pl.sinusoide.sinuschat.ui.FlowLens
        flowLens = L.attach(recycler, listOf(
            pl.sinusoide.sinuschat.ui.FlowLens.Lens(inputMessage, 22 * dp, magnify = 0.14f, blurDp = 0.8f),
            pl.sinusoide.sinuschat.ui.FlowLens.Lens(btnSend, magnify = 0.18f, blurDp = 0.5f),
            pl.sinusoide.sinuschat.ui.FlowLens.Lens(btnAttach, magnify = 0.18f, blurDp = 0.5f),
            pl.sinusoide.sinuschat.ui.FlowLens.Lens(chatToolbar, 30 * dp, magnify = 0.12f, blurDp = 0.8f),
            pl.sinusoide.sinuschat.ui.FlowLens.Lens(selectionToolbar, 30 * dp, magnify = 0.12f, blurDp = 0.8f),
            pl.sinusoide.sinuschat.ui.FlowLens.Lens(replyBar, 22 * dp, magnify = 0.12f, blurDp = 0.8f),
            pl.sinusoide.sinuschat.ui.FlowLens.Lens(searchContainer, 26 * dp, magnify = 0.12f, blurDp = 0.8f),
        ), topEdge = { (chatRoot.top + slot.top).toFloat() },
            bottomEdge = { (chatRoot.top + slot.bottom).toFloat() })
    }

    private var flowLens: pl.sinusoide.sinuschat.ui.FlowLens.Handle? = null

    private fun showTypingBubble() {
        typingHandler.removeCallbacks(hideTypingRunnable)
        typingBubbleView?.visibility = android.view.View.VISIBLE
        sinewaveView?.startWave()
        setTypingSpaceReserved(true)
    }

    private fun hideTypingBubble() {
        typingBubbleView?.visibility = android.view.View.GONE
        typingAvatarBox?.visibility = android.view.View.GONE
        sinewaveView?.stopWave()
        setTypingSpaceReserved(false)
    }

    private val typingSenders = LinkedHashMap<String, Long>()
    private val typingSenderNames = HashMap<String, String>()
    private val groupMemberNames = HashMap<String, String>()
    private val perSenderTimeout = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onTypingReceived(senderHash: String) {
        if (pl.sinusoide.sinuschat.util.BlockList.isBlocked(this, senderHash)) return
        typingSenders[senderHash] = System.currentTimeMillis()
        if (GroupRepository.isGroup(conversationId) && !typingSenderNames.containsKey(senderHash)) {
            typingSenderNames[senderHash] = groupMemberNames[senderHash] ?: "?"
        }
        refreshTypingUi()
        perSenderTimeout.postDelayed({ expireTypers() }, 4_000L)
    }

    private fun onTypingStopped(senderHash: String) {
        typingSenders.remove(senderHash)
        refreshTypingUi()
    }

    private fun expireTypers() {
        val now = System.currentTimeMillis()
        val it = typingSenders.entries.iterator()
        var changed = false
        while (it.hasNext()) {
            if (now - it.next().value > 4_000L) { it.remove(); changed = true }
        }
        if (changed) refreshTypingUi()
    }

    private fun refreshTypingUi() {
        if (typingSenders.isEmpty()) {
            hideTypingBubble()
            return
        }
        showTypingBubble()
        if (GroupRepository.isGroup(conversationId)) {
            updateTypingAvatars(typingSenders.keys.toList())
        }
    }

    private fun updateTypingAvatars(hashes: List<String>) {
        val box = typingAvatarBox ?: return
        box.removeAllViews()
        if (hashes.isEmpty()) { box.visibility = android.view.View.GONE; return }
        box.visibility = android.view.View.VISIBLE
        val dp = resources.displayMetrics.density
        val size = (24 * dp).toInt()

        for ((idx, hash) in hashes.take(3).withIndex()) {
            val name = groupMemberNames[hash] ?: "?"
            val img = android.widget.ImageView(this).apply {
                setImageBitmap(pl.sinusoide.sinuschat.util.AvatarHelper.createLetterBitmap(
                    pl.sinusoide.sinuschat.util.AvatarHelper.initialOf(name),
                    pl.sinusoide.sinuschat.util.AvatarHelper.colorForHash(hash), size))
            }
            box.addView(img, android.widget.LinearLayout.LayoutParams(size, size).apply {
                marginStart = if (idx == 0) 0 else (-6 * dp).toInt()
                marginEnd = (6 * dp).toInt()
            })
        }
        val extra = hashes.size - 3
        if (extra > 0) {
            box.addView(TextView(this).apply {
                text = "+$extra"; textSize = 12f
                setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(this@ChatActivity))
                setPadding((2*dp).toInt(), 0, (6*dp).toInt(), 0)
            })
        }
    }

    private fun loadGroupProfile() {
        GroupRepository.getGroupInfo(this, conversationId) { info ->
            if (info == null) return@getGroupInfo
            amIGroupAdmin = info.amIAdmin
            groupPerms = info.perms
            applyGroupPermissions()
            toolbarName.text = info.name
            otherUserName = info.name
            pl.sinusoide.sinuschat.util.ContactCache.saveName(this, conversationId, info.name)
            val namesBefore = HashMap(groupMemberNames)
            groupMemberNames.clear()
            info.members.forEach { groupMemberNames[it.userHash] = it.displayName }
            if (namesBefore != groupMemberNames && ::adapter.isInitialized) adapter.notifyDataSetChanged()
            val active = info.activeCount
            toolbarStatus.text = if (active > 0) getString(R.string.active_count, active) else getString(R.string.participants_count, info.members.size)
            toolbarStatus.setTextColor(
                if (active > 0) android.graphics.Color.parseColor("#2ECC71")
                else android.graphics.Color.parseColor("#9B9B9B"))
            toolbarStatus.visibility = View.VISIBLE
            toolbarAvatarImage?.let { img ->
                pl.sinusoide.sinuschat.util.AvatarHelper.loadGroupAvatar(this, img, info.name, info.avatarColor, info.avatarUrl, groupId = conversationId)
                img.visibility = View.VISIBLE
                toolbarAvatar.visibility = View.GONE
            }

            maybeDistributeGroupKey(info.members.map { it.userHash })
        }
    }

    private var lastKeyDistributionMs = 0L
    private var lastKnownMemberCount = -1
    private fun maybeDistributeGroupKey(memberHashes: List<String>) {
        val now = System.currentTimeMillis()

        val shrank = lastKnownMemberCount in 1 until memberHashes.size + 1 &&
                memberHashes.size < lastKnownMemberCount
        lastKnownMemberCount = memberHashes.size

        if (shrank) {
            lastKeyDistributionMs = now
            AppExecutor.io {
                pl.sinusoide.sinuschat.crypto.GroupKeyManager.markRotationNeeded(this, conversationId)
                pl.sinusoide.sinuschat.crypto.GroupKeyManager.ensureFreshKey(this, conversationId)
            }
            return
        }

        if (now - lastKeyDistributionMs < 5 * 60_000L) return
        lastKeyDistributionMs = now
        AppExecutor.io {
            pl.sinusoide.sinuschat.crypto.GroupKeyManager.distributeMyKey(
                this, conversationId, myPhoneHash, memberHashes)
        }
    }

    private fun sendGroupMessage() {
        val text = inputMessage.text.toString().trim()
        if (stagedAttachments.isNotEmpty() && takeCaption(text)) return
        if (text.isEmpty()) return
        inputMessage.text.clear()
        val reply = replyingTo
        cancelReply()
        sendGroupText(text, reply)
    }

    private fun sendGroupText(text: String, reply: MessageEntity?) {
        val tr = takeOutTranslation(text)
        val body = tr?.translated ?: text
        val replyId = reply?.id?.removeSuffix("_" + myPhoneHash.take(16))
        val replyText = reply?.let { ReplyLabel.of(this, it) }
        val replyJson = GroupRepository.sideJson(replyId, replyText,
            tr?.let { Translations.toJson(it.orig, it.from, it.to) })

        val msgId = java.util.UUID.randomUUID().toString()
        val now   = System.currentTimeMillis()

        val entity = pl.sinusoide.sinuschat.data.MessageEntity(
            id             = msgId,
            conversationId = conversationId,
            senderHash     = myPhoneHash,
            text           = body,
            timestampMs    = now,
            status         = pl.sinusoide.sinuschat.data.MessageEntity.STATUS_PENDING,
            type           = pl.sinusoide.sinuschat.data.MessageEntity.TYPE_TEXT,
            replyToId      = replyId,
            replyToText    = replyText,
            trOrig         = tr?.orig,
            trFrom         = tr?.from,
            trTo           = tr?.to
        )

        AppExecutor.local {
            pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(this).messageDao().insert(entity)
            MessageRepository.claim(msgId)
            AppExecutor.send { sendGroupTextClaimed(entity, body, replyJson) }
        }
    }

    private fun sendGroupTextClaimed(entity: pl.sinusoide.sinuschat.data.MessageEntity, text: String, replyJson: String?) {
        val msgId = entity.id
        val now = entity.timestampMs
        val db = pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(this)
        val sealed = pl.sinusoide.sinuschat.crypto.GroupKeyManager.sealForGroup(
            this, conversationId, text, "enc", replyJson)

        val payloadJson = org.json.JSONObject()
            .put("senderHash", myPhoneHash)
            .put("timestamp", now)
        if (sealed == null) {
            MessageRepository.release(msgId)
            db.messageDao().updateStatus(msgId, pl.sinusoide.sinuschat.data.MessageEntity.STATUS_FAILED)
            return
        }
        GroupRepository.merge(payloadJson, sealed)
        val payloadB64 = android.util.Base64.encodeToString(
            payloadJson.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

        val body = org.json.JSONObject().apply {
            put("conversationId", conversationId)
            put("messageId", msgId)
            put("kind", "msg")
            put("payloadB64", payloadB64)
        }

        val result = ApiClient.post("/api/message", body, this)
        MessageRepository.release(msgId)
        if (result == null) MessageRepository.scheduleRetry(this, myPhoneHash)
        val sentTs = GroupRepository.serverTime(result)
        sentTs?.let { db.messageDao().updateTimestamp(msgId, it) }
        val newStatus = if (result != null)
            pl.sinusoide.sinuschat.data.MessageEntity.STATUS_SENT
        else
            pl.sinusoide.sinuschat.data.MessageEntity.STATUS_PENDING

        db.messageDao().updateStatus(msgId, newStatus)

        val readTs = sentTs ?: now
        if (readTs > lastPublishedReadMs) {
            lastPublishedReadMs = readTs
            MessageRepository.publishRead(this, conversationId, myPhoneHash, readTs)
        }
    }

    private var screenCaptureCallbackRegistered = false

    private var otherLastHeartbeatMs = 0L
    private var otherLastSeenMs = 0L
    private var otherIsOnline = false
    private val statusRefreshHandler = Handler(Looper.getMainLooper())
    private val expirySweepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val expirySweepRunnable = object : Runnable {
        override fun run() {
            pl.sinusoide.sinuschat.util.AppExecutor.io {
                val removed = pl.sinusoide.sinuschat.util.DisappearingMessages
                    .sweepExpired(this@ChatActivity)
                if (removed > 0) {
                    pl.sinusoide.sinuschat.util.AppExecutor.main {
                        val now = System.currentTimeMillis()
                        val filtered = allMessages.filter { m ->
                            m.expiresAtMs == null || m.expiresAtMs > now
                        }
                        if (filtered.size != allMessages.size) {
                            allMessages = filtered
                            adapter.submitList(filtered)
                        }
                    }
                }
            }
            expirySweepHandler.postDelayed(this, 1000L)
        }
    }

    private var isOnline = true
    private val openProfile = View.OnClickListener {
        if (GroupRepository.isGroup(conversationId)) {
            startActivity(Intent(this, GroupProfileActivity::class.java).apply {
                putExtra("conversationId", conversationId)
                putExtra("groupName", otherUserName)
            })
        } else {
            startActivity(Intent(this, ContactProfileActivity::class.java).apply {
                putExtra("otherUserHash", otherUserHash)
                putExtra("otherUserName", otherUserName)
                putExtra("conversationId", conversationId)
            })
        }
    }
    private var otherChatPrivacyMode = false
    private lateinit var connectivityManager: android.net.ConnectivityManager
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            isOnline = true
            runOnUiThread { onNetworkChanged(true) }
        }
        override fun onLost(network: android.net.Network) {
            val up = networkUp()
            isOnline = up
            runOnUiThread { onNetworkChanged(up) }
        }
    }

    private fun networkUp(): Boolean {
        val cm = connectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Volatile private var statusFetching = false
    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            refreshSendButtonIcon()

            if (isContactCutOff()) {
                toolbarStatus.text = ""
                toolbarStatus.visibility = View.GONE
                statusRefreshHandler.postDelayed(this, 5_000L)
                return
            }
            if (otherUserHash.isNotEmpty() && !GroupRepository.isGroup(conversationId)) {
                if (!statusFetching) {
                    statusFetching = true
                    AppExecutor.io {
                        val user = try {
                            ApiClient.getObject("/api/user/$otherUserHash", this@ChatActivity)
                        } finally { statusFetching = false }
                        if (user == null) {
                            runOnUiThread { refreshOtherStatus() }
                            return@io
                        }
                        val online = user.optBoolean("is_online", false)
                        val hb = user.optLong("last_heartbeat_ms", 0)
                        val ls = user.optLong("last_seen_ms", 0)
                        runOnUiThread { updateOtherStatusUI(hb, ls, online) }
                    }
                }
            } else if (!GroupRepository.isGroup(conversationId)) {
                refreshOtherStatus()
            }
            statusRefreshHandler.postDelayed(this, 5_000L)
        }
    }

    companion object {
        private const val ONLINE_THRESHOLD_MS = 35_000L
        private const val DEFAULT_REACTION = "\u2764\uFE0F"
        private val HTML_EXT = setOf("html", "htm", "xhtml")

        @JvmStatic var pendingOpenSearch = false

        const val GROUP_SETTINGS_CHANGED = "pl.sinusoide.sinuschat.GROUP_SETTINGS_CHANGED"
        private const val REFRESH_CONVERSATIONS = "pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS"
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { addStagedAttachment(it, StagedKind.IMAGE) }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { addStagedAttachment(it, StagedKind.VIDEO) }
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { addStagedAttachment(it, StagedKind.FILE) }
    }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { addStagedAttachment(it, StagedKind.FILE) }
    }
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingAction?.invoke()
        pendingAction = null
    }
    private var pendingAction: (() -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val scrollToId = intent.getStringExtra("scrollToId")
        if (scrollToId != null) jumpAndFlash(scrollToId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (DbKeyManager.needsPinUnlock(this)) {
            ScreenLockActivity.start(this, ScreenLockActivity.MODE_UNLOCK)
            super.onCreate(null)
            finish()
            return
        }

        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_chat)
        if (pl.sinusoide.sinuschat.util.PrivacySettings.isCaptureBlockEnabled(this)) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        supportActionBar?.hide()

        val statusSpacer = findViewById<View>(R.id.status_bar_spacer)
        val navSpacer = findViewById<View>(R.id.nav_bar_spacer)
        val chatRoot = findViewById<View>(R.id.chat_root)

        ViewCompat.setOnApplyWindowInsetsListener(chatRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            val top = maxOf(systemBars.top, insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top)
            statusSpacer.layoutParams = statusSpacer.layoutParams.apply { height = top }

            val keyboardOpen = ime.bottom > systemBars.bottom
            if (keyboardOpen) {
                v.updatePadding(bottom = ime.bottom)
                navSpacer.layoutParams = navSpacer.layoutParams.apply { height = 0 }
            } else {
                v.updatePadding(bottom = 0)
                navSpacer.layoutParams = navSpacer.layoutParams.apply { height = systemBars.bottom }
            }
            WindowInsetsCompat.CONSUMED
        }

        conversationId = intent.getStringExtra("conversationId") ?: ""
        otherUserName = intent.getStringExtra("otherUserName") ?: "Nieznany"
        otherUserHash = intent.getStringExtra("otherUserHash") ?: ""
        myPhoneHash = SetupActivity.getUserPhoneHash(this)
        val isGroup = GroupRepository.isGroup(conversationId)
        translationBlocked = Translations.isBlocked(this, conversationId)
        Translations.addListener(translationChanged)

        initViews()
        setupRecycler()
        basePaddingBottom = recycler.paddingBottom
        basePaddingTop = recycler.paddingTop
        recycler.clipToPadding = false
        setupFlowGlass()
        setupRetro()

        if (isGroup) {
            loadGroupProfile()
            btnSend.isEnabled = true
            inputMessage.hint = getString(R.string.chat_input_group_hint)
        } else {
            fetchRealName()
            val cachedSecret = E2EManager.getSharedSecretCached(this, otherUserHash)
            if (cachedSecret != null) {
                sharedSecret = cachedSecret
                btnSend.isEnabled = true
                inputMessage.hint = getString(R.string.chat_input_hint)
            } else {
                btnSend.isEnabled = true
                inputMessage.hint = getString(R.string.connecting_server)
            }
            E2EManager.getSharedSecretForUser(this, otherUserHash) { secret ->
                if (secret != null) MessageRepository.retryPendingOutgoing(applicationContext, myPhoneHash)
                runOnUiThread {
                    if (secret != null) {
                        sharedSecret = secret
                        btnSend.isEnabled = true
                        inputMessage.hint = getString(R.string.chat_input_hint)
                    } else if (sharedSecret == null) {
                        inputMessage.hint = getString(R.string.no_e2e_key)
                    }
                }
            }
        }
        connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        isOnline = networkUp()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        if (!isOnline) onNetworkChanged(false)

        pendingDeleteIds = PendingDeletes.pendingIds(conversationId)
        observeMessages()
        loadDisappearingTtl()
        val expiryConv = conversationId
        AppExecutor.io { MessageRepository.refreshExpiryNotices(applicationContext, expiryConv) }
        
        val forwardedText = intent.getStringExtra("FORWARDED_TEXT")
        if (forwardedText != null) {
            inputMessage.setText(forwardedText)
        } else {
            pl.sinusoide.sinuschat.util.Drafts.get(this, conversationId)?.let {
                inputMessage.setText(it)
                inputMessage.setSelection(inputMessage.text.length)
            }
        }
        val forwardedUris = androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, "FORWARDED_URIS", Uri::class.java)
        forwardedUris?.forEach { uri ->
            val type = contentResolver.getType(uri) ?: ""
            val kind = when {
                type.startsWith("image/") -> StagedKind.IMAGE
                type.startsWith("video/") -> StagedKind.VIDEO
                else -> StagedKind.FILE
            }
            addStagedAttachment(uri, kind)
        }
        
        setupTypingBubble()
        setupScrollDownButton()
        MessageListenerService.triggerAckCheck(this)

        btnBack.setOnClickListener { finish() }
        btnAttach.setOnClickListener { showAttachBottomSheet() }
        btnMore.setOnClickListener { showPinnedMessages() }

        refreshSendButtonIcon()

        setupVoiceRecording()

        inputMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshSendButtonIcon()
                if (!s.isNullOrBlank()) {
                    val str = s.toString()
                    val url = pl.sinusoide.sinuschat.util.LinkPreviewHelper.findUrl(str)?.takeIf { pl.sinusoide.sinuschat.util.LinkPreviewHelper.isPreviewable(it) }
                    if (url != null && !isLinkPreviewClosedByUser) {
                        if (draftLinkData?.url != url) {
                            val cached = pl.sinusoide.sinuschat.util.LinkPreviewHelper.getCached(url)
                            when {
                                cached != null -> showDraftLinkPreview(cached)
                                pl.sinusoide.sinuschat.util.LinkPreviewHelper.hasNoPreview(this@ChatActivity, url) -> {
                                    linkPreviewDraft.visibility = View.GONE
                                    draftLinkData = null
                                }
                                pl.sinusoide.sinuschat.util.LinkPreviewHelper.canLoadWithoutAsking(this@ChatActivity, url) ->
                                    loadDraftLinkPreview(url)
                                else -> showDraftLinkPrompt(url)
                            }
                        }
                    } else {
                        linkPreviewDraft.visibility = View.GONE
                        draftLinkData = null
                        if (url == null) isLinkPreviewClosedByUser = false
                    }

                    val now = System.currentTimeMillis()
                    if (!pl.sinusoide.sinuschat.util.PrivacySettings.isTypingStatusDisabled(this@ChatActivity) &&
                        now - lastTypingSentMs > 2_000L) {
                        lastTypingSentMs = now
                        val typingMsg = org.json.JSONObject()
                            .put("type", "typing")
                            .put("conversationId", conversationId)
                        if (GroupRepository.isGroup(conversationId) ||
                            !pl.sinusoide.sinuschat.crypto.SealedSender.sendTyping(this@ChatActivity, conversationId, otherUserHash, true)) {
                            if (!GroupRepository.isGroup(conversationId)) typingMsg.put("recipientHash", otherUserHash)
                            pl.sinusoide.sinuschat.network.WebSocketManager.send(typingMsg)
                        }
                    }
                } else {
                    sendStopTyping()
                }
            }
        })

        btnSend.setOnClickListener {
            if (blockedFromSending()) return@setOnClickListener
            when {
                stagedAttachments.isNotEmpty() -> {
                    if (inputMessage.text?.isNotBlank() == true) sendMessage() else sendStagedAttachments()
                    refreshSendButtonIcon()
                }
                isShowingSend -> sendMessage()
                else -> PopupHelper.show(this, getString(R.string.voice_soon))
            }
        }

        btnCall.setOnClickListener { startCallOfType(false) }
        btnVideo.setOnClickListener { startCallOfType(true) }
        btnWalkie.setOnClickListener { openWalkie() }

        btnSearchClose.setOnClickListener { hideSearchBar() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterMessages(s?.toString() ?: "") }
        })

        btnCancelReply.setOnClickListener { cancelReply() }
    }

    private fun showDraftLinkPrompt(url: String) {
        draftLinkData = pl.sinusoide.sinuschat.util.LinkPreviewHelper.PreviewData(url, null, null, null, null)
        linkPreviewDraft.visibility = View.VISIBLE
        linkDraftTitle.text = getString(R.string.link_preview_tap)
        linkDraftDesc.text = ""
        linkDraftHost.text = pl.sinusoide.sinuschat.util.LinkPreviewHelper.hostOf(url)
        linkDraftFavicon.setImageDrawable(null)
        linkDraftImage.setImageResource(R.drawable.ic_link)
        draftRing?.visibility = View.GONE
        (linkDraftImage.parent as? View)?.visibility = View.VISIBLE
        linkPreviewDraft.setOnClickListener {
            pl.sinusoide.sinuschat.util.LinkPreviewHelper.askAndLoad(this, url, onStart = { showDraftLoading(url) }) { data ->
                onDraftLinkLoaded(url, data)
            }
        }
    }

    private var draftRing: pl.sinusoide.sinuschat.ui.SineProgressRing? = null

    private fun loadDraftLinkPreview(url: String) {
        showDraftLoading(url)
        pl.sinusoide.sinuschat.util.LinkPreviewHelper.fetchPreview(this, url) { data -> onDraftLinkLoaded(url, data) }
    }

    private fun onDraftLinkLoaded(url: String, data: pl.sinusoide.sinuschat.util.LinkPreviewHelper.PreviewData?) {
        if (isFinishing || isDestroyed) return
        if (!inputMessage.text.toString().contains(url) || draftLinkData?.url != url) return
        when {
            data != null -> showDraftLinkPreview(data)
            pl.sinusoide.sinuschat.util.LinkPreviewHelper.hasNoPreview(this, url) -> {
                draftRing?.visibility = View.GONE
                linkPreviewDraft.visibility = View.GONE
                draftLinkData = null
            }
            else -> showDraftFailed(url)
        }
    }

    private fun showDraftLoading(url: String) {
        if (isFinishing || isDestroyed) return
        draftLinkData = pl.sinusoide.sinuschat.util.LinkPreviewHelper.PreviewData(url, null, null, null, null)
        val card = linkPreviewDraft as? LinearLayout ?: return
        val ring = draftRing ?: pl.sinusoide.sinuschat.ui.SineProgressRing(this).apply {
            color = pl.sinusoide.sinuschat.ui.ThemeUi.accent(this@ChatActivity)
            disc = 0
            showCancel = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            progress = { null }
            val size = (48 * resources.displayMetrics.density).toInt()
            card.addView(this, 0, LinearLayout.LayoutParams(size, size))
        }.also { draftRing = it }
        ring.visibility = View.VISIBLE
        (linkDraftImage.parent as? View)?.visibility = View.GONE
        linkPreviewDraft.visibility = View.VISIBLE
        linkPreviewDraft.setOnClickListener(null)
        linkPreviewDraft.isClickable = false
        linkDraftTitle.text = getString(R.string.link_preview_loading)
        linkDraftDesc.text = ""
        linkDraftHost.text = pl.sinusoide.sinuschat.util.LinkPreviewHelper.hostOf(url)
        linkDraftFavicon.setImageDrawable(null)
    }

    private fun showDraftFailed(url: String) {
        draftRing?.visibility = View.GONE
        (linkDraftImage.parent as? View)?.visibility = View.VISIBLE
        linkDraftImage.setImageResource(R.drawable.ic_link)
        linkDraftTitle.text = getString(R.string.link_preview_failed)
        linkDraftDesc.text = getString(R.string.link_preview_retry)
        linkPreviewDraft.setOnClickListener { loadDraftLinkPreview(url) }
    }

    private fun showDraftLinkPreview(data: pl.sinusoide.sinuschat.util.LinkPreviewHelper.PreviewData) {
        if (isFinishing || isDestroyed) return
        draftRing?.visibility = View.GONE
        (linkDraftImage.parent as? View)?.visibility = View.VISIBLE
        draftLinkData = data
        linkPreviewDraft.setOnClickListener(null)
        linkPreviewDraft.isClickable = false
        linkPreviewDraft.visibility = View.VISIBLE
        linkDraftTitle.text = data.title
        linkDraftDesc.text = data.description
        linkDraftHost.text = Uri.parse(data.url).host

        if (data.icon != null) Glide.with(this).load(data.icon).into(linkDraftFavicon)
        else linkDraftFavicon.setImageDrawable(null)

        if (data.image != null) {
            Glide.with(this).load(data.image).centerCrop().into(linkDraftImage)
        } else {
            linkDraftImage.setImageResource(R.drawable.ic_link)
        }
    }

    private fun initViews() {
        dustOverlay = findViewById(R.id.dust_overlay)
        recycler = findViewById(R.id.chat_recycler)
        inputMessage = findViewById(R.id.chat_input)
        setupKeyboardMedia()
        btnSend = findViewById(R.id.btn_send_message)
        btnAttach = findViewById(R.id.btn_attach)
        btnMore = findViewById(R.id.btn_chat_more)
        toolbarName = findViewById(R.id.chat_toolbar_name)
        toolbarStatus = findViewById(R.id.chat_toolbar_status)
        toolbarAvatar = findViewById(R.id.chat_toolbar_avatar_letter)
        toolbarAvatarImage = findViewById(R.id.chat_toolbar_avatar_image)

        chatToolbar = findViewById(R.id.chat_toolbar)
        selectionToolbar = findViewById(R.id.selection_toolbar)
        selectionCount = findViewById(R.id.selection_count)
        btnSelectionReply = findViewById(R.id. btn_selection_reply)
        btnSelectionPin = findViewById(R.id.btn_selection_pin)
        btnSelectionForward = findViewById(R.id.btn_selection_forward)
        btnSelectionMore = findViewById(R.id.btn_selection_more)

        findViewById<ImageButton>(R.id.btn_selection_close).setOnClickListener { exitSelectionMode() }

        btnSelectionReply.setOnClickListener { replySelectedMessage() }
        btnSelectionPin.setOnClickListener { pinSelectedMessage() }
        btnSelectionDelete = ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete)
            imageTintList = android.content.res.ColorStateList.valueOf(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this@ChatActivity))
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            val p = (10 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
            contentDescription = getString(R.string.delete)
            setOnClickListener { deleteSelectedMessages() }
        }
        selectionToolbar.addView(btnSelectionDelete, selectionToolbar.indexOfChild(btnSelectionPin) + 1,
            LinearLayout.LayoutParams((44 * resources.displayMetrics.density).toInt(), (44 * resources.displayMetrics.density).toInt()))
        btnSelectionForward.setOnClickListener { forwardSelectedMessage() }
        btnSelectionMore.setOnClickListener { showSelectedMoreMenu() }
        btnSelectionCopy = selectionButton(R.drawable.ic_copy, R.string.copy_message) {
            val msg = adapter.selectedIds.firstOrNull()?.let { id -> allMessages.find { it.id == id } }
            exitSelectionMode()
            msg?.let { copyMessage(it) }
        }
        btnSelectionResend = selectionButton(R.drawable.ic_refresh, R.string.resend_message) { resendSelectedMessages() }
        val beforeMore = selectionToolbar.indexOfChild(btnSelectionMore)
        val selBtnSize = (44 * resources.displayMetrics.density).toInt()
        selectionToolbar.addView(btnSelectionCopy, beforeMore, LinearLayout.LayoutParams(selBtnSize, selBtnSize))
        selectionToolbar.addView(btnSelectionResend, beforeMore + 1, LinearLayout.LayoutParams(selBtnSize, selBtnSize))

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    ::selectionToolbar.isInitialized && selectionToolbar.visibility == View.VISIBLE -> {
                        exitSelectionMode()
                    }
                    ::searchContainer.isInitialized && isSearching -> {
                        hideSearchBar()
                    }
                    else -> finish()
                }
            }
        })
        toolbarAvatarImage?.clipToOutline = true
        btnBack = findViewById(R.id.btn_chat_back)
        btnCall = findViewById(R.id.btn_chat_call)
        btnVideo = findViewById(R.id.btn_chat_video)
        btnWalkie = findViewById(R.id.btn_chat_walkie)
        searchContainer = findViewById(R.id.search_container)
        searchInput = findViewById(R.id.search_input)
        btnSearchClose = findViewById(R.id.btn_search_close)
        replyBar = findViewById(R.id.reply_bar)
        attachPreviewScroll = findViewById(R.id.attach_preview_scroll)
        attachPreviewBar = findViewById(R.id.attach_preview_bar)
        undoBar = findViewById(R.id.undo_bar)
        undoRing = findViewById(R.id.undo_ring)
        undoButton = findViewById(R.id.undo_button)
        undoText = findViewById(R.id.undo_text)
        undoButton.setOnClickListener { undoDelete() }
        replyBarText = findViewById(R.id.reply_bar_text)
        btnCancelReply = findViewById(R.id.btn_cancel_reply)

        linkPreviewDraft = findViewById(R.id.link_preview_draft)
        linkDraftImage = findViewById(R.id.link_draft_image)
        linkDraftTitle = findViewById(R.id.link_draft_title)
        linkDraftDesc = findViewById(R.id.link_draft_desc)
        linkDraftHost = findViewById(R.id.link_draft_host)
        linkDraftFavicon = findViewById(R.id.link_draft_favicon)
        btnCloseLinkPreview = findViewById(R.id.btn_close_link_preview)

        btnCloseLinkPreview.setOnClickListener {
            isLinkPreviewClosedByUser = true
            linkPreviewDraft.visibility = View.GONE
            draftLinkData = null
        }

        chatBlockedBar = findViewById(R.id.chat_blocked_bar)
        chatRequestBar = findViewById(R.id.chat_request_bar)
        chatDisappearingBar = findViewById(R.id.chat_disappearing_bar)
        chatInputContainer = findViewById(R.id.chat_input_container)

        findViewById<View>(R.id.btn_block_delete).setOnClickListener { confirmDeleteConversation() }
        findViewById<View>(R.id.btn_block_unblock).setOnClickListener { unblockUser() }
        findViewById<View>(R.id.btn_request_block).setOnClickListener { blockFromRequest() }
        findViewById<View>(R.id.btn_request_accept).setOnClickListener { acceptConversation() }

        findViewById<View>(R.id.btn_disappearing_accept).setOnClickListener {
            DisappearingMessages.getPending(this, conversationId)?.let { (ttl, trigger, _) ->
                DisappearingMessages.clearPending(this, conversationId)
                handleDisappearingSettingsChange(ttl, trigger, true)
                updateInputVisibility(allMessages)
            }
        }
        findViewById<View>(R.id.btn_disappearing_decline).setOnClickListener {
            DisappearingMessages.getPending(this, conversationId)?.let { (ttl, trigger, _) ->
                DisappearingMessages.clearPending(this, conversationId)
                sendDisappearingSignal(DisappearingCoordinator.ACTION_DECLINE, ttl, trigger)
                insertSystemMessage(getString(R.string.disappearing_declined_sys_msg), "sys_disappearing_change")
                updateInputVisibility(allMessages)
            }
        }

        toolbarName.text = otherUserName

        if (!GroupRepository.isGroup(conversationId)) {
            val cachedColor    = pl.sinusoide.sinuschat.util.AvatarHelper.getContactColor(this, otherUserHash)
            val cachedAvatarUrl = pl.sinusoide.sinuschat.util.ContactCache.getAvatarUrl(this, otherUserHash)
            toolbarAvatar.visibility = View.GONE

            if (!cachedAvatarUrl.isNullOrEmpty() && toolbarAvatarImage != null) {
                toolbarAvatarImage!!.visibility = View.VISIBLE
                com.bumptech.glide.Glide.with(this).load(cachedAvatarUrl).circleCrop()
                    .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                        override fun onResourceReady(r: android.graphics.drawable.Drawable,
                                                     t: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?) {
                            toolbarAvatarImage!!.setImageDrawable(r)
                        }
                        override fun onLoadCleared(p: android.graphics.drawable.Drawable?) {}
                        override fun onLoadFailed(e: android.graphics.drawable.Drawable?) {
                            toolbarAvatarImage?.let {
                                if (it.drawable == null) pl.sinusoide.sinuschat.util.AvatarHelper.setLetterAvatar(this@ChatActivity, it, otherUserName, cachedColor)
                            }
                        }
                    })
            } else {
                toolbarAvatarImage?.let {
                    it.visibility = View.VISIBLE
                    pl.sinusoide.sinuschat.util.AvatarHelper.setLetterAvatar(this, it, otherUserName, cachedColor)
                }
            }
        } else {
            toolbarAvatar.visibility = View.GONE
            toolbarAvatarImage?.let {
                it.visibility = View.VISIBLE
                val groupColor = pl.sinusoide.sinuschat.util.AvatarHelper.getContactColor(this, conversationId)
                pl.sinusoide.sinuschat.util.AvatarHelper.loadGroupAvatar(this, it,
                    otherUserName.ifEmpty { pl.sinusoide.sinuschat.util.ContactCache.getName(this, conversationId) ?: "Grupa" },
                    groupColor,
                    pl.sinusoide.sinuschat.util.ContactCache.getAvatarUrl(this, conversationId),
                    groupId = conversationId)
            }
        }

        toolbarName.setOnClickListener(openProfile)
        toolbarAvatar.setOnClickListener(openProfile)
        
        toolbarAvatarImage?.setOnClickListener(openProfile)
        toolbarAvatarImage?.isClickable = true

        if (otherUserHash.isNotEmpty() && !GroupRepository.isGroup(conversationId)) {
            loadOtherUserProfile()
        }
    }

    private fun loadOtherUserProfile() {
        AppExecutor.io {
          try {
            val user = ApiClient.getObject("/api/user/$otherUserHash", this) ?: run {
                Log.e("ChatActivity", "Nie można pobrać profilu $otherUserHash")
                return@io
            }

            if (pl.sinusoide.sinuschat.util.BlockList.clearDeleted(this, otherUserHash)) runOnUiThread {
                refreshBlockedUi()
                showDeletedBar(false)
                chatInputContainer.visibility = View.VISIBLE
            }
            val name        = pl.sinusoide.sinuschat.crypto.ProfileCrypto.openName(this, otherUserHash, user.optStringOrNull("display_name"))
            val rawAvatar   = user.optStringOrNull("avatar_url")
            if (rawAvatar != null) pl.sinusoide.sinuschat.util.ContactCache.saveAvatarUrl(this, otherUserHash, rawAvatar)
            val avatarUrl   = pl.sinusoide.sinuschat.crypto.ProfileCrypto.displayAvatar(this, otherUserHash, rawAvatar)
            val avatarColor = user.optStringOrNull("avatar_color")
            val heartbeatMs = user.optLong("last_heartbeat_ms", 0)
            val lastSeenMs  = user.optLong("last_seen_ms", 0)
            val privacyOn   = user.optBoolean("chat_privacy_mode", false)

            if (!avatarColor.isNullOrEmpty()) {
                pl.sinusoide.sinuschat.util.AvatarHelper.setContactColor(this, otherUserHash, avatarColor)
            }

            val e2eKey = user.optStringOrNull("e2e_public_key")
            if (e2eKey != null) {
                val keyChanged = pl.sinusoide.sinuschat.util.PeerKeyCache.saveKey(this, otherUserHash, e2eKey)
                if (keyChanged) {
                    pl.sinusoide.sinuschat.util.PeerKeyCache.acknowledgeKeyChange(this, otherUserHash)
                }
                val computed = E2EManager.computeSharedSecret(this, e2eKey)
                if (computed != null) {
                    sharedSecret = computed
                    Log.d("ChatActivity", "Shared secret obliczony OK")
                } else {
                    Log.e("ChatActivity", "computeSharedSecret zwrócił null dla klucza: $e2eKey")
                }
            } else {
                Log.e("ChatActivity", "Brak e2e_public_key dla $otherUserHash")
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!name.isNullOrEmpty()) {
                    pl.sinusoide.sinuschat.util.ContactCache.saveName(this, otherUserHash, name)
                    val shown = pl.sinusoide.sinuschat.util.ContactCache.display(this, otherUserHash, name) ?: name
                    otherUserName = shown
                    toolbarName.text = shown
                    toolbarAvatar.text = pl.sinusoide.sinuschat.util.AvatarHelper.initialOf(shown)
                }
                if (!avatarUrl.isNullOrEmpty()) {
                    val avatarImg = toolbarAvatarImage
                    if (avatarImg != null) {
                        avatarImg.setOnClickListener(openProfile)
                        avatarImg.isClickable = true
                        
                        com.bumptech.glide.Glide.with(this@ChatActivity)
                            .load(avatarUrl)
                            .signature(com.bumptech.glide.signature.ObjectKey(avatarUrl))
                            .circleCrop()
                            .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                                override fun onResourceReady(r: android.graphics.drawable.Drawable,
                                                             t: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?) {
                                    avatarImg.setImageDrawable(r)
                                    avatarImg.visibility = View.VISIBLE
                                }
                                override fun onLoadCleared(p: android.graphics.drawable.Drawable?) {}
                                override fun onLoadFailed(e: android.graphics.drawable.Drawable?) {
                                    if (avatarImg.drawable == null) {
                                        pl.sinusoide.sinuschat.util.AvatarHelper.setLetterAvatar(
                                            this@ChatActivity, avatarImg, name ?: otherUserName,
                                            pl.sinusoide.sinuschat.util.AvatarHelper.getContactColor(this@ChatActivity, otherUserHash)
                                        )
                                    }
                                }
                            })
                    }
                } else {
                    toolbarAvatarImage?.setOnClickListener(openProfile)
                    toolbarAvatarImage?.isClickable = true

                    toolbarAvatarImage?.let { img ->
                        if (img.drawable == null) {
                            pl.sinusoide.sinuschat.util.AvatarHelper.setLetterAvatar(
                                this, img, name ?: otherUserName,
                                pl.sinusoide.sinuschat.util.AvatarHelper.getContactColor(this, otherUserHash)
                            )
                        }
                    }
                }
                otherLastHeartbeatMs = heartbeatMs
                otherLastSeenMs = lastSeenMs
                val online = user.optBoolean("is_online", false)
                updateOtherStatusUI(heartbeatMs, lastSeenMs, online)
                otherChatPrivacyMode = privacyOn
                if (isOnline) applyOtherPrivacyMode(privacyOn)
            }

            if (!e2eKey.isNullOrEmpty()) {
                if (SecurityUtils.hasKeyChanged(this, otherUserHash, e2eKey)) {
                    SecurityUtils.storeContactKey(this, otherUserHash, e2eKey)
                    insertKeyChangeSystemMessage()
                }
            }
          } catch (e: Exception) {
            Log.e("ChatActivity", "loadOtherUserProfile błąd (ignoruję): ${e.message}")
          }
        }
    }

    private fun fetchRealName() = loadOtherUserProfile()

    private fun formatLastSeen(ms: Long): String {
        if (ms == 0L) return ""
        return getString(R.string.last_seen_prefix) + " " + relativeTimeAgo(ms)
    }

    private fun relativeTimeAgo(ms: Long): String {
        val diff = pl.sinusoide.sinuschat.util.ServerTime.ageMs(ms)
        if (diff < 60_000) return getString(R.string.just_now)
        val minutes = (diff / 60_000).toInt()
        if (minutes < 60) return resources.getQuantityString(R.plurals.minutes_ago, minutes, minutes)
        val hours = (diff / 3_600_000).toInt()
        if (hours < 24) return resources.getQuantityString(R.plurals.hours_ago, hours, hours)
        val days = (diff / 86_400_000L).toInt()
        if (days < 30) return resources.getQuantityString(R.plurals.days_ago, days, days)
        val months = (days / 30)
        if (months < 12) return resources.getQuantityString(R.plurals.months_ago, months, months)
        val years = (days / 365).coerceAtLeast(1)
        return resources.getQuantityString(R.plurals.years_ago, years, years)
    }

    private fun onNetworkChanged(online: Boolean) {
        if (online) {
            applyOtherPrivacyMode(otherChatPrivacyMode)
            refreshOtherStatus()
        } else {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            if (!GroupRepository.isGroup(conversationId)) {
                refreshOtherStatus()
            }
        }
    }

    private fun refreshOtherStatus() {
        val stillOnline = otherIsOnline && otherLastHeartbeatMs > 0 &&
                pl.sinusoide.sinuschat.util.ServerTime.ageMs(otherLastHeartbeatMs) < ONLINE_THRESHOLD_MS
        updateOtherStatusUI(otherLastHeartbeatMs, otherLastSeenMs, stillOnline)
    }

    private fun updateOtherStatusUI(heartbeatMs: Long, lastSeenMs: Long, isOnlineServer: Boolean) {
        if (isFinishing || isDestroyed) return
        otherLastHeartbeatMs = heartbeatMs
        otherLastSeenMs = lastSeenMs
        otherIsOnline = isOnlineServer

        if (GroupRepository.isGroup(conversationId)) return

        if (isContactCutOff()) {
            toolbarStatus.text = ""
            toolbarStatus.visibility = View.GONE
            return
        }

        if (isOnlineServer) {
            toolbarStatus.text = getString(R.string.active_now)
            toolbarStatus.setTextColor(Color.parseColor("#2ECC71"))
            toolbarStatus.visibility = View.VISIBLE
        } else {
            val bestMs = maxOf(lastSeenMs, heartbeatMs)
            if (bestMs > 0) {
                toolbarStatus.text = formatLastSeen(bestMs)
                toolbarStatus.setTextColor(Color.parseColor("#9B9B9B"))
                toolbarStatus.visibility = View.VISIBLE
            } else {
                toolbarStatus.text = ""
            }
        }
    }

    override fun onDestroy() {
        PendingDeletes.setListener(conversationId, null)
        Translations.removeListener(translationChanged)
        super.onDestroy()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    private fun setupRecycler() {
        adapter = MessageAdapter(
            myPhoneHash = myPhoneHash,
            conversationId = conversationId,
            onImageClick = { bitmap -> showFullscreenBitmap(bitmap) },
            onAttachmentClick = { item ->
                if (item.viewOnce) {
                    when {
                        item.senderHash == myPhoneHash ->
                            PopupHelper.show(this, getString(R.string.view_once_sender_cant_open))
                        item.viewed ->
                            PopupHelper.show(this, getString(R.string.view_once_already_opened))
                        else -> openViewOnceMedia(item)
                    }
                } else if (item.fileId != null && item.fileKey != null) {
                    downloadAndOpenAttachment(item)
                }
            },
            onLongPress = { item, anchor ->
                if (reactionPopupMsgId == item.id && reactionPopup != null) {
                    reactionPopup?.dismiss()
                    exitSelectionMode()
                } else {
                    adapter.enterSelectionMode(item.id)
                    if (!isUndelivered(item)) showEmojiReactionPopup(item, anchor)
                }
            },
            onDoubleTap = { item ->
                if (!isUndelivered(item)) addReaction(item, DEFAULT_REACTION)
            }
        )
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter
        recycler.addItemDecoration(pl.sinusoide.sinuschat.ui.DayDividerDecoration(this) { pos ->
            adapter.currentList.getOrNull(pos)?.timestampMs
        })
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { recycler.post { recycler.invalidateItemDecorations() } }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { recycler.post { recycler.invalidateItemDecorations() } }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { recycler.post { recycler.invalidateItemDecorations() } }
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { recycler.post { recycler.invalidateItemDecorations() } }
        })

        adapter.onReplyQuoteClick = { replyToId -> jumpToMessage(replyToId) }
        adapter.senderName = { hash ->
            pl.sinusoide.sinuschat.util.ContactCache.getNick(this, hash)
                ?: groupMemberNames[hash]?.takeIf { it.isNotBlank() && !it.startsWith("p1:") }
                ?: pl.sinusoide.sinuschat.util.ContactCache.getName(this, hash)?.takeIf { !it.startsWith("p1:") }
        }
        adapter.onMediaOpen = { list, index -> showMediaViewer(list, index) }
        adapter.onAttachmentSave = { item -> saveAttachmentToDevice(item) }
        adapter.onUploadCancel = { item -> AttachmentSender.cancel(this, item) }
        adapter.onUploadRetry = { item -> sendTarget()?.let { AttachmentSender.retry(this, it, item) } }
        adapter.onMediaDownload = { item -> ensureLocal(item) { } }
        adapter.onCodeOpen = { item, file -> showCodeViewer(item, file) }
        AppExecutor.io { AttachmentSender.failStale(this) }
        adapter.onSwipeReply = { item -> setReplyingTo(item) }

        adapter.onSystemClick = { item ->
            if (item.fileId == "key_change") {
                openSafetyNumber()
            }
        }

        attachSwipeToReply()

        adapter.onMessageTapInSelection = { item ->
            if (reactionPopup != null) {
                reactionPopup?.dismiss()
                reactionPopup = null
                if (reactionPopupMsgId == item.id) {
                    exitSelectionMode()
                    true
                } else {
                    false
                }
            } else false
        }
        adapter.onSelectionChanged = { count ->
            if (count == 0) {
                hideSelectionBar()
            } else {
                enterSelectionMode()
                selectionCount.text = count.toString()

                val one = (count == 1)
                val chosen = adapter.selectedIds.mapNotNull { id -> allMessages.find { it.id == id } }
                val undelivered = chosen.isNotEmpty() && chosen.all { isUndelivered(it) }
                btnSelectionReply.visibility = if (one && !undelivered) View.VISIBLE else View.GONE
                btnSelectionPin.visibility = if (one && !undelivered) View.VISIBLE else View.GONE
                btnSelectionForward.visibility = View.VISIBLE
                btnSelectionMore.visibility = if (one && !undelivered) View.VISIBLE else View.GONE
                btnSelectionCopy.visibility =
                    if (one && undelivered && chosen.first().type == MessageEntity.TYPE_TEXT) View.VISIBLE else View.GONE
                btnSelectionResend.visibility = if (undelivered) View.VISIBLE else View.GONE
                if (one) {
                    val sel = adapter.selectedIds.firstOrNull()?.let { id -> allMessages.find { it.id == id } }
                    val pinned = sel?.pinned == true
                    btnSelectionPin.setImageResource(if (pinned) R.drawable.ic_pinoff else R.drawable.ic_pin)
                    btnSelectionPin.contentDescription = getString(if (pinned) R.string.unpin else R.string.pin)
                }
            }
        }
    }

    private lateinit var btnSelectionCopy: ImageButton
    private lateinit var btnSelectionResend: ImageButton

    private fun selectionButton(icon: Int, label: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this@ChatActivity))
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        val p = (10 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        contentDescription = getString(label)
        visibility = View.GONE
        setOnClickListener { onClick() }
    }

    private fun isUndelivered(m: MessageEntity) =
        m.senderHash == myPhoneHash && m.status == MessageEntity.STATUS_UNDELIVERED

    private fun resendSelectedMessages() {
        val chosen = withAlbums(adapter.selectedIds.mapNotNull { id -> allMessages.find { it.id == id } })
            .filter { isUndelivered(it) }.sortedBy { it.timestampMs }
        exitSelectionMode()
        if (chosen.isEmpty() || blockedFromSending()) return
        val group = GroupRepository.isGroup(conversationId)
        val secret = sharedSecret
        if (!group && secret == null) { PopupHelper.show(this, getString(R.string.loading_key_wait)); return }
        val target = sendTarget()
        val app = applicationContext
        AppExecutor.io {
            val sent = mutableListOf<MessageEntity>()
            var failed = 0
            for (m in chosen) {
                val ok = try {
                    when (m.type) {
                        MessageEntity.TYPE_TEXT -> {
                            if (group) sendGroupText(m.text, null)
                            else MessageRepository.sendText(app, conversationId, myPhoneHash, otherUserHash, secret!!, m.text)
                            true
                        }
                        MessageEntity.TYPE_ATTACHMENT -> resendAttachment(app, m, target)
                        MessageEntity.TYPE_VOICE -> !group && secret != null && resendVoice(app, m, secret)
                        else -> false
                    }
                } catch (e: Exception) { Log.w("ChatActivity", "Ponowne wysłanie: ${e.message}"); false }
                if (ok) sent += m else failed++
            }
            for (m in sent) MessageRepository.deleteWithMediaBlocking(app, m.id)
            MessageRepository.refreshExpiryNotices(app, conversationId)
            if (failed > 0) AppExecutor.main { PopupHelper.showError(this, getString(R.string.resend_failed)) }
        }
    }

    private fun resendAttachment(app: android.content.Context, m: MessageEntity, target: AttachmentSender.Target?): Boolean {
        val name = m.attachmentName ?: return false
        if (target == null || m.viewOnce) return false
        val source = MediaManager.getMediaFile(app, m.conversationId, m.id, name).takeIf { it.exists() && it.length() > 0 }
            ?: return false
        val dir = AttachmentSender.keyboardDir(app).apply { mkdirs() }
        val copy = java.io.File(dir, java.util.UUID.randomUUID().toString() + "_" + name)
        source.copyTo(copy, overwrite = true)
        val kind = when {
            MediaManager.isImage(name) -> AttachmentSender.Kind.IMAGE
            MediaManager.isVideo(name) -> AttachmentSender.Kind.VIDEO
            else -> AttachmentSender.Kind.FILE
        }
        AttachmentSender.send(app, target, listOf(AttachmentSender.Item(Uri.fromFile(copy), kind, viewOnce = false, keepOriginal = true)))
        return true
    }

    private fun resendVoice(app: android.content.Context, m: MessageEntity, secret: ByteArray): Boolean {
        val file = MediaManager.voiceCacheFile(app, m.id).takeIf { it.exists() && it.length() > 0 } ?: return false
        val seconds = m.attachmentName?.removeSuffix("s")?.toIntOrNull() ?: 1
        val up = pl.sinusoide.sinuschat.util.FileUploader.uploadVoiceBlocking(app, file) ?: return false
        MessageRepository.sendVoice(app, conversationId, myPhoneHash, otherUserHash, secret, up.first, up.second, seconds, file)
        return true
    }

    private fun enterSelectionMode() {
        selectionToolbar.visibility = View.VISIBLE
        chatToolbar.visibility = View.GONE
    }

    private fun hideSelectionBar() {
        moreMenu?.dismiss()
        moreMenu = null
        selectionToolbar.visibility = View.GONE
        chatToolbar.visibility = View.VISIBLE
    }

    private fun exitSelectionMode() {
        reactionPopup?.dismiss()
        reactionPopup = null
        hideSelectionBar()
        if (adapter.isSelectionMode) adapter.exitSelectionMode()
    }

    private lateinit var selectionToolbar: LinearLayout
    private lateinit var chatToolbar: LinearLayout
    private lateinit var selectionCount: TextView
    private lateinit var btnSelectionReply: ImageButton
    private lateinit var btnSelectionPin: ImageButton
    private lateinit var btnSelectionForward: ImageButton
    private lateinit var btnSelectionMore: ImageButton
    private lateinit var btnSelectionDelete: ImageButton

    private fun findViewHolderForMessageId(id: String): androidx.recyclerview.widget.RecyclerView.ViewHolder? {
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            val vh = recycler.getChildViewHolder(child)
            val pos = vh.bindingAdapterPosition
            val list = adapter.currentList
            if (pos >= 0 && pos < list.size && list[pos].id == id) return vh
        }
        return null
    }

    private fun showUndoBar(count: Int, durationMs: Long = UNDO_MS) {
        undoText.text = if (count > 1)
            getString(R.string.deleted_count, count) else getString(R.string.deleted)
        undoBar.visibility = View.VISIBLE
        undoBar.alpha = 0f
        undoBar.animate().alpha(1f).setDuration(150L).start()
        undoRing.start(durationMs) { commitPendingDelete() }
        PendingDeletes.setListener(conversationId) { if (!isFinishing && !isDestroyed) commitPendingDelete() }
    }

    private fun restorePendingDelete() {
        val ids = PendingDeletes.pendingIds(conversationId)
        if (ids.isEmpty()) {
            if (pendingDeleteIds.isNotEmpty()) commitPendingDelete()
            return
        }
        if (ids == pendingDeleteIds && undoBar.visibility == View.VISIBLE) return
        pendingDeleteIds = ids
        allMessages = allMessages.filter { it.id !in ids }
        lastNewestId = allMessages.lastOrNull()?.id
        if (::adapter.isInitialized) adapter.submitList(adapter.currentList.filter { it.id !in ids })
        showUndoBar(ids.size, PendingDeletes.remainingMs(conversationId).coerceAtLeast(300L))
    }

    private fun undoDelete() {
        undoRing.cancel()
        PendingDeletes.cancel(conversationId)
        PendingDeletes.setListener(conversationId, null)
        val idsToRestore = pendingDeleteIds.toList()
        pendingDeleteIds = emptyList()
        adapter.hiddenIds = idsToRestore.toSet()
        hideUndoBar()

        refreshMessagesList {
            lastNewestId = allMessages.lastOrNull()?.id
            if (stickToBottom && adapter.itemCount > 0) recycler.scrollToPosition(adapter.itemCount - 1)
            reviveRestored(idsToRestore)
        }
    }

    private fun reviveRestored(ids: List<String>, tries: Int = 0) {
        if (isFinishing || isDestroyed) return
        val settling = recycler.isLayoutRequested || recycler.hasPendingAdapterUpdates() ||
            recycler.itemAnimator?.isRunning == true
        if (settling && tries < 40) {
            recycler.postDelayed({ reviveRestored(ids, tries + 1) }, 16L)
            return
        }
        for (id in ids) {
            val vh = findViewHolderForMessageId(id) as? MessageAdapter.VH
            val target = vh?.let { it.bubble?.takeIf { b -> b.visibility == View.VISIBLE } ?: it.systemText?.parent as? View ?: it.itemView }
            val bmp = target?.let { DustEffect.capture(it) }
            if (target == null || bmp == null) { showRestored(id); continue }
            val loc = IntArray(2); target.getLocationOnScreen(loc)
            DustEffect.disintegrateManual(dustOverlay, bmp, loc[0].toFloat(), loc[1].toFloat(), true) {
                bmp.recycle()
                showRestored(id)
            }
        }
    }

    private fun showRestored(id: String) {
        adapter.hiddenIds = adapter.hiddenIds - id
        findViewHolderForMessageId(id)?.itemView?.visibility = View.VISIBLE
    }

    private val committedDeleteIds = HashSet<String>()

    private fun commitPendingDelete() {
        val ids = pendingDeleteIds
        committedDeleteIds.addAll(ids)
        pendingDeleteIds = emptyList()
        undoRing.cancel()
        hideUndoBar()
        PendingDeletes.setListener(conversationId, null)
        PendingDeletes.commitNow(conversationId)
    }

    private fun scheduleDelete(items: List<MessageEntity>) {
        val app = applicationContext
        val conv = conversationId
        val me = myPhoneHash
        val other = otherUserHash
        val isGroup = GroupRepository.isGroup(conv)
        val ids = items.map { it.id }
        val mine = items.filter { it.senderHash == me }.mapTo(HashSet()) { it.id }
        PendingDeletes.start(conv, ids, UNDO_MS) {
            AppExecutor.io {
                for (id in ids) {
                    val own = id in mine || MessageRepository.getByIdBlocking(app, id)?.senderHash == me
                    if (own) MessageRepository.deleteMessageEverywhere(app, conv, me, if (isGroup) "" else other, id)
                    else MessageRepository.deleteWithMediaBlocking(app, id)
                }
                AppExecutor.main {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        app.sendBroadcast(Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS").setPackage(app.packageName))
                    }, 400L)
                }
            }
        }
    }

    private val deletedCache = HashMap<String, MessageEntity>()
    private fun allMessagesById(ids: List<String>): List<MessageEntity> =
        ids.mapNotNull { id -> deletedCache[id] ?: allMessages.firstOrNull { it.id == id } }

    private fun refreshMessagesList(onDone: (() -> Unit)? = null) {
        val db = pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(this).messageDao()
        AppExecutor.io {
            val raw = db.getAllForConversationSync(conversationId).sortedBy { it.timestampMs }
            val shown = if (pendingDeleteIds.isEmpty() && committedDeleteIds.isEmpty()) raw
                        else raw.filter { it.id !in pendingDeleteIds && it.id !in committedDeleteIds }
            AppExecutor.main {
                allMessages = shown
                adapter.submitList(shown) {
                    onDone?.invoke()
                }
            }
        }
    }

    private fun hideUndoBar() {
        undoBar.animate().alpha(0f).setDuration(150L)
            .withEndAction { undoBar.visibility = View.GONE }.start()
    }

    private fun startCallOfType(isVideo: Boolean) {
        if (GroupRepository.isGroup(conversationId) && !canCallInGroup()) {
            PopupHelper.showError(this, getString(R.string.group_cant_call))
            return
        }
        if (GroupRepository.isGroup(conversationId)) {
            val running = pl.sinusoide.sinuschat.call.GroupCallHub.state(conversationId)
            val kind = running?.kind ?: if (isVideo) pl.sinusoide.sinuschat.call.GroupCallHub.KIND_VIDEO
                else pl.sinusoide.sinuschat.call.GroupCallHub.KIND_AUDIO
            val mode = if (running != null) pl.sinusoide.sinuschat.call.GroupCallActivity.MODE_JOIN
                else pl.sinusoide.sinuschat.call.GroupCallActivity.MODE_START
            startActivity(pl.sinusoide.sinuschat.call.GroupCallActivity.intent(this, conversationId, kind, mode))
            return
        }
        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (isVideo) perms.add(android.Manifest.permission.CAMERA)
        val missing = perms.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            pl.sinusoide.sinuschat.call.CallActivity.startAsCaller(
                this, conversationId, otherUserHash, otherUserName, isVideo
            )
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, missing.toTypedArray(), if (isVideo) 201 else 200
            )
        }
    }

    private fun openWalkie() {
        if (!canCallInGroup()) { PopupHelper.showError(this, getString(R.string.group_cant_call)); return }
        startActivity(pl.sinusoide.sinuschat.call.WalkieTalkieActivity.intent(this, conversationId))
    }

    private var callBar: LinearLayout? = null
    private var walkieBar: LinearLayout? = null
    private var walkieNearby = false
    private var walkieScanner: pl.sinusoide.sinuschat.call.WalkieTalkie.Scanner? = null
    private val groupCallListener: (String) -> Unit = { gid -> if (gid == conversationId) refreshCallBar() }

    private fun startCallWatch() {
        if (!GroupRepository.isGroup(conversationId)) return
        pl.sinusoide.sinuschat.call.GroupCallHub.addListener(groupCallListener)
        pl.sinusoide.sinuschat.call.GroupCallHub.query(this, conversationId)
        val keys = pl.sinusoide.sinuschat.call.WalkieTalkie.keys(this, conversationId)
        if (keys != null && walkieScanner == null) {
            walkieScanner = pl.sinusoide.sinuschat.call.WalkieTalkie.Scanner(this, mapOf(keys.tag to conversationId)) { found ->
                runOnUiThread { walkieNearby = conversationId in found; refreshCallBar() }
            }.also { it.start() }
        }
        refreshCallBar()
    }

    private fun stopCallWatch() {
        pl.sinusoide.sinuschat.call.GroupCallHub.removeListener(groupCallListener)
        walkieScanner?.stop()
        walkieScanner = null
        walkieNearby = false
    }

    private fun refreshCallBar() {
        if (!GroupRepository.isGroup(conversationId)) return
        val hub = pl.sinusoide.sinuschat.call.GroupCallHub
        val mine = pl.sinusoide.sinuschat.call.GroupCallSession.current?.takeIf { it.groupId == conversationId && !it.ended }
        val myWalkie = pl.sinusoide.sinuschat.call.WalkieTalkie.session?.takeIf { it.groupId == conversationId && !it.stopped }
        val inCall = pl.sinusoide.sinuschat.call.GroupCallSession.current?.ended == false ||
            pl.sinusoide.sinuschat.call.CallActivity.activeConversationId != null
        val state = hub.state(conversationId)
        data class Bar(val icon: Int, val title: String, val sub: String, val action: String, val go: () -> Unit)
        val call = when {
            mine != null -> Bar(if (mine.isVideo) R.drawable.ic_call_video_on else R.drawable.ic_call_answer,
                getString(if (mine.isVideo) R.string.gcall_bar_video else R.string.gcall_bar_audio),
                getString(R.string.gcall_bar_you_in), getString(R.string.gcall_back)) {
                startActivity(pl.sinusoide.sinuschat.call.GroupCallActivity.intent(this, conversationId, mine.kind,
                    pl.sinusoide.sinuschat.call.GroupCallActivity.MODE_RESUME))
            }
            state != null -> Bar(if (state.isVideo) R.drawable.ic_call_video_on else R.drawable.ic_call_answer,
                getString(if (state.isVideo) R.string.gcall_bar_video else R.string.gcall_bar_audio),
                resources.getQuantityString(R.plurals.gcall_bar_people, state.members.size, state.members.size),
                getString(R.string.gcall_join)) {
                startActivity(pl.sinusoide.sinuschat.call.GroupCallActivity.intent(this, conversationId, state.kind,
                    pl.sinusoide.sinuschat.call.GroupCallActivity.MODE_JOIN))
            }
            else -> null
        }
        val walkie = when {
            myWalkie != null -> Bar(R.drawable.ic_walkie_talkie, getString(R.string.gcall_bar_walkie),
                getString(R.string.gcall_bar_you_in), getString(R.string.gcall_back)) { openWalkie() }
            walkieNearby && !inCall -> Bar(R.drawable.ic_walkie_talkie, getString(R.string.gcall_bar_walkie),
                getString(R.string.walkie_local_only), getString(R.string.gcall_join)) { openWalkie() }
            else -> null
        }
        fun show(existing: LinearLayout?, bar: Bar?, before: View): LinearLayout? {
            if (bar == null) {
                existing?.visibility = View.GONE
                return existing
            }
            val view = existing ?: buildCallBar(before)
            view.visibility = View.VISIBLE
            (view.getChildAt(0) as ImageView).setImageResource(bar.icon)
            val texts = view.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = bar.title
            (texts.getChildAt(1) as TextView).text = bar.sub
            (view.getChildAt(2) as TextView).text = bar.action
            view.setOnClickListener { bar.go() }
            view.getChildAt(2).setOnClickListener { bar.go() }
            return view
        }
        walkieBar = show(walkieBar, walkie, searchContainer)
        callBar = show(callBar, call, walkieBar ?: searchContainer)
    }

    private fun buildCallBar(before: View): LinearLayout {
        val d = resources.displayMetrics.density
        val accent = pl.sinusoide.sinuschat.ui.ThemeUi.accent(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor((accent and 0x00FFFFFF) or 0x24000000)
            setPadding((14 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            isClickable = true
        }
        bar.addView(ImageView(this).apply { setColorFilter(accent) },
            LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()).apply { marginEnd = (12 * d).toInt() })
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ChatActivity).apply {
                textSize = 14.5f; setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this@ChatActivity))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@ChatActivity).apply {
                textSize = 12.5f; setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(this@ChatActivity))
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(TextView(this).apply {
            textSize = 14f; setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.onAccent(this@ChatActivity))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(accent) }
            setPadding((16 * d).toInt(), (7 * d).toInt(), (16 * d).toInt(), (7 * d).toInt())
        })
        val parent = searchContainer.parent as ViewGroup
        parent.addView(bar, parent.indexOfChild(before), LinearLayout.LayoutParams(-1, -2))
        return bar
    }

    private fun openSafetyNumber() {
        val intent = Intent(this, ContactProfileActivity::class.java).apply {
            putExtra("otherUserHash", otherUserHash)
            putExtra("otherUserName", otherUserName)
            putExtra("conversationId", conversationId)
            putExtra("openSafetyNumber", true)
        }
        startActivity(intent)
    }

    private var searchAnim: android.animation.Animator? = null
    private var searchToken = 0

    private fun searchPivotX(bar: View): Int =
        (bar.width - (28 * resources.displayMetrics.density).toInt()).coerceIn(0, bar.width)

    private fun showSearchBar() {
        isSearching = true
        val token = ++searchToken
        searchAnim?.cancel()
        showScrollDown(false)
        val bar = searchContainer
        bar.scaleX = 1f; bar.scaleY = 1f
        bar.alpha = 0f
        bar.visibility = View.VISIBLE
        btnSearchClose.animate().cancel()
        btnSearchClose.rotation = 180f; btnSearchClose.alpha = 0f
        bar.postOnAnimation {
            if (token != searchToken) return@postOnAnimation
            bar.postOnAnimation {
                if (token != searchToken) return@postOnAnimation
                val duration = startSearchOpen(bar)
                btnSearchClose.animate().rotation(0f).alpha(1f).setStartDelay(90).setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                searchInput.postDelayed({
                    if (token != searchToken) return@postDelayed
                    searchInput.requestFocus()
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }, (duration - 80L).coerceAtLeast(0L))
            }
        }
    }

    private fun startSearchOpen(bar: View): Long {
        if (!bar.isAttachedToWindow || bar.width == 0) {
            bar.alpha = 1f
            btnSearchClose.rotation = 0f; btnSearchClose.alpha = 1f
            return 0L
        }
        val cx = searchPivotX(bar)
        val cy = bar.height / 2
        val anim: android.animation.Animator
        val duration: Long
        if (ThemeManager.isFlow(this)) {
            duration = 440L
            bar.pivotX = cx.toFloat(); bar.pivotY = cy.toFloat()
            bar.scaleX = 0.12f; bar.scaleY = 0.9f
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                interpolator = android.view.animation.OvershootInterpolator(1.1f)
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    bar.scaleX = 0.12f + 0.88f * f
                    bar.scaleY = 0.9f + 0.1f * f
                    bar.alpha = f.coerceIn(0f, 1f)
                }
            }
        } else {
            duration = 340L
            bar.alpha = 1f
            anim = android.view.ViewAnimationUtils.createCircularReveal(bar, cx, cy, 0f,
                kotlin.math.hypot(bar.width.toDouble(), bar.height.toDouble()).toFloat()).apply {
                interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            }
        }
        anim.duration = duration
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (searchAnim === animation) searchAnim = null
            }
        })
        searchAnim = anim
        anim.start()
        return duration
    }

    private fun hideSearchBar(then: (() -> Unit)? = null) {
        if (!isSearching) return
        isSearching = false
        val token = ++searchToken
        searchAnim?.cancel()
        btnSearchClose.animate().cancel()
        btnSearchClose.rotation = 0f; btnSearchClose.alpha = 1f
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        val bar = searchContainer
        val lp = bar.layoutParams as ViewGroup.MarginLayoutParams
        val height = lp.height
        val topMargin = lp.topMargin
        val bottomMargin = lp.bottomMargin
        val startHeight = bar.height
        var done = false
        fun finish() {
            if (done) return
            done = true
            lp.height = height; lp.topMargin = topMargin; lp.bottomMargin = bottomMargin
            bar.layoutParams = lp
            bar.scaleX = 1f; bar.scaleY = 1f; bar.alpha = 1f
            if (token != searchToken || isSearching) return
            bar.visibility = View.GONE
            searchInput.clearFocus()
            searchInput.text.clear()
            adapter.submitList(allMessages) {
                if (then != null) then()
                else if (adapter.itemCount > 0) recycler.scrollToPosition(adapter.itemCount - 1)
                recycler.post { updateStickToBottom() }
            }
        }
        if (startHeight <= 0 || bar.width == 0 || !bar.isAttachedToWindow || bar.visibility != View.VISIBLE) { finish(); return }
        val cx = searchPivotX(bar)
        val cy = startHeight / 2
        val visual: android.animation.Animator
        val duration: Long
        if (ThemeManager.isFlow(this)) {
            duration = 260L
            bar.pivotX = cx.toFloat(); bar.pivotY = cy.toFloat()
            val fromAlpha = bar.alpha
            visual = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    bar.scaleX = 0.12f + 0.88f * f
                    bar.scaleY = 0.9f + 0.1f * f
                    bar.alpha = fromAlpha * f
                }
            }
        } else {
            duration = 280L
            bar.alpha = 1f
            visual = android.view.ViewAnimationUtils.createCircularReveal(bar, cx, cy,
                kotlin.math.hypot(bar.width.toDouble(), startHeight.toDouble()).toFloat(), 0f)
        }
        visual.duration = duration
        visual.interpolator = android.view.animation.AccelerateInterpolator(1.2f)
        visual.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (token == searchToken && !isSearching) bar.visibility = View.INVISIBLE
            }
        })
        val collapse = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            startDelay = (duration * 0.7f).toLong()
            this.duration = 200L
            interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                lp.height = (startHeight * f).toInt()
                lp.topMargin = (topMargin * f).toInt(); lp.bottomMargin = (bottomMargin * f).toInt()
                bar.layoutParams = lp
            }
        }
        val set = android.animation.AnimatorSet().apply {
            playTogether(visual, collapse)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (searchAnim === animation) searchAnim = null
                    finish()
                }
            })
        }
        searchAnim = set
        set.start()
    }

    private fun filterMessages(query: String) {
        if (query.isBlank()) {
            adapter.submitList(allMessages)
            return
        }
        val filtered = allMessages.filter { it.text.contains(query, ignoreCase = true) }
        adapter.submitList(filtered)
    }

    private fun showPinnedMessages() {
        val pinned = allMessages.filter { it.pinned }
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pl.sinusoide.sinuschat.ui.ThemeUi.sheet(this@ChatActivity))
            setPadding(0, 8, 0, 32)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.pinned_messages)
            setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this@ChatActivity))
            textSize = 16f
            textStyle(this)
            setPadding(48, 32, 48, 24)
        })
        if (pinned.isEmpty()) {
            root.addView(TextView(this).apply {
                text = getString(R.string.no_pinned)
                setTextColor(Color.parseColor("#9B9B9B"))
                textSize = 14f
                setPadding(48, 0, 48, 0)
            })
        } else {
            pinned.forEach { msg ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 16, 48, 16)
                    setBackgroundResource(R.drawable.bg_menu_ripple)
                    setOnClickListener {
                        jumpAndFlash(msg.id)
                        sheet.window?.setWindowAnimations(0)
                        sheet.dismiss()
                    }
                }
                row.addView(TextView(this).apply {
                    text = msg.text.take(120)
                    setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this@ChatActivity))
                    textSize = 14f
                })
                row.addView(TextView(this).apply {
                    text = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(msg.timestampMs))
                    setTextColor(Color.parseColor("#9B9B9B"))
                    textSize = 12f
                })
                root.addView(row)
            }
        }
        sheet.setContentView(root)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private var reactionPopup: PopupWindow? = null
    private var reactionPopupMsgId: String? = null

    private fun showEmojiReactionPopup(item: MessageEntity, anchor: View) {
        val dp = resources.displayMetrics.density

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            background = ContextCompat.getDrawable(this@ChatActivity, R.drawable.bg_reaction_popup)
            elevation = 8 * dp
        }
        val flowUi = ThemeManager.isFlow(this)
        if (flowUi) {
            root.background = pl.sinusoide.sinuschat.ui.GlassDrawable(dp, null, false, 28 * dp, null)
            root.elevation = 0f
        }
        root.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val popup = PopupWindow(root, (300 * dp).toInt(), (56 * dp).toInt(), false)
        popup.elevation = if (flowUi) 0f else 10 * dp
        popup.isOutsideTouchable = true
        reactionPopup = popup
        reactionPopupMsgId = item.id
        popup.setOnDismissListener {
            reactionPopup = null
            reactionPopupMsgId = null
            flowLens?.remove(root)
        }

        val emojis = listOf("👍","❤️","😂","😮","😢","🙏","👏","🔥","🥳","😍","💯","😎","🤔","😭","🙌")

        val emojiViews = mutableListOf<View>()
        emojis.forEach { emoji ->
            val tv = TextView(this).apply {
                text = emoji; textSize = 24f
                setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                background = ContextCompat.getDrawable(this@ChatActivity, R.drawable.bg_menu_ripple)
                isClickable = true
                scaleX = 0f; scaleY = 0f
                setOnClickListener {
                    addReaction(item, emoji)
                    popup.dismiss()
                }
            }
            row.addView(tv)
            emojiViews.add(tv)
        }

        val plusSize = (40 * dp).toInt()
        val plus = ImageView(this).apply {
            setImageResource(R.drawable.ic_add_circle)
            background = ContextCompat.getDrawable(this@ChatActivity, R.drawable.bg_circle_button)
            if (flowUi) {
                background = pl.sinusoide.sinuschat.ui.GlassDrawable(dp, null, true, 0f, null)
                setColorFilter(Color.WHITE)
            }
            val p = (8 * dp).toInt(); setPadding(p, p, p, p)
            isClickable = true
            scaleX = 0f; scaleY = 0f
            setOnClickListener {
                popup.dismiss()
                showEmojiPicker(item)
            }
        }
        root.addView(plus, LinearLayout.LayoutParams(plusSize, plusSize).apply {
            marginStart = (6 * dp).toInt()
        })
        emojiViews.add(plus)

        emojiViews.forEachIndexed { i, v ->
            v.animate()
                .scaleX(1f).scaleY(1f)
                .setStartDelay(i * 35L)
                .setDuration(160L)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f))
                .start()
        }

        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupHeight = (56 * dp).toInt()

        val x = location[0] + (anchor.width / 2) - ((300 * dp).toInt() / 2)
        val y = location[1] - popupHeight - (12 * dp).toInt()
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x.coerceAtLeast((8*dp).toInt()), y)
        if (flowUi) {
            flowLens?.add(pl.sinusoide.sinuschat.ui.FlowLens.Lens(root, 28 * dp, magnify = 0.1f, blurDp = 10f))
            root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> flowLens?.refresh() }
        }
    }

    private fun replySelectedMessage() {
        val id = adapter.selectedIds.firstOrNull() ?: return
        val msg = allMessages.find { it.id == id } ?: return
        exitSelectionMode()
        setReplyingTo(msg)
    }

    private fun pinSelectedMessage() {
        val id = adapter.selectedIds.firstOrNull() ?: return
        val msg = allMessages.find { it.id == id } ?: return
        exitSelectionMode()
        togglePin(msg)
    }

    private fun forwardSelectedMessage() {
        val chosen = withAlbums(adapter.selectedIds.mapNotNull { id -> allMessages.find { it.id == id } })
        if (chosen.isEmpty()) return
        val ok = chosen.filter { !it.isSystemMessage && !it.viewOnce }
        exitSelectionMode()
        if (ok.isEmpty()) {
            PopupHelper.show(this, getString(
                if (chosen.any { it.viewOnce }) R.string.forward_view_once else R.string.forward_nothing))
            return
        }
        val secure = (window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
        forwardLauncher.launch(ForwardActivity.forMessages(this, conversationId, ok.map { it.id }, secure))
    }

    private val forwardLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.getStringExtra(ForwardActivity.EXTRA_SUMMARY)?.let { PopupHelper.showSuccess(this, it) }
    }

    private fun showSelectedMoreMenu() {
        val id = adapter.selectedIds.firstOrNull() ?: return
        val msg = allMessages.find { it.id == id } ?: return
        moreMenu?.takeIf { it.isShowing }?.let { it.dismiss(); moreMenu = null; return }
        val isMine = msg.senderHash == myPhoneHash
        val items = buildList {
            if (isMine && msg.type != "attachment") add(pl.sinusoide.sinuschat.ui.GlassMenu.Item(R.drawable.ic_edit, getString(R.string.edit_message)) {
                editMessage(msg)
                exitSelectionMode()
            })
            add(pl.sinusoide.sinuschat.ui.GlassMenu.Item(R.drawable.ic_copy, getString(R.string.copy_message)) {
                copyMessage(msg)
                exitSelectionMode()
            })
            if (msg.type == MessageEntity.TYPE_ATTACHMENT && !msg.viewOnce && !msg.fileId.isNullOrEmpty())
                add(pl.sinusoide.sinuschat.ui.GlassMenu.Item(R.drawable.ic_download, getString(R.string.save_to_device)) {
                    val items = adapter.albumOf(msg.id) ?: listOf(msg)
                    val subject = if (items.size > 1) getString(R.string.safe_several_files) else (msg.attachmentName ?: getString(R.string.safe_this_file))
                    pl.sinusoide.sinuschat.util.SafeEngine.guard(this@ChatActivity, pl.sinusoide.sinuschat.util.SafeEngine.Risk.SAVE_FILE, subject) {
                        items.forEach { saveAttachmentToDevice(it, confirmed = true) }
                    }
                    exitSelectionMode()
                })
        }
        moreMenu = pl.sinusoide.sinuschat.ui.GlassMenu.show(this, btnSelectionMore, items, lens = flowLens)
    }

    private var moreMenu: pl.sinusoide.sinuschat.ui.GlassMenu.Handle? = null


    private fun copyMessage(item: MessageEntity) {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        val textToCopy = when {
            item.text.startsWith("geo:") -> {
                item.text.removePrefix("geo:").replace(",", ", ")
            }
            item.attachmentName?.startsWith("location_") == true -> {
                item.attachmentName.removePrefix("location_").removeSuffix(".png").replace("_", ", ")
            }
            else -> item.text
        }
        clip?.setPrimaryClip(android.content.ClipData.newPlainText("SinusChat", textToCopy))
        PopupHelper.show(this, getString(R.string.copied))
    }

    private fun editMessage(item: MessageEntity) {
        if (item.senderHash != myPhoneHash || item.type != MessageEntity.TYPE_TEXT) {
            PopupHelper.show(this, getString(R.string.edit_only_own_text)); return
        }
        val secret = sharedSecret
        if (secret == null) { PopupHelper.showError(this, getString(R.string.no_secure_channel)); return }

        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val view = layoutInflater.inflate(R.layout.layout_edit_message_sheet, null)

        val tvOriginal = view.findViewById<TextView>(R.id.tv_original_text)
        val etInput = view.findViewById<EditText>(R.id.et_edit_input)
        val btnCancel = view.findViewById<Button>(R.id.btn_edit_cancel)
        val btnSave = view.findViewById<Button>(R.id.btn_edit_save)

        tvOriginal.text = item.text
        etInput.setText(item.text)
        etInput.setSelection(item.text.length)

        btnCancel.setOnClickListener { sheet.dismiss() }
        btnSave.setOnClickListener {
            val newText = etInput.text.toString().trim()
            if (newText.isNotEmpty() && newText != item.text) {
                MessageRepository.editMessageEverywhere(
                    this, conversationId, myPhoneHash, otherUserHash, secret, item.id, newText
                )
            }
            sheet.dismiss()
            if (adapter.isSelectionMode) exitSelectionMode()
        }

        sheet.setContentView(view)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun showEmojiPicker(item: MessageEntity) {
        val dp = resources.displayMetrics.density
        val ui = pl.sinusoide.sinuschat.ui.ThemeUi
        val flow = ThemeManager.isFlow(this)
        val accent = ui.accent(this)
        val current = item.reactions[myPhoneHash]
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (!flow) setBackgroundColor(ui.sheet(this@ChatActivity))
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }
        root.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = 3 * dp; setColor(ui.handle(this@ChatActivity)) }
        }, LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (14 * dp).toInt()
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.choose_reaction)
            textSize = 19f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ui.textPrimary(this@ChatActivity))
            setPadding((8 * dp).toInt(), 0, 0, (4 * dp).toInt())
        })

        val width = resources.displayMetrics.widthPixels - (24 * dp).toInt()
        val columns = if (width / 8 >= (44 * dp).toInt()) 8 else 7
        val cell = width / columns
        val ripple = if (flow || ui.isDark(this)) 0x33FFFFFF else 0x1F000000

        val sections = buildList {
            val recent = recentReactions().take(columns)
            if (recent.isNotEmpty()) add(R.string.reactions_recent to recent)
            add(R.string.reactions_faces to ReactionEmojis.FACES)
            add(R.string.reactions_gestures to ReactionEmojis.GESTURES)
            add(R.string.reactions_hearts to ReactionEmojis.HEARTS)
            add(R.string.reactions_more to ReactionEmojis.MORE)
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cells = mutableListOf<View>()
        var picked = false
        sections.forEach { (title, emojis) ->
            content.addView(TextView(this).apply {
                text = getString(title).uppercase()
                textSize = 11.5f; letterSpacing = 0.08f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ui.textSecondary(this@ChatActivity))
                setPadding((8 * dp).toInt(), (14 * dp).toInt(), 0, (4 * dp).toInt())
            })
            val grid = GridLayout(this).apply { columnCount = columns }
            emojis.forEach { emoji ->
                val selected = emoji == current
                val tv = TextView(this).apply {
                    text = emoji
                    textSize = 27f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    val base = if (selected) GradientDrawable().apply {
                        cornerRadius = 14 * dp
                        setColor((accent and 0x00FFFFFF) or 0x3D000000)
                        setStroke((1.5f * dp).toInt(), accent)
                    } else null
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(ripple), base,
                        GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.WHITE) })
                    isClickable = true
                    setOnClickListener { v ->
                        if (picked) return@setOnClickListener
                        picked = true
                        v.animate().scaleX(1.4f).scaleY(1.4f).setDuration(120)
                            .setInterpolator(android.view.animation.OvershootInterpolator(3f))
                            .withEndAction {
                                sheet.dismiss()
                                addReaction(item, emoji)
                            }.start()
                    }
                }
                grid.addView(tv, GridLayout.LayoutParams().apply { this.width = cell; height = cell })
                cells += tv
            }
            content.addView(grid)
        }
        val scroll = androidx.core.widget.NestedScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.56f).toInt()))

        cells.take(columns * 4).forEachIndexed { i, v ->
            v.alpha = 0f; v.scaleX = 0.4f; v.scaleY = 0.4f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(60L + i * 9L).setDuration(260)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        }

        sheet.setContentView(root)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private val reactionPrefs get() = getSharedPreferences("sinuschat_reactions", MODE_PRIVATE)

    private fun recentReactions(): List<String> =
        reactionPrefs.getString("recent", null)?.split('\u0001')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun rememberReaction(emoji: String) {
        val list = (listOf(emoji) + recentReactions().filter { it != emoji }).take(16)
        reactionPrefs.edit().putString("recent", list.joinToString("\u0001")).apply()
    }

    private fun togglePin(item: MessageEntity) {
        val newVal = !item.pinned
        MessageRepository.setPinned(this, conversationId, myPhoneHash, otherUserHash, item.id, newVal)
        PopupHelper.show(this, if (newVal) getString(R.string.pinned) else getString(R.string.unpinned))
    }

    private fun deleteMessage(item: MessageEntity) = deleteMessages(listOf(item))

    private fun dustVanishedMessages(next: List<MessageEntity>) {
        val shown = adapter.currentList
        if (shown.isEmpty() || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        val keep = next.mapTo(HashSet()) { it.id }
        val gone = shown.filter { it.id !in keep }
        if (gone.isEmpty() || gone.size > 20) return
        val hidden = HashSet<String>()
        for (msg in gone) {
            val vh = findViewHolderForMessageId(msg.id) as? MessageAdapter.VH ?: continue
            val view = vh.bubble?.takeIf { it.visibility == View.VISIBLE }
                ?: vh.systemText?.parent as? View ?: vh.itemView
            val bmp = DustEffect.capture(view) ?: continue
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            DustEffect.disintegrateManual(dustOverlay, bmp, loc[0].toFloat(), loc[1].toFloat(), false) {
                bmp.recycle()
            }
            vh.itemView.visibility = View.INVISIBLE
            hidden += msg.id
        }
        if (hidden.isNotEmpty()) adapter.hiddenIds = adapter.hiddenIds + hidden
    }

    private fun deleteMessages(selected: List<MessageEntity>) {
        if (selected.isEmpty()) return
        val items = withAlbums(selected)
        items.filter { AttachmentSender.isActive(it.id) }.forEach { AttachmentSender.cancel(this, it) }
        items.forEach { deletedCache[it.id] = it }
        if (pendingDeleteIds.isNotEmpty()) commitPendingDelete()
        val ids = items.map { it.id }
        scheduleDelete(items)

        for (item in items) {
            val vh = findViewHolderForMessageId(item.id) as? MessageAdapter.VH ?: continue
            val view = vh.bubble?.takeIf { it.visibility == View.VISIBLE }
                ?: vh.systemText?.parent as? View ?: vh.itemView
            val bmp = DustEffect.capture(view) ?: continue
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            DustEffect.disintegrateManual(dustOverlay, bmp, loc[0].toFloat(), loc[1].toFloat(), false) {
                bmp.recycle()
            }
            vh.itemView.visibility = View.INVISIBLE
        }

        pendingDeleteIds = ids
        adapter.hiddenIds = ids.toSet()
        if (adapter.isSelectionMode) exitSelectionMode()
        allMessages = allMessages.filter { it.id !in ids }
        lastNewestId = allMessages.lastOrNull()?.id
        adapter.submitList(adapter.currentList.filter { it.id !in ids })
        showUndoBar(items.size)
    }

    private fun addReaction(item: MessageEntity, emoji: String) {
        val secret = sharedSecret ?: return
        val myCurrentReaction = item.reactions[myPhoneHash]
        val newEmoji = if (myCurrentReaction == emoji) null else emoji
        if (newEmoji != null) rememberReaction(newEmoji)
        MessageRepository.setReaction(this, conversationId, myPhoneHash, otherUserHash, secret, item.id, newEmoji)

        if (adapter.isSelectionMode) {
            exitSelectionMode()
        }
    }

    private fun deleteSelectedMessages() {
        val ids = adapter.selectedIds.toSet()
        val items = allMessages.filter { it.id in ids && it.id !in pendingDeleteIds }
        if (items.isEmpty()) return
        confirmDeleteMessages(items)
    }

    private fun confirmDeleteMessages(items: List<MessageEntity>) {
        val item = items.first()
        val many = items.size > 1
        val d = resources.displayMetrics.density
        val red = 0xFFE5484D.toInt()
        val secondary = pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(this)
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
        }
        root.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = 3 * d; setColor((pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(this@ChatActivity) and 0x00FFFFFF) or 0x55000000) }
        }, LinearLayout.LayoutParams((36 * d).toInt(), (4 * d).toInt()).apply {
            bottomMargin = (20 * d).toInt()
        })
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_delete); setColorFilter(red)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((red and 0x00FFFFFF) or 0x26000000)
            }
            val p = (16 * d).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()).apply {
            bottomMargin = (16 * d).toInt()
        })
        root.addView(TextView(this).apply {
            text = if (many) getString(R.string.delete_messages_q, items.size) else getString(R.string.delete_message_q)
            textSize = 20f; setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this@ChatActivity)); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(when {
                many && GroupRepository.isGroup(conversationId) -> R.string.delete_messages_desc_group
                many -> R.string.delete_messages_desc
                GroupRepository.isGroup(conversationId) -> R.string.delete_message_desc_group
                else -> R.string.delete_message_desc
            })
            textSize = 14f; setTextColor(secondary); gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, (18 * d).toInt())
        })

        val preview = when {
            item.viewOnce -> getString(R.string.view_once_sent)
            item.type == MessageEntity.TYPE_VOICE -> getString(R.string.voice_message)
            item.type == "attachment" -> item.attachmentName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.photo)
            else -> item.text
        }
        val mine = item.senderHash == myPhoneHash
        if (!many) root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(pl.sinusoide.sinuschat.ui.ThemeUi.card(this@ChatActivity)) }
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            addView(TextView(this@ChatActivity).apply {
                text = if (mine) getString(R.string.you)
                       else pl.sinusoide.sinuschat.util.ContactCache.getName(this@ChatActivity, item.senderHash)
                            ?: otherUserName
                textSize = 12f; setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.accent(this@ChatActivity))
            })
            addView(TextView(this@ChatActivity).apply {
                text = preview
                textSize = 15f; setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this@ChatActivity))
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (2 * d).toInt(), 0, 0)
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (22 * d).toInt() })

        root.addView(TextView(this).apply {
            text = getString(R.string.delete)
            textSize = 16f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 26 * d; setColor(red) }
            setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
            isClickable = true
            setOnClickListener {
                sheet.dismiss()
                recycler.post { if (many) deleteMessages(items) else deleteMessage(item) }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = getString(R.string.cancel)
            textSize = 15f; setTextColor(secondary); gravity = Gravity.CENTER
            setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))

        sheet.setContentView(root)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun attachSwipeToReply() {
        val icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_reply)
        val dp = resources.displayMetrics.density
        val triggerPx = 48 * dp

        var active: androidx.recyclerview.widget.RecyclerView.ViewHolder? = null
        var armed = false
        fun soft(dX: Float): Float {
            val a = kotlin.math.abs(dX)
            val s = if (a <= triggerPx) a else triggerPx + (a - triggerPx) * 0.3f
            return kotlin.math.sign(dX) * s.coerceAtMost(triggerPx * 1.6f)
        }
        fun buzz() {
            try {
                val vib = getSystemService(android.os.Vibrator::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vib.vibrate(android.os.VibrationEffect.createOneShot(15, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") vib.vibrate(15)
                }
            } catch (_: Exception) {}
        }

        val callback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            0, androidx.recyclerview.widget.ItemTouchHelper.RIGHT or androidx.recyclerview.widget.ItemTouchHelper.LEFT
        ) {
            override fun getSwipeThreshold(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder) = 2f

            override fun getSwipeEscapeVelocity(default: Float) = Float.MAX_VALUE

            override fun getSwipeDirs(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Int {
                if (adapter.isSelectionMode) return 0
                val msg = adapter.currentList.getOrNull(vh.bindingAdapterPosition) ?: return 0
                if (msg.isSystemMessage || isUndelivered(msg)) return 0
                return if (msg.senderHash == myPhoneHash) androidx.recyclerview.widget.ItemTouchHelper.LEFT
                       else androidx.recyclerview.widget.ItemTouchHelper.RIGHT
            }

            override fun onMove(
                r: androidx.recyclerview.widget.RecyclerView,
                a: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                b: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {}

            override fun onSelectedChanged(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE && vh != null) {
                    active = vh
                    armed = false
                } else if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_IDLE) {
                    val released = active
                    val fire = armed
                    active = null
                    armed = false
                    if (fire && released != null) {
                        adapter.currentList.getOrNull(released.bindingAdapterPosition)
                            ?.takeIf { !it.isSystemMessage }
                            ?.let { setReplyingTo(it) }
                    }
                }
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isActive: Boolean
            ) {
                val shift = soft(dX)
                vh.itemView.translationX = shift
                if (isActive && vh === active) {
                    val past = kotlin.math.abs(dX) >= triggerPx
                    if (past && !armed) buzz()
                    armed = past
                }

                icon?.let {
                    val a = kotlin.math.abs(shift)
                    if (a > 6) {
                        val h = vh.itemView.height
                        val iconSize = (22 * dp).toInt()
                        val top = vh.itemView.top + (h - iconSize) / 2
                        val offset = (a / 2 - iconSize / 2).toInt().coerceIn((10 * dp).toInt(), (32 * dp).toInt())
                        val left = if (dX > 0) offset else vh.itemView.width - offset - iconSize
                        it.setBounds(left, top, left + iconSize, top + iconSize)
                        it.alpha = ((a / triggerPx).coerceIn(0f, 1f) * 255).toInt()
                        it.draw(c)
                    }
                }
            }

            override fun clearView(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) {
                super.clearView(rv, vh)
                vh.itemView.translationX = 0f
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(callback).attachToRecyclerView(recycler)
    }

    private fun jumpToMessage(messageId: String?) {
        if (messageId.isNullOrEmpty()) return
        val target = adapter.resolveId(messageId)
            ?: messageId.takeIf { id -> allMessages.any { it.id == id } }
            ?: "${messageId}_${myPhoneHash.take(16)}".takeIf { id -> allMessages.any { it.id == id } }
            ?: messageId
        if (!jumpAndFlash(target)) PopupHelper.show(this, getString(R.string.original_not_found))
    }

    private fun jumpAndFlash(messageId: String): Boolean {
        val shownId = adapter.albumOf(messageId)?.firstOrNull()?.id ?: messageId
        val idx = adapter.currentList.indexOfFirst { it.id == shownId }
        if (idx < 0) {
            if (isSearching && allMessages.any { it.id == messageId }) {
                hideSearchBar { jumpAndFlash(messageId) }
                return true
            }
            return false
        }
        recycler.stopScroll()
        adapter.flash(shownId)
        val offset = ((recycler.height - recycler.paddingTop - recycler.paddingBottom) / 3).coerceAtLeast(0)
        val arrive = {
            recycler.findViewHolderForAdapterPosition(idx)?.itemView?.let { adapter.showFlash(it, shownId) }
        }
        val lm = recycler.layoutManager as? LinearLayoutManager
        if (lm == null) {
            recycler.scrollToPosition(idx)
            recycler.doOnNextLayout { arrive() }
            return true
        }
        fun glide() {
            val scroller = object : androidx.recyclerview.widget.LinearSmoothScroller(this) {
                override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int) =
                    boxStart + offset - viewStart
                override fun onStop() {
                    super.onStop()
                    recycler.post { arrive() }
                }
            }
            scroller.targetPosition = idx
            lm.startSmoothScroll(scroller)
        }
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first != androidx.recyclerview.widget.RecyclerView.NO_POSITION && idx in (first - 12)..(last + 12)) {
            arrive()
            glide()
        } else {
            val near = if (first == androidx.recyclerview.widget.RecyclerView.NO_POSITION || idx < first) idx + 4 else idx - 4
            lm.scrollToPositionWithOffset(near.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0)), offset)
            recycler.doOnNextLayout { recycler.post { glide() } }
        }
        return true
    }

    private fun setReplyingTo(item: MessageEntity) {
        replyingTo = item
        replyBar.visibility = View.VISIBLE
        replyBarText.text = ReplyLabel.of(this, item).take(80)
        inputMessage.requestFocus()

        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.showSoftInput(inputMessage, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun cancelReply() {
        replyingTo = null
        replyBar.visibility = View.GONE
    }

    private fun showAttachBottomSheet() {
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val dp = resources.displayMetrics.density

        val tv = android.util.TypedValue()
        fun attr(id: Int, fallback: Int) = pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(this, id) ?: fallback
        val colorSurface = attr(R.attr.sinuschat_surface, 0xFF17212B.toInt())
        val colorTextPrimary = attr(R.attr.sinuschat_text_primary, Color.WHITE)
        val colorTextSecondary = attr(R.attr.sinuschat_text_secondary, 0xFF7F91A4.toInt())

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadii = floatArrayOf(24 * dp, 24 * dp, 24 * dp, 24 * dp, 0f, 0f, 0f, 0f)
            }
            clipChildren = false
            clipToPadding = false
        }

        container.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 3 * dp; setColor((colorTextSecondary and 0x00FFFFFF) or 0x55000000)
            }
        }, LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = (18 * dp).toInt()
        })

        container.addView(TextView(this).apply {
            text = getString(R.string.send_attachment)
            setTextColor(colorTextPrimary)
            textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (18 * dp).toInt())
        })

        data class Opt(val iconRes: Int, val label: String, val colorFrom: Int, val colorTo: Int, val action: () -> Unit)
        val options = listOf(
            Opt(R.drawable.ic_camera, getString(R.string.camera), 0xFFFF6B5E.toInt(), 0xFFE5484D.toInt()) { openCameraWithPermission() },
            Opt(R.drawable.ic_image, getString(R.string.gallery), 0xFF5AA9F0.toInt(), 0xFF2F7ED8.toInt()) { pickImage.launch("image/*") },
            Opt(R.drawable.ic_call_video_on, getString(R.string.video), 0xFF8B7CF0.toInt(), 0xFF5B4FD0.toInt()) { pickVideo.launch("video/*") },
            Opt(R.drawable.ic_document, getString(R.string.document), 0xFFC37CF0.toInt(), 0xFF9442C9.toInt()) { pickFile.launch("*/*") },
            Opt(R.drawable.ic_location, getString(R.string.location), 0xFF5ED38A.toInt(), 0xFF2FA862.toInt()) { shareLocation() },
            Opt(R.drawable.ic_mic, getString(R.string.audio), 0xFFFFB35C.toInt(), 0xFFE67E22.toInt()) { pickAudio.launch("audio/*") }
        )

        val grid = GridLayout(this).apply {
            columnCount = 3
            clipChildren = false
            clipToPadding = false
        }

        var dismissing = false
        options.forEachIndexed { index, opt ->
            val tileSize = (62 * dp).toInt()
            val iconTile = FrameLayout(this).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(opt.colorFrom, opt.colorTo)
                ).apply { cornerRadius = 20 * dp }
                elevation = 3 * dp
                addView(ImageView(this@ChatActivity).apply {
                    setImageResource(opt.iconRes); setColorFilter(Color.WHITE)
                }, FrameLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt(), Gravity.CENTER))
            }

            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt())
                clipChildren = false
                clipToPadding = false
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                isClickable = true
                isFocusable = true
                contentDescription = opt.label
                setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN ->
                            iconTile.animate().scaleX(0.88f).scaleY(0.88f).setStartDelay(0).setDuration(70).start()
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                            iconTile.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(120).start()
                    }
                    false
                }
                setOnClickListener {
                    if (dismissing) return@setOnClickListener
                    dismissing = true
                    opt.action()
                    sheet.dismiss()
                }
            }
            item.addView(iconTile, LinearLayout.LayoutParams(tileSize, tileSize).apply {
                bottomMargin = (8 * dp).toInt()
            })
            item.addView(TextView(this).apply {
                text = opt.label
                setTextColor(colorTextPrimary)
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            grid.addView(item)

            iconTile.scaleX = 0.4f; iconTile.scaleY = 0.4f; iconTile.alpha = 0f
            iconTile.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(30L + index * 20L).setDuration(220)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        }
        container.addView(grid)
        sheet.setContentView(container)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun observeMessages() {
        MessageRepository.observeMessages(this, conversationId).observe(this) { raw ->
            val messages = if (pendingDeleteIds.isEmpty() && committedDeleteIds.isEmpty()) raw
                           else raw.filter { it.id !in pendingDeleteIds && it.id !in committedDeleteIds }
            if (!isSearching) dustVanishedMessages(messages)
            allMessages = messages
            if (isSearching) {
                filterMessages(searchInput.text.toString())
            } else {
                val wasAtBottom = stickToBottom
                val prevNewest = lastNewestId
                adapter.submitList(messages) {
                    val scrollToId = intent.getStringExtra("scrollToId")
                    val newest = messages.lastOrNull()
                    if (scrollToId != null) {
                        if (jumpAndFlash(scrollToId)) {
                        } else if (messages.isNotEmpty()) {
                            recycler.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0))
                        }
                        intent.removeExtra("scrollToId")
                    } else if (newest != null) {
                        val isNew = newest.id != prevNewest
                        val mine = newest.senderHash == myPhoneHash
                        if (!firstListShown || wasAtBottom || (isNew && mine)) {
                            recycler.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0))
                        } else if (isNew) {
                            val from = messages.indexOfFirst { it.id == prevNewest }
                            unseenWhileUp += if (from < 0) (if (!newest.isSystemMessage && !mine) 1 else 0)
                                else messages.drop(from + 1).count { !it.isSystemMessage && it.senderHash != myPhoneHash }
                            updateScrollDownBadge()
                        }
                    }
                    if (newest != null) firstListShown = true
                    lastNewestId = newest?.id
                }
            }
            autoDownloadAttachments(messages)
            markReadIfNeeded(messages)
            translationScan(messages)
            updateInputVisibility(messages)

            if (!conversationChecked && messages.isNotEmpty()) {
                conversationChecked = true
            }
        }
    }

    private fun updateInputVisibility(messages: List<MessageEntity>) {
        val isGroup = GroupRepository.isGroup(conversationId)
        if (isGroup) {
            chatBlockedBar.visibility = View.GONE
            chatRequestBar.visibility = View.GONE
            chatDisappearingBar.visibility = View.GONE
            applyGroupPermissions()
            return
        }

        val isBlocked = pl.sinusoide.sinuschat.util.BlockList.isBlocked(this, otherUserHash)

        if (isBlocked) {
            chatInputContainer.visibility = View.GONE
            chatBlockedBar.visibility = View.VISIBLE
            chatRequestBar.visibility = View.GONE
            chatDisappearingBar.visibility = View.GONE
            showDeletedBar(false)
            return
        }

        if (pl.sinusoide.sinuschat.util.BlockList.isDeleted(this, otherUserHash)) {
            chatInputContainer.visibility = View.GONE
            chatBlockedBar.visibility = View.GONE
            chatRequestBar.visibility = View.GONE
            chatDisappearingBar.visibility = View.GONE
            showDeletedBar(true)
            return
        }
        showDeletedBar(false)

        val pendingDisappearing = DisappearingMessages.getPending(this, conversationId)
        if (pendingDisappearing != null) {
            chatInputContainer.visibility = View.GONE
            chatBlockedBar.visibility = View.GONE
            chatRequestBar.visibility = View.GONE
            chatDisappearingBar.visibility = View.VISIBLE

            val (ttl, trigger, from) = pendingDisappearing
            val otherName = from.ifEmpty { otherUserName }
            val fullInfo = DisappearingMessages.fullLabel(this, ttl, trigger)
            findViewById<TextView>(R.id.tv_disappearing_info).text =
                getString(R.string.disappearing_consent_desc, otherName, fullInfo)
            return
        }

        val accepted = getSharedPreferences("sinuschat_accepted_convs", MODE_PRIVATE)
            .getBoolean(conversationId, false)
        val weStarted = intent.getBooleanExtra("weStarted", false)
        val weSentSomething = messages.any { it.senderHash == myPhoneHash }

        if (!accepted && !weStarted && !weSentSomething) {
            val hasIncoming = messages.any { it.senderHash == otherUserHash && !it.isSystemMessage }
            if (hasIncoming) {
                chatInputContainer.visibility = View.GONE
                chatBlockedBar.visibility = View.GONE
                chatDisappearingBar.visibility = View.GONE
                chatRequestBar.visibility = View.VISIBLE
                findViewById<TextView>(R.id.tv_request_info).text =
                    getString(R.string.new_conversation_request_desc, otherUserName)
                return
            }
        }

        chatInputContainer.visibility = View.VISIBLE
        chatBlockedBar.visibility = View.GONE
        chatRequestBar.visibility = View.GONE
        chatDisappearingBar.visibility = View.GONE
    }

    private fun acceptConversation() {
        getSharedPreferences("sinuschat_accepted_convs", MODE_PRIVATE)
            .edit().putBoolean(conversationId, true).apply()
        updateInputVisibility(allMessages)
    }

    private fun blockFromRequest() {
        pl.sinusoide.sinuschat.util.BlockList.block(this, otherUserHash)
        updateInputVisibility(allMessages)
        refreshBlockedUi()
    }

    private fun unblockUser() {
        pl.sinusoide.sinuschat.util.BlockList.unblock(this, otherUserHash)
        runOnUiThread {
            PopupHelper.show(this, getString(R.string.unblocked_toast))
            updateInputVisibility(allMessages)
            refreshBlockedUi()
        }
    }

    private fun confirmDeleteConversation() {
        val isGroup = GroupRepository.isGroup(conversationId)
        val last = allMessages.lastOrNull { !it.isSystemMessage }?.let { m ->
            when {
                m.viewOnce -> getString(R.string.view_once_sent)
                m.type == MessageEntity.TYPE_VOICE -> getString(R.string.voice_message)
                m.type == "attachment" -> m.attachmentName?.takeIf { it.isNotBlank() } ?: getString(R.string.photo)
                else -> m.text
            }
        }
        DeleteChatSheet.show(
            this, conversationId, otherUserName, isGroup,
            otherHash = if (isGroup) null else otherUserHash,
            avatarUrl = pl.sinusoide.sinuschat.util.ContactCache.getAvatarUrl(this,
                if (isGroup) conversationId else otherUserHash),
            groupColor = null,
            lastMessage = last,
            onConfirm = {
                MessageRepository.deleteConversation(this, conversationId, myPhoneHash, null)
                PopupHelper.show(this, getString(R.string.chat_deleted))
                finish()
            }
        )
    }

    private fun markReadIfNeeded(messages: List<MessageEntity>) {
        if (conversationId.isEmpty()) return
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            pl.sinusoide.sinuschat.util.MessageNotifier.dismiss(this, conversationId)
        }
        val isGroup = GroupRepository.isGroup(conversationId)
        val lastFromOther = messages
            .filter { msg ->
                !msg.isSystemMessage && msg.senderHash != myPhoneHash &&
                        (isGroup || msg.senderHash == otherUserHash)
            }
            .maxOfOrNull { it.timestampMs } ?: return

        if (lastFromOther > lastPublishedReadMs) {
            lastPublishedReadMs = lastFromOther
            Log.d("ChatActivity", "Wysyłam ACK readUpTo=$lastFromOther dla $conversationId")
            MessageRepository.publishRead(this, conversationId, myPhoneHash, lastFromOther)
            MessageRepository.publishDelivered(this, conversationId, myPhoneHash, lastFromOther)
        }
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            pl.sinusoide.sinuschat.util.DisappearingMessages.applyOnRead(this, conversationId, myPhoneHash)
        }
    }

    private fun canSendInGroup() = amIGroupAdmin || groupPerms.sendMessages
    private fun canCallInGroup() = amIGroupAdmin || groupPerms.callGroup

    private fun blockedFromSending(): Boolean {
        if (GroupRepository.isGroup(conversationId)) {
            if (canSendInGroup()) return false
            PopupHelper.showError(this, getString(R.string.group_cant_write))
            return true
        }
        if (pl.sinusoide.sinuschat.util.BlockList.isBlocked(this, otherUserHash)) {
            PopupHelper.showError(this, getString(R.string.send_blocked_by_me))
            return true
        }
        if (pl.sinusoide.sinuschat.util.BlockList.isDeleted(this, otherUserHash)) {
            PopupHelper.showError(this, getString(R.string.send_account_deleted))
            return true
        }
        return false
    }

    private var deletedBar: View? = null

    private fun showDeletedBar(show: Boolean) {
        if (!show) { deletedBar?.visibility = View.GONE; return }
        val bar = deletedBar ?: run {
            val dp = resources.displayMetrics.density
            val parent = chatBlockedBar.parent as? ViewGroup ?: return
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(this@ChatActivity,
                    R.attr.sinuschat_surface) ?: pl.sinusoide.sinuschat.ui.ThemeUi.card(this@ChatActivity))
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
                addView(TextView(this@ChatActivity).apply {
                    text = getString(R.string.chat_account_deleted)
                    textSize = 13.5f
                    gravity = Gravity.CENTER
                    setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(this@ChatActivity))
                })
                addView(TextView(this@ChatActivity).apply {
                    text = getString(R.string.delete_chat)
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.sinuschat_error))
                    setPadding(0, (10 * dp).toInt(), 0, (8 * dp).toInt())
                    setOnClickListener { confirmDeleteConversation() }
                }, LinearLayout.LayoutParams(-1, -2))
            }
            parent.addView(v, parent.indexOfChild(chatBlockedBar) + 1,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            deletedBar = v
            v
        }
        bar.visibility = View.VISIBLE
    }

    private class OutTranslation(val orig: String, val translated: String, val from: String, val to: String) {
        fun wire() = Translations.Wire(orig, from, to)
    }

    private var outTranslation: OutTranslation? = null
    private var translationChecked = false
    private var translationBusy = false
    private var translationBusySince = 0L

    private fun takeOutTranslation(text: String): OutTranslation? {
        val tr = outTranslation?.takeIf { it.orig == text }
        outTranslation = null
        return tr
    }

    private fun offerSendTranslation(): Boolean {
        if (translationChecked) { translationChecked = false; return false }
        outTranslation = null
        if (translationBusy && System.currentTimeMillis() - translationBusySince < 5_000L) return true
        translationBusy = false
        if (!Translations.enabled(this) || !Translations.askSend(this) ||
            Translations.isBlocked(this, conversationId)) return false
        val text = inputMessage.text.toString().trim()
        if (!Translations.hasLetters(text)) return false
        val peer = Translations.peerLang(this, conversationId) ?: return false
        if (Translations.decision(this, conversationId, peer) != Translations.YES) return false
        val app = Translations.appLang(this)
        if (peer == app) return false
        translationBusy = true
        translationBusySince = System.currentTimeMillis()
        Translations.detectRaw(text) { d ->
            if (isFinishing || isDestroyed || inputMessage.text.toString().trim() != text) {
                translationBusy = false; return@detectRaw
            }
            val inPeer = d?.lang == peer && d.confidence >= (if (d.long) 0.5f else 0.8f)
            val other = d?.lang?.takeIf { it != app && it != peer && d.long && d.confidence >= 0.8f &&
                Translations.canTranslate(this, it, peer) }
            val from = other ?: app
            if (inPeer || !Translations.canTranslate(this, from, peer)) {
                translationBusy = false; sendSkippingTranslation(); return@detectRaw
            }
            Translations.translate(this, text, from, peer) { out ->
                translationBusy = false
                if (isFinishing || isDestroyed || inputMessage.text.toString().trim() != text) return@translate
                if (out == null) { sendSkippingTranslation(); return@translate }
                TranslateSendSheet.show(this, text, out, from, peer,
                    onTranslated = {
                        if (inputMessage.text.toString().trim() == text) {
                            outTranslation = OutTranslation(text, out, from, peer)
                            sendSkippingTranslation()
                        }
                    },
                    onOriginal = { if (inputMessage.text.toString().trim() == text) sendSkippingTranslation() })
            }
        }
        return true
    }

    private fun sendSkippingTranslation() {
        translationChecked = true
        sendMessage()
        translationChecked = false
    }

    private var translatePill: LinearLayout? = null
    private var translatePillLang: String? = null
    private val translationSeen = mutableSetOf<String>()
    private var translationBlocked = false

    private val translationChanged: () -> Unit = {
        val now = Translations.isBlocked(this, conversationId)
        if (now != translationBlocked && !isFinishing && !isDestroyed) {
            translationBlocked = now
            if (now) hideTranslatePill()
            else {
                translationSeen.clear()
                translationScan(allMessages)
            }
            if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        }
    }

    private fun translationScan(messages: List<MessageEntity>) {
        if (!Translations.enabled(this) || Translations.isBlocked(this, conversationId)) { hideTranslatePill(); return }
        val app = Translations.appLang(this)
        val peer = Translations.peerLang(this, conversationId)
        val all = peer != null && peer != app &&
            Translations.decision(this, conversationId, peer) == Translations.YES &&
            Translations.canTranslate(this, peer, app)
        var n = 0
        for (m in messages.asReversed()) {
            if (!all && n >= 30) break
            if (m.isSystemMessage || m.senderHash == myPhoneHash || m.senderHash.isEmpty() ||
                m.type != MessageEntity.TYPE_TEXT || m.viewOnce) continue
            n++
            if (m.trOrig != null || m.trLocal != null) {
                val lang = m.trFrom
                if (lang != null && lang != app && translationSeen.add(m.id)) {
                    Translations.setPeerLang(this, conversationId, lang)
                    if (m.trOrig != null) considerLanguage(lang) {}
                }
                continue
            }
            if (!translationSeen.add(m.id)) continue
            Translations.detectMessage(m) { d -> onIncomingLanguage(m, d) }
        }
    }

    private fun onIncomingLanguage(m: MessageEntity, d: Translations.Detected?) {
        if (isFinishing || isDestroyed || d == null) return
        val app = Translations.appLang(this)
        val sure = d.long || (d.confidence >= 0.9f && m.text.count { it.isLetter() } >= 5)
        if (sure) {
            val lang = d.lang?.takeIf { d.confidence >= 0.5f } ?: return
            if (lang == app) return
            Translations.setPeerLang(this, conversationId, lang)
            considerLanguage(lang) { translateIncoming(m, lang, app) }
            return
        }
        val peer = Translations.peerLang(this, conversationId) ?: return
        if (peer == app || Translations.decision(this, conversationId, peer) != Translations.YES) return
        if (d.lang == app && d.confidence >= 0.5f) return
        if (d.lang != null && d.lang != peer && d.confidence >= 0.8f) return
        if (Translations.canTranslate(this, peer, app)) translateIncoming(m, peer, app)
    }

    private fun considerLanguage(lang: String, onYes: () -> Unit) {
        when (Translations.decision(this, conversationId, lang)) {
            Translations.YES -> onYes()
            Translations.NO -> {}
            else -> if (Translations.canTranslate(this, lang, Translations.appLang(this))) showTranslatePill(lang)
        }
    }

    private fun translateIncoming(m: MessageEntity, from: String, to: String) {
        val app = applicationContext
        Translations.translate(this, m.text, from, to) { out ->
            if (out == null || Translations.isBlocked(app, m.conversationId)) return@translate
            AppExecutor.io {
                pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(app).messageDao()
                    .setLocalTranslation(m.id, out, from, to)
            }
        }
    }

    private fun showTranslatePill(lang: String) {
        if (!::undoBar.isInitialized) return
        val pill = translatePill ?: buildTranslatePill().also { translatePill = it }
        val shown = pill.visibility == View.VISIBLE
        if (shown && translatePillLang == lang) return
        translatePillLang = lang
        pill.findViewWithTag<TextView>("pair")?.text = Translations.pair(this, lang, Translations.appLang(this))
        if (shown) return
        pill.animate().cancel()
        pill.visibility = View.VISIBLE
        pill.alpha = 0f
        pill.translationY = 28 * resources.displayMetrics.density
        pill.animate().alpha(1f).translationY(0f).setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun hideTranslatePill() {
        val pill = translatePill ?: return
        translatePillLang = null
        if (pill.visibility != View.VISIBLE) return
        pill.animate().cancel()
        pill.animate().alpha(0f).translationY(28 * resources.displayMetrics.density).setDuration(180)
            .withEndAction { pill.visibility = View.GONE }.start()
    }

    private fun answerTranslatePill(yes: Boolean) {
        val lang = translatePillLang ?: return
        Translations.setDecision(this, conversationId, lang, if (yes) Translations.YES else Translations.NO)
        hideTranslatePill()
        if (yes) {
            translationSeen.clear()
            translationScan(allMessages)
        }
    }

    private fun buildTranslatePill(): LinearLayout {
        val d = resources.displayMetrics.density
        val accent = pl.sinusoide.sinuschat.ui.ThemeUi.accent(this)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 26 * d
                setColor(pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(this@ChatActivity, R.attr.sinuschat_surface_variant)
                    ?: 0xFF162431.toInt())
            }
            elevation = 6 * d
            setPadding((14 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            visibility = View.GONE
            isClickable = true
        }
        pill.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_translate)
            setColorFilter(accent)
        }, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()).apply { marginEnd = (10 * d).toInt() })
        pill.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ChatActivity).apply {
                text = getString(R.string.translate_pill)
                textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this@ChatActivity))
            })
            addView(TextView(this@ChatActivity).apply {
                tag = "pair"
                textSize = 12f
                setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(this@ChatActivity))
            })
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (12 * d).toInt() })
        pill.addView(TextView(this).apply {
            text = getString(R.string.yes)
            textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.onAccent(this@ChatActivity))
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(accent) }
            setPadding((16 * d).toInt(), (7 * d).toInt(), (16 * d).toInt(), (7 * d).toInt())
            setOnClickListener { answerTranslatePill(true) }
        })
        pill.addView(TextView(this).apply {
            text = getString(R.string.no)
            textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(this@ChatActivity))
            setPadding((14 * d).toInt(), (7 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
            val ripple = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { answerTranslatePill(false) }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (4 * d).toInt() })
        val parent = undoBar.parent as ViewGroup
        parent.addView(pill, parent.indexOfChild(undoBar), LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            marginStart = (12 * d).toInt(); marginEnd = (12 * d).toInt(); bottomMargin = (10 * d).toInt()
        })
        return pill
    }

    private fun sendMessage() {
        if (blockedFromSending()) return
        if (offerSendTranslation()) return
        if (GroupRepository.isGroup(conversationId)) {
            sendGroupMessage(); return
        }

        linkPreviewDraft.visibility = View.GONE
        isLinkPreviewClosedByUser = false
        draftLinkData = null

        if (sharedSecret == null) {
            if (stagedAttachments.isNotEmpty()) {
                PopupHelper.show(this, getString(R.string.loading_key_wait))
                return
            }
            val queued = inputMessage.text.toString().trim()
            if (queued.isEmpty()) return
            inputMessage.text.clear()
            val queuedReply = replyingTo
            cancelReply()
            val queuedTr = takeOutTranslation(queued)
            MessageRepository.queueText(this, conversationId, myPhoneHash, queuedTr?.translated ?: queued,
                queuedReply?.id, queuedReply?.let { ReplyLabel.of(this, it) }, queuedTr?.wire())
            return
        }
        val secret = sharedSecret!!
        val text = inputMessage.text.toString().trim()

        if (stagedAttachments.isNotEmpty() && takeCaption(text)) return

        if (text.isEmpty()) return
        inputMessage.text.clear()
        val reply = replyingTo
        cancelReply()

        val expiresAt: Long? = null
        val tr = takeOutTranslation(text)

        MessageRepository.sendText(
            context = this,
            conversationId = conversationId,
            myHash = myPhoneHash,
            otherHash = otherUserHash,
            sharedSecret = secret,
            text = tr?.translated ?: text,
            replyToId = reply?.id,
            replyToText = reply?.let { ReplyLabel.of(this, it) },
            expiresAtMs = expiresAt,
            translation = tr?.wire()
        )
    }

    private enum class StagedKind { IMAGE, VIDEO, FILE }
    private data class StagedAttachment(
        val uri: Uri,
        val kind: StagedKind,
        var viewOnce: Boolean = false
    )
    private val stagedAttachments = mutableListOf<StagedAttachment>()

    private fun addStagedAttachment(uri: Uri, kind: StagedKind) {
        stagedAttachments.add(StagedAttachment(uri, kind))
        rebuildAttachPreview()
    }

    private fun removeStagedAttachment(item: StagedAttachment) {
        stagedAttachments.remove(item)
        rebuildAttachPreview()
    }

    private fun rebuildAttachPreview() {
        attachPreviewBar.removeAllViews()
        if (stagedAttachments.isEmpty()) {
            attachPreviewScroll.visibility = View.GONE
            refreshSendButtonIcon()
            return
        }
        attachPreviewScroll.visibility = View.VISIBLE
        refreshSendButtonIcon()
        val dp = resources.displayMetrics.density
        val size = (84 * dp).toInt()

        val colorTextSecondary = pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(this)

        for (item in stagedAttachments) {
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (12 * dp).toInt(); bottomMargin = (8 * dp).toInt()
                }
            }
            val thumb = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ContextCompat.getDrawable(this@ChatActivity, R.drawable.bg_attach_thumb)
                clipToOutline = true
            }
            when (item.kind) {
                StagedKind.IMAGE, StagedKind.VIDEO ->
                    Glide.with(this).load(item.uri).centerCrop().into(thumb)
                StagedKind.FILE -> {
                    thumb.setImageResource(pl.sinusoide.sinuschat.util.FileIcons.forName(
                        AttachmentSender.displayName(this, item.uri) ?: item.uri.lastPathSegment.orEmpty()))
                    thumb.setColorFilter(colorTextSecondary)
                    thumb.scaleType = ImageView.ScaleType.CENTER
                }
            }
            cell.addView(thumb)

            val lockOverlay = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                background = Color.parseColor("#99000000").let { android.graphics.drawable.ColorDrawable(it) }
                visibility = if (item.viewOnce) View.VISIBLE else View.GONE
            }
            lockOverlay.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_lock)
                setColorFilter(Color.WHITE)
                layoutParams = FrameLayout.LayoutParams((28*dp).toInt(), (28*dp).toInt(), Gravity.CENTER)
            })
            cell.addView(lockOverlay)

            if (item.kind == StagedKind.VIDEO && !item.viewOnce) {
                cell.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_call_video_on)
                    setColorFilter(0xFFFFFFFF.toInt())
                    layoutParams = FrameLayout.LayoutParams((22*dp).toInt(), (22*dp).toInt(), Gravity.CENTER)
                })
            }

            val x = (22 * dp).toInt()
            cell.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(0xFFFFFFFF.toInt())
                background = ContextCompat.getDrawable(this@ChatActivity, R.drawable.bg_circle_dark)
                setPadding((4*dp).toInt(), (4*dp).toInt(), (4*dp).toInt(), (4*dp).toInt())
                layoutParams = FrameLayout.LayoutParams(x, x, Gravity.TOP or Gravity.END).apply {
                    topMargin = (2*dp).toInt(); marginEnd = (2*dp).toInt()
                }
                isClickable = true
                setOnClickListener { removeStagedAttachment(item) }
            })

            val gd = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    if (GroupRepository.isGroup(conversationId)) {
                        PopupHelper.show(this@ChatActivity,
                            getString(R.string.view_once_not_in_groups))
                        return true
                    }
                    if (item.kind != StagedKind.IMAGE && item.kind != StagedKind.VIDEO) {
                        PopupHelper.show(this@ChatActivity,
                            getString(R.string.view_once_media_only))
                        return true
                    }
                    item.viewOnce = !item.viewOnce
                    lockOverlay.visibility = if (item.viewOnce) View.VISIBLE else View.GONE
                    vibrate(20)
                    if (item.viewOnce) {
                        lockOverlay.alpha = 0f
                        lockOverlay.animate().alpha(1f).setDuration(300).start()
                    }
                    return true
                }
            })
            cell.setOnTouchListener { _, event -> gd.onTouchEvent(event); true }

            attachPreviewBar.addView(cell)
        }
    }

    private fun applyGroupPermissions() {
        if (!GroupRepository.isGroup(conversationId)) return
        if (!::chatInputContainer.isInitialized) return

        val canCall = canCallInGroup()
        btnCall.visibility  = if (canCall) View.VISIBLE else View.GONE
        btnVideo.visibility = if (canCall) View.VISIBLE else View.GONE
        btnWalkie.visibility = if (canCall) View.VISIBLE else View.GONE

        if (voiceRecordingOverlay != null) return

        val canSend = canSendInGroup()
        val bar = groupReadOnlyBar ?: TextView(this).apply {
            text = getString(R.string.group_cant_write)
            textSize = 14f
            setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(this@ChatActivity))
            gravity = Gravity.CENTER
            setBackgroundColor(pl.sinusoide.sinuschat.ui.ThemeUi.surface(this@ChatActivity))
            val d = resources.displayMetrics.density
            setPadding((16 * d).toInt(), (18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt())
            visibility = View.GONE
        }.also { tv ->
            (chatInputContainer.parent as? ViewGroup)?.let { parent ->
                parent.addView(tv, parent.indexOfChild(chatInputContainer) + 1,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            groupReadOnlyBar = tv
        }

        if (canSend) {
            chatInputContainer.visibility = View.VISIBLE
            bar.visibility = View.GONE
        } else {
            chatInputContainer.visibility = View.GONE
            bar.visibility = View.VISIBLE
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(inputMessage.windowToken, 0)
        }
    }

    private val groupSettingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra("groupId") == conversationId) loadGroupProfile()
        }
    }

    private val peerKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == REFRESH_CONVERSATIONS) refreshNameFromCache()
            else if (intent.getStringExtra("hash") == otherUserHash) refreshSharedSecret()
        }
    }

    private fun refreshSharedSecret() {
        if (otherUserHash.isEmpty() || GroupRepository.isGroup(conversationId)) return
        val secret = E2EManager.getSharedSecretCached(this, otherUserHash) ?: return
        val fresh = sharedSecret == null
        sharedSecret = secret
        if (!fresh) return
        btnSend.isEnabled = true
        inputMessage.hint = getString(R.string.chat_input_hint)
        fetchRealName()
    }

    private fun refreshNameFromCache() {
        if (otherUserHash.isEmpty() || GroupRepository.isGroup(conversationId)) return
        val name = pl.sinusoide.sinuschat.util.ContactCache.getName(this, otherUserHash)
            ?.takeIf { it.isNotBlank() && it != otherUserName } ?: return
        otherUserName = name
        toolbarName.text = name
        toolbarAvatar.text = pl.sinusoide.sinuschat.util.AvatarHelper.initialOf(name)
    }

    private fun refreshBlockedUi() {
        refreshSendButtonIcon()
        if (!GroupRepository.isGroup(conversationId)) refreshOtherStatus()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun isContactCutOff(): Boolean =
        !GroupRepository.isGroup(conversationId) &&
                pl.sinusoide.sinuschat.util.BlockList.isCutOff(this, otherUserHash)

    private var lastSendIconRes = 0

    private fun refreshSendButtonIcon() {
        val shouldSend = stagedAttachments.isNotEmpty() || (inputMessage.text?.isNotBlank() == true)

        val res = if (isContactCutOff()) R.drawable.ic_lock
                  else if (shouldSend)   R.drawable.ic_send
                  else                   R.drawable.ic_mic
        if (lastSendIconRes != res) {
            btnSend.setImageResource(res)
            lastSendIconRes = res
        }

        isShowingSend = shouldSend
    }

    private var cameraTempUri: Uri? = null
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraTempUri
        if (success && uri != null) addStagedAttachment(uri, StagedKind.IMAGE)
        cameraTempUri = null
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> openCamera()
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                PopupHelper.showError(this, getString(R.string.camera_permission_settings))
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)))
                } catch (_: Exception) {}
            }
            else -> PopupHelper.showError(this, getString(R.string.camera_permission_chat))
        }
    }

    private fun openCameraWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            openCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun openCamera() {
        try {
            val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
            val file = java.io.File(dir, "cam_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            cameraTempUri = uri
            takePhoto.launch(uri)
        } catch (e: Exception) {
            android.util.Log.e("ChatActivity", "aparat się nie otworzył", e)
            cameraTempUri = null
            PopupHelper.showError(this, getString(R.string.camera_error))
        }
    }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        val granted = map.values.any { it }
        if (granted) fetchAndSendLocation()
        else PopupHelper.showError(this, getString(R.string.no_permission))
    }

    private fun shareLocation() {
        pl.sinusoide.sinuschat.util.SafeEngine.guard(this, pl.sinusoide.sinuschat.util.SafeEngine.Risk.SEND_LOCATION) { shareLocationConfirmed() }
    }

    private fun shareLocationConfirmed() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) fetchAndSendLocation()
        else locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun fetchAndSendLocation() {
        if (blockedFromSending()) return
        val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        var pending: MessageEntity? = null

        try {
            val providers = lm.getProviders(true)
            if (providers.isEmpty()) {
                PopupHelper.showError(this, getString(R.string.location_disabled))
                try { startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
                return
            }

            val target = sendTarget() ?: return
            val map = locationMapBytes()
            val reserved = AttachmentSender.reserve(this, target, AttachmentSender.LOCATION_PENDING, map)
            pending = reserved
            val deliver = { lat: Double, lon: Double ->
                val latS = "%.6f".format(java.util.Locale.US, lat)
                val lonS = "%.6f".format(java.util.Locale.US, lon)
                AttachmentSender.complete(this, target, reserved, "location_${latS}_${lonS}.png", map)
            }

            var bestKnown: android.location.Location? = null
            for (p in providers) {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        val loc = lm.getLastKnownLocation(p)
                        if (loc != null && (bestKnown == null || loc.time > bestKnown.time)) {
                            bestKnown = loc
                        }
                    }
                } catch (e: Exception) {}
            }

            if (bestKnown != null && (System.currentTimeMillis() - bestKnown.time) < 120_000) {
                deliver(bestKnown.latitude, bestKnown.longitude)
                return
            }

            var delivered = false
            val handler = Handler(Looper.getMainLooper())

            val listener = object : androidx.core.location.LocationListenerCompat {
                override fun onLocationChanged(loc: android.location.Location) {
                    if (delivered) return
                    delivered = true
                    lm.removeUpdates(this)
                    handler.removeCallbacksAndMessages(null)
                    deliver(loc.latitude, loc.longitude)
                }
            }

            for (p in providers) {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    }
                } catch (e: Exception) {}
            }

            handler.postDelayed({
                if (!delivered) {
                    delivered = true
                    lm.removeUpdates(listener)
                    if (bestKnown != null) {
                        deliver(bestKnown.latitude, bestKnown.longitude)
                    } else {
                        AttachmentSender.abandon(this@ChatActivity, reserved)
                        PopupHelper.showError(this@ChatActivity, getString(R.string.location_timeout))
                    }
                }
            }, 12_000L)

        } catch (e: Exception) {
            Log.e("ChatActivity", "fetchLocation error", e)
            pending?.let { AttachmentSender.abandon(this, it) }
            PopupHelper.showError(this, getString(R.string.location_error))
        }
    }

    private fun locationMapBytes(): ByteArray {
        val dp = resources.displayMetrics.density
        val width = (300 * dp).toInt()
        val height = (180 * dp).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        canvas.drawColor(android.graphics.Color.parseColor("#D6E4FF"))

        paint.color = android.graphics.Color.parseColor("#F0F5FF")
        paint.strokeWidth = 4 * dp
        val step = (40 * dp).toInt()
        for (i in 0..width step step) canvas.drawLine(i.toFloat(), 0f, i.toFloat(), height.toFloat(), paint)
        for (i in 0..height step step) canvas.drawLine(0f, i.toFloat(), width.toFloat(), i.toFloat(), paint)

        paint.style = android.graphics.Paint.Style.FILL
        val random = java.util.Random(42)
        for (i in 0..12) {
            paint.color = if (random.nextBoolean()) android.graphics.Color.parseColor("#ADC9FF")
                          else android.graphics.Color.parseColor("#94B8FF")
            val w = (20 + random.nextInt(40)) * dp
            val h = (20 + random.nextInt(40)) * dp
            val l = random.nextInt(width).toFloat()
            val t = random.nextInt(height).toFloat()
            canvas.drawRect(l, t, l + w, t + h, paint)
        }

        paint.color = android.graphics.Color.parseColor("#330044CC")
        canvas.drawCircle(width / 2f, height / 2f + (15 * dp), 6 * dp, paint)

        val icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_location)?.mutate()
        icon?.setTint(android.graphics.Color.parseColor("#0055FF"))
        if (icon != null) {
            val size = (56 * dp).toInt()
            val left = (width - size) / 2
            val top = (height - size) / 2 - (10 * dp).toInt()
            icon.setBounds(left, top, left + size, top + size)
            icon.draw(canvas)
        }

        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private fun takeCaption(text: String): Boolean {
        if (text.isEmpty()) { sendStagedAttachments(); return true }
        if (stagedAttachments.all { it.viewOnce }) { sendStagedAttachments(); return false }
        inputMessage.text.clear()
        val reply = replyingTo
        cancelReply()
        val tr = takeOutTranslation(text)
        val group = GroupRepository.isGroup(conversationId)
        sendStagedAttachments(AttachmentSender.Caption(
            text = tr?.translated ?: text,
            replyToId = reply?.id?.let { if (group) it.removeSuffix("_" + myPhoneHash.take(16)) else it },
            replyToText = reply?.let { ReplyLabel.of(this, it) }))
        return true
    }

    private fun sendStagedAttachments(caption: AttachmentSender.Caption? = null) {
        if (blockedFromSending()) return
        if (stagedAttachments.isEmpty()) return
        val target = sendTarget() ?: return
        val staged = stagedAttachments.toList()
        stagedAttachments.clear()
        rebuildAttachPreview()
        val app = applicationContext
        AppExecutor.io {
            val sized = staged.map { it to AttachmentSender.sizeOf(app, it.uri) }
            AppExecutor.main {
                if (isFinishing || isDestroyed) return@main
                val limit = pl.sinusoide.sinuschat.util.FileUploader.MAX_CONTENT
                val fits = sized.filter { it.second <= limit }.map { stagedItem(it.first, target) }
                if (fits.isNotEmpty()) AttachmentSender.send(this, target, fits, caption)
                sendOversized(target, sized.filter { it.second > limit }, if (fits.isEmpty()) caption else null)
            }
        }
    }

    private fun stagedItem(s: StagedAttachment, target: AttachmentSender.Target, packed: java.io.File? = null) =
        AttachmentSender.Item(s.uri, when (s.kind) {
            StagedKind.IMAGE -> AttachmentSender.Kind.IMAGE
            StagedKind.VIDEO -> AttachmentSender.Kind.VIDEO
            StagedKind.FILE -> AttachmentSender.Kind.FILE
        }, s.viewOnce && !target.isGroup, packed = packed)

    private fun sendOversized(
        target: AttachmentSender.Target, list: List<Pair<StagedAttachment, Long>>,
        caption: AttachmentSender.Caption? = null
    ) {
        val (s, size) = list.firstOrNull() ?: return
        val rest = list.drop(1)
        val giveBack = {
            if (caption != null && rest.isEmpty() && !isFinishing && !isDestroyed && inputMessage.text.isNullOrBlank()) {
                inputMessage.setText(caption.text)
                inputMessage.setSelection(inputMessage.text.length)
            }
        }
        val name = AttachmentSender.displayName(this, s.uri) ?: getString(R.string.safe_this_file)
        val compression = pl.sinusoide.sinuschat.util.FileCompression
        val out = java.io.File(AttachmentSender.packedDir(this), java.util.UUID.randomUUID().toString() + ".gz")
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val sheet = pl.sinusoide.sinuschat.ui.CompressSheet.show(this, name) { cancelled.set(true) }
        val app = applicationContext
        AppExecutor.io {
            var lastShown = -1
            val ok = try {
                app.contentResolver.openInputStream(s.uri)?.use { input ->
                    compression.pack(input, out, pl.sinusoide.sinuschat.util.FileUploader.MAX_CONTENT, size, { cancelled.get() }) { p ->
                        val step = (p * 100).toInt()
                        if (step != lastShown) { lastShown = step; runOnUiThread { sheet.progress(p) } }
                    }
                } == true
            } catch (_: Exception) { false }
            AppExecutor.main {
                if (isFinishing || isDestroyed || cancelled.get()) {
                    out.delete()
                    sheet.dismiss()
                    giveBack()
                    return@main
                }
                if (!ok) {
                    sheet.dismiss()
                    PopupHelper.showError(this, getString(R.string.file_too_large_150))
                    giveBack()
                    sendOversized(target, rest, caption)
                    return@main
                }
                sheet.ask(size, out.length()) { yes ->
                    if (yes && !isFinishing && !isDestroyed) {
                        AttachmentSender.send(this, target, listOf(stagedItem(s, target, out)), caption)
                        sendOversized(target, rest)
                    } else {
                        out.delete()
                        giveBack()
                        sendOversized(target, rest, caption)
                    }
                }
            }
        }
    }

    private fun setupKeyboardMedia() {
        ViewCompat.setOnReceiveContentListener(inputMessage, arrayOf("image/*")) { _, payload ->
            if (payload.source != androidx.core.view.ContentInfoCompat.SOURCE_INPUT_METHOD) return@setOnReceiveContentListener payload
            val split = payload.partition { it.uri != null }
            split.first?.clip?.let { clip ->
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                val described = (0 until clip.description.mimeTypeCount)
                    .map { clip.description.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
                sendKeyboardMedia(uris, described)
            }
            split.second
        }
    }

    private fun sendKeyboardMedia(uris: List<Uri>, describedMime: String?) {
        if (uris.isEmpty() || blockedFromSending()) return
        val target = sendTarget() ?: return
        val app = applicationContext
        AppExecutor.io {
            val dir = AttachmentSender.keyboardDir(app).apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000L }?.forEach { it.delete() }
            val items = uris.mapNotNull { uri ->
                val mime = ((try { app.contentResolver.getType(uri) } catch (_: Exception) { null }) ?: describedMime)
                    ?.lowercase()?.takeIf { it.startsWith("image/") } ?: return@mapNotNull null
                val ext = when (mime) { "image/gif" -> "gif"; "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                val file = java.io.File(dir, "${java.util.UUID.randomUUID()}.$ext")
                val copied = try {
                    app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
                } catch (e: Exception) {
                    Log.w("ChatActivity", "Treść z klawiatury nieczytelna: ${e.javaClass.simpleName}")
                    false
                }
                if (!copied || file.length() == 0L) { file.delete(); return@mapNotNull null }
                AttachmentSender.Item(Uri.fromFile(file), AttachmentSender.Kind.IMAGE, false, mime,
                    keepOriginal = mime == "image/gif" || mime == "image/webp")
            }
            AppExecutor.main {
                if (isFinishing || isDestroyed) return@main
                if (items.isEmpty()) PopupHelper.showError(this, getString(R.string.cant_read_file))
                else AttachmentSender.send(this, target, items)
            }
        }
    }

    private fun sendTarget(): AttachmentSender.Target? {
        val isGroup = GroupRepository.isGroup(conversationId)
        val secret = sharedSecret
        if (!isGroup && secret == null) {
            PopupHelper.showError(this, getString(R.string.no_e2e_key))
            return null
        }
        return AttachmentSender.Target(conversationId, myPhoneHash, otherUserHash, secret, isGroup,
            attachmentRecipientCount())
    }

    private fun downloadAndOpenAttachment(item: MessageEntity) {
        if (item.fileId.isNullOrEmpty()) return
        ensureLocal(item) { file ->
            if (file == null) PopupHelper.showError(this, getString(R.string.file_download_failed))
            else openLocalFile(item, file)
        }
    }

    private fun ensureLocal(item: MessageEntity, done: (java.io.File?) -> Unit) {
        AttachmentDownloader.fetch(this, item) { file -> if (!isFinishing && !isDestroyed) done(file) }
    }

    private fun mimeOf(name: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: guessMimeType(name)

    private fun openLocalFile(item: MessageEntity, file: java.io.File) {
        val name = item.attachmentName ?: file.name
        val mime = mimeOf(name)
        if (mime.startsWith("image/")) {
            val album = adapter.albumOf(item.id) ?: listOf(item)
            showMediaViewer(album, album.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            return
        }
        if (!item.viewOnce && MediaManager.isVideo(name)) {
            showVideoPlayer(item, file)
            return
        }
        if (!item.viewOnce && name.substringAfterLast('.', "").lowercase() in HTML_EXT) {
            openExternally(item, file)
            return
        }
        if (!item.viewOnce && pl.sinusoide.sinuschat.util.CodeHighlighter.isCode(name)) {
            showCodeViewer(item, file)
            return
        }
        openExternally(item, file)
    }

    private fun showCodeViewer(item: MessageEntity, file: java.io.File) {
        CodePreview.showViewer(this, file, item.attachmentName ?: file.name) { saveAttachmentToDevice(item) }
    }

    private var mediaHandle: MediaViewers.Handle? = null

    private fun showVideoPlayer(item: MessageEntity, file: java.io.File) {
        showMediaViewer(listOf(item), 0)
    }

    private fun openExternally(item: MessageEntity, file: java.io.File) {
        val name = item.attachmentName ?: file.name
        val mime = mimeOf(name)
        pl.sinusoide.sinuschat.util.SafeEngine.guard(this, pl.sinusoide.sinuschat.util.SafeEngine.Risk.OPEN_FILE, name) {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e: android.content.ActivityNotFoundException) {
                PopupHelper.showError(this, getString(R.string.no_app_to_open))
            } catch (e: Exception) {
                PopupHelper.showError(this, getString(R.string.error_format, e.message))
            }
        }
    }

    private fun saveAttachmentToDevice(item: MessageEntity, confirmed: Boolean = false) {
        if (item.viewOnce || item.fileId.isNullOrEmpty()) return
        if (!confirmed) {
            pl.sinusoide.sinuschat.util.SafeEngine.guard(this, pl.sinusoide.sinuschat.util.SafeEngine.Risk.SAVE_FILE, item.attachmentName ?: getString(R.string.safe_this_file)) {
                saveAttachmentToDevice(item, confirmed = true)
            }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingAction = { saveAttachmentToDevice(item, confirmed = true) }
            requestPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        ensureLocal(item) { file ->
            if (file == null) { PopupHelper.showError(this, getString(R.string.file_download_failed)); return@ensureLocal }
            val name = item.attachmentName ?: file.name
            val mime = mimeOf(name)
            AppExecutor.io {
                val saved = pl.sinusoide.sinuschat.util.DownloadsSaver.save(this, file, name, mime)
                AppExecutor.main {
                    if (saved != null) PopupHelper.showSuccess(this, getString(R.string.saved_to_folder,
                        pl.sinusoide.sinuschat.util.DownloadsSaver.locationFor(this, mime)))
                    else PopupHelper.showError(this, getString(R.string.error_save))
                }
            }
        }
    }

    private fun withAlbums(items: List<MessageEntity>): List<MessageEntity> {
        val ids = items.mapTo(LinkedHashSet()) { it.id }
        val out = items.toMutableList()
        for (m in items) adapter.albumOf(m.id)?.forEach { if (ids.add(it.id)) out += it }
        return out
    }

    private fun showMediaViewer(items: List<MessageEntity>, start: Int) {
        val tapped = items.getOrNull(start) ?: return
        val list = adapter.albumOf(tapped.id) ?: listOf(tapped)
        val idx = list.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
        mediaHandle?.dialog?.dismiss()
        var handle: MediaViewers.Handle? = null
        handle = MediaViewers.showMedia(this, list, idx, { saveAttachmentToDevice(it) }, { m, f -> openExternally(m, f) }) {
            if (mediaHandle === handle) mediaHandle = null
        }
        mediaHandle = handle
    }

    private fun guessMimeType(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun textStyle(tv: TextView) {
        tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun onResume() {
        super.onResume()
        if (::inputMessage.isInitialized) pl.sinusoide.sinuschat.util.PrivacySettings.applyIncognitoKeyboard(inputMessage)
        startCallWatch()
        AttachmentSender.onLocalChanged = { convId ->
            if (convId == conversationId) runOnUiThread { adapter.notifyDataSetChanged() }
        }
        AttachmentSender.onError = { convId, msg ->
            if (convId == conversationId) runOnUiThread { PopupHelper.showError(this, msg) }
        }
        AttachmentDownloader.onChanged = { convId ->
            if (convId == conversationId) runOnUiThread { adapter.notifyDataSetChanged() }
        }

        refreshBlockedUi()
        refreshNameFromCache()
        restorePendingDelete()

        if (pendingOpenSearch) {
            pendingOpenSearch = false
            searchContainer.post { showSearchBar() }
        }

        updateInputVisibility(allMessages)

        chatPollHandler.removeCallbacks(groupStatusRunnable)
        if (GroupRepository.isGroup(conversationId)) {
            chatPollHandler.postDelayed(groupStatusRunnable, GROUP_STATUS_REFRESH_MS)
        }

        adapter.notifyDataSetChanged()

        WebSocketManager.onTypingCallback = { convId, senderHash, typing ->
            if (convId == conversationId) {
                if (typing) onTypingReceived(senderHash)
                else onTypingStopped(senderHash)
            }
        }

        WebSocketManager.onPresenceCallback = { userHash, hb, ls ->
            if (userHash == otherUserHash) {
                updateOtherStatusUI(hb, ls, hb > 0)
            }
        }

        loadDisappearingTtl()
        markReadIfNeeded(allMessages)

        statusRefreshHandler.post(statusRefreshRunnable)
        expirySweepHandler.post(expirySweepRunnable)
        chatPollHandler.post(chatPollRunnable)

        val filter = IntentFilter("pl.sinusoide.sinuschat.DISAPPEARING_REQUEST")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(disappearingRequestReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(disappearingRequestReceiver, filter)
        }
        val gFilter = IntentFilter(GROUP_SETTINGS_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(groupSettingsReceiver, gFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(groupSettingsReceiver, gFilter)
        }
        val kFilter = IntentFilter(pl.sinusoide.sinuschat.util.PeerKeyCache.ACTION_CHANGED)
        kFilter.addAction(REFRESH_CONVERSATIONS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(peerKeyReceiver, kFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(peerKeyReceiver, kFilter)
        }
        refreshSharedSecret()

        if (MessageListenerService.currentlyOpenConversationId != conversationId) {
            MessageListenerService.currentlyOpenConversationId = conversationId
            if (allMessages.isNotEmpty()) {
                val last = allMessages.last().timestampMs
                if (last > lastPublishedReadMs) {
                    lastPublishedReadMs = last
                    MessageRepository.publishRead(this, conversationId, myPhoneHash, last)
                }
            }
        }
    }

    private val chatPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val CHAT_POLL_MS = 4_000L
    private val chatPollRunnable = object : Runnable {
        override fun run() {
            if (!pl.sinusoide.sinuschat.network.WebSocketManager.isConnected()) {
                MessageListenerService.pollNow(this@ChatActivity)
            }
            chatPollHandler.postDelayed(this, CHAT_POLL_MS)
        }
    }

    private val GROUP_STATUS_REFRESH_MS = 10_000L
    private val groupStatusRunnable = object : Runnable {
        override fun run() {
            if (pl.sinusoide.sinuschat.messenger.GroupRepository.isGroup(conversationId)) loadGroupProfile()
            chatPollHandler.postDelayed(this, GROUP_STATUS_REFRESH_MS)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::inputMessage.isInitialized) pl.sinusoide.sinuschat.util.Drafts.save(this, conversationId, inputMessage.text.toString())
        stopCallWatch()
        mediaHandle?.pause()
        AttachmentSender.onLocalChanged = null
        AttachmentSender.onError = null
        AttachmentDownloader.onChanged = null
        try { adapter.stopAllVoice() } catch (_: Exception) {}
        sendStopTyping()
        chatPollHandler.removeCallbacks(chatPollRunnable)
        chatPollHandler.removeCallbacks(groupStatusRunnable)
        pl.sinusoide.sinuschat.network.WebSocketManager.onTypingCallback = null
        pl.sinusoide.sinuschat.network.WebSocketManager.onPresenceCallback = null
        typingHandler.removeCallbacks(hideTypingRunnable)
        if (screenCaptureCallbackRegistered &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                unregisterScreenCaptureCallback {}
                screenCaptureCallbackRegistered = false
            } catch (_: Exception) {}
        }
        statusRefreshHandler.removeCallbacks(statusRefreshRunnable)
        expirySweepHandler.removeCallbacks(expirySweepRunnable)
        try { unregisterReceiver(disappearingRequestReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(groupSettingsReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(peerKeyReceiver) } catch (_: Exception) {}
        if (MessageListenerService.currentlyOpenConversationId == conversationId) {
            MessageListenerService.currentlyOpenConversationId = null
        }
    }

    private fun autoDownloadAttachments(messages: List<MessageEntity>) {
        if (!pl.sinusoide.sinuschat.util.NetworkSettings.canDownloadNow(this)) return
        for (msg in messages) {
            if (msg.viewOnce) continue
            if (msg.fileId != null && msg.fileKey != null &&
                (msg.type == MessageEntity.TYPE_ATTACHMENT)) {
                val fileName = msg.attachmentName ?: "file"
                if (!MediaManager.isDownloaded(this, conversationId, msg.id, fileName)) {
                    AttachmentDownloader.fetch(this, msg, auto = true)
                }
            }
        }
    }

    private fun saveBitmapToGallery(bitmap: android.graphics.Bitmap) {
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            val ok = try {
                val fileName = "SinusChat_${System.currentTimeMillis()}.jpg"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES + "/SinusChat")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val resolver = contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                    true
                } else false
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "Zapis do galerii nie powiódł się", e); false
            }
            pl.sinusoide.sinuschat.util.AppExecutor.main {
                PopupHelper.show(this, if (ok) getString(R.string.saved_to_gallery) else getString(R.string.error_save))
            }
        }
    }

    private fun openViewOnceMedia(msg: MessageEntity) {
        if (msg.fileId == null || msg.fileKey == null) {
            PopupHelper.showError(this, getString(R.string.view_once_unavailable))
            return
        }
        startActivity(Intent(this, ViewOnceActivity::class.java).apply {
            putExtra(ViewOnceActivity.EXTRA_CONV_ID,   conversationId)
            putExtra(ViewOnceActivity.EXTRA_MSG_ID,    msg.id)
            putExtra(ViewOnceActivity.EXTRA_FILE_ID,   msg.fileId)
            putExtra(ViewOnceActivity.EXTRA_FILE_KEY,  msg.fileKey)
            putExtra(ViewOnceActivity.EXTRA_FILE_NAME, msg.attachmentName ?: "plik")
        })
    }

    private fun showFullscreenBitmap(bitmap: android.graphics.Bitmap) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        pl.sinusoide.sinuschat.util.PopupHelper.trackOverlay(dialog)
        val layout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val dp = resources.displayMetrics.density

        val img = ZoomableImageView(this).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(Color.BLACK)
            alpha = 0f; scaleX = 0.8f; scaleY = 0.8f
        }
        layout.addView(img, FrameLayout.LayoutParams(-1, -1))

        val menuBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_download); setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT); setPadding(24, 24, 24, 24)
            setOnClickListener { saveBitmapToGallery(bitmap) }
        }
        layout.addView(menuBtn, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (64 * dp).toInt(); rightMargin = (16 * dp).toInt()
        })

        val closeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT); setPadding(24, 24, 24, 24)
            setOnClickListener { dialog.dismiss() }
        }
        layout.addView(closeBtn, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = (64 * dp).toInt(); leftMargin = (16 * dp).toInt()
        })

        img.setOnClickListener {
            val alpha = if (menuBtn.alpha == 0f) 1f else 0f
            menuBtn.animate().alpha(alpha).setDuration(200).start()
            closeBtn.animate().alpha(alpha).setDuration(200).start()
        }

        dialog.setContentView(layout)
        dialog.show()
        img.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).start()
    }

    private fun applyOtherPrivacyMode(enabled: Boolean) {
        if (enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            if (!screenCaptureCallbackRegistered &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    registerScreenCaptureCallback(mainExecutor) { notifyScreenshotAttempt() }
                    screenCaptureCallbackRegistered = true
                } catch (e: SecurityException) {
                    android.util.Log.w("ChatActivity", "ScreenCapture unavailable: ${e.message}")
                }
            }
        } else {
            if (!pl.sinusoide.sinuschat.util.PrivacySettings.isCaptureBlockEnabled(this)) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    private val voiceRecorder = pl.sinusoide.sinuschat.util.VoiceRecorder(this)
    private var voiceRecordingStartX = 0f
    private var voiceRecordingStartY = 0f
    private var voiceLocked = false
    private var voiceRecordingOverlay: View? = null
    private var voiceTimerView: TextView? = null
    private var voiceDotView: View? = null
    private var voiceDotAnimator: android.animation.ValueAnimator? = null
    private var voiceLiveWave: pl.sinusoide.sinuschat.messenger.WaveformView? = null
    private val liveWaveBars = java.util.ArrayList<Float>()
    private var voiceLockHintView: TextView? = null
    private var voiceLockIcon: ImageView? = null
    private val voiceHandler = Handler(Looper.getMainLooper())

    private fun vibrate(ms: Long) {
        try {
            val vib = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(ms)
            }
        } catch (_: Exception) {}
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupVoiceRecording() {
        btnSend.setOnTouchListener { v, event ->
            if (isContactCutOff()) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    blockedFromSending()
                }
                return@setOnTouchListener true
            }
            if (isShowingSend) return@setOnTouchListener false
            if (voiceLocked) {
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    resetSendButtonDrag()
                }
                return@setOnTouchListener true
            }
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (blockedFromSending()) return@setOnTouchListener true
                    voiceRecordingStartX = event.rawX
                    voiceRecordingStartY = event.rawY
                    if (checkVoicePermission()) { vibrate(20); startVoiceRecording() }
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaX = voiceRecordingStartX - event.rawX
                    val deltaY = voiceRecordingStartY - event.rawY
                    if (deltaY > 160 && !voiceLocked) { vibrate(40); lockVoiceRecording() }
                    else if (deltaX > 160) {
                        voiceTimerView?.setTextColor(Color.RED)
                        voiceTimerView?.text = getString(R.string.cancel)
                        voiceLockHintView?.visibility = View.GONE
                        voiceRecordingOverlay?.alpha = (1f - (deltaX - 160) / 200f).coerceIn(0.3f, 1f)
                    } else {
                        voiceRecordingOverlay?.alpha = 1f; updateVoiceTimer()
                        btnSend.translationX = (-deltaX).coerceIn(-100f, 0f)
                        btnSend.translationY = (-deltaY).coerceIn(-100f, 0f)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    btnSend.translationX = 0f; btnSend.translationY = 0f
                    if (voiceLocked) return@setOnTouchListener true
                    if (voiceRecordingStartX - event.rawX > 160) cancelVoiceRecording()
                    else { vibrate(20); stopAndSendVoice() }
                    true
                }
                else -> false
            }
        }
    }

    private fun checkVoicePermission(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 300)
        return false
    }

    private fun startVoiceRecording() {
        if (!voiceRecorder.start()) {
            PopupHelper.showError(this, getString(R.string.cant_record))
            return
        }
        showVoiceRecordingUI()
        voiceLiveWave?.startAnimation()
        voiceHandler.post(voiceTimerRunnable)
    }

    private val voiceTimerRunnable = object : Runnable {
        override fun run() { updateVoiceTimer(); voiceHandler.postDelayed(this, 200) }
    }

    private fun updateVoiceTimer() {
        val s = voiceRecorder.durationSeconds
        voiceTimerView?.text = "%d:%02d".format(s / 60, s % 60)
        voiceLiveWave?.let { wv ->
            val amp = (voiceRecorder.getAmplitude() / 100f).coerceIn(0.05f, 1f)
            liveWaveBars.add(amp)
            if (liveWaveBars.size > 46) liveWaveBars.removeAt(0)
            wv.setAmplitudes(liveWaveBars.toFloatArray())
        }
    }

    private fun resetSendButtonDrag() {
        btnSend.animate().cancel()
        btnSend.translationX = 0f; btnSend.translationY = 0f
    }

    private fun lockVoiceRecording() {
        voiceLocked = true; vibrate(30)
        resetSendButtonDrag()
        voiceLockIcon?.visibility = View.VISIBLE
        voiceLockHintView?.text = getString(R.string.voice_hint_locked)
    }

    private fun showVoiceRecordingUI() {
        val dp = resources.displayMetrics.density
        val inputContainer = findViewById<View>(R.id.chat_input_container)
        val parent = inputContainer.parent as? ViewGroup ?: return

        val flowUi = ThemeManager.isFlow(this)
        val overlay = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            if (!flowUi) {
                setBackgroundColor(pl.sinusoide.sinuschat.ui.ThemeUi.sheet(this@ChatActivity))
                elevation = 4 * dp
            }
            setPadding((8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt())
        }

        val trash = ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete); setBackgroundResource(0)
            setColorFilter(Color.RED); setPadding((11*dp).toInt(), (11*dp).toInt(), (11*dp).toInt(), (11*dp).toInt())
            setOnClickListener { cancelVoiceRecording() }
        }
        overlay.addView(trash, android.widget.LinearLayout.LayoutParams((44*dp).toInt(), (44*dp).toInt()))

        val capsule = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = if (flowUi) pl.sinusoide.sinuschat.ui.GlassDrawable(dp, null, false, 28f * dp, null)
            else GradientDrawable().apply {
                cornerRadius = 28f * dp; setColor(pl.sinusoide.sinuschat.ui.ThemeUi.card(this@ChatActivity))
            }
            setPadding((12*dp).toInt(), (6*dp).toInt(), (12*dp).toInt(), (6*dp).toInt())
        }

        val dot = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.RED)
            }
        }
        capsule.addView(dot, android.widget.LinearLayout.LayoutParams((8*dp).toInt(), (8*dp).toInt()).apply { marginEnd = (8*dp).toInt() })

        voiceLiveWave = WaveformView(this).apply {
            pl.sinusoide.sinuschat.ui.ThemeUi.accent(this@ChatActivity).let { a -> setColors(a, (a and 0x00FFFFFF) or 0x40000000) }
        }
        capsule.addView(voiceLiveWave, LinearLayout.LayoutParams(0, (24*dp).toInt(), 1f))

        voiceTimerView = TextView(this).apply { text = "0:00"; setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this@ChatActivity)) }
        capsule.addView(voiceTimerView, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8*dp).toInt() })

        voiceLockIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock); setColorFilter(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(this@ChatActivity)); visibility = View.GONE
        }
        capsule.addView(voiceLockIcon, LinearLayout.LayoutParams((18*dp).toInt(), (18*dp).toInt()).apply { marginStart = (8*dp).toInt() })

        overlay.addView(capsule, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (6*dp).toInt(); marginEnd = (6*dp).toInt() })

        val send = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send); setColorFilter(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pl.sinusoide.sinuschat.ui.ThemeUi.accent(this@ChatActivity)) }
            setPadding((11*dp).toInt(), (11*dp).toInt(), (11*dp).toInt(), (11*dp).toInt())
            setOnClickListener { stopAndSendVoice() }
        }
        overlay.addView(send, LinearLayout.LayoutParams((44*dp).toInt(), (44*dp).toInt()))
        if (flowUi) {
            pl.sinusoide.sinuschat.ui.FlowGlass.glass(trash, 22f, oval = true)
            pl.sinusoide.sinuschat.ui.FlowGlass.glass(send, 22f, oval = true,
                tint = pl.sinusoide.sinuschat.ui.ThemeUi.accent(this))
        }

        parent.addView(overlay, parent.indexOfChild(inputContainer) + 1)
        inputContainer.visibility = View.GONE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(120).start()
        voiceRecordingOverlay = overlay; voiceDotView = dot; startVoiceDotPulse()
    }

    private fun startVoiceDotPulse() {
        voiceDotAnimator?.cancel()
        voiceDotAnimator = android.animation.ValueAnimator.ofFloat(1f, 0.35f, 1f).apply {
            duration = 1600; repeatCount = -1
            addUpdateListener { voiceDotView?.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun hideVoiceRecordingUI() {
        val ov = voiceRecordingOverlay ?: return
        voiceRecordingOverlay = null
        voiceDotAnimator?.cancel()
        voiceLocked = false
        ov.animate().cancel()
        val parent = ov.parent as? ViewGroup
        if (parent != null) {
            parent.overlay.add(ov)
            ov.animate().alpha(0f).setDuration(150).withEndAction { parent.overlay.remove(ov) }.start()
        }
        resetSendButtonDrag()
        val container = findViewById<View>(R.id.chat_input_container)
        container.visibility = View.VISIBLE; container.alpha = 0f
        container.animate().alpha(1f).setDuration(180).start()
    }

    private fun cancelVoiceRecording() {
        voiceHandler.removeCallbacks(voiceTimerRunnable)
        voiceLiveWave?.stopAnimation(); voiceRecorder.cancel(); hideVoiceRecordingUI()
    }

    private fun stopAndSendVoice() {
        if (blockedFromSending()) { voiceRecorder.stop(); hideVoiceRecordingUI(); return }
        voiceHandler.removeCallbacks(voiceTimerRunnable)
        voiceLiveWave?.stopAnimation()
        val file = voiceRecorder.stop()
        val durationSec = voiceRecorder.lastDurationSeconds
        hideVoiceRecordingUI()

        if (file == null || durationSec < 1) {
            if (durationSec < 1) PopupHelper.show(this, getString(R.string.recording_too_short))
            return
        }

        AppExecutor.io {
            try {
                val up = pl.sinusoide.sinuschat.util.FileUploader.uploadVoiceBlocking(this, file)
                if (up == null) {
                    file.delete()
                    AppExecutor.main { PopupHelper.showError(this, getString(R.string.error_sending_voice)) }
                    return@io
                }
                val secret = sharedSecret ?: run { file.delete(); return@io }
                MessageRepository.sendVoice(this, conversationId, myPhoneHash, otherUserHash, secret,
                    up.first, up.second, durationSec, file)
                file.delete()
            } catch (e: Exception) {
                AppExecutor.main { PopupHelper.showError(this, getString(R.string.error_format, e.message)) }
            }
        }
    }

    private fun attachmentRecipientCount(): Int {
        if (!GroupRepository.isGroup(conversationId)) return 1
        val known = groupMemberNames.size - 1
        return if (known > 0) known.coerceAtMost(500) else 50
    }

    private fun insertKeyChangeSystemMessage() {
        val entity = MessageEntity(
            id = "sys_keychange_${otherUserHash}_${System.currentTimeMillis()}",
            conversationId = conversationId, senderHash = "",
            text = getString(R.string.safety_number_changed_sys_msg),
            timestampMs = System.currentTimeMillis(), type = "sys_key_change",
            isSystemMessage = true, status = MessageEntity.STATUS_READ
        )
        AppExecutor.local { pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(this).messageDao().insert(entity) }
    }

    private fun notifyScreenshotAttempt() {
        if (conversationId.isEmpty() || myPhoneHash.isEmpty()) return
        AppExecutor.io {
            val payload = org.json.JSONObject().put("senderHash", myPhoneHash).put("timestamp", System.currentTimeMillis())
            val body = org.json.JSONObject().apply {
                put("conversationId", conversationId); put("messageId", java.util.UUID.randomUUID().toString())
                put("kind", "screenshot_attempt"); put("recipientHash", otherUserHash)
                put("payloadB64", android.util.Base64.encodeToString(payload.toString().toByteArray(), android.util.Base64.NO_WRAP))
            }
            pl.sinusoide.sinuschat.crypto.SealedSender.post(this, body)
        }
    }

    private val disappearingRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "pl.sinusoide.sinuschat.DISAPPEARING_REQUEST") {
                val cid = intent.getStringExtra("conversationId")
                if (cid == conversationId) {
                    updateInputVisibility(allMessages)
                }
            }
        }
    }

    private val disappearingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val ttl = result.data?.getLongExtra("selectedTtlMs", 0L) ?: 0L
            val trigger = result.data?.getStringExtra("selectedTrigger") ?: DisappearingMessages.TRIGGER_READ
            proposeDisappearing(ttl, trigger)
        }
    }

    private fun proposeDisappearing(newTtl: Long, newTrigger: String) {
        val applied = DisappearingCoordinator.propose(
            this, conversationId, otherUserHash, newTtl, newTrigger)
        if (applied) {
            disappearingTtlMs = newTtl
            disappearingTrigger = newTrigger
        }
    }

    private fun handleDisappearingSettingsChange(newTtl: Long, newTrigger: String, byMe: Boolean) {
        disappearingTtlMs = newTtl
        disappearingTrigger = newTrigger
        DisappearingCoordinator.applyEffective(
            this, conversationId, otherUserHash, newTtl, newTrigger, announce = byMe)
    }

    private fun sendDisappearingSignal(action: String, ttlMs: Long, trigger: String) {
        DisappearingCoordinator.sendSignal(
            this, conversationId, otherUserHash, action, ttlMs, trigger)
    }

    private fun insertSystemMessage(text: String, type: String) {
        val entity = MessageEntity(
            id = "sys_${type}_${System.currentTimeMillis()}",
            conversationId = conversationId, senderHash = "",
            text = text, timestampMs = System.currentTimeMillis(), type = type,
            isSystemMessage = true, status = MessageEntity.STATUS_READ
        )
        AppExecutor.local { pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(this).messageDao().insert(entity) }
    }

    private fun loadDisappearingTtl() {
        if (conversationId.isEmpty()) return
        val localTtl = pl.sinusoide.sinuschat.util.DisappearingMessages.getTtl(this, conversationId)
        disappearingTtlMs = localTtl
        disappearingTrigger = pl.sinusoide.sinuschat.util.DisappearingMessages.getTrigger(this, conversationId)
    }
}
