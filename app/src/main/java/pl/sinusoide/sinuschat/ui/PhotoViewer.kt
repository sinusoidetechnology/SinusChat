package pl.sinusoide.sinuschat.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

object PhotoViewer {

    private class ViewerDialog(activity: Activity) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
        var onBack: (() -> Unit)? = null
        @Deprecated("Deprecated in Java")
        override fun onBackPressed() {
            val back = onBack
            if (back != null) back() else super.onBackPressed()
        }
    }

    fun show(activity: Activity, onDownload: ((ImageView) -> Unit)? = null, bind: (ImageView) -> Unit) {
        val dialog = ViewerDialog(activity)
        pl.sinusoide.sinuschat.util.PopupHelper.trackOverlay(dialog)
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setWindowAnimations(0)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        val img = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            scaleX = 0.9f; scaleY = 0.9f
        }
        root.addView(img, FrameLayout.LayoutParams(-1, -1))
        if (onDownload != null) {
            val d = activity.resources.displayMetrics.density
            val btn = ImageView(activity).apply {
                setImageResource(pl.sinusoide.sinuschat.R.drawable.ic_download)
                setColorFilter(Color.WHITE)
                val p = (12 * d).toInt(); setPadding(p, p, p, p)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x66000000)
                }
                contentDescription = activity.getString(pl.sinusoide.sinuschat.R.string.avatar_download)
                isClickable = true
                setOnClickListener { onDownload(img) }
            }
            root.addView(btn, FrameLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt(), android.view.Gravity.TOP or android.view.Gravity.END).apply {
                topMargin = (40 * d).toInt(); marginEnd = (16 * d).toInt()
            })
        }

        var closing = false
        val close = {
            if (!closing) {
                closing = true
                root.animate().cancel(); img.animate().cancel()
                img.animate().scaleX(0.92f).scaleY(0.92f).setDuration(190)
                    .setInterpolator(AccelerateInterpolator(1.4f)).start()
                root.animate().alpha(0f).setDuration(190)
                    .setInterpolator(AccelerateInterpolator(1.4f))
                    .withEndAction { try { dialog.dismiss() } catch (_: Exception) {} }.start()
            }
        }
        dialog.onBack = close
        root.setOnClickListener { close() }
        img.setOnClickListener { close() }
        bind(img)
        dialog.setContentView(root)
        dialog.show()
        root.animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        img.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(DecelerateInterpolator(1.8f)).start()
    }
}
