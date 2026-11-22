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
import pl.sinusoide.sinuschat.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class JuggleIconView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private class Toy(val path: Path, val px: Float, val py: Float,
                      val u0: Float, val target: Float, val spin: Boolean)

    private val toys: List<Toy>
    private val person = Path()
    private val personHalo = Path()

    private var ink = Color.WHITE
    private var startNs = 0L
    private val iconToView = Matrix()
    private var scale = 1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pop = OvershootInterpolator(2.2f)

    init {
        val res = R.drawable.ic_personplay
        fun group(r: RectF) = Path().apply {
            for (p in IconDrawView.shapesInside(context, res, r)) addPath(p)
        }
        toys = listOf(
            Toy(group(RectF(40f, 160f, 400f, 520f)), 220f, 340f, 0f, 0f, spin = true),
            Toy(group(RectF(340f, 20f, 620f, 300f)), 480f, 160f, FRONT / 2, 1f / 3, spin = false),
            Toy(group(RectF(590f, 220f, 910f, 500f)), 750f, 360f, FRONT, 2f / 3, spin = true)
        )
        for (p in IconDrawView.shapesInside(context, res, RectF(100f, 390f, 860f, 900f))) person.addPath(p)
        val outline = Path()
        Paint().apply { style = Paint.Style.STROKE; strokeWidth = 44f }.getFillPath(person, outline)
        personHalo.op(person, outline, Path.Op.UNION)
    }

    fun setColor(color: Int) {
        ink = color
        layoutGlow()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        scale = minOf(w, h) * 0.6f / CONTENT.height()
        iconToView.setTranslate(-CONTENT.centerX(), -CONTENT.centerY())
        iconToView.postScale(scale, scale)
        iconToView.postTranslate(w / 2f, h / 2f)
        layoutGlow()
        if (startNs == 0L) startNs = System.nanoTime()
    }

    private fun layoutGlow() {
        if (width == 0) return
        glow.shader = RadialGradient(width / 2f, height / 2f, minOf(width, height) / 2f,
            intArrayOf(withAlpha(ink, 0.12f), withAlpha(ink, 0.035f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        if (startNs == 0L) startNs = System.nanoTime()
        val still = A11y.reducedMotion(context)
        val ms = if (still) START_MS.toFloat() else (System.nanoTime() - startNs) / 1_000_000f

        val intro = (ms / 450f).coerceIn(0f, 1f)
        val introScale = 0.82f + 0.18f * pop.getInterpolation(intro)
        val alpha = (intro * 2.5f).coerceAtMost(1f)

        glow.alpha = (255 * ((ms - 300f) / 600f).coerceIn(0f, 1f)).toInt()
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, glow)

        val tau = (ms - START_MS).coerceAtLeast(0f)
        val cycles = (if (tau < RAMP_MS) tau * tau / (2 * RAMP_MS) else RAMP_MS / 2 + (tau - RAMP_MS)) / PERIOD_MS
        val settle = smooth(cycles.coerceAtMost(1f))

        canvas.save()
        canvas.concat(iconToView)
        canvas.scale(introScale, introScale, CONTENT.centerX(), CONTENT.centerY())

        for (pass in 0..1) {
            for (toy in toys) {
                val u = toy.u0 + (toy.target - toy.u0) * settle + cycles
                val here = at(u)
                if (here.front != (pass == 1)) continue
                val base = at(toy.u0)
                paint.color = ink
                paint.alpha = (255 * alpha * here.alpha).toInt()
                canvas.save()
                if (!here.front) canvas.clipOutPath(personHalo)
                canvas.translate(here.x - base.x, here.y - base.y)
                canvas.scale(here.scale, here.scale, toy.px, toy.py)
                if (toy.spin) canvas.rotate(360f * cycles, toy.px, toy.py)
                canvas.drawPath(toy.path, paint)
                canvas.restore()
            }
            if (pass == 0) {
                paint.color = ink
                paint.alpha = (255 * alpha).toInt()
                canvas.drawPath(person, paint)
            }
        }
        canvas.restore()

        if (isShown && !still) postInvalidateOnAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate()
    }

    private class Spot(val x: Float, val y: Float, val scale: Float, val alpha: Float, val front: Boolean)

    private fun at(uRaw: Float): Spot {
        val u = uRaw - kotlin.math.floor(uRaw)
        if (u < FRONT) {
            val v = u / FRONT
            val x = XL + (XR - XL) * v
            val y = YL + (YR - YL) * v - 4f * ARC * v * (1 - v)
            return Spot(x, y, 1f, 1f, true)
        }
        val w = (u - FRONT) / (1 - FRONT)
        val e = 0.5f - 0.5f * cos(PI.toFloat() * w)
        val k = sin(PI.toFloat() * w)
        val x = XR + (XL - XR) * e
        val y = YR + (YL - YR) * e + DEPTH * k
        return Spot(x, y, 1f - 0.38f * k, 1f - 0.55f * k, false)
    }

    private fun smooth(x: Float) = x * x * (3 - 2 * x)

    private fun withAlpha(color: Int, a: Float) =
        (color and 0x00FFFFFF) or ((255 * a).toInt() shl 24)

    private companion object {
        val CONTENT = RectF(64f, 40f, 886f, 880f)

        const val FRONT = 0.6f
        const val XL = 220f
        const val YL = 340f
        const val XR = 750f
        const val YR = 360f
        const val ARC = 190f
        const val DEPTH = 95f

        const val START_MS = 700f
        const val RAMP_MS = 900f
        const val PERIOD_MS = 3000f
    }
}
