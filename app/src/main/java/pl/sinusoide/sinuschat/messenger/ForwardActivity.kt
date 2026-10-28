package pl.sinusoide.sinuschat.messenger

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import pl.sinusoide.sinuschat.MainActivity
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.ScreenLockActivity
import pl.sinusoide.sinuschat.crypto.DbKeyManager
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.ui.FlowGlass
import pl.sinusoide.sinuschat.ui.FlowLens
import pl.sinusoide.sinuschat.ui.ThemeColors
import pl.sinusoide.sinuschat.ui.ThemeUi
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.BlockList
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.ConversationCache
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.MediaManager
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.optStringOrNull
import java.text.Normalizer

class ForwardActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SOURCE = "forward_source_conversation"
        private const val EXTRA_IDS = "forward_message_ids"
        private const val EXTRA_SECURE = "forward_secure"

        const val EXTRA_SUMMARY = "forward_summary"

        private const val MAX_TARGETS = 10

        fun forMessages(ctx: Context, conversationId: String, ids: List<String>, secure: Boolean): Intent =
            Intent(ctx, ForwardActivity::class.java)
                .putExtra(EXTRA_SOURCE, conversationId)
                .putStringArrayListExtra(EXTRA_IDS, ArrayList(ids))
                .putExtra(EXTRA_SECURE, secure)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    private class Chat(
        val target: ForwardSender.Target,
        val avatarUrl: String?,
        val groupColor: String,
        val blockedReason: String?
    )

    private class Loaded(val items: List<ForwardSender.Item>, val comment: String?, val notes: List<String>)

    private val tc by lazy { ThemeColors(this) }
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()

    private var fromOtherApp = false
    private var items: List<ForwardSender.Item> = emptyList()
    private var chats: List<Chat> = emptyList()
    private val selected = LinkedHashMap<String, Chat>()
    private var query = ""
    private var sending = false
    private var panelShown = false
    private var justAdded: String? = null

    private lateinit var column: LinearLayout
    private lateinit var search: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var panel: LinearLayout
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chipsRow: LinearLayout
    private lateinit var comment: EditText
    private lateinit var sendButton: FrameLayout
    private lateinit var sendIcon: ImageView
    private lateinit var sendProgress: ProgressBar
    private lateinit var status: TextView
    private val rows = RowAdapter()
    private var preview: View? = null

    private val lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!DbKeyManager.needsPinUnlock(this)) start() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SetupActivity.isSetupDone(this) || SetupActivity.getUserPhoneHash(this).isEmpty()) {
            Toast.makeText(this, R.string.forward_setup_first, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (DbKeyManager.needsPinUnlock(this)) {
            lockLauncher.launch(Intent(this, ScreenLockActivity::class.java)
                .putExtra(ScreenLockActivity.EXTRA_MODE, ScreenLockActivity.MODE_UNLOCK)
                .putExtra(ScreenLockActivity.EXTRA_RETURN_RESULT, true))
            return
        }
        start()
    }

    private fun start() {
        enableEdgeToEdge()
        if (intent.getBooleanExtra(EXTRA_SECURE, false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        fromOtherApp = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
        buildUi()
        AppExecutor.io {
            val loaded = try { loadItems() } catch (e: Exception) {
                android.util.Log.e("ForwardActivity", "Odczyt udostępnionych rzeczy nieudany", e)
                Loaded(emptyList(), null, emptyList())
            }
            AppExecutor.main {
                if (isFinishing || isDestroyed) return@main
                if (loaded.items.isEmpty()) {
                    Toast.makeText(this, loaded.notes.firstOrNull() ?: getString(R.string.forward_nothing),
                        Toast.LENGTH_LONG).show()
                    finish()
                    return@main
                }
                loaded.notes.forEach { PopupHelper.show(this, it) }
                items = loaded.items
                loaded.comment?.let { comment.setText(it) }
                chats = loadChats()
                rows.refresh()
            }
        }
    }

    private fun loadItems(): Loaded {
        val notes = ArrayList<String>()
        intent.getStringArrayListExtra(EXTRA_IDS)?.let { ids ->
            return Loaded(messageItems(ids, notes), null, notes)
        }

        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
        }
        if (uris.isEmpty()) intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
        }

        val out = ArrayList<ForwardSender.Item>()
        for (uri in uris.distinct()) {
            if (!isSafeExternal(uri)) { notes += getString(R.string.forward_unsafe); continue }
            val m = ForwardSender.describe(this, uri, intent.type)
            if (m.size > ForwardSender.MAX_BYTES) { notes += getString(R.string.forward_too_large, m.name); continue }
            out += m
        }
        val text = sharedText()
        var comment: String? = null
        if (!text.isNullOrBlank()) {
            if (uris.isEmpty()) out += ForwardSender.Item.Text(text) else comment = text
        }
        return Loaded(out, comment, notes)
    }

    private fun messageItems(ids: List<String>, notes: MutableList<String>): List<ForwardSender.Item> {
        val msgs = ids.mapNotNull { MessageRepository.getByIdBlocking(this, it) }.sortedBy { it.timestampMs }
        var viewOnce = false
        var missing = false
        val out = ArrayList<ForwardSender.Item>()
        for (m in msgs) {
            if (m.isSystemMessage) continue
            if (m.viewOnce) { viewOnce = true; continue }
            when (m.type) {
                MessageEntity.TYPE_ATTACHMENT -> {
                    val name = m.attachmentName ?: ""
                    val file = MediaManager.getMediaFile(this, m.conversationId, m.id, name)
                    if (file.exists() && file.length() > 0) out += ForwardSender.chatMedia(file, name) else missing = true
                }
                MessageEntity.TYPE_VOICE -> {
                    val url = m.fileId
                    if (url.isNullOrEmpty()) missing = true
                    else out += ForwardSender.Item.Voice(m.id, url, m.attachmentName ?: "0s", m.fileKey)
                }
                else -> if (m.text.isNotBlank()) out += ForwardSender.Item.Text(m.text)
            }
        }
        if (viewOnce) notes += getString(R.string.forward_view_once)
        if (missing) notes += getString(R.string.forward_not_downloaded)
        return out
    }

    private fun sharedText(): String? {
        val text = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.joinToString("\n"))?.trim()
        if (text.isNullOrEmpty()) return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
        if (subject.isNullOrEmpty() || text.contains(subject) ||
            android.util.Patterns.WEB_URL.matcher(text).matches()) return text
        return "$subject\n\n$text"
    }

    private fun isSafeExternal(uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority?.startsWith(packageName) != true

    private fun summary(list: List<ForwardSender.Item>): String {
        val media = list.filterIsInstance<ForwardSender.Item.Media>()
        val parts = listOf(
            R.plurals.forward_photos to media.count { it.isImage },
            R.plurals.forward_videos to media.count { it.isVideo },
            R.plurals.forward_files to media.count { !it.isImage && !it.isVideo },
            R.plurals.forward_voices to list.count { it is ForwardSender.Item.Voice },
            R.plurals.forward_texts to list.count { it is ForwardSender.Item.Text },
        ).filter { it.second > 0 }.map { (res, n) -> resources.getQuantityString(res, n, n) }
        if (parts.isEmpty()) return ""
        val joined = if (parts.size == 1) parts[0]
            else parts.dropLast(1).joinToString(", ") + getString(R.string.forward_and) + parts.last()
        return joined.replaceFirstChar { it.uppercase() }
    }

    private fun loadChats(): List<Chat> {
        val arr = ConversationCache.load(this) ?: return emptyList()
        val hasVoice = items.any { it is ForwardSender.Item.Voice }
        val out = ArrayList<Chat>()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val id = j.optString("id")
            if (id.isEmpty()) continue
            val isGroup = j.optBoolean("is_group", false) || GroupRepository.isGroup(id)
            val other = if (isGroup) "" else j.optString("other_hash")
            if (!isGroup && other.isEmpty()) continue
            val name = if (isGroup) j.optStringOrNull("group_name") ?: getString(R.string.group)
                else ContactCache.bestName(this, other, j.optStringOrNull("other_name"))
            val avatar = if (isGroup) j.optStringOrNull("avatar_url")
                else ContactCache.getAvatarUrl(this, other)
                    ?: j.optStringOrNull("avatar_url")?.takeIf { !pl.sinusoide.sinuschat.crypto.ProfileCrypto.isEncryptedAvatar(it) }
            val reason = when {
                !isGroup && BlockList.isBlocked(this, other) -> getString(R.string.forward_blocked_row)
                isGroup && hasVoice -> getString(R.string.forward_voice_no_groups)
                else -> null
            }
            out += Chat(ForwardSender.Target(id, other, isGroup, name), avatar,
                j.optStringOrNull("group_avatar_color") ?: "#5288C1", reason)
        }
        return out
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace('ł', 'l').trim()

    private fun toggle(chat: Chat) {
        if (sending) return
        chat.blockedReason?.let { PopupHelper.show(this, it); return }
        val id = chat.target.conversationId
        if (selected.remove(id) == null) {
            if (selected.size >= MAX_TARGETS) {
                PopupHelper.show(this, getString(R.string.forward_limit, MAX_TARGETS))
                return
            }
            selected[id] = chat
            justAdded = id
        }
        recycler.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val pos = rows.rows.indexOfFirst { it is Row.ChatRow && it.chat.target.conversationId == id }
        if (pos >= 0) rows.notifyItemChanged(pos, "sel")
        status.visibility = View.GONE
        updatePanel()
        justAdded = null
    }

    private fun buildUi() {
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tc.bg)
        }
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, ime.bottom))
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6f), px(8f), px(12f), px(8f))
        }
        header.addView(ImageButton(this).apply {
            setImageResource(if (fromOtherApp) R.drawable.ic_close else R.drawable.ic_back)
            background = borderlessRipple()
            setColorFilter(tc.text)
            setPadding(px(10f), px(10f), px(10f), px(10f))
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(px(44f), px(44f)))
        header.addView(TextView(this).apply {
            text = getString(if (fromOtherApp) R.string.forward_title_share else R.string.forward_to)
            textSize = 19f
            setTextColor(tc.text)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(px(6f), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(header, LinearLayout.LayoutParams(-1, -2))

        search = EditText(this).apply {
            hint = getString(R.string.forward_search_hint)
            setHintTextColor(tc.text2)
            setTextColor(tc.text)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = ThemeUi.rounded(this@ForwardActivity, tc.input, 22f)
            setPadding(px(14f), px(11f), px(16f), px(11f))
            ContextCompat.getDrawable(this@ForwardActivity, R.drawable.ic_search)?.mutate()?.let { d ->
                d.setTint(tc.text2)
                d.setBounds(0, 0, px(18f), px(18f))
                setCompoundDrawablesRelative(d, null, null, null)
                compoundDrawablePadding = px(10f)
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    rows.refresh()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        column.addView(search, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(px(12f), 0, px(12f), px(6f))
        })

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ForwardActivity)
            adapter = rows
            clipToPadding = false
            setPadding(0, px(4f), 0, px(12f))
        }
        (recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        column.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))

        column.addView(buildPanel(), LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(px(8f), px(4f), px(8f), px(8f))
        })

        setContentView(column)
        setupFlow(header)
    }

    private fun buildPanel(): View {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(px(10f), px(10f), px(10f), px(10f))
            background = ThemeUi.rounded(this@ForwardActivity, tc.sheet, 24f)
            elevation = 6 * dp
        }
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chipsRow)
        }
        panel.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        comment = EditText(this).apply {
            hint = getString(R.string.forward_comment_hint)
            setHintTextColor(tc.text2)
            setTextColor(tc.text)
            textSize = 15f
            maxLines = 5
            minHeight = px(46f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = ThemeUi.rounded(this@ForwardActivity, tc.input, 22f)
            setPadding(px(16f), px(11f), px(16f), px(11f))
        }
        pl.sinusoide.sinuschat.util.PrivacySettings.applyIncognitoKeyboard(comment)
        row.addView(comment, LinearLayout.LayoutParams(0, -2, 1f))

        sendButton = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc.accent) }
            isClickable = true
            contentDescription = getString(R.string.forward_send_cd)
            setOnClickListener { send() }
        }
        sendIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_send)
            setColorFilter(tc.onAccent)
        }
        sendButton.addView(sendIcon, FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
        sendProgress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(tc.onAccent)
            visibility = View.GONE
        }
        sendButton.addView(sendProgress, FrameLayout.LayoutParams(px(26f), px(26f), Gravity.CENTER))
        FlowGlass.pressable(sendButton)
        row.addView(sendButton, LinearLayout.LayoutParams(px(48f), px(48f)).apply { marginStart = px(8f) })
        panel.addView(row, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(tc.text2)
            visibility = View.GONE
            setPadding(px(6f), px(8f), px(6f), 0)
        }
        panel.addView(status, LinearLayout.LayoutParams(-1, -2))
        return panel
    }

    private fun setupFlow(header: View) {
        if (!ThemeUi.isFlow(this)) return
        header.setPadding(px(6f), px(4f), px(12f), px(4f))
        FlowGlass.bar(header, 28f)
        FlowGlass.bar(search, 24f, hMarginDp = 12f, vMarginDp = 4f)
        FlowGlass.bar(panel, 26f, hMarginDp = 8f, vMarginDp = 6f)
        FlowGlass.glass(sendButton, 24f, oval = true, tint = tc.accent)
        FlowLens.underBars(column, recycler, listOf(
            FlowLens.Lens(header, 28 * dp, magnify = 0.12f, blurDp = 0.8f),
            FlowLens.Lens(search, 24 * dp, magnify = 0.12f, blurDp = 0.8f),
            FlowLens.Lens(panel, 26 * dp, magnify = 0.12f, blurDp = 0.8f)))
    }

    private fun updatePanel() {
        chipsRow.removeAllViews()
        for (c in selected.values) {
            val chip = chip(c)
            chipsRow.addView(chip)
            if (c.target.conversationId == justAdded) {
                chip.scaleX = 0.6f; chip.scaleY = 0.6f; chip.alpha = 0f
                chip.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setInterpolator(OvershootInterpolator(2.2f)).setDuration(260).start()
                chipsScroll.post { chipsScroll.smoothScrollTo(chipsRow.width, 0) }
            }
        }
        val show = selected.isNotEmpty()
        if (show == panelShown) return
        panelShown = show
        panel.animate().cancel()
        if (show) {
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.translationY = 60 * dp
            panel.animate().translationY(0f).alpha(1f)
                .setInterpolator(DecelerateInterpolator()).setDuration(240).start()
        } else {
            panel.animate().translationY(60 * dp).alpha(0f).setDuration(180)
                .withEndAction {
                    if (!panelShown) panel.visibility = View.GONE
                    panel.translationY = 0f
                }.start()
        }
    }

    private fun chip(c: Chat): View {
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeUi.rounded(this@ForwardActivity, tc.input, 16f)
            setPadding(px(4f), px(4f), px(10f), px(4f))
            isClickable = true
            contentDescription = c.target.name
            setOnClickListener { toggle(c) }
        }
        val img = ImageView(this)
        chip.addView(img, LinearLayout.LayoutParams(px(24f), px(24f)))
        loadAvatar(img, c, px(24f))
        chip.addView(TextView(this).apply {
            text = c.target.name
            textSize = 13f
            setTextColor(tc.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = px(120f)
            setPadding(px(6f), 0, px(5f), 0)
        })
        chip.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(tc.text2)
        }, LinearLayout.LayoutParams(px(14f), px(14f)))
        chip.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6f) }
        return chip
    }

    private fun loadAvatar(img: ImageView, c: Chat, size: Int) {
        if (c.target.isGroup) {
            AvatarHelper.loadGroupAvatar(this, img, c.target.name, c.groupColor, c.avatarUrl, size, c.target.conversationId)
            return
        }
        val letter = BitmapDrawable(resources, AvatarHelper.createLetterBitmap(
            AvatarHelper.initialOf(c.target.name), AvatarHelper.getContactColor(this, c.target.otherHash), size))
        Glide.with(img).clear(img)
        val url = c.avatarUrl
        if (url.isNullOrEmpty()) {
            img.setImageDrawable(letter)
        } else {
            Glide.with(img).load(url).signature(ObjectKey(url)).circleCrop()
                .placeholder(letter).error(letter).into(img)
        }
    }

    private fun borderlessRipple(): Drawable =
        RippleDrawable(ColorStateList.valueOf((tc.text and 0x00FFFFFF) or 0x22000000), null, null)

    private fun rowBackground(isSel: Boolean): Drawable {
        val fill = GradientDrawable().apply {
            cornerRadius = 18 * dp
            setColor(if (isSel) (tc.accent and 0x00FFFFFF) or 0x26000000 else Color.TRANSPARENT)
        }
        val mask = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf((tc.text and 0x00FFFFFF) or 0x1A000000), fill, mask)
    }

    private fun styleCheck(check: ImageView, isSel: Boolean, animate: Boolean) {
        check.setImageResource(if (isSel) R.drawable.ic_check else 0)
        check.setColorFilter(Color.WHITE)
        check.setPadding(px(4f), px(4f), px(4f), px(4f))
        check.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (isSel) setColor(tc.accent) else { setColor(Color.TRANSPARENT); setStroke(px(2f), tc.muted) }
        }
        if (ThemeUi.isFlow(this)) {
            FlowGlass.check(check, isSel, animate = animate)
            return
        }
        if (animate && isSel) {
            check.scaleX = 0.5f; check.scaleY = 0.5f
            check.animate().scaleX(1f).scaleY(1f)
                .setInterpolator(OvershootInterpolator(3f)).setDuration(260).start()
        }
    }

    private fun previewCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeUi.rounded(this@ForwardActivity, tc.card, 20f)
            setPadding(px(14f), px(12f), px(14f), px(14f))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_forward)
            setColorFilter(tc.accent)
        }, LinearLayout.LayoutParams(px(18f), px(18f)))
        head.addView(TextView(this).apply {
            text = getString(if (fromOtherApp) R.string.forward_you_share else R.string.forward_you_forward)
            textSize = 13f
            setTextColor(tc.accent)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(px(8f), 0, 0, 0)
        })
        head.addView(TextView(this).apply {
            text = "  ·  " + summary(items)
            textSize = 13f
            setTextColor(tc.text2)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head)

        val all = items.filterIsInstance<ForwardSender.Item.Media>()
        val visual = all.filter { it.isImage || it.isVideo }
        val files = all.filter { !it.isImage && !it.isVideo }
        val voices = items.filterIsInstance<ForwardSender.Item.Voice>()
        val texts = items.filterIsInstance<ForwardSender.Item.Text>()

        if (visual.size == 1) {
            card.addView(thumb(visual[0], 0), LinearLayout.LayoutParams(-1, px(180f)).apply { topMargin = px(12f) })
        } else if (visual.size > 1) {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val shown = visual.take(4)
            shown.forEachIndexed { i, m ->
                val more = if (i == shown.lastIndex) visual.size - shown.size else 0
                strip.addView(thumb(m, more), LinearLayout.LayoutParams(0, px(78f), 1f).apply {
                    if (i > 0) marginStart = px(6f)
                })
            }
            repeat(4 - shown.size) { strip.addView(View(this), LinearLayout.LayoutParams(0, px(78f), 1f).apply { marginStart = px(6f) }) }
            card.addView(strip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
        }
        files.take(3).forEach {
            card.addView(fileRow(pl.sinusoide.sinuschat.util.FileIcons.forName(it.name), it.name, it.size.takeIf { s -> s >= 0 }?.let(MediaManager::formatSize)),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        }
        if (files.size > 3) card.addView(moreText(resources.getQuantityString(R.plurals.forward_more_files, files.size - 3, files.size - 3)))
        voices.take(2).forEach {
            card.addView(fileRow(R.drawable.ic_mic, getString(R.string.voice_message), duration(it.duration)),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        }
        if (voices.size > 2) card.addView(moreText("+${voices.size - 2}"))
        texts.take(2).forEach {
            card.addView(quote(it.text), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        }
        if (texts.size > 2) card.addView(moreText(resources.getQuantityString(R.plurals.forward_more_texts, texts.size - 2, texts.size - 2)))

        return FrameLayout(this).apply {
            setPadding(px(12f), px(6f), px(12f), px(6f))
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun thumb(m: ForwardSender.Item.Media, more: Int): View {
        val cell = FrameLayout(this)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ThemeUi.rounded(this@ForwardActivity, tc.input, 14f)
            clipToOutline = true
        }
        cell.addView(img, FrameLayout.LayoutParams(-1, -1))
        Glide.with(this).load(m.uri).centerCrop().into(img)
        if (m.isVideo) {
            cell.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_play_voice)
                setColorFilter(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
                setPadding(px(8f), px(8f), px(8f), px(8f))
            }, FrameLayout.LayoutParams(px(36f), px(36f), Gravity.CENTER))
        }
        if (more > 0) {
            cell.addView(TextView(this).apply {
                text = "+$more"
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = ThemeUi.rounded(this@ForwardActivity, 0x88000000.toInt(), 14f)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        return cell
    }

    private fun fileRow(icon: Int, title: String, sub: String?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(tc.accent)
            background = ThemeUi.rounded(this@ForwardActivity, tc.input, 12f)
            setPadding(px(9f), px(9f), px(9f), px(9f))
        }, LinearLayout.LayoutParams(px(40f), px(40f)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12f), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 14.5f
            setTextColor(tc.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        if (sub != null) texts.addView(TextView(this).apply {
            text = sub
            textSize = 12.5f
            setTextColor(tc.text2)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun quote(text: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(View(this).apply {
            background = ThemeUi.rounded(this@ForwardActivity, tc.accent, 2f)
        }, LinearLayout.LayoutParams(px(3f), -1))
        row.addView(TextView(this).apply {
            this.text = text
            textSize = 14.5f
            setTextColor(tc.text)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
            setPadding(px(10f), px(2f), 0, px(2f))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun moreText(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(tc.text2)
        setPadding(px(2f), px(8f), 0, 0)
    }

    private fun duration(raw: String): String {
        val s = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun send() {
        if (sending || selected.isEmpty() || items.isEmpty()) return
        sending = true
        setSendingUi(true)
        val targets = selected.values.map { it.target }
        val text = comment.text.toString()
        val appCtx = applicationContext
        val list = items
        AppExecutor.io {
            val outcome = try {
                ForwardSender.sendBlocking(appCtx, targets, list, text) { done, total ->
                    if (!isDestroyed && sending) status.text = getString(R.string.forward_sending, done * 100 / total)
                }
            } catch (e: Throwable) {
                android.util.Log.e("ForwardActivity", "Przekazywanie nieudane", e)
                ForwardSender.Outcome(emptyList(),
                    targets.map { ForwardSender.Failure(it, appCtx.getString(R.string.forward_fail_generic)) },
                    emptyList())
            }
            AppExecutor.main { onSent(outcome) }
        }
    }

    private fun setSendingUi(on: Boolean) {
        comment.isEnabled = !on
        search.isEnabled = !on
        sendButton.isEnabled = !on
        sendIcon.setImageResource(R.drawable.ic_send)
        sendIcon.visibility = if (on) View.GONE else View.VISIBLE
        sendProgress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            status.setTextColor(tc.text2)
            status.text = getString(R.string.forward_sending, 0)
            status.visibility = View.VISIBLE
        }
    }

    private fun onSent(o: ForwardSender.Outcome) {
        val sentText = when (o.sent.size) {
            0 -> null
            1 -> getString(R.string.forward_sent_to, o.sent[0].name)
            else -> getString(R.string.forward_sent_many, o.sent.size)
        }
        if (isFinishing || isDestroyed) {
            val msg = if (o.failed.isEmpty() && o.skipped.isEmpty()) sentText else failureText(o)
            msg?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show() }
            return
        }
        sending = false
        o.skipped.forEach { PopupHelper.showError(this, getString(R.string.forward_skipped, it)) }

        if (o.failed.isEmpty() && sentText != null) {
            showDone(sentText)
            val delay = if (o.skipped.isEmpty()) 800L else 2600L
            sendButton.postDelayed({ finishSent(o, sentText) }, delay)
            return
        }

        setSendingUi(false)
        o.sent.forEach { selected.remove(it.conversationId) }
        rows.refresh()
        updatePanel()
        if (sentText != null) PopupHelper.showSuccess(this, sentText)
        val fail = failureText(o)
        if (fail != null) {
            status.setTextColor(ContextCompat.getColor(this, R.color.sinuschat_error))
            status.text = fail
            status.visibility = View.VISIBLE
        } else {
            status.visibility = View.GONE
        }
    }

    private fun failureText(o: ForwardSender.Outcome): String? {
        if (o.failed.isEmpty()) return null
        return getString(R.string.forward_not_sent) + "\n" +
            o.failed.joinToString("\n") {
                getString(R.string.forward_failed_line, it.target.name, it.reason.replaceFirstChar { c -> c.lowercase() })
            }
    }

    private fun showDone(text: String) {
        sendProgress.visibility = View.GONE
        sendIcon.setImageResource(R.drawable.ic_check)
        sendIcon.visibility = View.VISIBLE
        sendIcon.scaleX = 0.3f; sendIcon.scaleY = 0.3f
        sendIcon.animate().scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(3f)).setDuration(320).start()
        status.setTextColor(ContextCompat.getColor(this, R.color.sinuschat_success))
        status.text = text
        status.visibility = View.VISIBLE
        sendButton.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun finishSent(o: ForwardSender.Outcome, text: String) {
        if (isFinishing || isDestroyed) return
        if (!fromOtherApp) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_SUMMARY, text))
            finish()
            return
        }
        if (o.sent.size == 1) {
            val t = o.sent[0]
            startActivity(Intent(this, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("conversationId", t.conversationId)
                .putExtra("otherUserName", t.name)
                .putExtra("otherUserHash", t.otherHash)
                .putExtra("isGroup", t.isGroup))
        } else {
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private sealed class Row {
        object Preview : Row()
        class Label(val text: String) : Row()
        class ChatRow(val chat: Chat) : Row()
        class Empty(val text: String) : Row()
    }

    private inner class ChatHolder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val avatar = ImageView(this@ForwardActivity)
        val name = TextView(this@ForwardActivity).apply {
            textSize = 16f
            setTextColor(tc.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(this@ForwardActivity).apply {
            textSize = 13f
            setTextColor(tc.text2)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val check = ImageView(this@ForwardActivity)
        var chat: Chat? = null

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(px(12f), px(9f), px(12f), px(9f))
            root.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(px(6f), px(1f), px(6f), px(1f)) }
            root.addView(avatar, LinearLayout.LayoutParams(px(46f), px(46f)))
            val texts = LinearLayout(this@ForwardActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(14f), 0, px(10f), 0)
                addView(name)
                addView(sub)
            }
            root.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(check, LinearLayout.LayoutParams(px(26f), px(26f)))
            root.setOnClickListener { chat?.let { toggle(it) } }
        }

        fun bind(c: Chat, animate: Boolean) {
            val isSel = selected.containsKey(c.target.conversationId)
            if (!animate || chat !== c) {
                chat = c
                name.text = c.target.name
                val subText = c.blockedReason ?: if (c.target.isGroup) getString(R.string.group) else ""
                sub.text = subText
                sub.visibility = if (subText.isEmpty()) View.GONE else View.VISIBLE
                root.alpha = if (c.blockedReason != null) 0.45f else 1f
                loadAvatar(avatar, c, px(46f))
            }
            root.background = rowBackground(isSel)
            styleCheck(check, isSel, animate)
        }
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()

        fun refresh() {
            val q = norm(query)
            val list = if (q.isEmpty()) chats else chats.filter { norm(it.target.name).contains(q) }
            val out = ArrayList<Row>()
            if (q.isEmpty() && items.isNotEmpty()) out += Row.Preview
            if (list.isEmpty()) {
                out += Row.Empty(getString(if (chats.isEmpty()) R.string.no_chats else R.string.forward_no_results))
            } else {
                out += Row.Label(getString(R.string.forward_chats_label))
                list.forEach { out += Row.ChatRow(it) }
            }
            rows = out
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            Row.Preview -> 0
            is Row.Label -> 1
            is Row.ChatRow -> 2
            is Row.Empty -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 2) return ChatHolder(LinearLayout(this@ForwardActivity))
            val v: View = when (viewType) {
                0 -> FrameLayout(this@ForwardActivity)
                1 -> TextView(this@ForwardActivity).apply {
                    textSize = 13f
                    setTextColor(tc.text2)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(px(20f), px(14f), px(20f), px(6f))
                }
                else -> TextView(this@ForwardActivity).apply {
                    textSize = 15f
                    setTextColor(tc.text2)
                    gravity = Gravity.CENTER
                    setPadding(px(24f), px(48f), px(24f), px(24f))
                }
            }
            v.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) =
            onBindViewHolder(holder, position, mutableListOf())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            when (val row = rows[position]) {
                Row.Preview -> {
                    val host = holder.itemView as FrameLayout
                    val card = preview ?: previewCard().also { preview = it }
                    if (card.parent !== host) {
                        (card.parent as? ViewGroup)?.removeView(card)
                        host.removeAllViews()
                        host.addView(card)
                    }
                }
                is Row.Label -> (holder.itemView as TextView).text = row.text
                is Row.ChatRow -> (holder as ChatHolder).bind(row.chat, animate = payloads.isNotEmpty())
                is Row.Empty -> (holder.itemView as TextView).text = row.text
            }
        }
    }
}
