package pl.sinusoide.sinuschat.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

class RetroCheckDrawable(private val density: Float) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = BORDER
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = CHECK
    }
    private val box = RectF()
    private val inner = RectF()
    private val path = Path()
    private var checked = false
    private var enabled = true
    private var pressed = false
    private var alphaValue = 255

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val c = state.contains(android.R.attr.state_checked)
        val e = state.contains(android.R.attr.state_enabled)
        val p = state.contains(android.R.attr.state_pressed)
        if (c == checked && e == enabled && p == pressed) return false
        checked = c; enabled = e; pressed = p
        invalidateSelf()
        return true
    }

    override fun getIntrinsicWidth() = (SIZE * density).toInt()
    override fun getIntrinsicHeight() = (SIZE * density).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val side = minOf(b.width(), b.height(), (SIZE * density).toInt()).toFloat()
        val left = b.exactCenterX() - side / 2f
        val top = b.exactCenterY() - side / 2f
        val sw = density.coerceAtLeast(1f)
        box.set(left + sw / 2f, top + sw / 2f, left + side - sw / 2f, top + side - sw / 2f)
        val a = if (enabled) alphaValue else alphaValue / 2
        fill.alpha = a; border.alpha = a; tick.alpha = a
        border.strokeWidth = sw
        fill.shader = LinearGradient(0f, box.top, 0f, box.bottom,
            if (pressed) 0xFFBCE4F9.toInt() else 0xFFDCDCDC.toInt(), 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(box, fill)
        val inset = 2.5f * sw
        inner.set(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
        fill.shader = LinearGradient(0f, inner.top, 0f, inner.bottom,
            if (pressed) 0xFFC4E5F6.toInt() else 0xFFE6E6E6.toInt(), if (pressed) 0xFFE5F4FC.toInt() else 0xFFF8F8F8.toInt(),
            Shader.TileMode.CLAMP)
        canvas.drawRect(inner, fill)
        border.color = if (pressed) PRESSED_BORDER else BORDER
        canvas.drawRect(box, border)
        if (!checked) return
        val u = side / 13f
        tick.strokeWidth = 1.8f * u
        path.reset()
        path.moveTo(left + 3.4f * u, top + 6.6f * u)
        path.lineTo(left + 5.6f * u, top + 9.0f * u)
        path.lineTo(left + 9.8f * u, top + 3.8f * u)
        canvas.drawPath(path, tick)
    }

    override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val SIZE = 18f
        const val BORDER = 0xFF8E8F8F.toInt()
        const val PRESSED_BORDER = 0xFF2C628B.toInt()
        const val CHECK = 0xFF4A5F97.toInt()
    }
}
