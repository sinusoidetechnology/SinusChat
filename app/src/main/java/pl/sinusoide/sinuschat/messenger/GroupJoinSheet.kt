package pl.sinusoide.sinuschat.messenger

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.PopupHelper

class GroupJoinSheet(
    private val activity: Activity,
    private val code: String,
    private val onClosed: () -> Unit = {}
) {
    private val dp = activity.resources.displayMetrics.density
    private val sheet = BottomSheetDialog(activity, R.style.Theme_SinusChat_BottomSheet)
    private var opened = false
    private var busy = false

    private var cPrimary = 0; private var cSecondary = 0; private var cCard = 0; private var cAccent = 0
    private val cGreen = 0xFF2ECC71.toInt()

    private lateinit var avatar: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var nameView: TextView
    private lateinit var metaView: TextView
    private lateinit var descView: TextView
    private lateinit var noteView: TextView
    private lateinit var button: TextView
    private lateinit var pill: TextView

    private fun attr(id: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(activity, id) ?: fallback
    }

    fun show() {
        cPrimary = attr(R.attr.sinuschat_text_primary, Color.WHITE)
        cSecondary = attr(R.attr.sinuschat_text_secondary, 0xFF7F91A4.toInt())
        cCard = attr(R.attr.sinuschat_surface_elevated, 0xFF1D2A38.toInt())
        cAccent = pl.sinusoide.sinuschat.ui.ThemeUi.accent(activity)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (22 * dp).toInt())
        }
        root.addView(View(activity).apply {
            background = GradientDrawable().apply { cornerRadius = 3 * dp; setColor((cSecondary and 0x00FFFFFF) or 0x55000000) }
        }, LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply { bottomMargin = (20 * dp).toInt() })

        val frame = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((cGreen and 0x00FFFFFF) or 0x1F000000)
                setStroke((2 * dp).toInt(), (cGreen and 0x00FFFFFF) or 0x99000000.toInt())
            }
            val p = (5 * dp).toInt(); setPadding(p, p, p, p)
        }
        avatar = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            alpha = 0f
        }
        progress = ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(cAccent)
        }
        frame.addView(avatar, FrameLayout.LayoutParams(-1, -1))
        frame.addView(progress, FrameLayout.LayoutParams((34 * dp).toInt(), (34 * dp).toInt(), Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams((96 * dp).toInt(), (96 * dp).toInt()))

        pill = TextView(activity).apply {
            text = activity.getString(R.string.type_community)
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cGreen)
            background = GradientDrawable().apply { cornerRadius = 10 * dp; setColor((cGreen and 0x00FFFFFF) or 0x24000000) }
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            visibility = View.INVISIBLE
        }
        root.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * dp).toInt() })

        nameView = TextView(activity).apply {
            text = activity.getString(R.string.group_join_loading)
            textSize = 21f; setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; maxLines = 2
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }
        root.addView(nameView, LinearLayout.LayoutParams(-1, -2))
        metaView = TextView(activity).apply {
            textSize = 13.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        root.addView(metaView, LinearLayout.LayoutParams(-1, -2))
        descView = TextView(activity).apply {
            textSize = 14f; setTextColor(cPrimary); gravity = Gravity.CENTER
            maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
            background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(cCard) }
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            visibility = View.GONE
        }
        root.addView(descView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() })
        noteView = TextView(activity).apply {
            textSize = 12.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding((4 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt(), (18 * dp).toInt())
        }
        root.addView(noteView, LinearLayout.LayoutParams(-1, -2))

        button = TextView(activity).apply {
            text = activity.getString(R.string.group_join_btn)
            textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF1C8A50.toInt(), 0xFF25A96C.toInt())).apply { cornerRadius = 26 * dp }
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            isClickable = true
            alpha = 0.4f
        }
        root.addView(button, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.cancel)
            textSize = 15f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))

        sheet.setContentView(root)
        sheet.setOnDismissListener { if (!opened) onClosed() }
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
        load()
    }

    private fun load() {
        GroupInviteManager.preview(activity, code) { result ->
            if (!sheet.isShowing) return@preview
            progress.visibility = View.GONE
            when (result) {
                is GroupInviteManager.Result.Error -> showError(GroupInviteManager.errorMessage(activity, result.code))
                is GroupInviteManager.Result.Ok -> render(result.value)
            }
        }
    }

    private fun render(p: GroupInviteManager.Preview) {
        AvatarHelper.loadGroupAvatar(activity, avatar, p.name, p.avatarColor, p.avatarUrl, (86 * dp).toInt(), p.groupId)
        avatar.scaleX = 0.8f; avatar.scaleY = 0.8f
        avatar.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
        pill.visibility = View.VISIBLE
        nameView.text = p.name
        metaView.text = activity.resources.getQuantityString(R.plurals.group_join_members, p.memberCount, p.memberCount)
        if (p.description.isNotEmpty()) { descView.text = p.description; descView.visibility = View.VISIBLE }

        if (p.alreadyMember && p.groupId != null) {
            noteView.text = activity.getString(R.string.group_join_already)
            button.text = activity.getString(R.string.group_join_open)
            button.setOnClickListener { open(p.groupId, p.name) }
        } else {
            noteView.text = activity.getString(R.string.group_join_note)
            button.setOnClickListener { join() }
        }
        button.animate().alpha(1f).setDuration(200).start()
    }

    private fun join() {
        if (busy) return
        busy = true
        button.alpha = 0.6f
        button.text = activity.getString(R.string.group_join_joining)
        GroupInviteManager.join(activity, code) { result ->
            busy = false
            if (!sheet.isShowing) return@join
            when (result) {
                is GroupInviteManager.Result.Ok -> {
                    PopupHelper.showSuccess(activity, activity.getString(R.string.group_join_done))
                    open(result.value.groupId, result.value.name)
                }
                is GroupInviteManager.Result.Error -> {
                    button.alpha = 1f
                    button.text = activity.getString(R.string.group_join_btn)
                    PopupHelper.showError(activity, GroupInviteManager.errorMessage(activity, result.code))
                }
            }
        }
    }

    private fun open(groupId: String, name: String) {
        opened = true
        sheet.dismiss()
        GroupInviteManager.openGroup(activity, groupId, name)
        if (activity is QrLinkActivity) activity.finish()
    }

    private fun showError(message: String) {
        avatar.setImageResource(R.drawable.ic_link)
        avatar.setColorFilter(cSecondary)
        avatar.scaleType = ImageView.ScaleType.CENTER_INSIDE
        avatar.animate().alpha(1f).setDuration(200).start()
        nameView.text = activity.getString(R.string.group_join_invalid_title)
        metaView.text = message
        button.text = activity.getString(R.string.action_close)
        button.background = GradientDrawable().apply { cornerRadius = 26 * dp; setColor(cCard) }
        button.setTextColor(cPrimary)
        button.alpha = 1f
        button.setOnClickListener { sheet.dismiss() }
    }
}
