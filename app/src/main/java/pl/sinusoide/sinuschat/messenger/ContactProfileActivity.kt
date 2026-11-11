package pl.sinusoide.sinuschat.messenger

import pl.sinusoide.sinuschat.util.optStringOrNull

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.DisappearingMessages
import java.util.*

class ContactProfileActivity : AppCompatActivity() {
    private val tc by lazy { pl.sinusoide.sinuschat.ui.ThemeColors(this) }
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }

    private var otherUserHash = ""
    private var otherUserName = ""
    private var conversationId = ""
    private var myPhoneHash = ""
    private var currentTtlMs = 0L
    
    private lateinit var avatarView: ImageView
    private lateinit var nameView: TextView
    private lateinit var realNameView: TextView
    private lateinit var statusView: TextView
    private var realName: String? = null
    private lateinit var disappearingSubtitle: TextView
    private lateinit var muteSubtitle: TextView
    
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        
        otherUserHash = intent.getStringExtra("otherUserHash") ?: ""
        otherUserName = intent.getStringExtra("otherUserName") ?: getString(R.string.user_generic)
        conversationId = intent.getStringExtra("conversationId") ?: ""
        myPhoneHash = SetupActivity.getUserPhoneHash(this)

        buildUI()
        
        loadProfile()
        loadDisappearingTtl()
        loadMuteStatus()

        if (intent.getBooleanExtra("openSafetyNumber", false)) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                showSafetyNumber()
            }, 400)
        }
    }

    private fun buildUI() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tc.bg)
        }
        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val top = maxOf(b.top, insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top)
            v.setPadding(0, top, 0, b.bottom); insets
        }
        ViewCompat.requestApplyInsets(outer)

        outer.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundResource(0); setColorFilter(tc.text)
            setPadding((14*dp).toInt(), (14*dp).toInt(), (14*dp).toInt(), (14*dp).toInt())
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((52*dp).toInt(), (52*dp).toInt()))

        val root = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        outer.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer)

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(0, (8*dp).toInt(), 0, (20*dp).toInt())
        }
        avatarView = ImageView(this).apply {
            setImageBitmap(AvatarHelper.createLetterBitmap(
                AvatarHelper.initialOf(otherUserName), "#5288C1", (110*dp).toInt()))
        }
        center.addView(avatarView, LinearLayout.LayoutParams((110*dp).toInt(), (110*dp).toInt()).apply {
            bottomMargin = (14*dp).toInt()
        })
        nameView = TextView(this).apply {
            text = otherUserName; textSize = 24f; setTextColor(tc.text)
            gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        center.addView(nameView)
        realNameView = TextView(this).apply {
            textSize = 13f; setTextColor(tc.text2)
            gravity = Gravity.CENTER; setPadding(0, (2*dp).toInt(), 0, 0)
            visibility = View.GONE
        }
        center.addView(realNameView)
        statusView = TextView(this).apply {
            text = ""; textSize = 14f; setTextColor(tc.text2)
            gravity = Gravity.CENTER; setPadding(0, (4*dp).toInt(), 0, 0)
        }
        center.addView(statusView)
        content.addView(center)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 0, 0, (20*dp).toInt())
        }
        actions.addView(actionButton(getString(R.string.call_audio), R.drawable.ic_call_answer) { startCall(false) })
        actions.addView(actionButton(getString(R.string.action_video), R.drawable.ic_call_video_on) { startCall(true) })
        actions.addView(actionButton(getString(R.string.search), R.drawable.ic_search) { openContactSearch() })
        content.addView(actions)

        content.addView(separator())

        content.addView(bottomRow(getString(R.string.local_name_action), R.drawable.ic_edit, false, tc.accent) {
            LocalNameSheet.show(this, otherUserHash, realName ?: otherUserName) { applyName() }
        })

        content.addView(bottomRow(getString(R.string.media_links_docs), R.drawable.ic_image, false, tc.accent) {
            startActivity(Intent(this, MediaGalleryActivity::class.java).apply {
                putExtra("conversationId", conversationId)
                putExtra("otherUserName", otherUserName)
            })
        })

        val disRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
            isClickable = true
            setOnClickListener { showDisappearingScreen() }
        }
        disRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_clock); setColorFilter(tc.accent)
        }, LinearLayout.LayoutParams((24*dp).toInt(), (24*dp).toInt()).apply { marginEnd = (18*dp).toInt() })
        val disCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        disCol.addView(TextView(this).apply {
            text = getString(R.string.chat_disappearing); textSize = 16f; setTextColor(tc.text)
        })
        disappearingSubtitle = TextView(this).apply {
            text = getString(R.string.disappearing_messages_sub); textSize = 13f; setTextColor(tc.text2)
        }
        disCol.addView(disappearingSubtitle)
        disRow.addView(disCol)
        content.addView(disRow)

        val muteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
            isClickable = true
            setOnClickListener { showMuteScreen() }
        }
        muteRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_notifications_off); setColorFilter(tc.accent)
        }, LinearLayout.LayoutParams((24*dp).toInt(), (24*dp).toInt()).apply { marginEnd = (18*dp).toInt() })
        val muteCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        muteCol.addView(TextView(this).apply {
            text = getString(R.string.mute_notifications); textSize = 16f; setTextColor(tc.text)
        })
        muteSubtitle = TextView(this).apply {
            text = getString(R.string.mute_off); textSize = 13f; setTextColor(tc.text2)
        }
        muteCol.addView(muteSubtitle)
        muteRow.addView(muteCol)
        content.addView(muteRow)

        pl.sinusoide.sinuschat.ui.TranslateBlockRow.create(this, conversationId, false, otherUserHash,
            { realName ?: otherUserName }, tc.text, tc.text2, tc.accent)?.let { content.addView(it) }

        content.addView(bottomRow(getString(R.string.verify_key_title), R.drawable.ic_shield, false, tc.accent) {
            showSafetyNumber()
        })

        content.addView(separator())

        content.addView(bottomRow(getString(R.string.clear_chat), R.drawable.ic_delete, false, 0xFFF39C12.toInt()) {
            confirmClearChat()
        })

        if (pl.sinusoide.sinuschat.util.BlockList.isBlocked(this, otherUserHash)) {
            content.addView(bottomRow(getString(R.string.unblock), R.drawable.ic_block, false) {
                pl.sinusoide.sinuschat.util.BlockList.unblock(this, otherUserHash)
                PopupHelper.show(this, getString(R.string.unblocked_toast))
                finish()
            })
        } else {
            content.addView(bottomRow(getString(R.string.block_user), R.drawable.ic_block, true) {
                confirmBlockUser()
            })
        }
    }

    private fun actionButton(label: String, icon: Int, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding((14*dp).toInt(), 0, (14*dp).toInt(), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
        col.addView(ImageView(this).apply {
            setImageResource(icon); setColorFilter(tc.text)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(tc.input)
            }
            setPadding((14*dp).toInt(), (14*dp).toInt(), (14*dp).toInt(), (14*dp).toInt())
        }, LinearLayout.LayoutParams((52*dp).toInt(), (52*dp).toInt()))
        col.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(tc.text2)
            gravity = Gravity.CENTER; setPadding(0, (6*dp).toInt(), 0, 0)
        })
        return col
    }

    private fun separator(): View = View(this).apply {
        setBackgroundColor(tc.input)
        layoutParams = LinearLayout.LayoutParams(-1, (1*dp).toInt()).apply {
            topMargin = (4*dp).toInt(); bottomMargin = (4*dp).toInt()
        }
    }

    private fun bottomRow(label: String, icon: Int, danger: Boolean, iconTint: Int? = null, onClick: () -> Unit): View {
        val color = if (danger) 0xFFFF4444.toInt() else tc.text
        val iconColor = iconTint ?: color
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
            isClickable = true
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(icon); setColorFilter(iconColor)
            }, LinearLayout.LayoutParams((24*dp).toInt(), (24*dp).toInt()).apply {
                marginEnd = (18*dp).toInt()
            })
            addView(TextView(context).apply {
                text = label; textSize = 16f; setTextColor(color)
            })
        }
    }

    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, 15_000L)
        }
    }

    private fun applyStatusText(isOnline: Boolean, lastSeenMs: Long) {
        if (pl.sinusoide.sinuschat.util.BlockList.isCutOff(this, otherUserHash)) {
            statusView.text = ""
            statusView.visibility = View.GONE
            return
        }
        statusView.visibility = View.VISIBLE
        when {
            isOnline -> {
                statusView.text = getString(R.string.active_now)
                statusView.setTextColor(Color.parseColor("#2ECC71"))
            }
            lastSeenMs > 0 -> {
                pl.sinusoide.sinuschat.util.LastSeenText.show(statusView, lastSeenMs)
                statusView.setTextColor(Color.parseColor("#8AA0B8"))
            }
            else -> {
                statusView.text = ""
            }
        }
    }

    private fun refreshStatus() {
        if (otherUserHash.isEmpty()) return
        AppExecutor.io {
            val doc = ApiClient.getObject("/api/user/$otherUserHash", this) ?: return@io
            val heartbeatMs = doc.optLong("last_heartbeat_ms", 0)
            val lastSeenMs  = doc.optLong("last_seen_ms", 0)
            val isOnline    = doc.optBoolean("is_online",
                heartbeatMs > 0 && pl.sinusoide.sinuschat.util.ServerTime.ageMs(heartbeatMs) < 45_000)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyStatusText(isOnline, maxOf(lastSeenMs, heartbeatMs))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        statusHandler.post(statusRunnable)
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRunnable)
    }

    private fun applyName() {
        val nick = ContactCache.getNick(this, otherUserHash)
        val real = realName ?: ContactCache.realName(this, otherUserHash)
        otherUserName = nick ?: real ?: otherUserName
        nameView.text = otherUserName
        if (nick != null && !real.isNullOrBlank() && real != nick) {
            realNameView.text = getString(R.string.local_name_real, real)
            realNameView.visibility = View.VISIBLE
        } else {
            realNameView.visibility = View.GONE
        }
    }

    private fun loadProfile() {
        applyName()
        avatarView.clipToOutline = true
        val cachedColor = AvatarHelper.getContactColor(this, otherUserHash)
        
        val size = (110 * dp).toInt()
        val letterBmp = AvatarHelper.createLetterBitmap(
            AvatarHelper.initialOf(otherUserName), 
            cachedColor, 
            size
        )
        val placeholder = BitmapDrawable(resources, letterBmp)

        val cachedAvatarUrl = ContactCache.getAvatarUrl(this, otherUserHash)
        
        fun updateClickable(hasPhoto: Boolean, url: String?) {
            val photoUrl = url?.takeIf { hasPhoto && it.isNotEmpty() }
            avatarView.setOnClickListener { showFullScreenAvatar(photoUrl, placeholder) }
            avatarView.isClickable = true
            avatarView.isEnabled = true
            avatarView.isFocusable = true
        }

        updateClickable(!cachedAvatarUrl.isNullOrEmpty(), cachedAvatarUrl)

        if (!cachedAvatarUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(cachedAvatarUrl)
                .signature(ObjectKey(cachedAvatarUrl))
                .circleCrop()
                .placeholder(placeholder)
                .error(placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(avatarView)
        } else {
            avatarView.setImageDrawable(placeholder)
        }

        AppExecutor.io {
            try {
                val doc = ApiClient.getObject("/api/user/$otherUserHash", this) ?: return@io
                val fresh       = pl.sinusoide.sinuschat.crypto.ProfileCrypto.openName(this, otherUserHash, doc.optStringOrNull("display_name"))
                val lastSeenMs  = maxOf(doc.optLong("last_seen_ms", 0),
                                        doc.optLong("last_heartbeat_ms", 0))
                val isOnline    = doc.optBoolean("is_online", false)
                val rawAvatar   = doc.optStringOrNull("avatar_url")
                val avatarUrl   = pl.sinusoide.sinuschat.crypto.ProfileCrypto.displayAvatar(this, otherUserHash, rawAvatar)
                val avatarColor = doc.optStringOrNull("avatar_color")

                if (!avatarColor.isNullOrEmpty()) {
                    AvatarHelper.setContactColor(this, otherUserHash, avatarColor)
                }
                pl.sinusoide.sinuschat.util.ContactCache.saveAvatarUrl(this, otherUserHash, rawAvatar)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (!fresh.isNullOrBlank()) {
                        realName = fresh
                        ContactCache.saveName(this, otherUserHash, fresh)
                    }
                    applyName()
                    applyStatusText(isOnline, lastSeenMs)
                    
                    updateClickable(!avatarUrl.isNullOrEmpty(), avatarUrl)

                    if (!avatarUrl.isNullOrEmpty()) {
                        Glide.with(this)
                            .load(avatarUrl)
                            .signature(ObjectKey(avatarUrl))
                            .circleCrop()
                            .placeholder(placeholder)
                            .error(placeholder)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .into(avatarView)
                    } else {
                        avatarView.setImageDrawable(placeholder)
                    }
                }
            } catch (e: Exception) {
                Log.e("ContactProfile", "loadProfile błąd: ${e.message}")
            }
        }
    }

    private fun showFullScreenAvatar(url: String?, placeholder: android.graphics.drawable.Drawable) {
        val saveName = otherUserName.ifBlank { "SinusChat" }
        pl.sinusoide.sinuschat.ui.PhotoViewer.show(this, onDownload = { img ->
            if (!url.isNullOrEmpty()) {
                pl.sinusoide.sinuschat.util.AvatarSaver.saveFrom(this, url, { bigLetterBitmap() }, saveName)
            } else {
                pl.sinusoide.sinuschat.util.AvatarSaver.save(this,
                    pl.sinusoide.sinuschat.util.AvatarSaver.letter(otherUserName, AvatarHelper.getContactColor(this, otherUserHash)), saveName)
            }
        }) { img ->
            if (!url.isNullOrEmpty()) {
                Glide.with(this)
                    .load(url)
                    .placeholder(placeholder)
                    .error(placeholder)
                    .into(img)
            } else {
                img.setImageBitmap(bigLetterBitmap())
            }
        }
    }

    private fun bigLetterBitmap(): android.graphics.Bitmap {
        val m = resources.displayMetrics
        val size = minOf(minOf(m.widthPixels, m.heightPixels), (420 * dp).toInt())
            .coerceAtLeast((160 * dp).toInt())
        return AvatarHelper.createLetterBitmap(
            AvatarHelper.initialOf(otherUserName),
            AvatarHelper.getContactColor(this, otherUserHash),
            size
        )
    }

    private fun startCall(video: Boolean) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) perms.add(Manifest.permission.CAMERA)
        val missing = perms.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            pl.sinusoide.sinuschat.call.CallActivity.startAsCaller(
                this, conversationId, otherUserHash, otherUserName, video)
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, missing.toTypedArray(), if (video) 201 else 200)
        }
    }

    private fun openContactSearch() {
        ChatActivity.pendingOpenSearch = true
        finish()
    }

    private fun loadDisappearingTtl() {
        if (conversationId.isEmpty()) return
        val localTtl = pl.sinusoide.sinuschat.util.DisappearingMessages.getTtl(this, conversationId)
        val localTrigger = DisappearingMessages.getTrigger(this, conversationId)
        currentTtlMs = localTtl
        disappearingSubtitle.text = subtitleFor(localTtl, localTrigger)
    }

    private fun subtitleFor(ttlMs: Long, trigger: String): String {
        val awaiting = DisappearingMessages.getAwaitingConsent(this, conversationId)
        return if (awaiting != null) getString(R.string.disappearing_waiting_consent, otherUserName)
               else DisappearingMessages.fullLabel(this, ttlMs, trigger)
    }

    private fun showDisappearingScreen() {
        val intent = Intent(this, DisappearingSettingsActivity::class.java).apply {
            putExtra("conversationId", conversationId)
            putExtra("currentTtlMs", currentTtlMs)
            putExtra("currentTrigger", DisappearingMessages.getTrigger(this@ContactProfileActivity, conversationId))
        }
        disappearingLauncher.launch(intent)
    }

    private val disappearingLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val ttl = result.data?.getLongExtra("selectedTtlMs", 0L) ?: 0L
            val trigger = result.data?.getStringExtra("selectedTrigger") ?: DisappearingMessages.TRIGGER_READ
            requestDisappearing(ttl, trigger)
        }
    }

    private fun showMuteScreen() {
        val intent = Intent(this, MuteSettingsActivity::class.java).apply {
            putExtra("conversationId", conversationId)
            putExtra("currentMutedUntil", pl.sinusoide.sinuschat.util.MuteSettings.getMutedUntil(this@ContactProfileActivity, conversationId))
        }
        muteLauncher.launch(intent)
    }

    private val muteLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val until = result.data?.getLongExtra("selectedMutedUntil", 0L) ?: 0L
            val before = pl.sinusoide.sinuschat.util.MuteSettings.getMutedUntil(this, conversationId)
            pl.sinusoide.sinuschat.util.MuteSettings.setMutedUntil(this, conversationId, until)
            muteSubtitle.text = pl.sinusoide.sinuschat.util.MuteSettings.label(this, until)
            pl.sinusoide.sinuschat.util.MuteSettings.changeMessage(this, before, until)?.let { PopupHelper.show(this, it) }
        }
    }

    private fun loadMuteStatus() {
        val until = pl.sinusoide.sinuschat.util.MuteSettings.getMutedUntil(this, conversationId)
        muteSubtitle.text = pl.sinusoide.sinuschat.util.MuteSettings.label(this, until)
    }

    private fun requestDisappearing(ttlMs: Long, trigger: String) {
        if (conversationId.isEmpty()) return
        val applied = pl.sinusoide.sinuschat.util.DisappearingCoordinator.propose(
            this, conversationId, otherUserHash, ttlMs, trigger)
        if (applied) {
            currentTtlMs = ttlMs
            disappearingSubtitle.text = DisappearingMessages.fullLabel(this, ttlMs, trigger)
        } else {
            disappearingSubtitle.text = getString(R.string.disappearing_waiting_consent, otherUserName)
        }
    }

    private fun confirmClearChat() {
        AlertDialog.Builder(this, R.style.Theme_SinusChat_Dialog)
            .setTitle(getString(R.string.clear_chat))
            .setMessage(getString(R.string.clear_chat_local_desc))
            .setPositiveButton(getString(R.string.clear_word)) { _, _ ->
                MessageRepository.clearChatLocal(this, conversationId)
                PopupHelper.show(this, getString(R.string.chat_cleared_local))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmBlockUser() {
        if (myPhoneHash.isEmpty() || otherUserHash.isEmpty()) return
        BlockSheet.show(
            this, otherUserName, otherUserHash,
            pl.sinusoide.sinuschat.util.ContactCache.getAvatarUrl(this, otherUserHash),
            allowDelete = false,
            onBlock = { blockUser() }
        )
    }

    private fun blockUser() {
        if (myPhoneHash.isEmpty() || otherUserHash.isEmpty()) return
        pl.sinusoide.sinuschat.util.BlockList.block(this, otherUserHash)
        PopupHelper.show(this, getString(R.string.blocked_toast, otherUserName))
        finish()
    }

    fun showSafetyNumber() {
        if (myPhoneHash.isEmpty() || otherUserHash.isEmpty()) return
        startActivity(Intent(this, SafetyNumberActivity::class.java).apply {
            putExtra("myHash", myPhoneHash)
            putExtra("otherUserHash", otherUserHash)
            putExtra("otherUserName", otherUserName)
        })
    }
}
