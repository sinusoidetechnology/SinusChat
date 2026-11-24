package pl.sinusoide.sinuschat.backup

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import pl.sinusoide.sinuschat.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class HandoffView(
    context: Context,
    private val from: Drawable,
    private val to: Drawable,
    private val accent: Int,
    private val track: Int
) : View(context) {

    private val d = resources.displayMetrics.density
    private val green = 0xFF2ECC71.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.6f * d
    }
    private val lock = ContextCompat.getDrawable(context, R.drawable.ic_lock)?.mutate()
    private val check = Path()
    private var flow = 0f
    private var done = 0f

    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { flow = it.animatedValue as Float; invalidate() }
    }

    fun complete(onEnd: () -> Unit) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460
            interpolator = OvershootInterpolator(2.2f)
            addUpdateListener { done = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    loop.cancel()
                    postDelayed({ onEnd() }, 520)
                }
            })
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!loop.isStarted) loop.start()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (72 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val iconR = 26 * d
        val lx = iconR + 2 * d
        val rx = width - iconR - 2 * d
        val cx = width / 2f
        val ready = done.coerceIn(0f, 1f)
        val lineColor = ColorUtils.blendARGB(accent, green, ready)

        val start = lx + iconR + 12 * d
        val end = rx - iconR - 12 * d
        val n = 12
        val gap = 26 * d
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val x = start + t * (end - start)
            if (abs(x - cx) < gap) continue
            val behind = ((flow - t) % 1f + 1f) % 1f
            val glow = (1f - behind / 0.4f).coerceAtLeast(0f) * (1f - ready)
            val a = (0.25f + 0.75f * maxOf(glow, ready)).coerceIn(0f, 1f)
            paint.color = ColorUtils.setAlphaComponent(if (glow > 0.05f || ready > 0f) lineColor else track, (a * 255).toInt())
            canvas.drawCircle(x, cy, 2.6f * d * (1f + 0.4f * glow), paint)
        }

        drawIcon(canvas, from, lx, cy, iconR)
        drawIcon(canvas, to, rx, cy, iconR)

        val pulse = 1f + 0.08f * sin(2 * PI * flow).toFloat() * (1f - ready)
        val br = 18 * d * pulse
        paint.color = ColorUtils.setAlphaComponent(lineColor, 0x30)
        canvas.drawCircle(cx, cy, br, paint)
        if (done > 0.02f) {
            val s = 9 * d * done
            check.reset()
            check.moveTo(cx - s * 0.72f, cy + s * 0.05f)
            check.lineTo(cx - s * 0.2f, cy + s * 0.55f)
            check.lineTo(cx + s * 0.75f, cy - s * 0.5f)
            stroke.color = lineColor
            canvas.drawPath(check, stroke)
        } else {
            lock?.let {
                val h = (8 * d).toInt()
                it.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
                it.setTint(lineColor)
                it.draw(canvas)
            }
        }
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable, x: Float, y: Float, r: Float) {
        icon.setBounds((x - r).toInt(), (y - r).toInt(), (x + r).toInt(), (y + r).toInt())
        icon.draw(canvas)
    }
}
