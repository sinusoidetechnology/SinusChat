package pl.sinusoide.sinuschat.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import pl.sinusoide.sinuschat.R

object ConfirmSheet {

    fun show(
        activity: Activity,
        icon: Int,
        color: Int,
        title: String,
        message: String,
        confirm: String,
        confirmIcon: Int = icon,
        onConfirm: () -> Unit,
        onCancel: () -> Unit = {},
        dismiss: String? = null,
        extra: View? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density
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
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((color and 0x00FFFFFF) or 0x14000000) }
        }
        halo.addView(ImageView(activity).apply {
            setImageResource(icon)
            setColorFilter(color)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((color and 0x00FFFFFF) or 0x2E000000) }
            val p = (15 * d).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((58 * d).toInt(), (58 * d).toInt()))
        root.addView(halo, LinearLayout.LayoutParams((84 * d).toInt(), (84 * d).toInt()).apply { bottomMargin = (14 * d).toInt() })
        val breathe = ObjectAnimator.ofPropertyValuesHolder(halo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.07f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.07f, 1f)).apply {
            duration = 1600; repeatCount = ObjectAnimator.INFINITE
            start()
        }

        root.addView(TextView(activity).apply {
            text = title
            textSize = 20f; setTextColor(ThemeUi.textPrimary(activity)); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(activity).apply {
            text = message
            textSize = 14f; setTextColor(ThemeUi.textSecondary(activity)); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(0, (8 * d).toInt(), 0, (22 * d).toInt())
        })
        extra?.let { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (18 * d).toInt() }) }

        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 26 * d; setColor(color) }
            setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
            isClickable = true
            addView(ImageView(activity).apply {
                setImageResource(confirmIcon)
                setColorFilter(Color.WHITE)
            }, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
            addView(TextView(activity).apply {
                text = confirm
                textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            })
            setOnClickListener {
                confirmed = true
                sheet.dismiss()
                onConfirm()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(activity).apply {
            text = dismiss ?: activity.getString(R.string.cancel)
            textSize = 15f; setTextColor(ThemeUi.textSecondary(activity)); gravity = Gravity.CENTER
            setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))

        sheet.setOnDismissListener {
            breathe.cancel()
            if (!confirmed) onCancel()
        }
        sheet.setContentView(root)
        FlowGlass.show(sheet)
    }
}
