package pl.sinusoide.sinuschat.call

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import pl.sinusoide.sinuschat.ui.CallButtonView
import pl.sinusoide.sinuschat.ui.LockPalette

class AudioRouteBar(
    context: Context,
    palette: LockPalette,
    private val onPick: (CallAudio.Route) -> Unit
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private val routes = listOf(CallAudio.Route.BLUETOOTH, CallAudio.Route.SPEAKER, CallAudio.Route.PHONE)
    private val buttons = HashMap<CallAudio.Route, CallButtonView>()
    private val labels = HashMap<CallAudio.Route, TextView>()

    var palette: LockPalette = palette
        set(value) { field = value; restyle() }

    var open = false
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        visibility = GONE
        isClickable = true
        setPadding((8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), (10 * d).toInt())
        val size = (50 * d).toInt()
        for (r in routes) {
            val button = CallButtonView(context, CallAudio.icon(r), palette = palette).apply {
                setOnClickListener { pick(r) }
            }
            val label = TextView(context).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener { pick(r) }
                addView(button, LayoutParams(size, size))
                addView(label, LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() })
            }
            addView(column, LayoutParams(0, -2, 1f).apply {
                marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt()
            })
            buttons[r] = button
            labels[r] = label
        }
        restyle()
    }

    fun bind(audio: CallAudio) {
        for (r in routes) {
            val text = audio.label(context, r)
            labels[r]?.text = text
            buttons[r]?.contentDescription = text
            buttons[r]?.active = r == audio.route
        }
    }

    fun show() {
        if (open) return
        open = true
        animate().cancel()
        visibility = VISIBLE
        alpha = 0f
        translationY = 24 * d
        animate().alpha(1f).translationY(0f).setDuration(240)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    fun hide() {
        if (!open) return
        open = false
        animate().cancel()
        animate().alpha(0f).translationY(16 * d).setDuration(180)
            .withEndAction { if (!open) visibility = GONE }.start()
    }

    private fun pick(r: CallAudio.Route) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onPick(r)
    }

    private fun restyle() {
        buttons.values.forEach { it.palette = palette }
        labels.values.forEach { it.setTextColor(palette.textSecondary) }
        background = GradientDrawable().apply {
            cornerRadius = 28 * d
            if (palette.flow) { setColor(0x14FFFFFF); setStroke((1 * d).toInt(), 0x29FFFFFF) }
            else { setColor(palette.fill); setStroke((1 * d).toInt(), palette.rimTop) }
        }
    }
}
