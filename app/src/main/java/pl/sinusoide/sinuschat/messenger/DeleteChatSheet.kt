package pl.sinusoide.sinuschat.messenger

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import pl.sinusoide.sinuschat.ui.ThemeUi
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.AvatarHelper

object DeleteChatSheet {

    private const val RED = 0xFFE5484D.toInt()

    fun show(
        activity: Activity,
        conversationId: String,
        name: String,
        isGroup: Boolean,
        otherHash: String?,
        avatarUrl: String?,
        groupColor: String?,
        lastMessage: String?,
        onConfirm: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val d = activity.resources.displayMetrics.density
        val secondary = ThemeUi.textSecondary(activity)
        val primary = ThemeUi.textPrimary(activity)
        val sheet = BottomSheetDialog(activity, R.style.Theme_SinusChat_BottomSheet)
        var confirmed = false

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
        }
        root.addView(View(activity).apply {
            background = GradientDrawable().apply { cornerRadius = 3 * d; setColor(ThemeUi.handle(activity)) }
        }, LinearLayout.LayoutParams((36 * d).toInt(), (4 * d).toInt()).apply { bottomMargin = (22 * d).toInt() })

        val halo = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((RED and 0x00FFFFFF) or 0x14000000) }
        }
        val iconBox = ImageView(activity).apply {
            setImageResource(if (isGroup) R.drawable.ic_leave else R.drawable.ic_delete)
            setColorFilter(RED)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((RED and 0x00FFFFFF) or 0x2E000000) }
            val p = (15 * d).toInt(); setPadding(p, p, p, p)
        }
        halo.addView(iconBox, LinearLayout.LayoutParams((58 * d).toInt(), (58 * d).toInt()))
        root.addView(halo, LinearLayout.LayoutParams((84 * d).toInt(), (84 * d).toInt()).apply { bottomMargin = (14 * d).toInt() })
        val breathe = ObjectAnimator.ofPropertyValuesHolder(halo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.07f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.07f, 1f)).apply {
            duration = 1600; repeatCount = ObjectAnimator.INFINITE
            start()
        }

        root.addView(TextView(activity).apply {
            text = activity.getString(if (isGroup) R.string.delete_chat_q_group else R.string.delete_chat_q)
            textSize = 20f; setTextColor(primary); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(activity).apply {
            text = if (isGroup) activity.getString(R.string.delete_chat_desc_group)
                   else activity.getString(R.string.delete_chat_desc, name)
            textSize = 14f; setTextColor(secondary); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(0, (8 * d).toInt(), 0, (18 * d).toInt())
        })

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(ThemeUi.card(activity))
                setStroke((1 * d).toInt().coerceAtLeast(1), (RED and 0x00FFFFFF) or 0x33000000)
            }
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
        }
        val avatarSize = (46 * d).toInt()
        val avatar = ImageView(activity)
        if (isGroup) {
            AvatarHelper.loadGroupAvatar(activity, avatar, name, groupColor ?: "#5288C1", avatarUrl, avatarSize, conversationId)
        } else {
            val color = AvatarHelper.getContactColor(activity, otherHash.orEmpty())
            avatar.setImageBitmap(AvatarHelper.createLetterBitmap(AvatarHelper.initialOf(name), color, avatarSize))
            avatarUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                com.bumptech.glide.Glide.with(avatar).load(url).circleCrop().into(avatar)
            }
        }
        card.addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize).apply { marginEnd = (12 * d).toInt() })
        val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(activity).apply {
            text = name; textSize = 15.5f; setTextColor(primary); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (!lastMessage.isNullOrBlank() && !pl.sinusoide.sinuschat.util.PrivacySettings.shouldHidePreview(activity)) {
            info.addView(TextView(activity).apply {
                text = lastMessage; textSize = 12.5f; setTextColor(secondary)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
        val countView = TextView(activity).apply {
            textSize = 12f; setTextColor(RED); visibility = View.GONE
            setPadding(0, (3 * d).toInt(), 0, 0)
        }
        info.addView(countView)
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (22 * d).toInt() })

        AppExecutor.io {
            val n = try {
                SinusChatDatabase.getInstance(activity).messageDao().countInConversation(conversationId)
            } catch (_: Exception) { -1 }
            if (n > 0) AppExecutor.main {
                countView.text = activity.resources.getQuantityString(R.plurals.chat_message_count, n, n)
                countView.visibility = View.VISIBLE
            }
        }

        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 26 * d; setColor(RED) }
            setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
            isClickable = true
            addView(ImageView(activity).apply {
                setImageResource(if (isGroup) R.drawable.ic_leave else R.drawable.ic_delete)
                setColorFilter(Color.WHITE)
            }, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
            addView(TextView(activity).apply {
                text = activity.getString(if (isGroup) R.string.delete_chat_leave_group else R.string.delete_chat)
                textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            })
            setOnClickListener {
                confirmed = true
                sheet.dismiss()
                onConfirm()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.cancel)
            textSize = 15f; setTextColor(secondary); gravity = Gravity.CENTER
            setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))

        sheet.setOnDismissListener {
            breathe.cancel()
            if (!confirmed) onCancel()
        }
        sheet.setContentView(root)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }
}
