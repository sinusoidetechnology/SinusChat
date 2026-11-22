package pl.sinusoide.sinuschat.call

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import pl.sinusoide.sinuschat.R

object IncomingCallScreen {

    class Built(val root: FrameLayout, val bounce: Runnable?)

    fun build(
        activity: Activity,
        handler: Handler,
        phoneLocked: Boolean,
        isVideo: Boolean,
        pillText: String,
        title: String,
        subtitle: String,
        letter: Bitmap,
        loadAvatar: (ImageView) -> Unit,
        onAnswer: () -> Unit,
        onDecline: () -> Unit
    ): Built {
        val dp = activity.resources.displayMetrics.density
        val palette = pl.sinusoide.sinuschat.ui.LockPalette.of(activity)
        val cfg = activity.resources.configuration
        val wide = cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenHeightDp in 1..559

        val root = FrameLayout(activity)
        root.addView(pl.sinusoide.sinuschat.ui.AuroraBackgroundView(activity, palette), FrameLayout.LayoutParams(-1, -1))

        val frame = LinearLayout(activity).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), (40 * dp).toInt())
        }
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        val edge = ((if (wide) 16 else 36) * dp).toInt()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + (24 * dp).toInt(), bars.top + edge, bars.right + (24 * dp).toInt(), bars.bottom + edge)
            insets
        }

        val info = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val typePill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp; setColor(palette.fill); setStroke((1 * dp).toInt(), palette.rimTop)
            }
            setPadding((14 * dp).toInt(), (7 * dp).toInt(), (16 * dp).toInt(), (7 * dp).toInt())
            addView(ImageView(activity).apply {
                setImageResource(if (isVideo) R.drawable.ic_call_video_on else R.drawable.ic_call_answer)
                setColorFilter(palette.text)
            }, LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
            addView(TextView(activity).apply {
                text = pillText
                textSize = 13.5f; setTextColor(palette.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }
        info.addView(typePill, LinearLayout.LayoutParams(-2, -2))

        val avatar = pl.sinusoide.sinuschat.ui.PulseAvatarView(activity, palette)
        val avatarDp = when {
            wide -> (cfg.screenHeightDp - 150).coerceIn(96, 200).toFloat()
            phoneLocked -> (activity.resources.displayMetrics.heightPixels / dp - 500f).coerceIn(150f, 240f)
            else -> 240f
        }
        val avatarPx = (avatarDp * dp).toInt()
        avatar.setAvatar(letter)
        loadAvatar(avatar.imageView())
        info.addView(avatar, LinearLayout.LayoutParams(avatarPx, avatarPx).apply { topMargin = ((if (wide) 10 else 18) * dp).toInt() })

        val nameView = TextView(activity).apply {
            text = title
            textSize = if (wide) 26f else 32f
            setTextColor(palette.text)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 2
        }
        info.addView(nameView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * dp).toInt() })
        info.addView(TextView(activity).apply {
            text = subtitle
            textSize = 14f; setTextColor(palette.textSecondary); gravity = Gravity.CENTER
            letterSpacing = 0.06f
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * dp).toInt() })

        val actions: View
        val actionsHeight: Int
        var bounce: Runnable? = null
        if (phoneLocked && !pl.sinusoide.sinuschat.ui.A11y.enabled(activity)) {
            val arc = pl.sinusoide.sinuschat.ui.ArcAnswerView(activity, palette, onAnswer = onAnswer, onDecline = onDecline)
            actionsHeight = ((if (wide) minOf(262, cfg.screenHeightDp - 40) else 262) * dp).toInt()
            arc.alpha = 0f; arc.translationY = 30 * dp
            arc.animate().alpha(1f).translationY(0f).setStartDelay(260).setDuration(520)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            actions = arc
        } else {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                clipChildren = false; clipToPadding = false
            }
            row.addView(actionColumn(activity, palette, activity.getString(R.string.decline), R.drawable.ic_call_end, 0xFFE5484D.toInt(), onDecline),
                LinearLayout.LayoutParams(0, -2, 1f))
            val answerCol = actionColumn(activity, palette, activity.getString(R.string.answer), R.drawable.ic_call_answer, 0xFF2FBF71.toInt(), onAnswer)
            row.addView(answerCol, LinearLayout.LayoutParams(0, -2, 1f))
            val answerBtn = answerCol.getChildAt(0)
            val loop = object : Runnable {
                override fun run() {
                    if (activity.isFinishing) return
                    answerBtn.animate().translationY(-10 * dp).setDuration(260)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                            answerBtn.animate().translationY(0f).setDuration(420)
                                .setInterpolator(android.view.animation.BounceInterpolator()).start()
                        }.start()
                    handler.postDelayed(this, 1800)
                }
            }
            bounce = loop
            handler.postDelayed(loop, 900)
            actionsHeight = -2
            actions = row
        }

        if (wide) {
            frame.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            frame.addView(actions, LinearLayout.LayoutParams(0, actionsHeight, 1f))
        } else {
            frame.addView(info, LinearLayout.LayoutParams(-1, -2))
            frame.addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))
            frame.addView(actions, LinearLayout.LayoutParams(-1, actionsHeight))
        }

        avatar.scaleX = 0.7f; avatar.scaleY = 0.7f; avatar.alpha = 0f
        avatar.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(600)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        listOf<View>(typePill, nameView).forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = 16 * dp
            v.animate().alpha(1f).translationY(0f).setStartDelay(120L + i * 80L).setDuration(420).start()
        }
        return Built(root, bounce)
    }

    private fun actionColumn(
        activity: Activity,
        palette: pl.sinusoide.sinuschat.ui.LockPalette,
        label: String,
        iconRes: Int,
        color: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val dp = activity.resources.displayMetrics.density
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        col.addView(pl.sinusoide.sinuschat.ui.CallButtonView(activity, iconRes, color).apply {
            contentDescription = label
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onClick()
            }
        }, LinearLayout.LayoutParams((84 * dp).toInt(), (84 * dp).toInt()))
        col.addView(TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() })
        return col
    }
}
