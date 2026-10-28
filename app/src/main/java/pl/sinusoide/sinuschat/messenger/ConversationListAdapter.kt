package pl.sinusoide.sinuschat.messenger

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.MuteSettings
import pl.sinusoide.sinuschat.util.BlockList
import pl.sinusoide.sinuschat.util.PrivacySettings
import java.text.SimpleDateFormat
import java.util.*

class ConversationListAdapter(
    private val myPhoneHash: String,
    private val onClick: (ConversationItem) -> Unit,
    private val onLongClick: ((ConversationItem, View) -> Unit)? = null
) : ListAdapter<ConversationItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val RETRO_PREFIX = "retro:"
        private const val TYPE_HEADER = 1
        private val DIFF = object : DiffUtil.ItemCallback<ConversationItem>() {
            override fun areItemsTheSame(a: ConversationItem, b: ConversationItem) = a.id == b.id
            override fun areContentsTheSame(a: ConversationItem, b: ConversationItem) = a == b
        }
    }

    private var allItems: List<ConversationItem> = emptyList()

    val allConversations: List<ConversationItem> get() = allItems

    var onFiltered: ((shown: Int) -> Unit)? = null

    var pins: () -> Map<String, Long> = { emptyMap() }

    private fun ordered(list: List<ConversationItem>): List<ConversationItem> {
        val p = pins()
        val marked = list.map { if ((it.id in p) != it.isPinned) it.copy(isPinned = it.id in p) else it }
        if (p.isEmpty()) return marked
        val (top, rest) = marked.partition { it.isPinned }
        return top.sortedByDescending { p[it.id] ?: 0L } + rest
    }

    var query: String = ""
        set(value) {
            field = value.trim()
            super.submitList(applyQuery(allItems)) { onFiltered?.invoke(currentList.size) }
        }

    override fun submitList(list: List<ConversationItem>?) = submitList(list, null)

    override fun submitList(list: List<ConversationItem>?, commitCallback: Runnable?) {
        allItems = ordered(list.orEmpty())
        super.submitList(applyQuery(allItems)) {
            if (query.isNotEmpty()) onFiltered?.invoke(currentList.size)
            commitCallback?.run()
        }
    }

    var retro = false

    private val collapsed = mutableSetOf<String>()

    private fun header(key: String, count: Int) = ConversationItem(
        id = RETRO_PREFIX + key, otherUserHash = "", otherUserName = key,
        lastMessage = count.toString(), lastMessageAt = pl.sinusoide.sinuschat.util.Timestamp(0, 0))

    private fun grouped(items: List<ConversationItem>): List<ConversationItem> {
        val online = items.filter { !it.isGroup && it.isOnline }
        val groups = items.filter { it.isGroup }
        val offline = items.filter { !it.isGroup && !it.isOnline }
        val out = mutableListOf<ConversationItem>()
        for ((key, part) in listOf("on" to online, "groups" to groups, "off" to offline)) {
            if (part.isEmpty()) continue
            out += header(key, part.size)
            if (key !in collapsed) out += part
        }
        return out
    }

    private fun toggle(key: String) {
        if (!collapsed.remove(key)) collapsed += key
        super.submitList(applyQuery(allItems))
    }

    private fun applyQuery(items: List<ConversationItem>): List<ConversationItem> {
        if (query.isEmpty()) return if (retro) grouped(items) else items
        val q = fold(query)
        return items.filter { fold(it.otherUserName).contains(q) }
    }

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('ł', 'l')

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatarLetter: TextView = view.findViewById(R.id.conv_avatar_letter)
        val avatarImage: ImageView = view.findViewById(R.id.conv_avatar_image)
        val onlineIndicator: View? = view.findViewById(R.id.conv_online_indicator)
        val name: TextView = view.findViewById(R.id.conv_name)
        val lastMsg: TextView = view.findViewById(R.id.conv_last_message)
        val lockIcon: ImageView = view.findViewById(R.id.conv_lock_icon)
        val time: TextView = view.findViewById(R.id.conv_time)
        val badge: TextView = view.findViewById(R.id.conv_unread_badge)
        val muteIcon: ImageView = view.findViewById(R.id.conv_mute_icon)
        val pinIcon: ImageView? = view.findViewById(R.id.conv_pin_icon)
    }

    private var colorsResolved = false
    private var cPrimary = 0
    private var cSecondary = 0
    private var cMuted = 0
    private var cAccent = 0
    private var cBackground = 0

    private fun resolveColors(ctx: android.content.Context) {
        if (colorsResolved) return
        fun attr(id: Int, fallback: Int): Int {
            val tv = android.util.TypedValue()
            return pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(ctx, id) ?: fallback
        }
        cPrimary = attr(R.attr.sinuschat_text_primary, Color.WHITE)
        cSecondary = attr(R.attr.sinuschat_text_secondary, Color.parseColor("#7F91A4"))
        cMuted = attr(R.attr.sinuschat_text_muted, Color.parseColor("#5E6D7E"))
        cBackground = attr(R.attr.sinuschat_background_solid, Color.parseColor("#0E1621"))
        cAccent = pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx)
        colorsResolved = true
    }

    inner class HeaderVH(view: android.widget.LinearLayout) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.getChildAt(0) as TextView
        val count: TextView = view.getChildAt(1) as TextView
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).id.startsWith(RETRO_PREFIX)) TYPE_HEADER else 0

    private fun headerView(parent: ViewGroup): android.widget.LinearLayout {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val retroUi = pl.sinusoide.sinuschat.ui.RetroUi
        return android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(-1, (30 * d).toInt())
            setPadding((10 * d).toInt(), 0, (12 * d).toInt(), 0)
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(retroUi.FRAME),
                android.graphics.drawable.ColorDrawable(Color.WHITE),
                android.graphics.drawable.ColorDrawable(retroUi.SECTION))).apply {
                setLayerInset(1, 0, 0, 0, d.toInt())
                setLayerInset(2, 0, d.toInt(), 0, d.toInt())
            }
            addView(TextView(ctx).apply {
                textSize = 13.5f; setTextColor(retroUi.NAVY); setTypeface(null, Typeface.BOLD)
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(ctx).apply {
                textSize = 13f; setTextColor(retroUi.NAVY); setTypeface(null, Typeface.BOLD)
            })
        }
    }

    private fun statusDot(ctx: android.content.Context, color: Int): android.graphics.drawable.Drawable {
        val s = (11 * ctx.resources.displayMetrics.density).toInt()
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1), 0x40000000)
            setSize(s, s)
            setBounds(0, 0, s, s)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        resolveColors(parent.context)
        if (viewType == TYPE_HEADER) return HeaderVH(headerView(parent))
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation_e2e, parent, false)
        val vh = VH(view)
        val d = parent.resources.displayMetrics.density
        if (retro) {
            view.findViewById<View>(R.id.avatar_container)?.visibility = View.GONE
            view.setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            vh.name.compoundDrawablePadding = (8 * d).toInt()
            vh.name.textSize = 15f
            vh.lastMsg.textSize = 13f
            vh.lastMsg.setPadding((19 * d).toInt(), 0, 0, 0)
        }
        vh.onlineIndicator?.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#3FB97A"))
            setStroke((2.5f * d).toInt(), cBackground)
        }
        return vh
    }

    override fun onBindViewHolder(vh: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val ctx = vh.itemView.context
        if (vh is HeaderVH) {
            val key = item.id.removePrefix(RETRO_PREFIX)
            val label = ctx.getString(when (key) {
                "on" -> R.string.retro_online
                "groups" -> R.string.retro_groups
                else -> R.string.retro_offline
            })
            vh.label.text = (if (key in collapsed) "+  " else "−  ") + label
            vh.count.text = item.lastMessage
            vh.itemView.setOnClickListener { toggle(key) }
            return
        }
        val holder = vh as VH

        val color = AvatarHelper.getContactColor(ctx, item.otherUserHash)
        val img = holder.avatarImage
        holder.avatarLetter.visibility = View.GONE
        img.visibility = View.VISIBLE
        img.clipToOutline = true

        Glide.with(ctx).clear(img)
        img.setImageDrawable(null)

        if (item.isGroup) {
            AvatarHelper.loadGroupAvatar(ctx, img, item.otherUserName, item.groupColor, item.avatarUrl, groupId = item.id)
        } else if (!item.avatarUrl.isNullOrEmpty()) {
            val fallback = BitmapDrawable(
                ctx.resources,
                AvatarHelper.createLetterBitmap(
                    AvatarHelper.initialOf(item.otherUserName),
                    color,
                    (44 * ctx.resources.displayMetrics.density).toInt()
                )
            )
            Glide.with(ctx)
                .load(item.avatarUrl)
                .signature(ObjectKey(item.avatarUrl))
                .circleCrop()
                .placeholder(fallback)
                .error(fallback)
                .into(img)
        } else {
            AvatarHelper.setLetterAvatar(ctx, img, item.otherUserName, color)
        }

        holder.name.text = item.otherUserName
        if (retro) {
            val cut = BlockList.isCutOff(ctx, item.otherUserHash)
            val dot = when {
                item.isGroup -> 0xFFE6B422.toInt()
                item.isOnline && !cut -> 0xFF3CB043.toInt()
                else -> 0xFFC0392B.toInt()
            }
            holder.name.setCompoundDrawables(statusDot(ctx, dot), null, null, null)
            holder.onlineIndicator?.visibility = View.GONE
        }
        holder.onlineIndicator?.visibility = if (item.isOnline && !item.isGroup && !BlockList.isCutOff(ctx, item.otherUserHash)) View.VISIBLE else View.GONE

        val hidePreview = PrivacySettings.shouldHidePreview(ctx)
        
        if (hidePreview) {
            holder.lockIcon.visibility = View.VISIBLE
            holder.lastMsg.text = ctx.getString(R.string.locked_release).split(" — ").first()
            holder.time.visibility = View.INVISIBLE
        } else {
            holder.lockIcon.visibility = View.GONE
            
            val draft = if (item.unreadCount == 0) pl.sinusoide.sinuschat.util.Drafts.get(ctx, item.id) else null
            holder.lastMsg.text = if (draft != null) {
                val label = ctx.getString(R.string.draft_prefix)
                android.text.SpannableStringBuilder(label).apply {
                    setSpan(android.text.style.ForegroundColorSpan(0xFFE5484D.toInt()), 0, label.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(" ")
                    append(draft.replace('\n', ' '))
                }
            } else item.lastMessage
            
            val hasTime = item.lastMessageAt.seconds > 0L
            holder.time.visibility = if (hasTime && item.lastMessage.isNotEmpty()) View.VISIBLE else View.INVISIBLE
            holder.time.text = formatTime(item.lastMessageAt.toDate(), ctx)
        }

        val muted = MuteSettings.isMuted(ctx, item.id)
        holder.muteIcon.visibility = if (muted) View.VISIBLE else View.GONE
        holder.pinIcon?.visibility = if (item.isPinned) View.VISIBLE else View.GONE

        if (item.unreadCount > 0) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
            holder.badge.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * ctx.resources.displayMetrics.density
                setColor(if (muted) cMuted else cAccent)
            }
            holder.name.setTypeface(null, Typeface.BOLD)
            holder.lastMsg.setTypeface(null, if (muted) Typeface.NORMAL else Typeface.BOLD)
            holder.lastMsg.setTextColor(if (muted) cSecondary else cPrimary)
            holder.time.setTextColor(if (muted) cMuted else cAccent)
        } else {
            holder.badge.visibility = View.GONE
            holder.name.setTypeface(null, Typeface.NORMAL)
            holder.lastMsg.setTypeface(null, Typeface.NORMAL)
            holder.lastMsg.setTextColor(cSecondary)
            holder.time.setTextColor(cMuted)
        }

        holder.itemView.setOnClickListener { onClick(item) }

        holder.itemView.setOnLongClickListener { view ->
            onLongClick?.invoke(item, view)
            true
        }
    }

    private var yesterdayLabel: String? = null

    private fun formatTime(date: Date, ctx: android.content.Context): String {
        val msg = Calendar.getInstance().apply { time = date }
        val today = Calendar.getInstance()
        fun startOfDay(c: Calendar) = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val days = ((startOfDay(today) - startOfDay(msg)) / 86_400_000L).toInt()
        return when {
            days <= 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            days == 1 -> yesterdayLabel ?: ctx.getString(R.string.calls_yesterday).also { yesterdayLabel = it }
            days < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(date)
                .replaceFirstChar { it.uppercase() }
            today.get(Calendar.YEAR) == msg.get(Calendar.YEAR) ->
                SimpleDateFormat("d MMM", Locale.getDefault()).format(date)
            else -> SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(date)
        }
    }
}
