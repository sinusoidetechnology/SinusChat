package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import pl.sinusoide.sinuschat.R
import kotlin.math.PI
import kotlin.math.sin

class ConnectedHopView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val icon = IconDrawView.vectorPath(context, R.drawable.ic_connected) ?: Path()
    private val content = RectF(1f, 3f, 23f, 21f)
    private val phone = RectF(13.4f, 7.4f, 23f, 21f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ease = PathInterpolator(0.45f, 0f, 0.2f, 1f)
    private val bounce = OvershootInterpolator(2.2f)
    private val m = Matrix()
    private var ink = Color.WHITE
    private var accent = ThemeUi.accent(context)
    private val startNs = System.nanoTime()

    fun setColors(inkColor: Int, accentColor: Int) {
        ink = inkColor
        accent = accentColor
        layoutGlow()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutGlow()
    }

    private fun layoutGlow() {
        if (width == 0) return
        val r = minOf(width, height) / 2f
        glow.shader = RadialGradient(width / 2f, height / 2f, r,
            intArrayOf((accent and 0x00FFFFFF) or 0x1F000000, (accent and 0x00FFFFFF) or 0x09000000, Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    private fun span(ms: Float, from: Float, to: Float) = ((ms - from) / (to - from)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val ms = (System.nanoTime() - startNs) / 1_000_000f
        val g = span(ms, 500f, 1100f)
        if (g > 0f) {
            glow.alpha = (255 * g).toInt()
            canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, glow)
        }
        val s = minOf(width, height) * 0.6f / content.width()
        val monitorK = ease.getInterpolation(span(ms, 0f, 450f))
        val drop = span(ms, 250f, 900f)
        val landing = if (drop < 1f) (1f - bounce.getInterpolation(drop)) * -7f else 0f
        val since = ms - 1300f
        val hop = if (since > 0f) {
            val k = (since % 1600f) / 450f
            if (k < 1f) -2.4f * sin(PI.toFloat() * k) else 0f
        } else 0f

        canvas.save()
        m.setTranslate(-content.centerX(), -content.centerY())
        m.postScale(s, s)
        m.postTranslate(width / 2f, height / 2f)
        canvas.concat(m)

        canvas.save()
        val sc = 0.9f + 0.1f * monitorK
        canvas.scale(sc, sc, 8f, 12f)
        canvas.clipOutRect(phone)
        paint.color = ink
        paint.alpha = (255 * monitorK).toInt()
        canvas.drawPath(icon, paint)
        canvas.restore()

        if (drop > 0f) {
            canvas.save()
            canvas.translate(0f, landing + hop)
            canvas.clipRect(phone)
            paint.color = ink
            paint.alpha = (255 * span(ms, 250f, 450f)).toInt()
            canvas.drawPath(icon, paint)
            canvas.restore()
        }
        canvas.restore()
        postInvalidateOnAnimation()
    }
}
