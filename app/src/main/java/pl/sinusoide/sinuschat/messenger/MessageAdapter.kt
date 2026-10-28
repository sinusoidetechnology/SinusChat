package pl.sinusoide.sinuschat.messenger

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.util.BlockList
import pl.sinusoide.sinuschat.util.MediaManager
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val myPhoneHash: String,
    private val conversationId: String = "",
    private val onImageClick: ((Bitmap) -> Unit)? = null,
    private val onAttachmentClick: ((MessageEntity) -> Unit)? = null,
    private val onLongPress: ((MessageEntity, View) -> Unit)? = null,
    private val onDoubleTap: ((MessageEntity) -> Unit)? = null
) : ListAdapter<MessageEntity, MessageAdapter.VH>(DIFF) {

    private fun otherHashOfConversation(): String? {
        if (conversationId.isEmpty() || conversationId.startsWith("g_")) return null
        return conversationId.split("_").firstOrNull { it != myPhoneHash && it.isNotEmpty() }
    }

    val selectedIds = mutableSetOf<String>()
    var isSelectionMode = false
        private set

    var onMediaOpen: ((List<MessageEntity>, Int) -> Unit)? = null
    var onAttachmentSave: ((MessageEntity) -> Unit)? = null
    var onUploadCancel: ((MessageEntity) -> Unit)? = null
    var onUploadRetry: ((MessageEntity) -> Unit)? = null
    var onMediaDownload: ((MessageEntity) -> Unit)? = null
    var onCodeOpen: ((MessageEntity, java.io.File) -> Unit)? = null
    private val expandedCode = HashSet<String>()

    var senderName: ((String) -> String?)? = null
    private val letterCache = HashMap<String, Bitmap>()
    private val isGroupChat: Boolean
        get() = conversationId.startsWith("g_") || conversationId.startsWith("group:")

    private var albums: Map<String, List<MessageEntity>> = emptyMap()
    private var byId: Map<String, MessageEntity> = emptyMap()

    fun resolveId(id: String?): String? {
        if (id.isNullOrEmpty()) return null
        if (byId.containsKey(id)) return id
        val local = "${id}_${myPhoneHash.take(16)}"
        return if (byId.containsKey(local)) local else null
    }

    private fun replyQuoteText(ctx: android.content.Context, item: MessageEntity): String? {
        val id = item.replyToId
        val original = resolveId(id)?.let { byId[it] }
        original?.let { ReplyLabel.media(ctx, it) }?.let { return it }
        item.replyToText?.takeIf { it.isNotEmpty() }?.let { return it }
        if (id == null) return null
        return original?.let { ReplyLabel.of(ctx, it) } ?: ctx.getString(R.string.reply_quote_message)
    }

    fun albumOf(id: String): List<MessageEntity>? =
        albums[id] ?: albums.values.firstOrNull { m -> m.any { it.id == id } }

    override fun submitList(list: List<MessageEntity>?) = submitList(list, null)

    override fun submitList(list: List<MessageEntity>?, commitCallback: Runnable?) {
        val all = list.orEmpty()
        byId = all.associateBy { it.id }
        val groups = all.filter { isAlbumPart(it) }.groupBy { it.albumId!! }.filterValues { it.size >= 2 }
        val leaders = groups.values.associateBy { it.first().id }
        val shown = all.filter { m -> val a = m.albumId; a == null || a !in groups || groups[a]!!.first().id == m.id }
        val changed = leaders.filter { (id, m) -> albums[id]?.let(::albumSignature) != albumSignature(m) }.keys
        albums = leaders
        super.submitList(shown) {
            for (id in changed) {
                val i = currentList.indexOfFirst { it.id == id }
                if (i >= 0) notifyItemChanged(i)
            }
            commitCallback?.run()
        }
    }

    private fun bindCaption(holder: VH, caption: String, isMine: Boolean) {
        val ctx = holder.itemView.context
        val tv = holder.text ?: return
        tv.visibility = View.VISIBLE
        tv.autoLinkMask = android.text.util.Linkify.WEB_URLS
        tv.linksClickable = true
        tv.setLinkTextColor(if (isMine) Color.WHITE else pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx))
        tv.text = caption
        safeLinks(tv)
        tv.textSize = 15f
        tv.alpha = 1.0f
        tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    private fun isAlbumPart(m: MessageEntity) = m.albumId != null && m.type == MessageEntity.TYPE_ATTACHMENT &&
        !m.viewOnce && !m.isSystemMessage && MediaManager.isImage(m.attachmentName ?: "")

    private fun albumSignature(m: List<MessageEntity>) =
        m.joinToString("|") { "${it.id}:${it.status}:${it.fileId}" }

    private val voicePlayers = mutableMapOf<String, pl.sinusoide.sinuschat.util.VoicePlayer>()

    private var flowCache: Boolean? = null
    private fun flowUi(ctx: android.content.Context): Boolean =
        flowCache ?: pl.sinusoide.sinuschat.util.ThemeManager.isFlow(ctx).also { flowCache = it }
    private fun applyRetroRow(
        holder: VH, item: MessageEntity, isMine: Boolean,
        params: FrameLayout.LayoutParams, bubbleLp: LinearLayout.LayoutParams
    ) {
        val ctx = holder.itemView.context
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.START
        params.marginStart = 0
        holder.bubbleContainer!!.layoutParams = params
        bubbleLp.width = ViewGroup.LayoutParams.MATCH_PARENT
        bubbleLp.gravity = Gravity.START
        holder.bubble!!.layoutParams = bubbleLp
        holder.bubble.background = android.graphics.drawable.ColorDrawable(
            if (isMine) pl.sinusoide.sinuschat.ui.RetroUi.ROW_MINE else pl.sinusoide.sinuschat.ui.RetroUi.ROW_OTHER)
        holder.bubble.elevation = 0f
        holder.senderAvatar?.visibility = View.GONE
        holder.senderName?.let { tv ->
            val who = if (isMine) ctx.getString(R.string.retro_me)
                else senderName?.invoke(item.senderHash)?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.chat_partner)
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestampMs))
            tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            tv.text = android.text.SpannableStringBuilder(who).apply {
                setSpan(android.text.style.ForegroundColorSpan(pl.sinusoide.sinuschat.ui.RetroUi.NAVY), 0, length, 0)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
                val start = length
                append(" ($time)")
                setSpan(android.text.style.ForegroundColorSpan(pl.sinusoide.sinuschat.ui.RetroUi.TIME_MINE), start, length, 0)
            }
            tv.visibility = View.VISIBLE
        }
        holder.time!!.visibility = View.GONE
    }

    private var retroCache: Boolean? = null
    private fun retroUi(ctx: android.content.Context): Boolean =
        retroCache ?: pl.sinusoide.sinuschat.util.ThemeManager.isRetro(ctx).also { retroCache = it }
    private val waveformCache = mutableMapOf<String, FloatArray>()
    private var currentlyPlayingId: String? = null

    private class VoiceViews(val wave: WaveformView, val dur: android.widget.TextView,
                             val play: android.widget.ImageButton, val durationSec: Int)
    private val voiceViews = HashMap<String, VoiceViews>()
    private val wavePhase = HashMap<String, Float>()
    private var voiceScrubbing: String? = null
    private var voiceResumeAfterScrub = false

    private fun voiceTime(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

    fun stopAllVoice() {
        voicePlayers.values.forEach { try { it.stop() } catch (_: Exception) {} }
        voicePlayers.clear()
        voiceViews.values.forEach { it.wave.stopAnimation() }
        voiceViews.clear()
        wavePhase.clear()
        currentlyPlayingId = null
    }

    var onSelectionChanged: ((count: Int) -> Unit)? = null

    var onReplyQuoteClick: ((replyToId: String?) -> Unit)? = null

    var onSystemClick: ((MessageEntity) -> Unit)? = null

    var onSwipeReply: ((MessageEntity) -> Unit)? = null

    var onMessageTapInSelection: ((MessageEntity) -> Boolean)? = null

    var hiddenIds: Set<String> = emptySet()

    fun enterSelectionMode(id: String) {
        val position = currentList.indexOfFirst { it.id == id }
        isSelectionMode = true
        selectedIds.add(id)
        if (position >= 0) notifyItemChanged(position)
        else notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    fun toggleSelection(id: String) {
        val position = currentList.indexOfFirst { it.id == id }
        val wasSelected = selectedIds.contains(id)

        if (wasSelected) selectedIds.remove(id) else selectedIds.add(id)

        if (selectedIds.isEmpty()) {
            exitSelectionMode()
            if (position >= 0) notifyItemChanged(position)
        } else {
            if (position >= 0) notifyItemChanged(position)
            else notifyDataSetChanged()
            onSelectionChanged?.invoke(selectedIds.size)
        }
    }

    fun exitSelectionMode() {
        val previouslySelected = selectedIds.toList()
        isSelectionMode = false
        selectedIds.clear()

        for (id in previouslySelected) {
            val pos = currentList.indexOfFirst { it.id == id }
            if (pos >= 0) notifyItemChanged(pos)
        }

        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun selectAll() {
        isSelectionMode = true
        currentList.forEach { selectedIds.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    companion object {
        private const val BADGE_TAG = "upload_badge"
        private const val VIDEO_CHIP_TAG = "video_chip"
        private const val GONE_TAG = "media_gone"
        private const val SNIPPET_TEXT_TAG = "snippet_text"
        private val ANIMATED_EXT = setOf("gif", "webp")
        private const val LINK_RING_TAG = "link_ring"

        private val DIFF = object : DiffUtil.ItemCallback<MessageEntity>() {
            override fun areItemsTheSame(a: MessageEntity, b: MessageEntity) = a.id == b.id
            override fun areContentsTheSame(a: MessageEntity, b: MessageEntity) = a == b
        }
    }

    override fun getItemViewType(position: Int): Int = when {
        getItem(position).isSystemMessage                 -> 2
        getItem(position).senderHash == myPhoneHash       -> 0
        else                                              -> 1
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val systemText: TextView? = view.findViewById(R.id.system_msg_text)
        val systemIcon: ImageView? = view.findViewById(R.id.system_msg_icon)

        val bubbleContainer: LinearLayout? = view.findViewById(R.id.msg_bubble_container)
        val bubble: LinearLayout? = view.findViewById(R.id.msg_bubble)
        val text: TextView? = view.findViewById(R.id.msg_text)
        val time: TextView? = view.findViewById(R.id.msg_time)
        val timerSlot: FrameLayout? = view.findViewById(R.id.msg_timer_slot)
        val statusIcon: ImageView? = view.findViewById(R.id.msg_status_icon)
        val image: ImageView? = view.findViewById(R.id.msg_image)
        val pinIndicator: TextView? = view.findViewById(R.id.msg_pin_indicator)
        val replyQuote: LinearLayout? = view.findViewById(R.id.msg_reply_quote)
        val replyText: TextView? = view.findViewById(R.id.msg_reply_text)
        val reactionsScroll: HorizontalScrollView? = view.findViewById(R.id.msg_reactions_scroll)
        val reactionsRow: LinearLayout? = view.findViewById(R.id.msg_reactions_row)
        val docCard: LinearLayout? = view.findViewById(R.id.msg_doc_card)
        val docName: TextView? = view.findViewById(R.id.msg_doc_name)
        val docMeta: TextView? = view.findViewById(R.id.msg_doc_meta)
        val docAction: ImageView? = view.findViewById(R.id.msg_doc_action)
        val docIcon: ImageView? = view.findViewById(R.id.msg_doc_icon)
        val docExpand: ImageView? = view.findViewById(R.id.msg_doc_expand)
        val codeBox: LinearLayout? = view.findViewById(R.id.msg_code_box)

        val linkPreview: LinearLayout? = view.findViewById(R.id.msg_link_preview)
        val linkImage: ImageView? = view.findViewById(R.id.msg_link_image)
        val linkTitle: TextView? = view.findViewById(R.id.msg_link_title)
        val linkDesc: TextView? = view.findViewById(R.id.msg_link_desc)
        val linkHost: TextView? = view.findViewById(R.id.msg_link_host)
        val linkImageContainer: View? = view.findViewById(R.id.msg_link_image_container)
        val linkFavicon: ImageView? = view.findViewById(R.id.msg_link_favicon)
        val linkIcon: ImageView? = view.findViewById(R.id.msg_link_icon)
        val blockedError: TextView? = view.findViewById(R.id.msg_blocked_error)
        val imageOverlay: FrameLayout? = view.findViewById(R.id.msg_image_overlay)
        val album: LinearLayout? = view.findViewById(R.id.msg_album)
        val onceRow: LinearLayout? = view.findViewById(R.id.msg_once_row)
        val onceIcon: FrameLayout? = view.findViewById(R.id.msg_once_icon)
        val onceText: TextView? = view.findViewById(R.id.msg_once_text)
        val senderName: TextView? = view.findViewById(R.id.msg_sender_name)
        val senderAvatar: ImageView? = view.findViewById(R.id.msg_sender_avatar)
        val translateToggle: TextView? = view.findViewById(R.id.msg_translate_toggle)
        val translatedIcon: ImageView? = view.findViewById(R.id.msg_translated_icon)
    }

    private val showOriginal = mutableSetOf<String>()

    private fun bindTranslation(holder: VH, item: MessageEntity, original: Boolean, isMine: Boolean) {
        val ctx = holder.itemView.context
        val muted = if (isMine) Color.parseColor("#B0D4F1") else Color.parseColor("#999999")
        holder.translatedIcon?.let {
            it.visibility = View.VISIBLE
            it.setColorFilter(muted)
        }
        holder.translateToggle?.apply {
            visibility = View.VISIBLE
            text = ctx.getString(if (original) R.string.translate_show_translation else R.string.translate_show_original)
            setTextColor(if (isMine) Color.parseColor("#D6E9FA") else pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx))
            isClickable = true
            setOnClickListener {
                if (isSelectionMode) { toggleSelection(item.id); return@setOnClickListener }
                if (!showOriginal.remove(item.id)) showOriginal.add(item.id)
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 2) R.layout.item_message_system
                     else R.layout.item_message_bubble
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        stopFlash(holder.itemView)

        holder.itemView.visibility = View.VISIBLE
        holder.itemView.alpha = 1f
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        holder.itemView.translationX = 0f
        holder.itemView.translationY = 0f

        holder.bubble?.visibility = View.VISIBLE
        if (item.id in hiddenIds) holder.itemView.visibility = View.INVISIBLE
        holder.bubble?.alpha = 1f
        holder.systemText?.parent?.let { (it as View).visibility = View.VISIBLE; (it as View).alpha = 1f }

        holder.text?.let { tv ->
            tv.autoLinkMask = 0
            tv.movementMethod = null
            tv.textSize = 15f
            tv.alpha = 1.0f
        }

        holder.linkPreview?.visibility = View.GONE
        holder.linkPreview?.findViewWithTag<View>(LINK_RING_TAG)?.visibility = View.GONE
        holder.linkImageContainer?.visibility = View.GONE
        holder.linkPreview?.setOnClickListener(null)
        holder.linkPreview?.isClickable = false
        holder.blockedError?.visibility = View.GONE
        holder.imageOverlay?.let { it.removeAllViews(); it.visibility = View.GONE }
        (holder.image?.parent as? ViewGroup)?.let { f ->
            for (i in f.childCount - 1 downTo 0) if (f.getChildAt(i).tag == VIDEO_CHIP_TAG) f.removeViewAt(i)
        }
        holder.album?.let { it.removeAllViews(); it.visibility = View.GONE }
        holder.onceRow?.visibility = View.GONE
        holder.onceIcon?.removeAllViews()
        holder.docIcon?.visibility = View.VISIBLE
        holder.docExpand?.let { it.animate().cancel(); it.rotation = 0f; it.visibility = View.GONE; it.setOnClickListener(null) }
        holder.codeBox?.let { CodePreview.reset(it) }
        (holder.docIcon?.parent as? FrameLayout)?.let { f ->
            for (i in f.childCount - 1 downTo 0) if (f.getChildAt(i).tag == BADGE_TAG) f.removeViewAt(i)
        }
        holder.text?.setOnClickListener(null)
        holder.text?.isClickable = false
        holder.translateToggle?.let { it.visibility = View.GONE; it.setOnClickListener(null) }
        holder.translatedIcon?.visibility = View.GONE

        if (item.isSystemMessage) {
            holder.systemText?.text = item.text
            val typeOrFileId = item.fileId ?: item.type
            val iconRes = when (typeOrFileId) {
                "call_done"               -> R.drawable.ic_call_made
                "call_missed"             -> R.drawable.ic_call_missed
                "call_declined"           -> R.drawable.ic_call_end
                "key_change", "sys_key_change" -> R.drawable.ic_key
                "sys_disappearing_change" -> R.drawable.ic_clock
                else                      -> R.drawable.ic_info
            }
            holder.systemIcon?.setImageResource(iconRes)
            holder.systemIcon?.setColorFilter(pl.sinusoide.sinuschat.ui.ThemeUi.accent(holder.itemView.context))
            holder.systemIcon?.visibility = View.VISIBLE

            holder.itemView.setOnLongClickListener {
                if (isSelectionMode) toggleSelection(item.id) else enterSelectionMode(item.id)
                true
            }
            holder.itemView.setOnClickListener {
                if (isSelectionMode) toggleSelection(item.id)
                else onSystemClick?.invoke(item)
            }
            val isSelected = selectedIds.contains(item.id)
            holder.itemView.setBackgroundColor(
                if (isSelected) 0x335288C1 else 0x00000000
            )
            val topP = (if (position > 0) 12 else 8) * holder.itemView.context.resources.displayMetrics.density
            holder.itemView.setPadding(holder.itemView.paddingLeft, topP.toInt(), holder.itemView.paddingRight, 0)
            (holder.itemView.layoutParams as? RecyclerView.LayoutParams)?.setMargins(0, 0, 0, 0)
            return
        }

        val isMine = item.senderHash == myPhoneHash
        val ctx = holder.itemView.context
        val isAttachment = item.type == "attachment" && item.fileId != null
        val isVoice     = item.type == "voice" && item.fileId != null
        val fileName = item.attachmentName ?: ""
        val isImage = isAttachment && MediaManager.isImage(fileName)
        val isViewOnce = item.viewOnce
        val isViewed   = item.viewed
        val placeholder = isMine && isAttachment && item.fileId.isNullOrEmpty()
        val album = albums[item.id]

        val showSender = isGroupChat && !isMine
        val prevSame = position > 0 &&
            getItem(position - 1).let { !it.isSystemMessage && it.senderHash == item.senderHash }
        val nextSame = position + 1 < itemCount &&
            getItem(position + 1).let { !it.isSystemMessage && it.senderHash == item.senderHash }
        val authorName = if (!showSender) "" else
            senderName?.invoke(item.senderHash)?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.group_member)
        holder.senderName?.let { tv ->
            if (showSender && !prevSame) {
                tv.text = authorName
                tv.setTextColor(senderColor(ctx, item.senderHash))
                tv.visibility = View.VISIBLE
            } else tv.visibility = View.GONE
        }
        holder.senderAvatar?.let { iv ->
            when {
                !showSender -> { Glide.with(iv).clear(iv); iv.visibility = View.GONE }
                nextSame -> { Glide.with(iv).clear(iv); iv.visibility = View.INVISIBLE }
                else -> { iv.visibility = View.VISIBLE; bindSenderAvatar(iv, item.senderHash, authorName) }
            }
        }

        if (isVoice) {
            holder.image!!.visibility   = View.GONE
            holder.docCard!!.visibility = View.GONE
            holder.text!!.visibility    = View.GONE

            val durationSec = fileName.removeSuffix("s").toIntOrNull() ?: 0
            val dp = ctx.resources.displayMetrics.density

            val voiceContainer = (holder.itemView.tag as? android.widget.LinearLayout)
                ?: android.widget.LinearLayout(ctx).also { ll ->
                    ll.orientation = android.widget.LinearLayout.HORIZONTAL
                    ll.gravity = android.view.Gravity.CENTER_VERTICAL
                    ll.setPadding((12 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                    ll.layoutParams = android.widget.LinearLayout.LayoutParams((240 * dp).toInt(),
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    (holder.text.parent as? android.view.ViewGroup)?.let { parent ->
                        val idx = parent.indexOfChild(holder.text)
                        parent.addView(ll, idx)
                    }
                    holder.itemView.tag = ll
                }
            voiceContainer.layoutParams = voiceContainer.layoutParams?.apply {
                width = (240 * dp).toInt()
            } ?: android.widget.LinearLayout.LayoutParams((240 * dp).toInt(),
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)

            for (i in 0 until voiceContainer.childCount) {
                val old = voiceContainer.getChildAt(i) as? WaveformView ?: continue
                (old.tag as? String)?.let { id ->
                    wavePhase[id] = old.currentPhase()
                    if (voiceViews[id]?.wave === old) voiceViews.remove(id)
                }
                old.pauseAnimation()
            }
            voiceContainer.removeAllViews()

            val btnPlay = android.widget.ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_play_voice)
                setBackgroundResource(0)
                setColorFilter(if (isMine) android.graphics.Color.WHITE else pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx))
            }
            voiceContainer.addView(btnPlay, (36*dp).toInt().let {
                android.widget.LinearLayout.LayoutParams(it, it)
            })

            val waveform = WaveformView(ctx).apply {
                tag = item.id
                setSeed(item.id.hashCode())
                val played = if (isMine) android.graphics.Color.WHITE
                             else pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx)
                val unplayed = if (isMine) android.graphics.Color.parseColor("#66FFFFFF")
                               else (pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(ctx) and 0x00FFFFFF) or 0x55000000
                setColors(played, unplayed)
            }
            voiceContainer.addView(waveform, android.widget.LinearLayout.LayoutParams(0, (28*dp).toInt(), 1f).apply {
                marginStart = (8*dp).toInt(); marginEnd = (8*dp).toInt()
            })

            val cacheForWave = pl.sinusoide.sinuschat.util.MediaManager.voiceCacheFile(ctx, item.id)
            if (cacheForWave.exists() && cacheForWave.length() > 0) {
                val cached = waveformCache[item.id]
                if (cached != null) {
                    waveform.setAmplitudes(cached)
                } else {
                    pl.sinusoide.sinuschat.util.AppExecutor.io {
                        val amps = pl.sinusoide.sinuschat.util.AudioWaveform.generate(cacheForWave.absolutePath)
                        if (amps != null) {
                            waveformCache[item.id] = amps
                            pl.sinusoide.sinuschat.util.AppExecutor.main {
                                waveform.setAmplitudes(amps)
                            }
                        }
                    }
                }
            }

            val tvDur = android.widget.TextView(ctx).apply {
                text = voiceTime(durationSec)
                textSize = 14f
                setPadding((4 * dp).toInt(), 0, 0, 0)
                setTextColor(if (isMine) android.graphics.Color.parseColor("#CCFFFFFF")
                             else pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(ctx))
            }
            voiceContainer.addView(tvDur, android.widget.LinearLayout.LayoutParams(-2, -2))

            voiceViews[item.id]?.wave?.let { prev ->
                if (prev !== waveform) { wavePhase[item.id] = prev.currentPhase(); prev.pauseAnimation() }
            }
            voiceViews[item.id] = VoiceViews(waveform, tvDur, btnPlay, durationSec)
            wavePhase[item.id]?.let { waveform.setPhase(it) }

            val currentPlayer = voicePlayers[item.id]
            if (currentPlayer != null && currentPlayer.canResume && currentPlayer.durationMs > 0) {
                val pos = currentPlayer.currentPositionMs
                val total = currentPlayer.durationMs
                if (pos in 1 until total - 50) {
                    waveform.progress = pos.toFloat() / total
                    tvDur.text = voiceTime(pos / 1000)
                }
            }
            if (currentPlayer?.isPlaying == true) {
                waveform.resumeAnimation()
                btnPlay.setImageResource(R.drawable.ic_pause)
            } else {
                btnPlay.setImageResource(R.drawable.ic_play_voice)
            }

            waveform.onScrub = { active ->
                val player = voicePlayers[item.id]
                if (active) {
                    voiceScrubbing = item.id
                    voiceResumeAfterScrub = player?.isPlaying == true
                    if (voiceResumeAfterScrub) player?.pause()
                    voiceViews[item.id]?.wave?.pauseAnimation()
                } else if (voiceScrubbing == item.id) {
                    voiceScrubbing = null
                    val views = voiceViews[item.id]
                    if (player != null && player.canResume && player.durationMs > 0) {
                        player.seekTo(((views?.wave?.progress ?: 0f) * player.durationMs).toInt())
                        if (voiceResumeAfterScrub) {
                            player.resume()
                            views?.wave?.resumeAnimation()
                            views?.play?.setImageResource(R.drawable.ic_pause)
                        }
                    } else {
                        views?.wave?.progress = 0f
                    }
                    voiceResumeAfterScrub = false
                }
            }
            waveform.onSeek = { p ->
                val player = voicePlayers[item.id]
                if (player != null && player.canResume && player.durationMs > 0) {
                    voiceViews[item.id]?.dur?.text = voiceTime((p * player.durationMs).toInt() / 1000)
                }
            }

            btnPlay.setOnClickListener {
                val existing = voicePlayers[item.id]
                val views = voiceViews[item.id]
                if (existing != null && existing.isPlaying) {
                    existing.pause()
                    views?.wave?.pauseAnimation()
                    views?.play?.setImageResource(R.drawable.ic_play_voice)
                    return@setOnClickListener
                }
                val prevId = currentlyPlayingId
                voicePlayers.forEach { (id, p) -> if (id != item.id && p.isPlaying) p.pause() }
                currentlyPlayingId = item.id
                if (prevId != null && prevId != item.id) {
                    voiceViews[prevId]?.let { it.wave.pauseAnimation(); it.play.setImageResource(R.drawable.ic_play_voice) }
                }
                if (existing != null && existing.canResume) {
                    existing.resume()
                    views?.wave?.resumeAnimation()
                    views?.play?.setImageResource(R.drawable.ic_pause)
                    return@setOnClickListener
                }

                val fileUrl = pl.sinusoide.sinuschat.util.VoicePlayer
                    .normalizeSource(item.fileId)
                val cacheFile = pl.sinusoide.sinuschat.util.MediaManager.voiceCacheFile(ctx, item.id)
                val playFrom = {
                    val player = voicePlayers.getOrPut(item.id) { pl.sinusoide.sinuschat.util.VoicePlayer() }
                    player.play(ctx, cacheFile.absolutePath,
                        onProgress = { curMs, totalMs ->
                            val v = voiceViews[item.id]
                            if (v != null && voiceScrubbing != item.id) {
                                if (totalMs > 0) v.wave.progress = curMs.toFloat() / totalMs
                                val t = voiceTime(curMs / 1000)
                                if (v.dur.text.toString() != t) v.dur.text = t
                            }
                        },
                        onComplete = {
                            if (currentlyPlayingId == item.id) currentlyPlayingId = null
                            wavePhase.remove(item.id)
                            voiceViews[item.id]?.let { v ->
                                v.play.setImageResource(R.drawable.ic_play_voice)
                                v.wave.progress = 0f
                                v.wave.stopAnimation()
                                v.dur.text = voiceTime(v.durationSec)
                            }
                        },
                        onInterrupt = {
                            if (currentlyPlayingId == item.id) currentlyPlayingId = null
                            voiceViews[item.id]?.let { v ->
                                v.play.setImageResource(R.drawable.ic_play_voice)
                                v.wave.pauseAnimation()
                            }
                        }
                    )
                    voiceViews[item.id]?.let { v ->
                        v.wave.resumeAnimation()
                        v.play.setImageResource(R.drawable.ic_pause)
                    }
                }
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    playFrom()
                } else {
                    pl.sinusoide.sinuschat.util.AppExecutor.io {
                        try {
                            val bytes = pl.sinusoide.sinuschat.util.FileUploader
                                .downloadVoiceBlocking(ctx, fileUrl, item.fileKey) ?: return@io
                            if (bytes.isEmpty()) return@io
                            cacheFile.writeBytes(bytes)
                            pl.sinusoide.sinuschat.util.AppExecutor.main {
                                if (currentlyPlayingId == item.id) playFrom()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("VoiceMsg", "Download error: ${e.message}")
                        }
                    }
                }
            }
            voiceContainer.visibility = View.VISIBLE
        } else {
            (holder.itemView.tag as? android.widget.LinearLayout)?.let { ll ->
                for (i in 0 until ll.childCount) {
                    val old = ll.getChildAt(i) as? WaveformView ?: continue
                    (old.tag as? String)?.let { id ->
                        wavePhase[id] = old.currentPhase()
                        if (voiceViews[id]?.wave === old) voiceViews.remove(id)
                    }
                    old.pauseAnimation()
                }
                (ll.parent as? android.view.ViewGroup)?.removeView(ll)
            }
            holder.itemView.tag = null
        }

        holder.pinIndicator!!.visibility = if (item.pinned) View.VISIBLE else View.GONE

        val quote = replyQuoteText(holder.itemView.context, item)
        if (quote != null) {
            holder.replyQuote!!.visibility = View.VISIBLE
            holder.replyText!!.text = quote
            holder.replyQuote.setOnClickListener { onReplyQuoteClick?.invoke(item.replyToId) }
        } else { holder.replyQuote!!.visibility = View.GONE }

        if (album != null) {
            bindAlbum(holder, item, album, isMine)
        } else if (isViewOnce && isAttachment) {
            holder.image!!.visibility   = View.GONE
            holder.docCard!!.visibility = View.GONE
            holder.text!!.visibility    = View.GONE
            val row = holder.onceRow!!
            row.visibility = View.VISIBLE
            val canOpen = !isMine && !isViewed
            holder.onceText!!.text = ctx.getString(when {
                isViewed -> R.string.view_once_opened
                MediaManager.isVideo(fileName) -> R.string.view_once_label_video
                else -> R.string.view_once_label_photo
            })
            val fg = if (isMine) Color.WHITE else pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx)
            holder.onceText.setTextColor(if (isMine) Color.WHITE else pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(ctx))
            row.alpha = if (isViewed) 0.6f else 1.0f
            val slot = holder.onceIcon!!
            when {
                placeholder && item.status == MessageEntity.STATUS_FAILED ->
                    slot.addView(retryBadge(ctx, item, fg, 0, 3), FrameLayout.LayoutParams(-1, -1))
                placeholder -> slot.addView(ring(ctx, item, fg, 0), FrameLayout.LayoutParams(-1, -1))
                else -> slot.addView(ImageView(ctx).apply {
                    setImageResource(if (isViewed) R.drawable.ic_lock_open else R.drawable.ic_lock)
                    setColorFilter(fg)
                }, FrameLayout.LayoutParams(-1, -1))
            }
            row.setOnClickListener {
                if (isSelectionMode) { toggleSelection(item.id); return@setOnClickListener }
                if (!placeholder && (canOpen || isMine)) onAttachmentClick?.invoke(item)
            }
            row.setOnLongClickListener {
                if (isSelectionMode) toggleSelection(item.id) else onLongPress?.invoke(item, holder.itemView)
                true
            }
        }
        else if (isImage && conversationId.isNotEmpty()) {
            val bitmap = MediaManager.loadBitmap(ctx, conversationId, item.id, fileName)

            run {
                holder.image!!.visibility = View.VISIBLE
                Glide.with(holder.image).clear(holder.image)
                holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.image.background = null
                val animated = bitmap != null && fileName.substringAfterLast('.', "").lowercase() in ANIMATED_EXT
                if (animated) {
                    Glide.with(holder.image).load(MediaManager.getMediaFile(ctx, conversationId, item.id, fileName))
                        .placeholder(android.graphics.drawable.BitmapDrawable(ctx.resources, bitmap))
                        .into(holder.image)
                } else holder.image.setImageBitmap(bitmap ?: placeholderBitmap())
                holder.text!!.visibility = View.GONE
                holder.docCard!!.visibility = View.GONE


                holder.image.clipToOutline = true
                holder.image.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 14f)
                    }
                }
                if (fileName.startsWith("location_")) {
                    val parts = fileName.removePrefix("location_").removeSuffix(".png").split("_")
                    val latStr = parts.getOrNull(0)
                    val lonStr = parts.getOrNull(1)

                    if (latStr != null && lonStr != null) {
                        holder.text.visibility = View.VISIBLE
                        holder.text.text = "$latStr, $lonStr"
                        holder.text.textSize = 13f
                        holder.text.alpha = 0.9f
                    }

                    holder.image.setOnClickListener {
                        if (isSelectionMode) {
                            toggleSelection(item.id)
                        } else {
                            val lat = latStr?.toDoubleOrNull()
                            val lon = lonStr?.toDoubleOrNull()
                            if (lat != null && lon != null) openMap(ctx, lat, lon)
                        }
                    }
                } else {
                    val gone = bitmap == null && !placeholder && pl.sinusoide.sinuschat.util.MediaGone.isGone(ctx, item.id)
                    holder.image.setOnClickListener {
                        if (isSelectionMode) toggleSelection(item.id)
                        else if (placeholder) Unit
                        else if (gone) explainGone(ctx, item)
                        else if (bitmap == null) onMediaDownload?.invoke(item)
                        else if (onMediaOpen != null) onMediaOpen?.invoke(listOf(item), 0)
                        else bitmap.let { onImageClick?.invoke(it) }
                    }
                    if (gone) showGoneOverlay(holder, item)
                }
                when {
                    placeholder -> showUploadOverlay(holder, item)
                    bitmap == null && holder.imageOverlay?.findViewWithTag<View>(GONE_TAG) == null -> showDownloadOverlay(holder, item)
                }
            }
        } else if (isAttachment && MediaManager.isVideo(fileName) && conversationId.isNotEmpty()) {
            bindVideo(holder, item, fileName, placeholder)
        } else if (isAttachment) {
            holder.image!!.visibility = View.GONE
            holder.text!!.visibility = View.GONE
            holder.docCard!!.visibility = View.VISIBLE
            holder.docName!!.text = fileName

            val dl = conversationId.isNotEmpty() && MediaManager.isDownloaded(ctx, conversationId, item.id, fileName)
            val file = if (dl) MediaManager.getMediaFile(ctx, conversationId, item.id, fileName) else null
            val ext = fileName.substringAfterLast('.', "").uppercase()
            val gone = !dl && !placeholder && pl.sinusoide.sinuschat.util.MediaGone.isGone(ctx, item.id)

            when {
                gone -> {
                    holder.docMeta!!.text = "$ext  ·  " + pl.sinusoide.sinuschat.util.MediaGone.text(ctx, item.timestampMs, short = true)
                    holder.docAction!!.visibility = View.VISIBLE
                    holder.docAction.setImageResource(R.drawable.ic_no_media)
                }
                placeholder -> {
                    holder.docMeta!!.text = when {
                        item.status == MessageEntity.STATUS_FAILED -> ctx.getString(R.string.upload_failed_tap)
                        file != null -> "$ext  ·  ${MediaManager.formatSize(file.length())}"
                        else -> ext
                    }
                    holder.docAction!!.visibility = View.GONE
                }
                AttachmentDownloader.isActive(item.id) -> {
                    holder.docMeta!!.text = ext
                    holder.docAction!!.visibility = View.GONE
                }
                dl && file != null -> {
                    holder.docMeta!!.text = "$ext  ·  ${MediaManager.formatSize(file.length())}"
                    holder.docAction!!.visibility = View.VISIBLE
                    holder.docAction.setImageResource(R.drawable.ic_download)
                }
                else -> {
                    holder.docMeta!!.text = "$ext  ·  " + ctx.getString(R.string.msg_click_to_download)
                    holder.docAction!!.visibility = View.VISIBLE
                    holder.docAction.setImageResource(R.drawable.ic_download)
                }
            }
            holder.docAction.contentDescription = if (gone) pl.sinusoide.sinuschat.util.MediaGone.text(ctx, item.timestampMs) else ctx.getString(R.string.save_to_device)
            holder.docAction.setOnClickListener {
                if (isSelectionMode) toggleSelection(item.id) else if (gone) explainGone(ctx, item) else onAttachmentSave?.invoke(item)
            }

            holder.docIcon!!.setImageResource(
                if (MediaManager.isVideo(fileName)) R.drawable.ic_call_video_on
                else pl.sinusoide.sinuschat.util.FileIcons.forFile(fileName, file))
            holder.docIcon.setTag(R.id.msg_doc_icon, item.id)
            if (file != null && pl.sinusoide.sinuschat.util.FileIcons.needsScan(fileName, file)) {
                val iconView = holder.docIcon
                pl.sinusoide.sinuschat.util.AppExecutor.io {
                    pl.sinusoide.sinuschat.util.FileIcons.scan(fileName, file)
                    pl.sinusoide.sinuschat.util.AppExecutor.main {
                        if (iconView.getTag(R.id.msg_doc_icon) == item.id) {
                            iconView.setImageResource(pl.sinusoide.sinuschat.util.FileIcons.forFile(fileName, file))
                        }
                    }
                }
            }

            if (placeholder) {
                holder.docIcon.visibility = View.INVISIBLE
                (holder.docIcon.parent as? FrameLayout)?.addView(
                    if (item.status == MessageEntity.STATUS_FAILED) retryBadge(ctx, item, Color.WHITE, 0, 6)
                    else ring(ctx, item, Color.WHITE, 0),
                    FrameLayout.LayoutParams(-1, -1))
            } else if (AttachmentDownloader.isActive(item.id)) {
                holder.docIcon.visibility = View.INVISIBLE
                (holder.docIcon.parent as? FrameLayout)?.addView(downloadRing(ctx, item, Color.WHITE, 0),
                    FrameLayout.LayoutParams(-1, -1))
            }
            if (!item.viewOnce && conversationId.isNotEmpty() && pl.sinusoide.sinuschat.util.CodeHighlighter.isCode(fileName)) {
                bindCode(holder, item, fileName, file)
            }
            holder.docCard.setOnClickListener {
                if (isSelectionMode) toggleSelection(item.id)
                else if (placeholder) { if (item.status == MessageEntity.STATUS_FAILED) onUploadRetry?.invoke(item) }
                else if (gone) explainGone(ctx, item)
                else onAttachmentClick?.invoke(item)
            }
        } else if (item.text.startsWith("geo:")) {
            holder.docCard!!.visibility = View.GONE
            holder.image!!.visibility = View.VISIBLE

            val coords = item.text.removePrefix("geo:").split(",")
            val latStr = coords.getOrNull(0) ?: "0"
            val lonStr = coords.getOrNull(1) ?: "0"

            holder.text!!.visibility = View.VISIBLE
            holder.text.text = "$latStr, $lonStr"
            holder.text.textSize = 13f
            holder.text.alpha = 0.9f

            Glide.with(ctx).load(R.drawable.ic_location)
                .into(holder.image)
            holder.image.scaleType = ImageView.ScaleType.CENTER
            holder.image.setBackgroundColor(Color.parseColor("#5288C1"))

            holder.image.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(item.id)
                } else {
                    val lat = latStr.toDoubleOrNull() ?: 0.0
                    val lon = lonStr.toDoubleOrNull() ?: 0.0
                    openMap(ctx, lat, lon)
                }
            }
        } else {
            holder.image!!.visibility = View.GONE
            holder.docCard!!.visibility = View.GONE
            holder.text!!.visibility = View.VISIBLE
            holder.text.autoLinkMask = android.text.util.Linkify.WEB_URLS
            holder.text.linksClickable = true
            holder.text.setLinkTextColor(if (isMine) Color.WHITE else pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx))
            val tr = if (item.type == MessageEntity.TYPE_TEXT) pl.sinusoide.sinuschat.util.Translations.view(item) else null
            val showingOriginal = tr != null && item.id in showOriginal
            holder.text.text = when {
                tr == null -> item.text
                showingOriginal -> tr.original
                else -> tr.translated
            }
            if (tr != null) bindTranslation(holder, item, showingOriginal, isMine)
            safeLinks(holder.text)
            holder.text.textSize = 15f
            holder.text.alpha = 1.0f
            holder.text.movementMethod = android.text.method.LinkMovementMethod.getInstance()

            if (item.type == pl.sinusoide.sinuschat.data.MessageEntity.TYPE_TEXT && !item.text.startsWith("geo:")) {
                val snippets = if (tr == null) snippetsFor(item) else null
                if (snippets != null) bindSnippets(holder, item, snippets, isMine, showSender)
                val plain = snippets?.filter { !it.code }?.joinToString("\n") { it.text } ?: holder.text.text.toString()
                val url = pl.sinusoide.sinuschat.util.LinkPreviewHelper.findUrl(plain)?.takeIf { pl.sinusoide.sinuschat.util.LinkPreviewHelper.isPreviewable(it) }
                if (url != null) {
                    val cached = pl.sinusoide.sinuschat.util.LinkPreviewHelper.getCached(url)
                    when {
                        cached != null -> showLinkPreview(holder, cached)
                        pl.sinusoide.sinuschat.util.LinkPreviewHelper.hasNoPreview(ctx, url) -> {}
                        pl.sinusoide.sinuschat.util.LinkPreviewHelper.canLoadWithoutAsking(ctx, url) ->
                            loadLinkPreview(holder, url, position)
                        else -> showLinkPrompt(holder, url, position)
                    }
                }
            }
        }

        val caption = if (isViewOnce || item.type != MessageEntity.TYPE_ATTACHMENT || fileName.startsWith("location_")) null
            else (album?.firstOrNull { it.text.isNotBlank() }?.text ?: item.text).takeIf { it.isNotBlank() }
        holder.text?.maxWidth = if (caption != null) (246 * ctx.resources.displayMetrics.density).toInt() else Int.MAX_VALUE
        if (caption != null) bindCaption(holder, caption, isMine)

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestampMs))
        holder.time!!.text = timeStr
        if (item.edited) {
            holder.time.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_edit, 0, 0, 0)
            holder.time.compoundDrawablePadding = (3 * ctx.resources.displayMetrics.density).toInt()
        } else {
            holder.time.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        }

        holder.timerSlot?.let { slot ->
            val expire = item.expiresAtMs
            if (expire != null && expire > 0L) {
                slot.visibility = View.VISIBLE
                slot.removeAllViews()
                val ttl = pl.sinusoide.sinuschat.util.DisappearingMessages.getTtl(
                    slot.context, item.conversationId)
                val timer = pl.sinusoide.sinuschat.util.DisappearingTimerView(slot.context)
                slot.addView(timer, FrameLayout.LayoutParams(-1, -1))
                timer.set(expire, if (ttl > 0) ttl else (expire - item.timestampMs))
            } else {
                slot.visibility = View.GONE
                slot.removeAllViews()
            }
        }
        val params = holder.bubbleContainer!!.layoutParams as FrameLayout.LayoutParams
        val bubbleLp = holder.bubble!!.layoutParams as LinearLayout.LayoutParams
        val errorLp = holder.blockedError?.layoutParams as? LinearLayout.LayoutParams
        val replyLp = holder.replyQuote.layoutParams as? LinearLayout.LayoutParams
        val pinLp = holder.pinIndicator.layoutParams as? LinearLayout.LayoutParams
        val reactionsLp = holder.reactionsScroll?.layoutParams as? LinearLayout.LayoutParams

        if (isMine) {
            params.gravity = Gravity.END
            params.marginStart = 0
            bubbleLp.gravity = Gravity.END
            errorLp?.gravity = Gravity.END
            replyLp?.gravity = Gravity.END
            pinLp?.gravity = Gravity.END
            reactionsLp?.gravity = Gravity.END

            if (flowUi(ctx)) holder.bubble.background = pl.sinusoide.sinuschat.ui.FlowGlass.bubble(ctx, mine = true)
            else if (retroUi(ctx)) holder.bubble.background = android.graphics.drawable.ColorDrawable(pl.sinusoide.sinuschat.ui.RetroUi.ROW_MINE)
            else holder.bubble.setBackgroundResource(R.drawable.bg_bubble_mine)
            holder.time.setTextColor(if (retroUi(ctx)) pl.sinusoide.sinuschat.ui.RetroUi.TIME_MINE else Color.parseColor("#B0D4F1"))
            holder.statusIcon!!.visibility = View.VISIBLE
            val otherHash = otherHashOfConversation()
            if (BlockList.isBlockedByThem(holder.itemView.context, otherHash) && item.status == "pending") {
                holder.statusIcon.setImageResource(R.drawable.ic_close)
                tint(holder.statusIcon, "#E65100")
                holder.blockedError?.visibility = View.VISIBLE
            } else
            when (item.status) {
                "uploading" -> { holder.statusIcon.setImageResource(R.drawable.ic_clock)
                                 tint(holder.statusIcon, "#80FFFFFF") }
                "failed"    -> { holder.statusIcon.setImageResource(R.drawable.ic_close)
                                 tint(holder.statusIcon, "#E65100") }
                MessageEntity.STATUS_UNDELIVERED -> { holder.statusIcon.setImageResource(R.drawable.ic_close)
                                 tint(holder.statusIcon, "#E5484D") }
                "pending"   -> { holder.statusIcon.setImageResource(R.drawable.ic_clock)
                                 tint(holder.statusIcon, "#80FFFFFF") }
                "sent"      -> { holder.statusIcon.setImageResource(R.drawable.ic_check)
                                 tint(holder.statusIcon, "#AAAAAA") }
                "delivered" -> { holder.statusIcon.setImageResource(R.drawable.ic_check_double)
                                 tint(holder.statusIcon, "#AAAAAA") }
                "read"      -> { holder.statusIcon.setImageResource(R.drawable.ic_check_double)
                                 tint(holder.statusIcon, "#5288C1") }
                else        -> { holder.statusIcon.setImageResource(R.drawable.ic_check)
                                 tint(holder.statusIcon, "#AAAAAA") }
            }
        } else {
            params.gravity = Gravity.START
            params.marginStart = if (showSender) (40 * ctx.resources.displayMetrics.density).toInt() else 0
            bubbleLp.gravity = Gravity.START
            errorLp?.gravity = Gravity.START
            replyLp?.gravity = Gravity.START
            pinLp?.gravity = Gravity.START
            reactionsLp?.gravity = Gravity.START

            if (flowUi(ctx)) holder.bubble.background = pl.sinusoide.sinuschat.ui.FlowGlass.bubble(ctx, mine = false)
            else if (retroUi(ctx)) holder.bubble.background = android.graphics.drawable.ColorDrawable(pl.sinusoide.sinuschat.ui.RetroUi.ROW_OTHER)
            else holder.bubble.setBackgroundResource(R.drawable.bg_bubble_other)
            holder.time.setTextColor(if (retroUi(ctx)) pl.sinusoide.sinuschat.ui.RetroUi.TIME_MINE else Color.parseColor("#999999"))
            holder.statusIcon!!.visibility = View.GONE
        }
        holder.bubbleContainer.layoutParams = params
        holder.bubble.layoutParams = bubbleLp
        if (errorLp != null) holder.blockedError.layoutParams = errorLp
        if (replyLp != null) holder.replyQuote.layoutParams = replyLp
        if (pinLp != null) holder.pinIndicator.layoutParams = pinLp
        if (reactionsLp != null) holder.reactionsScroll.layoutParams = reactionsLp
        if (retroUi(ctx)) applyRetroRow(holder, item, isMine, params, bubbleLp)

        holder.reactionsRow!!.removeAllViews()
        if (item.reactions.isEmpty()) { holder.reactionsScroll!!.visibility = View.GONE } else {
            holder.reactionsScroll!!.visibility = View.VISIBLE
            val grouped = mutableMapOf<String, Int>()
            for ((_, emoji) in item.reactions) grouped[emoji] = (grouped[emoji] ?: 0) + 1
            for ((emoji, count) in grouped) {
                val chip = TextView(ctx).apply {
                    text = if (count > 1) "$emoji $count" else emoji
                    textSize = 14f; setPadding(12, 4, 12, 4)
                    val darkUi = pl.sinusoide.sinuschat.ui.ThemeUi.isDark(ctx)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 12 * ctx.resources.displayMetrics.density
                        setColor(if (darkUi) 0x28FFFFFF else 0x12000000)
                        setStroke(ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1), if (darkUi) 0x38FFFFFF else 0x22000000)
                    }
                    setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(ctx))
                }
                holder.reactionsRow.addView(chip)
                (chip.layoutParams as LinearLayout.LayoutParams).marginEnd = 6
            }
        }

        val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isSelectionMode) { onDoubleTap?.invoke(item) }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (isSelectionMode) {
                    toggleSelection(item.id)
                } else {
                    onLongPress?.invoke(item, holder.itemView)
                }
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isSelectionMode) {
                    if (onMessageTapInSelection?.invoke(item) == true) return true
                    toggleSelection(item.id)
                    return true
                }
                if (holder.text!!.visibility == View.VISIBLE && holder.text.autoLinkMask != 0) {
                    return false
                }
                if (isAttachment && !isImage && !placeholder && album == null && !isViewOnce) onAttachmentClick?.invoke(item)
                return true
            }
        })

        val isSelected = selectedIds.contains(item.id)
        holder.itemView.alpha = 1.0f
        holder.bubble.alpha = 1.0f
        if (isSelected) {
            holder.itemView.setBackgroundColor(Color.parseColor("#334A90D9"))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            showFlash(holder.itemView, item.id)
        }
        gd.setIsLongpressEnabled(true)
        holder.bubble.isClickable = true
        holder.bubble.isFocusable = true
        val bubbleTouchListener = View.OnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
        }
        holder.bubble.setOnTouchListener(bubbleTouchListener)
        if (holder.text!!.visibility == View.VISIBLE) {
            holder.text.setOnTouchListener(bubbleTouchListener)
        }
        holder.codeBox?.let { box ->
            for (i in 0 until box.childCount) {
                val child = box.getChildAt(i)
                if (child.tag == SNIPPET_TEXT_TAG) child.setOnTouchListener(bubbleTouchListener)
            }
        }
        holder.image?.setOnLongClickListener {
            if (isSelectionMode) toggleSelection(item.id)
            else onLongPress?.invoke(item, holder.itemView)
            true
        }

        val topM = if (position > 0 && getItem(position - 1).senderHash != item.senderHash) 16 else 6
        val topPx = (topM * ctx.resources.displayMetrics.density).toInt()
        holder.itemView.setPadding(holder.itemView.paddingLeft, topPx, holder.itemView.paddingRight, 0)
        (holder.itemView.layoutParams as? RecyclerView.LayoutParams)?.setMargins(0, 0, 0, 0)
    }

    private fun ring(ctx: android.content.Context, item: MessageEntity, color: Int, disc: Int) =
        pl.sinusoide.sinuschat.ui.SineProgressRing(ctx).apply {
            tag = BADGE_TAG
            this.color = color
            this.disc = disc
            progress = { AttachmentSender.progressOf(item.id) }
            setOnClickListener { onUploadCancel?.invoke(item) }
        }

    private fun retryBadge(ctx: android.content.Context, item: MessageEntity, color: Int, disc: Int, padDp: Int) =
        ImageView(ctx).apply {
            tag = BADGE_TAG
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(color)
            if (disc != 0) background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(disc)
            }
            val p = (padDp * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            contentDescription = ctx.getString(R.string.upload_retry)
            setOnClickListener { onUploadRetry?.invoke(item) }
        }

    private fun showUploadOverlay(holder: VH, item: MessageEntity) {
        val box = holder.imageOverlay ?: return
        box.removeAllViews()
        box.visibility = View.VISIBLE
        box.addView(
            if (item.status == MessageEntity.STATUS_FAILED) retryBadge(box.context, item, Color.WHITE, 0x73000000, 14)
            else ring(box.context, item, Color.WHITE, 0x73000000),
            FrameLayout.LayoutParams(-1, -1))
    }

    private fun senderColor(ctx: android.content.Context, hash: String): Int = try {
        Color.parseColor(pl.sinusoide.sinuschat.util.AvatarHelper.getContactColor(ctx, hash))
    } catch (_: Exception) { pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx) }

    private fun bindSenderAvatar(iv: ImageView, hash: String, name: String) {
        val ctx = iv.context
        val size = (32 * ctx.resources.displayMetrics.density).toInt()
        val letter = letterCache.getOrPut("$hash|$name") {
            pl.sinusoide.sinuschat.util.AvatarHelper.createLetterBitmap(
                pl.sinusoide.sinuschat.util.AvatarHelper.initialOf(name),
                pl.sinusoide.sinuschat.util.AvatarHelper.getContactColor(ctx, hash), size)
        }
        val url = pl.sinusoide.sinuschat.util.ContactCache.getAvatarUrl(ctx, hash)
        if (url.isNullOrEmpty()) {
            Glide.with(iv).clear(iv)
            iv.setImageBitmap(letter)
        } else {
            val ph = android.graphics.drawable.BitmapDrawable(ctx.resources, letter)
            Glide.with(iv).load(url).circleCrop().placeholder(ph).error(ph).into(iv)
        }
    }

    private fun downloadRing(ctx: android.content.Context, item: MessageEntity, color: Int, disc: Int) =
        pl.sinusoide.sinuschat.ui.SineProgressRing(ctx).apply {
            tag = BADGE_TAG
            this.color = color
            this.disc = disc
            showCancel = false
            isClickable = false
            progress = { AttachmentDownloader.progressOf(item.id) }
        }

    private fun explainGone(ctx: android.content.Context, item: MessageEntity) {
        (ctx as? android.app.Activity)?.let { pl.sinusoide.sinuschat.util.PopupHelper.show(it, pl.sinusoide.sinuschat.util.MediaGone.text(ctx, item.timestampMs)) }
    }

    private fun showGoneOverlay(holder: VH, item: MessageEntity) {
        val box = holder.imageOverlay ?: return
        val ctx = box.context
        val d = ctx.resources.displayMetrics.density
        val label = pl.sinusoide.sinuschat.util.MediaGone.text(ctx, item.timestampMs)
        val image = holder.image
        val imageWidth = image?.layoutParams?.width?.takeIf { it > 0 } ?: (240 * d).toInt()
        val inset = (image?.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin ?: 0
        box.removeAllViews()
        box.visibility = View.VISIBLE
        box.addView(ImageView(ctx).apply {
            tag = GONE_TAG
            setImageResource(R.drawable.ic_no_media)
            setColorFilter(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x73000000)
            }
            val p = (14 * d).toInt()
            setPadding(p, p, p, p)
            contentDescription = label
        }, FrameLayout.LayoutParams(-1, -1))
        (image?.parent as? FrameLayout)?.addView(TextView(ctx).apply {
            tag = VIDEO_CHIP_TAG
            text = label
            textSize = 11.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * d; setColor(0x8C000000.toInt())
            }
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
        }, FrameLayout.LayoutParams(imageWidth - (16 * d).toInt(), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = inset + (8 * d).toInt(); bottomMargin = (11 * d).toInt()
        })
    }

    private fun showDownloadOverlay(holder: VH, item: MessageEntity) {
        val box = holder.imageOverlay ?: return
        val ctx = box.context
        box.removeAllViews()
        box.visibility = View.VISIBLE
        if (AttachmentDownloader.isActive(item.id)) {
            box.addView(downloadRing(ctx, item, Color.WHITE, 0x73000000), FrameLayout.LayoutParams(-1, -1))
        } else {
            box.addView(ImageView(ctx).apply {
                tag = BADGE_TAG
                setImageResource(R.drawable.ic_download)
                setColorFilter(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x73000000)
                }
                val p = (14 * ctx.resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                contentDescription = ctx.getString(R.string.downloading)
                setOnClickListener { onMediaDownload?.invoke(item) }
            }, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun bindVideo(holder: VH, item: MessageEntity, fileName: String, placeholder: Boolean) {
        val ctx = holder.itemView.context
        val d = ctx.resources.displayMetrics.density
        val iv = holder.image!!
        holder.text!!.visibility = View.GONE
        holder.docCard!!.visibility = View.GONE
        iv.visibility = View.VISIBLE
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = null
        iv.contentDescription = ctx.getString(R.string.video)
        iv.clipToOutline = true
        iv.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 14f)
            }
        }
        Glide.with(iv).clear(iv)

        val thumbs = pl.sinusoide.sinuschat.util.VideoThumbs
        val info = thumbs.load(ctx, conversationId, item.id)
        val local = MediaManager.getMediaFile(ctx, conversationId, item.id, fileName)
            .takeIf { it.exists() && it.length() > 0 }
        val base = thumbs.bitmap(ctx, conversationId, item.id) ?: videoPlaceholder(info)
        if (local != null) {
            val frameAt = if ((info?.durationMs ?: 0L) > 2000L) 1_000_000L else 0L
            val ph = android.graphics.drawable.BitmapDrawable(ctx.resources, base)
            Glide.with(iv).asBitmap().load(local)
                .apply(com.bumptech.glide.request.RequestOptions.frameOf(frameAt))
                .placeholder(ph).error(ph)
                .into(iv)
        } else iv.setImageBitmap(base)

        val gone = local == null && !placeholder && pl.sinusoide.sinuschat.util.MediaGone.isGone(ctx, item.id)
        val parts = mutableListOf<String>()
        info?.durationMs?.takeIf { it > 0 }?.let { parts += thumbs.formatDuration(it) }
        if (local == null && !placeholder) info?.sizeBytes?.takeIf { it > 0 }?.let { parts += MediaManager.formatSize(it) }
        (iv.parent as? FrameLayout)?.takeIf { parts.isNotEmpty() && !gone }?.addView(TextView(ctx).apply {
            tag = VIDEO_CHIP_TAG
            text = parts.joinToString("  ·  ")
            textSize = 11.5f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * d; setColor(0x8C000000.toInt())
            }
            setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = (10 * d).toInt(); bottomMargin = (10 * d).toInt()
        })

        val open = View.OnClickListener {
            when {
                isSelectionMode -> toggleSelection(item.id)
                placeholder -> Unit
                gone -> explainGone(ctx, item)
                else -> onAttachmentClick?.invoke(item)
            }
        }
        iv.setOnClickListener(open)
        val box = holder.imageOverlay ?: return
        when {
            placeholder -> showUploadOverlay(holder, item)
            gone -> showGoneOverlay(holder, item)
            AttachmentDownloader.isActive(item.id) -> {
                box.removeAllViews()
                box.visibility = View.VISIBLE
                box.addView(downloadRing(ctx, item, Color.WHITE, 0x73000000), FrameLayout.LayoutParams(-1, -1))
            }
            else -> {
                box.removeAllViews()
                box.visibility = View.VISIBLE
                box.addView(ImageView(ctx).apply {
                    tag = BADGE_TAG
                    setImageResource(if (local != null) R.drawable.ic_play_voice else R.drawable.ic_download)
                    setColorFilter(Color.WHITE)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x73000000)
                    }
                    val p = (14 * d).toInt()
                    setPadding(p, p, p, p)
                    contentDescription = ctx.getString(R.string.video)
                    setOnClickListener(open)
                }, FrameLayout.LayoutParams(-1, -1))
            }
        }
    }

    private class Flash(val id: String, val anim: android.animation.ValueAnimator)
    private var flashId: String? = null
    private var flashRequestedAt = 0L
    private var flashStartedAt = 0L
    private val flashMs = 1800L

    fun flash(id: String) {
        flashId = id
        flashRequestedAt = android.os.SystemClock.uptimeMillis()
        flashStartedAt = 0L
    }

    private fun stopFlash(view: View) {
        val f = view.getTag(R.id.msg_bubble_container) as? Flash ?: return
        view.setTag(R.id.msg_bubble_container, null)
        f.anim.cancel()
    }

    fun showFlash(view: View, id: String) {
        if ((view.getTag(R.id.msg_bubble_container) as? Flash)?.id == id) return
        stopFlash(view)
        if (id != flashId) return
        val now = android.os.SystemClock.uptimeMillis()
        if (flashStartedAt == 0L) {
            if (now - flashRequestedAt > 5000L) { flashId = null; return }
            flashStartedAt = now
        }
        val elapsed = now - flashStartedAt
        if (elapsed >= flashMs) return
        val startAlpha = (0x90 * (1f - elapsed.toFloat() / flashMs)).toInt().coerceIn(0, 0x90)
        val anim = android.animation.ValueAnimator.ofArgb((startAlpha shl 24) or 0x5288C1, 0x005288C1)
        anim.duration = flashMs - elapsed
        val flash = Flash(id, anim)
        anim.addUpdateListener { if (view.getTag(R.id.msg_bubble_container) === flash) view.setBackgroundColor(it.animatedValue as Int) }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (view.getTag(R.id.msg_bubble_container) === flash) {
                    view.setTag(R.id.msg_bubble_container, null)
                    view.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        })
        view.setTag(R.id.msg_bubble_container, flash)
        anim.start()
    }

    private class Snippets(val hash: Int, val segments: List<pl.sinusoide.sinuschat.util.CodeSnippets.Segment>?)
    private val snippetCache = android.util.LruCache<String, Snippets>(300)

    private fun snippetsFor(item: MessageEntity): List<pl.sinusoide.sinuschat.util.CodeSnippets.Segment>? {
        val hash = item.text.hashCode()
        snippetCache.get(item.id)?.let { if (it.hash == hash) return it.segments }
        val segments = try {
            pl.sinusoide.sinuschat.util.CodeSnippets.segments(item.text)
        } catch (_: Throwable) { null }
        snippetCache.put(item.id, Snippets(hash, segments))
        return segments
    }

    private fun bindSnippets(
        holder: VH, item: MessageEntity, segments: List<pl.sinusoide.sinuschat.util.CodeSnippets.Segment>,
        isMine: Boolean, showSender: Boolean
    ) {
        val box = holder.codeBox ?: return
        val tv = holder.text ?: return
        val ctx = holder.itemView.context
        val d = ctx.resources.displayMetrics.density
        val width = (ctx.resources.displayMetrics.widthPixels - (if (showSender) 124 else 84) * d).toInt()
            .coerceAtMost((400 * d).toInt()).coerceAtLeast((220 * d).toInt())
        val colors = tv.textColors
        val linkColor = if (isMine) Color.WHITE else pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx)
        tv.visibility = View.GONE
        CodePreview.fillSnippets(box, item.id, segments, pl.sinusoide.sinuschat.ui.ThemeUi.isDark(ctx), width,
            textView = { text ->
                TextView(ctx).apply {
                    tag = SNIPPET_TEXT_TAG
                    setTextColor(colors)
                    textSize = 15f
                    setLineSpacing(2 * d, 1f)
                    setPadding((9 * d).toInt(), 0, (9 * d).toInt(), 0)
                    autoLinkMask = android.text.util.Linkify.WEB_URLS
                    linksClickable = true
                    setLinkTextColor(linkColor)
                    this.text = text
                    safeLinks(this)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
            },
            onOpen = { seg ->
                if (isSelectionMode) toggleSelection(item.id)
                else activityOf(ctx)?.let { CodePreview.showSnippet(it, seg) }
            },
            onLongPress = { if (isSelectionMode) toggleSelection(item.id) else onLongPress?.invoke(item, holder.itemView) })
    }

    private fun bindCode(holder: VH, item: MessageEntity, fileName: String, file: java.io.File?) {
        val ctx = holder.itemView.context
        val chevron = holder.docExpand ?: return
        val box = holder.codeBox ?: return
        val dark = pl.sinusoide.sinuschat.ui.ThemeUi.isDark(ctx)
        val expanded = item.id in expandedCode
        chevron.visibility = View.VISIBLE
        chevron.rotation = if (expanded) 180f else 0f
        chevron.contentDescription = ctx.getString(if (expanded) R.string.code_hide else R.string.code_show)
        val open: () -> Unit = { if (isSelectionMode) toggleSelection(item.id) else if (file != null) onCodeOpen?.invoke(item, file) }
        val press: () -> Unit = { if (isSelectionMode) toggleSelection(item.id) else onLongPress?.invoke(item, holder.itemView) }
        chevron.setOnClickListener {
            if (isSelectionMode) { toggleSelection(item.id); return@setOnClickListener }
            if (item.id in expandedCode) {
                expandedCode.remove(item.id)
                chevron.animate().rotation(0f).setDuration(220).start()
                chevron.contentDescription = ctx.getString(R.string.code_show)
                CodePreview.collapse(box)
            } else {
                expandedCode.add(item.id)
                chevron.animate().rotation(180f).setDuration(220).start()
                chevron.contentDescription = ctx.getString(R.string.code_hide)
                if (file == null) onMediaDownload?.invoke(item)
                else CodePreview.fill(box, item.id, file, fileName, dark, true, open, press)
            }
        }
        if (expanded && file != null) CodePreview.fill(box, item.id, file, fileName, dark, false, open, press)
    }

    private val videoPlaceholders = HashMap<Int, Bitmap>()
    private fun videoPlaceholder(info: pl.sinusoide.sinuschat.util.VideoThumbs.Info?): Bitmap {
        val w = info?.width ?: 0
        val h = info?.height ?: 0
        if (w <= 0 || h <= 0) return placeholderBitmap()
        val ph = (64f * h / w).toInt().coerceIn(24, 128)
        return videoPlaceholders.getOrPut(ph) {
            Bitmap.createBitmap(64, ph, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF22303C.toInt()) }
        }
    }

    private var placeholderBmp: Bitmap? = null
    private fun placeholderBitmap(): Bitmap = placeholderBmp
        ?: Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF22303C.toInt()) }
            .also { placeholderBmp = it }

    private fun bindAlbum(holder: VH, item: MessageEntity, members: List<MessageEntity>, isMine: Boolean) {
        val ctx = holder.itemView.context
        val d = ctx.resources.displayMetrics.density
        holder.image!!.visibility = View.GONE
        holder.docCard!!.visibility = View.GONE
        holder.text!!.visibility = View.GONE
        val grid = holder.album ?: return
        grid.removeAllViews()
        grid.visibility = View.VISIBLE
        val width = (246 * d).toInt()
        val gap = (2 * d).toInt()
        val half = (width - gap) / 2
        val shown = members.take(4)
        val more = members.size - shown.size

        fun tile(index: Int, w: Int, h: Int): View {
            val m = shown[index]
            val file = MediaManager.getMediaFile(ctx, conversationId, m.id, m.attachmentName ?: "")
            val have = file.exists() && file.length() > 0
            val uploading = isMine && m.fileId.isNullOrEmpty()
            return FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(w, h)
                setBackgroundColor(0xFF22303C.toInt())
                val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(iv, FrameLayout.LayoutParams(-1, -1))
                if (have) Glide.with(iv).load(file).centerCrop().into(iv)
                val badge = (44 * d).toInt()
                when {
                    uploading && m.status == MessageEntity.STATUS_FAILED ->
                        addView(retryBadge(ctx, m, Color.WHITE, 0x73000000, 11), FrameLayout.LayoutParams(badge, badge, Gravity.CENTER))
                    uploading ->
                        addView(ring(ctx, m, Color.WHITE, 0x73000000), FrameLayout.LayoutParams(badge, badge, Gravity.CENTER))
                    !have && AttachmentDownloader.isActive(m.id) ->
                        addView(downloadRing(ctx, m, Color.WHITE, 0x73000000), FrameLayout.LayoutParams(badge, badge, Gravity.CENTER))
                    !have -> addView(ImageView(ctx).apply {
                        setImageResource(if (pl.sinusoide.sinuschat.util.MediaGone.isGone(ctx, m.id)) R.drawable.ic_no_media else R.drawable.ic_download)
                        setColorFilter(Color.WHITE)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x73000000)
                        }
                        val p = (11 * d).toInt(); setPadding(p, p, p, p)
                    }, FrameLayout.LayoutParams(badge, badge, Gravity.CENTER))
                }
                if (index == shown.lastIndex && more > 0) addView(TextView(ctx).apply {
                    text = "+$more"
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setBackgroundColor(0x8C000000.toInt())
                }, FrameLayout.LayoutParams(-1, -1))
                setOnClickListener {
                    when {
                        isSelectionMode -> toggleSelection(item.id)
                        uploading -> Unit
                        !have && pl.sinusoide.sinuschat.util.MediaGone.isGone(ctx, m.id) -> explainGone(ctx, m)
                        !have -> onMediaDownload?.invoke(m)
                        else -> onMediaOpen?.invoke(members, members.indexOf(m).coerceAtLeast(0))
                    }
                }
                setOnLongClickListener {
                    if (isSelectionMode) toggleSelection(item.id) else onLongPress?.invoke(item, holder.itemView)
                    true
                }
            }
        }
        fun row(vararg tiles: View) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            tiles.forEachIndexed { i, v ->
                if (i > 0) (v.layoutParams as LinearLayout.LayoutParams).marginStart = gap
                addView(v)
            }
        }
        when (shown.size) {
            2 -> grid.addView(row(tile(0, half, (170 * d).toInt()), tile(1, half, (170 * d).toInt())))
            3 -> {
                grid.addView(tile(0, width, (150 * d).toInt()))
                grid.addView(row(tile(1, half, half), tile(2, half, half)),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = gap })
            }
            else -> {
                grid.addView(row(tile(0, half, half), tile(1, half, half)))
                grid.addView(row(tile(2, half, half), tile(3, half, half)),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = gap })
            }
        }
        grid.clipToOutline = true
        grid.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 14 * d)
            }
        }
    }

    private fun linkRing(holder: VH, create: Boolean): pl.sinusoide.sinuschat.ui.SineProgressRing? {
        val card = holder.linkPreview ?: return null
        card.findViewWithTag<pl.sinusoide.sinuschat.ui.SineProgressRing>(LINK_RING_TAG)?.let { return it }
        if (!create) return null
        val ctx = holder.itemView.context
        val d = ctx.resources.displayMetrics.density
        val ring = pl.sinusoide.sinuschat.ui.SineProgressRing(ctx).apply {
            tag = LINK_RING_TAG
            color = pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx)
            disc = 0
            showCancel = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            progress = { null }
        }
        card.addView(ring, 0, LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = (4 * d).toInt()
        })
        return ring
    }

    private fun loadLinkPreview(holder: VH, url: String, position: Int) {
        showLinkLoading(holder, url)
        pl.sinusoide.sinuschat.util.LinkPreviewHelper.fetchPreview(holder.itemView.context, url) { data ->
            onLinkLoaded(holder, url, position, data)
        }
    }

    private fun onLinkLoaded(holder: VH, url: String, position: Int, data: pl.sinusoide.sinuschat.util.LinkPreviewHelper.PreviewData?) {
        if (holder.bindingAdapterPosition != position) return
        val act = activityOf(holder.itemView.context)
        if (act != null && (act.isFinishing || act.isDestroyed)) return
        when {
            data != null -> showLinkPreview(holder, data)
            pl.sinusoide.sinuschat.util.LinkPreviewHelper.hasNoPreview(holder.itemView.context, url) -> {
                linkRing(holder, false)?.visibility = View.GONE
                holder.linkPreview?.setOnClickListener(null)
                holder.linkPreview?.visibility = View.GONE
            }
            else -> showLinkFailed(holder, url, position)
        }
    }

    private fun showLinkLoading(holder: VH, url: String) {
        val ctx = holder.itemView.context
        holder.linkPreview?.visibility = View.VISIBLE
        holder.linkPreview?.setOnClickListener(null)
        holder.linkPreview?.isClickable = false
        linkRing(holder, true)?.visibility = View.VISIBLE
        holder.linkImageContainer?.visibility = View.GONE
        holder.linkTitle?.text = ctx.getString(R.string.link_preview_loading)
        holder.linkTitle?.visibility = View.VISIBLE
        holder.linkDesc?.visibility = View.GONE
        holder.linkHost?.text = pl.sinusoide.sinuschat.util.LinkPreviewHelper.hostOf(url)
        holder.linkHost?.visibility = View.VISIBLE
        markLinkTrust(holder, pl.sinusoide.sinuschat.util.LinkPreviewHelper.isTrusted(url))
        holder.linkFavicon?.visibility = View.GONE
    }

    private fun showLinkFailed(holder: VH, url: String, position: Int) {
        val ctx = holder.itemView.context
        linkRing(holder, false)?.visibility = View.GONE
        holder.linkPreview?.visibility = View.VISIBLE
        holder.linkImageContainer?.visibility = View.GONE
        holder.linkTitle?.text = ctx.getString(R.string.link_preview_failed)
        holder.linkTitle?.visibility = View.VISIBLE
        holder.linkDesc?.text = ctx.getString(R.string.link_preview_retry)
        holder.linkDesc?.visibility = View.VISIBLE
        holder.linkHost?.text = pl.sinusoide.sinuschat.util.LinkPreviewHelper.hostOf(url)
        holder.linkHost?.visibility = View.VISIBLE
        holder.linkFavicon?.visibility = View.GONE
        holder.linkPreview?.setOnClickListener { loadLinkPreview(holder, url, position) }
    }

    private fun showLinkPrompt(holder: VH, url: String, position: Int) {
        val ctx = holder.itemView.context
        linkRing(holder, false)?.visibility = View.GONE
        holder.linkPreview?.visibility = View.VISIBLE
        holder.linkTitle?.text = ctx.getString(R.string.link_preview_tap)
        holder.linkTitle?.visibility = View.VISIBLE
        holder.linkDesc?.visibility = View.GONE
        holder.linkHost?.text = pl.sinusoide.sinuschat.util.LinkPreviewHelper.hostOf(url)
        holder.linkHost?.visibility = View.VISIBLE
        markLinkTrust(holder, false)
        holder.linkFavicon?.visibility = View.GONE
        holder.linkImageContainer?.visibility = View.GONE
        holder.linkPreview?.setOnClickListener {
            val act = activityOf(ctx) ?: return@setOnClickListener
            if (act.isFinishing || act.isDestroyed) return@setOnClickListener
            pl.sinusoide.sinuschat.util.LinkPreviewHelper.askAndLoad(act, url, onStart = {
                if (holder.bindingAdapterPosition == position) showLinkLoading(holder, url)
            }) { data -> onLinkLoaded(holder, url, position, data) }
        }
    }

    private fun openMap(ctx: android.content.Context, lat: Double, lon: Double) {
        val act = activityOf(ctx) ?: return
        pl.sinusoide.sinuschat.util.SafeEngine.guard(act, pl.sinusoide.sinuschat.util.SafeEngine.Risk.OPEN_MAP) {
            try {
                act.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")))
            } catch (_: Exception) {
                try {
                    act.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=15/$lat/$lon")))
                } catch (_: Exception) {}
            }
        }
    }

    private fun activityOf(context: android.content.Context): android.app.Activity? {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun safeLinks(tv: TextView) {
        val text = tv.text as? android.text.Spannable ?: return
        for (span in text.getSpans(0, text.length, android.text.style.URLSpan::class.java)) {
            if (span is SafeUrlSpan) continue
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val flags = text.getSpanFlags(span)
            text.removeSpan(span)
            text.setSpan(SafeUrlSpan(span.url), start, end, flags)
        }
    }

    private class SafeUrlSpan(url: String) : android.text.style.URLSpan(url) {
        override fun onClick(widget: View) {
            var c: android.content.Context? = widget.context
            while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
            val act = c as? android.app.Activity
            if (act != null) pl.sinusoide.sinuschat.util.SafeEngine.openLink(act, url)
            else try { super.onClick(widget) } catch (_: Exception) {}
        }
    }

    private fun showLinkPreview(holder: VH, data: pl.sinusoide.sinuschat.util.LinkPreviewHelper.PreviewData) {
        val act = activityOf(holder.itemView.context)
        if (act != null && (act.isFinishing || act.isDestroyed)) return
        linkRing(holder, false)?.visibility = View.GONE
        holder.linkPreview?.setOnClickListener(null)
        holder.linkPreview?.isClickable = false
        holder.linkPreview?.visibility = View.VISIBLE
        holder.linkTitle?.text = data.title
        holder.linkTitle?.visibility = if (data.title != null) View.VISIBLE else View.GONE
        holder.linkDesc?.text = data.description
        holder.linkDesc?.visibility = if (data.description != null) View.VISIBLE else View.GONE

        val uri = Uri.parse(data.url)
        val host = uri.host
        val trusted = pl.sinusoide.sinuschat.util.LinkPreviewHelper.isTrusted(data.url)
        markLinkTrust(holder, trusted)
        holder.linkHost?.text = if (trusted && host != null)
            holder.itemView.context.getString(R.string.link_trusted, host) else host
        holder.linkHost?.visibility = if (host != null) View.VISIBLE else View.GONE

        if (data.icon != null) {
            holder.linkFavicon?.visibility = View.VISIBLE
            Glide.with(holder.itemView.context).load(data.icon).into(holder.linkFavicon!!)
        } else {
            holder.linkFavicon?.visibility = View.GONE
        }

        if (data.image != null) {
            holder.linkImageContainer?.visibility = View.VISIBLE
            Glide.with(holder.itemView.context)
                .load(data.image)
                .centerCrop()
                .into(holder.linkImage!!)
        } else {
            holder.linkImageContainer?.visibility = View.GONE
        }
    }

    private fun markLinkTrust(holder: VH, trusted: Boolean) {
        val icon = holder.linkIcon ?: return
        icon.setImageResource(if (trusted) R.drawable.ic_shield else R.drawable.ic_link)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (trusted) 0xFF3FB97A.toInt() else 0xC0FFFFFF.toInt())
    }

    private fun tint(iv: ImageView, hex: String) {
        val d = iv.drawable ?: return; DrawableCompat.setTint(DrawableCompat.wrap(d.mutate()), Color.parseColor(hex))
    }
}
