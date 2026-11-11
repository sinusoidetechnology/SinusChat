package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator

class LogoDrawView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var startNs = 0L
    private var cx = 0f
    private var cy = 0f
    private var discRadius = 0f

    private val ease = PathInterpolator(0.45f, 0f, 0.2f, 1f)
    private val pop = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = CYAN
    }

    private val bubbleShape = Path()
    private val waveShape = Path()
    private val bubbleLine = Path()
    private val waveLine = Path()
    private val piece = Path()
    private val measure = PathMeasure()
    private var bubbleLen = 0f
    private var waveLen = 0f
    private var scale = 1f

    @Suppress("UNUSED_PARAMETER")
    fun setColors(inkColor: Int, accentColor: Int) = Unit

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cx = w / 2f
        cy = h / 2f
        discRadius = minOf(w, h) / 2f * 0.8f
        build()
        glow.shader = RadialGradient(cx, cy, discRadius * 1.25f,
            intArrayOf(withAlpha(CYAN, 0.22f), withAlpha(CYAN, 0.22f), withAlpha(CYAN, 0.07f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.74f, 0.86f, 1f), Shader.TileMode.CLAMP)
        startNs = System.nanoTime()
    }

    private fun build() {
        val side = discRadius * 2f * 0.62f
        val target = RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        val m: Matrix = SinusLogo.fit(target)
        val c = SinusLogo.CONTENT
        scale = minOf(target.width() / c.width(), target.height() / c.height())
        bubbleShape.set(SinusLogo.bubble()); bubbleShape.transform(m)
        waveShape.set(SinusLogo.wave()); waveShape.transform(m)
        bubbleLine.set(SinusLogo.bubbleLine()); bubbleLine.transform(m)
        waveLine.set(SinusLogo.waveLine()); waveLine.transform(m)
        bubbleShape.fillType = Path.FillType.EVEN_ODD
        measure.setPath(bubbleLine, false)
        bubbleLen = measure.length
        measure.setPath(waveLine, false)
        waveLen = measure.length
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (startNs == 0L) startNs = System.nanoTime()
        val ms = (System.nanoTime() - startNs) / 1_000_000f

        val g = span(ms, 1500f, 2200f)
        if (g > 0f) {
            glow.alpha = (255 * g).toInt()
            canvas.drawCircle(cx, cy, discRadius * 1.25f, glow)
        }

        val d = pop.getInterpolation(span(ms, 0f, 520f))
        if (d > 0f) {
            disc.alpha = (255 * minOf(1f, d * 1.4f)).toInt()
            canvas.drawCircle(cx, cy, discRadius * (0.84f + 0.16f * d), disc)
        }

        line.strokeWidth = 26f * scale
        reveal(canvas, bubbleShape, bubbleLine, bubbleLen, ease.getInterpolation(span(ms, 200f, 1300f)))
        line.strokeWidth = 13f * scale
        reveal(canvas, waveShape, waveLine, waveLen, ease.getInterpolation(span(ms, 1050f, 1850f)))

        if (ms < 2200f) postInvalidateOnAnimation()
    }

    private fun reveal(canvas: Canvas, shape: Path, path: Path, length: Float, k: Float) {
        if (k <= 0f) return
        if (k >= 1f) { canvas.drawPath(shape, fill); return }
        measure.setPath(path, false)
        piece.reset()
        measure.getSegment(0f, length * k, piece, true)
        canvas.save()
        canvas.clipPath(shape)
        canvas.drawPath(piece, line)
        canvas.restore()
    }

    private fun span(ms: Float, from: Float, to: Float) = ((ms - from) / (to - from)).coerceIn(0f, 1f)

    private fun withAlpha(color: Int, a: Float) =
        (color and 0x00FFFFFF) or ((255 * a).toInt() shl 24)

    companion object {
        const val CYAN = SinusLogo.CYAN

        const val DRAW_MS = 1900L
    }
}
