package pl.sinusoide.sinuschat.messenger

import pl.sinusoide.sinuschat.util.optStringOrNull

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import pl.sinusoide.sinuschat.util.Timestamp
import org.json.JSONArray
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.crypto.DbKeyManager
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.BlockList
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.MuteSettings
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.PrivacySettings

class MessengerFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var tvTitle: TextView
    private lateinit var fab: FloatingActionButton
    private lateinit var adapter: ConversationListAdapter

    private fun pushUnreadBadge() {
        if (!isAdded) return
        val count = adapter.allConversations.count { it.unreadCount > 0 && !it.isMuted }
        (activity as? pl.sinusoide.sinuschat.MainActivity)?.setChatsBadge(count)
    }
    private lateinit var offlineBanner: View

    private var myPhoneHash: String = ""

    private val refreshReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            if (isOnline && myPhoneHash.isNotEmpty()) loadConversations()
        }
    }

    private var isOnline = true
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isOnline = true
            view?.post { updateOfflineBanner(false); loadConversations() }
        }
        override fun onLost(network: Network) {
            val up = networkUp()
            isOnline = up
            view?.post { updateOfflineBanner(!up) }
        }
    }

    private fun networkUp(): Boolean {
        val cm = connectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private val privacyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "super_privacy_mode" || key == "show_message_preview" || key == "privacy_hide_last_message") {
            if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        }
    }

    private val avatarColorListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isOnline) loadConversations()
            updateOfflineBanner(!isOnline)
            refreshHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_messenger, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val header = view.findViewById<View>(R.id.messenger_header)
        if (pl.sinusoide.sinuschat.util.ThemeManager.isRetro(requireContext()))
            pl.sinusoide.sinuschat.ui.RetroUi.styleTitleBar(header)
        ViewCompat.setOnApplyWindowInsetsListener(header) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
            insets
        }

        myPhoneHash = SetupActivity.getUserPhoneHash(requireContext())

        recycler      = view.findViewById(R.id.conversations_recycler)
        emptyView     = view.findViewById(R.id.empty_view)
        tvTitle       = view.findViewById(R.id.tv_app_title)
        fab           = view.findViewById(R.id.fab_new_chat)
        offlineBanner = view.findViewById(R.id.offline_banner)

        setupGreeting()

        connectivityManager = requireContext().getSystemService(ConnectivityManager::class.java)
        isOnline = networkUp()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        updateOfflineBanner(!isOnline)

        ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin =
                (20 * resources.displayMetrics.density).toInt() + bars.bottom
            v.layoutParams = v.layoutParams
            insets
        }

        adapter = ConversationListAdapter(myPhoneHash,
            onClick = { conv ->
                openingChat = true
                val updated = adapter.allConversations.map {
                    if (it.id == conv.id) it.copy(unreadCount = 0) else it
                }
                adapter.submitList(updated)

                startActivity(Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra("conversationId", conv.id)
                    putExtra("otherUserName", conv.otherUserName)
                    putExtra("otherUserHash", conv.otherUserHash)
                    putExtra("isGroup", GroupRepository.isGroup(conv.id))
                })
            },
            onLongClick = { conv, anchor -> showConversationMenu(conv, anchor) }
        )
        adapter.pins = { context?.let { pl.sinusoide.sinuschat.util.ChatPins.all(it) } ?: emptyMap() }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        if (pl.sinusoide.sinuschat.util.ThemeManager.isRetro(requireContext())) {
            adapter.retro = true
            val d = resources.displayMetrics.density
            recycler.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(pl.sinusoide.sinuschat.ui.RetroUi.PANEL)
                setStroke(d.toInt().coerceAtLeast(1), pl.sinusoide.sinuschat.ui.RetroUi.FRAME)
            }
            (recycler.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.setMargins((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), 0)
                recycler.layoutParams = it
            }
            recycler.setPadding(0, 0, 0, recycler.paddingBottom)
        }
        recycler.adapter = adapter
        listLens = pl.sinusoide.sinuschat.ui.FlowLens.attach(recycler, emptyList())
        adapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() = pushUnreadBadge()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = pushUnreadBadge()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = pushUnreadBadge()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = pushUnreadBadge()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = pushUnreadBadge()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = pushUnreadBadge()
        })

        fab.setOnClickListener { startActivity(Intent(requireContext(), AddHubActivity::class.java)) }
        setupSearch(view.findViewById(R.id.messenger_header), view as android.widget.FrameLayout)

        val d = resources.displayMetrics.density
        val accent = pl.sinusoide.sinuschat.ui.ThemeUi.accent(requireContext())
        fab.shapeAppearanceModel = fab.shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(18 * d).build()
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 6 && fab.isShown) fab.hide()
                else if (dy < -6 && !fab.isShown) fab.show()
            }
        })
        view.findViewById<android.widget.ImageView>(R.id.empty_icon)?.apply {
            setColorFilter(accent)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor((accent and 0x00FFFFFF) or 0x24000000)
            }
        }
        view.findViewById<View>(R.id.empty_new_chat)?.setOnClickListener {
            startActivity(Intent(requireContext(), AddHubActivity::class.java))
        }
        recycler.layoutAnimation = android.view.animation.LayoutAnimationController(
            android.view.animation.AnimationSet(true).apply {
                addAnimation(android.view.animation.AlphaAnimation(0f, 1f))
                addAnimation(android.view.animation.TranslateAnimation(0f, 0f, 14 * d, 0f))
                duration = 260
                interpolator = android.view.animation.DecelerateInterpolator()
            }, 0.06f)

        renderFromCache()
        if (myPhoneHash.isNotEmpty()) loadConversations()
    }

    override fun onStart() {
        super.onStart()
        requireContext().getSharedPreferences("sinuschat_privacy", android.content.Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(privacyListener)
        requireContext().getSharedPreferences("sinuschat_avatar_prefs", android.content.Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(avatarColorListener)
    }

    override fun onStop() {
        super.onStop()
        requireContext().getSharedPreferences("sinuschat_privacy", android.content.Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(privacyListener)
        requireContext().getSharedPreferences("sinuschat_avatar_prefs", android.content.Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(avatarColorListener)
    }

    override fun onResume() {
        super.onResume()
        openingChat = false
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        if (isOnline) loadConversations()
        updateOfflineBanner(!isOnline)
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 30_000L)
        try {
            requireContext().registerReceiver(
                refreshReceiver,
                android.content.IntentFilter("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS"),
                android.content.Context.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}
    }

    override fun onPause() {
        if (searchOpen && !openingChat) closeSearch(animate = false)
        conversationMenu?.dismiss()
        conversationMenu = null
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
        try { requireContext().unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshHandler.removeCallbacksAndMessages(null)
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    private fun renderFromCache() {
        val ctx = context ?: return
        val arr = pl.sinusoide.sinuschat.util.ConversationCache.load(ctx) ?: return
        
        val cachedItems = parseConversations(ctx, arr, applyAcks = false)
        if (cachedItems.isNotEmpty()) {
            animateListOnce(cachedItems.isNotEmpty())
            adapter.submitList(cachedItems)
            emptyView.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }

        AppExecutor.io {
            val enriched = enrichWithPreviewsSync(ctx, cachedItems)
            if (enriched != cachedItems) {
                AppExecutor.main { adapter.submitList(enriched) }
            }
        }
    }

    private fun parseConversations(
        ctx: android.content.Context,
        arr: JSONArray,
        applyAcks: Boolean
    ): List<ConversationItem> {
        val conversations = mutableListOf<ConversationItem>()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val convId  = j.optString("id")
            val isGroup = j.optBoolean("is_group", false) || GroupRepository.isGroup(convId)

            val otherHash = if (isGroup) "" else j.optString("other_hash")
            val groupColor = j.optStringOrNull("group_avatar_color") ?: "#5288C1"

            val displayName = if (isGroup) {
                j.optStringOrNull("group_name") ?: getString(R.string.group)
            } else {
                val freshName = j.optStringOrNull("other_name")
                ContactCache.bestName(ctx, otherHash, freshName)
            }

            val freshAvatar = j.optStringOrNull("avatar_url")
            if (!isGroup && freshAvatar != null && freshAvatar.startsWith("http")) ContactCache.saveAvatarUrl(ctx, otherHash, freshAvatar)
            if (isGroup) ContactCache.saveAvatarUrl(ctx, convId, freshAvatar)
            val avatarUrl = if (isGroup) freshAvatar else ContactCache.getAvatarUrl(ctx, otherHash)
                ?: freshAvatar?.takeIf { !pl.sinusoide.sinuschat.crypto.ProfileCrypto.isEncryptedAvatar(it) }

            val lastMsgAt = j.optLong("last_message_at", 0)
            val isMuted = MuteSettings.isMuted(ctx, convId)
            
            val cachedText = ctx.getSharedPreferences("sinuschat_last_msg_cache", Context.MODE_PRIVATE)
                .getString("msg_$convId", "") ?: ""

            conversations.add(
                ConversationItem(
                    id            = convId,
                    otherUserHash = otherHash,
                    otherUserName = displayName,
                    lastMessage   = cachedText,
                    lastMessageAt = Timestamp(lastMsgAt / 1000, 0),
                    unreadCount   = j.optInt("unread_count", 0),
                    avatarUrl     = avatarUrl,
                    isGroup       = isGroup,
                    groupColor    = groupColor,
                    isMuted       = isMuted,
                    isOnline      = j.optBoolean("is_online", false)
                )
            )
            if (applyAcks && !isGroup && !pl.sinusoide.sinuschat.util.BlockList.isBlockedByThem(ctx, otherHash)) {
                MessageRepository.applyServerAcks(ctx, convId, myPhoneHash,
                    j.optLong("other_delivered_up_to", 0), j.optLong("other_read_up_to", 0),
                    repair = MessageRepository.needsAckRepair(ctx))
            }
        }
        return conversations
    }

    private fun loadConversations() {
        val ctx = context ?: return
        AppExecutor.io {
            val server: JSONArray = ApiClient.getArray("/api/conversations", ctx)
                ?: return@io
            val arr = pl.sinusoide.sinuschat.util.LocalChats.merge(ctx, myPhoneHash, server)

            pl.sinusoide.sinuschat.crypto.GroupMeta.openConversations(ctx, arr)
            pl.sinusoide.sinuschat.util.ConversationCache.save(ctx, arr)
            MessageListenerService.syncPeerKeys(ctx, arr)
            val conversations = parseConversations(ctx, arr, applyAcks = true)
            MessageRepository.markAckRepairDone(ctx)
            
            val enriched = enrichWithPreviewsSync(ctx, conversations)

            AppExecutor.main {
                if (!isAdded) return@main
                animateListOnce(enriched.isNotEmpty())
                adapter.submitList(enriched)
                showEmpty(enriched.isEmpty())
                recycler.visibility  = if (enriched.isEmpty()) View.GONE  else View.VISIBLE
            }
        }
    }

    private fun enrichWithPreviewsSync(ctx: Context, list: List<ConversationItem>): List<ConversationItem> {
        if (DbKeyManager.isEnabled(ctx) && DbKeyManager.cachedKey() == null) return list
        
        val db = SinusChatDatabase.getInstance(ctx)
        val photoExts = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif")
        val fastCache = ctx.getSharedPreferences("sinuschat_last_msg_cache", Context.MODE_PRIVATE).edit()
        
        val enrichedList = list.map { conv ->
            val last = db.messageDao().getLastMessage(conv.id)
            if (last == null) {
                fastCache.remove("msg_${conv.id}")
                return@map conv.copy(lastMessage = "") 
            }
            val text = when {
                last.type == MessageEntity.TYPE_ATTACHMENT -> {
                    val name = last.attachmentName ?: ""
                    val isPhoto = photoExts.any { name.lowercase().endsWith(it) } || 
                                 name.contains("SinusChat_") || name.contains("location_")
                    if (isPhoto) getString(R.string.photo_cd) else name.ifEmpty { getString(R.string.attachment) }
                }
                last.type == MessageEntity.TYPE_VOICE -> getString(R.string.voice_message)
                last.text.isNotEmpty() -> last.text
                else -> ""
            }
            if (text.isNotEmpty()) {
                fastCache.putString("msg_${conv.id}", text)
                conv.copy(lastMessage = text)
            } else {
                conv.copy(lastMessage = "")
            }
        }
        fastCache.apply()
        return enrichedList
    }

    private var bannerQuiet: Boolean? = null

    private fun updateOfflineBanner(offline: Boolean) {
        if (!isAdded) return
        val ctx = requireContext()
        val quiet = !offline && pl.sinusoide.sinuschat.util.NotificationPrefs.isQuietNow(ctx)
        val show = offline || quiet
        if (show && bannerQuiet != quiet) {
            bannerQuiet = quiet
            val icon = offlineBanner.findViewById<android.widget.ImageView>(R.id.offline_banner_icon)
            val text = offlineBanner.findViewById<android.widget.TextView>(R.id.offline_banner_text)
            if (quiet) {
                val purple = 0xFFA684EC.toInt()
                offlineBanner.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14 * resources.displayMetrics.density
                    setColor(0x269B6BE0)
                    setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), 0x599B6BE0)
                }
                icon.setImageResource(R.drawable.ic_moon)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(purple)
                text.setTextColor(purple)
                text.text = getString(if (pl.sinusoide.sinuschat.util.NotificationPrefs.quietAllowCalls(ctx))
                    R.string.quiet_banner_msgs else R.string.quiet_banner_all)
            } else {
                val orange = 0xFFF0A155.toInt()
                offlineBanner.setBackgroundResource(R.drawable.bg_offline_banner)
                icon.setImageResource(R.drawable.ic_offline)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(orange)
                text.setTextColor(orange)
                text.text = getString(R.string.offline_mode_no_connection)
            }
        } else if (show && quiet) {
            offlineBanner.findViewById<android.widget.TextView>(R.id.offline_banner_text).text =
                getString(if (pl.sinusoide.sinuschat.util.NotificationPrefs.quietAllowCalls(ctx))
                    R.string.quiet_banner_msgs else R.string.quiet_banner_all)
        }
        val visible = offlineBanner.visibility == View.VISIBLE
        if (show == visible) return
        if (show) {
            offlineBanner.visibility = View.VISIBLE
            offlineBanner.alpha = 0f; offlineBanner.translationY = -8 * resources.displayMetrics.density
            offlineBanner.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else {
            offlineBanner.animate().alpha(0f).setDuration(180)
                .withEndAction { if (isAdded) offlineBanner.visibility = View.GONE }.start()
        }
    }

    private var listAnimated = false

    private fun animateListOnce(hasItems: Boolean) {
        if (listAnimated || !hasItems) return
        listAnimated = true
        recycler.scheduleLayoutAnimation()
    }

    private var searchField: android.widget.EditText? = null
    private var searchButton: android.widget.ImageView? = null
    private var searchPill: View? = null
    private var searchBackBtn: android.widget.ImageView? = null
    private var searchNone: View? = null
    private var searchOpen = false
    private var openingChat = false
    private val searchBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearch()
    }

    private fun setupSearch(header: android.widget.LinearLayout, root: android.widget.FrameLayout) {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val fg = pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(ctx)
        val dim = pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(ctx)
        val accent = pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx)
        val flow = pl.sinusoide.sinuschat.util.ThemeManager.isFlow(ctx)

        val btn = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(fg)
            val p = (10 * d).toInt(); setPadding(p, p, p, p)
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.chats_search)
            setOnClickListener { openSearch() }
        }
        header.addView(btn, android.widget.LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        searchButton = btn

        val clear = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(dim)
            val p = (8 * d).toInt(); setPadding(p, p, p, p)
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_menu_ripple)
            visibility = View.GONE
            contentDescription = getString(R.string.settings_search_clear)
        }
        val back = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(fg)
            val p = (9 * d).toInt(); setPadding(p, p, p, p)
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.back)
            setOnClickListener { closeSearch() }
        }
        searchBackBtn = back
        val field = android.widget.EditText(ctx).apply {
            hint = getString(R.string.chats_search_hint)
            setHintTextColor((dim and 0x00FFFFFF) or (0xCC shl 24))
            setTextColor(fg)
            textSize = 15.5f
            background = null
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            setOnEditorActionListener { v, _, _ -> hideKeyboard(v); true }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString().orEmpty()
                    clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                    adapter.query = q
                    if (q.isBlank()) searchNone?.visibility = View.GONE
                }
            })
        }
        searchField = field
        clear.setOnClickListener { field.setText(""); field.requestFocus() }
        val pill = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = if (flow) pl.sinusoide.sinuschat.ui.GlassDrawable(d, null, false, 23 * d, null)
            else android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 23 * d
                setColor(pl.sinusoide.sinuschat.ui.ThemeUi.card(ctx))
                setStroke((1 * d).toInt().coerceAtLeast(1), (accent and 0x00FFFFFF) or 0x55000000)
            }
            setPadding((2 * d).toInt(), 0, (4 * d).toInt(), 0)
            visibility = View.INVISIBLE
            elevation = 6 * d
            outlineProvider = null
            addView(back, android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply {
                marginEnd = (2 * d).toInt()
            })
            addView(field, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(clear, android.widget.LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt()))
        }
        root.addView(pill, android.widget.FrameLayout.LayoutParams(-1, (46 * d).toInt(), android.view.Gravity.TOP).apply {
            marginStart = (10 * d).toInt(); marginEnd = (10 * d).toInt()
        })
        header.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val lp = pill.layoutParams as android.widget.FrameLayout.LayoutParams
            val top = v.top + v.paddingTop + (v.height - v.paddingTop - v.paddingBottom - lp.height) / 2
            if (lp.topMargin != top) { lp.topMargin = top; pill.post { pill.layoutParams = lp } }
        }
        searchPill = pill
        pl.sinusoide.sinuschat.ui.PillWarmUp.prime(pill) { searchOpen }

        val none = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding((32 * d).toInt(), 0, (32 * d).toInt(), 0)
        }
        val noneText = TextView(ctx).apply {
            textSize = 15f
            setTextColor(dim)
            gravity = android.view.Gravity.CENTER
        }
        none.addView(noneText)
        none.addView(TextView(ctx).apply {
            text = getString(R.string.new_chat_title)
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_btn_primary)
            setPadding((22 * d).toInt(), (12 * d).toInt(), (22 * d).toInt(), (12 * d).toInt())
            isClickable = true
            setOnClickListener { startActivity(Intent(requireContext(), AddHubActivity::class.java)) }
        }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = (18 * d).toInt() })
        root.addView(none, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP).apply {
            topMargin = (150 * d).toInt()
        })
        searchNone = none

        adapter.onFiltered = { shown ->
            val q = adapter.query
            if (q.isEmpty() || shown > 0) none.visibility = View.GONE
            else {
                noneText.text = getString(R.string.chats_search_none, q)
                none.visibility = View.VISIBLE
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBack)
    }

    private fun pivotX(pill: View, icon: View): Int {
        val a = IntArray(2); val b = IntArray(2)
        icon.getLocationInWindow(a); pill.getLocationInWindow(b)
        return (a[0] + icon.width / 2 - b[0]).coerceIn(0, pill.width)
    }

    private fun openSearch() {
        val pill = searchPill ?: return
        val icon = searchButton ?: return
        val field = searchField ?: return
        if (searchOpen) return
        searchOpen = true
        searchBack.isEnabled = true
        val d = resources.displayMetrics.density

        tvTitle.animate().alpha(0f).translationX(-16 * d).setStartDelay(0).setDuration(170).start()
        icon.animate().alpha(0f).setStartDelay(0).setDuration(150).start()
        fab.hide()
        pill.animate().cancel()
        pill.scaleX = 1f; pill.scaleY = 1f
        pill.alpha = 0f
        pill.visibility = View.VISIBLE
        searchBackBtn?.apply { animate().cancel(); rotation = 180f; alpha = 0f }
        pill.postOnAnimation {
            if (!searchOpen || !isAdded) return@postOnAnimation
            pill.postOnAnimation {
                if (!searchOpen || !isAdded) return@postOnAnimation
                val duration = startPillOpen(pill, icon)
                searchBackBtn?.animate()?.rotation(0f)?.alpha(1f)?.setStartDelay(90)?.setDuration(300)
                    ?.setInterpolator(android.view.animation.DecelerateInterpolator())?.start()
                field.postDelayed({
                    if (!searchOpen || !isAdded) return@postDelayed
                    field.requestFocus()
                    requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }, (duration - 80L).coerceAtLeast(0L))
            }
        }
    }

    private fun startPillOpen(pill: View, icon: View): Long {
        if (!pill.isAttachedToWindow || pill.width == 0) {
            pill.alpha = 1f
            return 0L
        }
        val cx = pivotX(pill, icon)
        val cy = pill.height / 2
        return if (pl.sinusoide.sinuschat.util.ThemeManager.isFlow(requireContext())) {
            pill.pivotX = cx.toFloat(); pill.pivotY = cy.toFloat()
            pill.scaleX = 0.12f; pill.scaleY = 0.9f; pill.alpha = 0f
            pill.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0).setDuration(440)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
            440L
        } else {
            pill.alpha = 1f
            android.view.ViewAnimationUtils.createCircularReveal(pill, cx, cy, 0f,
                kotlin.math.hypot(pill.width.toDouble(), pill.height.toDouble()).toFloat()).apply {
                duration = 340
                interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                start()
            }
            340L
        }
    }

    private fun closeSearch(animate: Boolean = true) {
        val pill = searchPill ?: return
        val icon = searchButton ?: return
        val field = searchField ?: return
        if (!searchOpen) return
        searchOpen = false
        searchBack.isEnabled = false
        hideKeyboard(field)
        val hadQuery = field.text.isNotEmpty()
        val finish = {
            if (!searchOpen && isAdded) {
                field.clearFocus()
                if (hadQuery || field.text.isNotEmpty()) field.setText("")
                searchNone?.visibility = View.GONE
                if (adapter.query.isNotEmpty()) adapter.query = ""
                fab.show()
            }
        }

        if (!animate || !pill.isAttachedToWindow || pill.width == 0) {
            pill.animate().cancel()
            pill.visibility = View.INVISIBLE
            pill.scaleX = 1f; pill.scaleY = 1f; pill.alpha = 1f
            tvTitle.animate().cancel(); tvTitle.alpha = 1f; tvTitle.translationX = 0f
            icon.animate().cancel(); icon.alpha = 1f
            finish()
            return
        }
        val cx = pivotX(pill, icon)
        val duration: Long
        if (pl.sinusoide.sinuschat.util.ThemeManager.isFlow(requireContext())) {
            duration = 260L
            pill.animate().cancel()
            pill.pivotX = cx.toFloat(); pill.pivotY = pill.height / 2f
            pill.animate().scaleX(0.12f).scaleY(0.9f).alpha(0f).setStartDelay(0).setDuration(duration)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction {
                    if (!searchOpen) {
                        pill.visibility = View.INVISIBLE
                        pill.scaleX = 1f; pill.scaleY = 1f; pill.alpha = 1f
                    }
                }.start()
        } else {
            duration = 280L
            android.view.ViewAnimationUtils.createCircularReveal(pill, cx, pill.height / 2,
                kotlin.math.hypot(pill.width.toDouble(), pill.height.toDouble()).toFloat(), 0f).apply {
                this.duration = duration
                interpolator = android.view.animation.AccelerateInterpolator(1.2f)
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!searchOpen) pill.visibility = View.INVISIBLE
                    }
                })
                start()
            }
        }
        tvTitle.animate().alpha(1f).translationX(0f).setStartDelay(120).setDuration(220).start()
        icon.animate().alpha(1f).setStartDelay(160).setDuration(200).start()
        pill.postDelayed({ finish() }, duration + 40L)
    }

    private fun hideKeyboard(v: View) {
        requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun showEmpty(empty: Boolean) {
        if (!isAdded) return
        if (empty && emptyView.visibility != View.VISIBLE) {
            emptyView.visibility = View.VISIBLE
            emptyView.alpha = 0f; emptyView.translationY = 12 * resources.displayMetrics.density
            emptyView.animate().alpha(1f).translationY(0f).setDuration(260).start()
            fab.show()
        } else if (!empty) {
            emptyView.visibility = View.GONE
        }
    }

    private fun setupGreeting() {
        val prefs = requireContext().getSharedPreferences("sinuschat_greetings", android.content.Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val lastDay = prefs.getString("last_day", "")
        var count = prefs.getInt("open_count", 0)

        if (today != lastDay) {
            count = 1
            prefs.edit().putString("last_day", today).putInt("open_count", count).apply()
        } else {
            count++
            prefs.edit().putInt("open_count", count).apply()
        }

        val name = SetupActivity.getUserDisplayName(requireContext()).split(" ").firstOrNull() ?: ""
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val random = java.util.Random()

        val possibleGreetings = mutableListOf<Int>()

        if (hour < 6 || hour >= 22) possibleGreetings.add(R.string.greet_night)
        if (hour in 6..11) possibleGreetings.add(R.string.greet_morning)
        if (hour in 12..17) possibleGreetings.add(R.string.greet_afternoon)
        if (hour in 18..21) possibleGreetings.add(R.string.greet_evening)
        if (hour >= 16) possibleGreetings.add(R.string.greet_day_status)

        when (dayOfWeek) {
            java.util.Calendar.MONDAY -> possibleGreetings.add(R.string.greet_monday)
            java.util.Calendar.TUESDAY -> if (random.nextBoolean()) possibleGreetings.add(R.string.greet_tuesday)
            java.util.Calendar.WEDNESDAY -> if (random.nextBoolean()) possibleGreetings.add(R.string.greet_wednesday)
            java.util.Calendar.THURSDAY -> possibleGreetings.add(R.string.greet_thursday)
            java.util.Calendar.FRIDAY -> {
                possibleGreetings.add(R.string.greet_friday)
                if (random.nextBoolean()) possibleGreetings.add(R.string.greet_friday_feeling)
            }
            java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY -> {
                if (dayOfWeek == java.util.Calendar.SATURDAY) possibleGreetings.add(R.string.greet_saturday)
                else possibleGreetings.add(R.string.greet_sunday)
                if (random.nextBoolean()) possibleGreetings.add(R.string.greet_weekend)
            }
        }

        possibleGreetings.add(R.string.greet_hey)
        possibleGreetings.add(R.string.greet_hi)
        possibleGreetings.add(R.string.greet_how_going)
        possibleGreetings.add(R.string.greet_welcome)
        possibleGreetings.add(R.string.greet_whats_new)

        if (count >= 10) possibleGreetings.add(R.string.greet_returns)

        val selectedRes = possibleGreetings[random.nextInt(possibleGreetings.size)]
        val greetingText = getString(selectedRes, name)

        val title = tvTitle
        val d = resources.displayMetrics.density
        title.ellipsize = null
        title.setHorizontallyScrolling(true)
        title.isHorizontalFadingEdgeEnabled = true
        title.setFadingEdgeLength((20 * d).toInt())
        title.scrollTo(0, 0)
        title.text = greetingText
        title.alpha = 0f

        fun restore() {
            if (!isAdded) return
            title.animate().alpha(0f).setDuration(500).withEndAction {
                if (!isAdded) return@withEndAction
                title.scrollTo(0, 0)
                title.isHorizontalFadingEdgeEnabled = false
                title.setHorizontallyScrolling(false)
                title.ellipsize = android.text.TextUtils.TruncateAt.END
                title.text = getString(R.string.app_name)
                title.animate().alpha(1f).setDuration(500).start()
            }.start()
        }

        title.animate().alpha(1f).setDuration(500).withEndAction {
            if (!isAdded) return@withEndAction
            val room = title.width - title.totalPaddingLeft - title.totalPaddingRight
            val textWidth = title.layout?.getLineWidth(0)?.toInt() ?: 0
            val overflow = textWidth - room
            if (room <= 0 || overflow <= (2 * d).toInt()) {
                title.postDelayed({ restore() }, 3000)
                return@withEndAction
            }
            val travel = overflow + (4 * d).toInt()
            val scrollMs = (travel / (70 * d) * 1000).toLong().coerceIn(900L, 3200L)
            title.postDelayed({
                if (!isAdded) return@postDelayed
                android.animation.ObjectAnimator.ofInt(title, "scrollX", 0, travel).apply {
                    duration = scrollMs
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            title.postDelayed({ restore() }, 1400)
                        }
                    })
                    start()
                }
            }, 900)
        }.start()
    }

    private var listLens: pl.sinusoide.sinuschat.ui.FlowLens.Handle? = null
    private var conversationMenu: pl.sinusoide.sinuschat.ui.GlassMenu.Handle? = null

    private fun showConversationMenu(conv: ConversationItem, anchor: View) {
        val activity = activity ?: return
        conversationMenu?.dismiss()
        val Item = pl.sinusoide.sinuschat.ui.GlassMenu::Item
        val isGroup = conv.isGroup || GroupRepository.isGroup(conv.id)
        val pinned = pl.sinusoide.sinuschat.util.ChatPins.isPinned(activity, conv.id)
        val items = mutableListOf(
            Item(R.drawable.ic_pin, getString(if (pinned) R.string.unpin else R.string.pin), false) { togglePin(conv) },
            Item(R.drawable.ic_notifications_off, getString(R.string.mute_notifications), false) { showMuteOptions(conv) },
        )
        if (conv.unreadCount > 0) items += Item(R.drawable.ic_check_double, getString(R.string.mark_as_read), false) { markRead(conv) }
        if (!isGroup && conv.otherUserHash.isNotEmpty()) {
            items += Item(R.drawable.ic_edit, getString(R.string.local_name_action), false) {
                LocalNameSheet.show(activity, conv.otherUserHash, conv.otherUserName) { if (isAdded) loadConversations() }
            }
            if (BlockList.isBlocked(activity, conv.otherUserHash))
                items += Item(R.drawable.ic_block, getString(R.string.unblock), false) { unblockUser(conv) }
            else
                items += Item(R.drawable.ic_block, getString(R.string.block), true) { blockUser(conv) }
        }
        items += if (isGroup) Item(R.drawable.ic_leave, getString(R.string.leave_group), true) { confirmDelete(conv) }
                 else Item(R.drawable.ic_delete, getString(R.string.delete_chat), true) { confirmDelete(conv) }
        conversationMenu = pl.sinusoide.sinuschat.ui.GlassMenu.show(activity, anchor, items, atRow = true, lens = listLens)
    }

    private fun togglePin(conv: ConversationItem) {
        val ctx = activity ?: return
        val pin = !pl.sinusoide.sinuschat.util.ChatPins.isPinned(ctx, conv.id)
        pl.sinusoide.sinuschat.util.ChatPins.setPinned(ctx, conv.id, pin)
        adapter.submitList(adapter.allConversations) {
            if (pin && isAdded) recycler.smoothScrollToPosition(0)
        }
        PopupHelper.show(ctx, getString(if (pin) R.string.pinned else R.string.unpinned))
    }

    private fun showMuteOptions(conv: ConversationItem) {
        val i = android.content.Intent(requireContext(), MuteSettingsActivity::class.java).apply {
            putExtra("conversationId", conv.id)
            putExtra("currentMutedUntil", pl.sinusoide.sinuschat.util.MuteSettings.getMutedUntil(requireContext(), conv.id))
        }
        muteLauncher.launch(i)
    }

    private val muteLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val until = result.data?.getLongExtra("selectedMutedUntil", 0L) ?: 0L
            val convId = result.data?.getStringExtra("conversationId")
            if (convId != null) {
                val ctx = requireContext()
                val before = pl.sinusoide.sinuschat.util.MuteSettings.getMutedUntil(ctx, convId)
                pl.sinusoide.sinuschat.util.MuteSettings.setMutedUntil(ctx, convId, until)
                pl.sinusoide.sinuschat.util.MuteSettings.changeMessage(ctx, before, until)?.let {
                    PopupHelper.show(requireActivity(), it)
                }
                val muted = pl.sinusoide.sinuschat.util.MuteSettings.isMuted(ctx, convId)
                adapter.submitList(adapter.allConversations.map {
                    if (it.id == convId) it.copy(isMuted = muted) else it
                })
            }
        }
    }

    private fun markRead(conv: ConversationItem) {
        val ctx = requireContext()
        MessageRepository.publishRead(ctx, conv.id,
            pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(ctx), System.currentTimeMillis())
    }

    private fun confirmDelete(conv: ConversationItem) {
        val ctx = activity ?: return
        val isGroup = conv.isGroup || GroupRepository.isGroup(conv.id)
        DeleteChatSheet.show(
            ctx, conv.id, conv.otherUserName, isGroup,
            otherHash = conv.otherUserHash,
            avatarUrl = conv.avatarUrl,
            groupColor = conv.groupColor,
            lastMessage = conv.lastMessage,
            onConfirm = {
                removeConversation(ctx, conv)
                PopupHelper.show(ctx, getString(R.string.chat_deleted))
            }
        )
    }

    private fun removeConversation(ctx: android.app.Activity, conv: ConversationItem) {
        pl.sinusoide.sinuschat.util.ChatPins.setPinned(ctx, conv.id, false)
        val filtered = adapter.allConversations.filter { it.id != conv.id }
        adapter.submitList(filtered)
        showEmpty(filtered.isEmpty())
        MessageRepository.deleteConversation(ctx, conv.id, myPhoneHash) {
            AppExecutor.main {
                if (isAdded) loadConversations()
            }
        }
    }

    private fun blockUser(conv: ConversationItem) {
        val ctx = activity ?: return
        BlockSheet.show(
            ctx, conv.otherUserName, conv.otherUserHash, conv.avatarUrl,
            allowDelete = true,
            onBlock = { deleteChat ->
                BlockList.block(ctx, conv.otherUserHash)
                if (deleteChat) removeConversation(ctx, conv)
                else adapter.notifyDataSetChanged()
                PopupHelper.show(ctx, getString(R.string.blocked_toast, conv.otherUserName))
            }
        )
    }

    private fun unblockUser(conv: ConversationItem) {
        val ctx = activity ?: return
        BlockList.unblock(ctx, conv.otherUserHash)
        adapter.notifyDataSetChanged()
        PopupHelper.show(ctx, getString(R.string.unblocked_toast))
    }
}

data class ConversationItem(
    val id: String,
    val otherUserHash: String,
    val otherUserName: String,
    val lastMessage: String,
    val lastMessageAt: Timestamp,
    val unreadCount: Int = 0,
    val avatarUrl: String? = null,
    val isGroup: Boolean = false,
    val groupColor: String = "#5288C1",
    val isMuted: Boolean = false,
    val isOnline: Boolean = false,
    val isPinned: Boolean = false
)
