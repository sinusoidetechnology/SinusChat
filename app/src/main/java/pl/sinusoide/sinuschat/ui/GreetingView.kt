package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator

class GreetingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private class Stroke(val path: Path, val length: Float, val startMs: Long, val endMs: Long)

    private class Word(val lang: String, val text: String, val shapes: List<Path>, val bounds: RectF) {
        var strokes: List<Stroke> = emptyList()
        var writeMs = 0L
        val separate get() = shapes.size > 4
        val cycleMs get() = writeMs + HOLD_MS + FADE_MS + GAP_MS
    }

    private val words = HandwritingData.WORDS.map { w ->
        val shapes = w.strokes.map { toPath(it) }
        val bounds = RectF().also { shapes.first().computeBounds(it, true) }
        val part = RectF()
        for (s in shapes.drop(1)) { s.computeBounds(part, true); bounds.union(part) }
        Word(w.lang, w.text, shapes, bounds)
    }

    private val order: MutableList<Int> = run {
        val lang = pl.sinusoide.sinuschat.util.LocaleManager.getEffective(context)
        val first = words.indices.filter { words[it].lang == lang }
        (first + words.indices.filter { it !in first }.shuffled()).toMutableList()
    }

    private fun reshuffle() {
        val head = order.first()
        val rest = order.drop(1).shuffled()
        order.clear()
        order.add(head)
        order.addAll(rest)
    }

    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private var ink = Color.WHITE
    private val measure = PathMeasure()
    private val piece = Path()

    private val fade = PathInterpolator(0.45f, 0f, 0.55f, 1f)

    private var index = 0
    private var wordStart = 0L
    private var startDelay = 0L
    private var running = false

    fun setTextColor(color: Int) {
        ink = color
        invalidate()
    }

    fun startAfter(delayMs: Long) {
        startDelay = delayMs
        if (running) {
            wordStart = SystemClock.uptimeMillis() + startDelay
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        wordStart = SystemClock.uptimeMillis() + startDelay
        startDelay = 250
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) layoutWords(w, h)
    }

    private fun layoutWords(w: Int, h: Int) {
        val base = h / (HandwritingData.ASCENT + HandwritingData.DESCENT)
        val baseline = HandwritingData.ASCENT * base
        val room = w - paddingLeft - paddingRight - HandwritingData.PEN * base * 2
        val m = Matrix()
        for (word in words) {
            val scale = minOf(base, room / word.bounds.width())
            val dx = paddingLeft + (w - paddingLeft - paddingRight - word.bounds.width() * scale) / 2f -
                word.bounds.left * scale
            m.setScale(scale, scale)
            m.postTranslate(dx, baseline)

            var t = 0L
            val separate = word.separate
            word.strokes = word.shapes.mapIndexed { i, shape ->
                val p = Path()
                shape.transform(m, p)
                measure.setPath(p, false)
                val len = measure.length
                val units = len / scale
                val dur = if (separate) (120 + units * 1.2f).toLong()
                else if (i == 0) (450 + units * 0.66f).toLong().coerceIn(1200, 3200)
                else if (units < 8f) 130L else (190 + units * 1.6f).toLong()
                val start = if (i == 0) 0L else t + if (separate) 90 else 170
                t = start + dur
                Stroke(p, len, start, t)
            }
            word.writeMs = t
        }
        pen.strokeWidth = HandwritingData.PEN * base
    }

    override fun onDraw(canvas: Canvas) {
        if (!running || width == 0 || words.first().strokes.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (now < wordStart) { postInvalidateDelayed(wordStart - now); return }

        var word = words[order[index]]
        var t = now - wordStart
        while (t >= word.cycleMs) {
            wordStart += word.cycleMs
            index = (index + 1) % words.size
            if (index == 0) reshuffle()
            word = words[order[index]]
            t = now - wordStart
        }
        if (contentDescription != word.text) contentDescription = word.text

        val fadeFrom = word.writeMs + HOLD_MS
        val a = when {
            t < fadeFrom -> 1f
            t < fadeFrom + FADE_MS -> 1f - fade.getInterpolation((t - fadeFrom) / FADE_MS.toFloat())
            else -> 0f
        }
        pen.color = ink or 0xFF000000.toInt()
        val layerAlpha = (Color.alpha(ink) * a).toInt()

        if (a > 0f) {
            val layer = if (layerAlpha < 255)
                canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), layerAlpha) else -1
            for ((i, s) in word.strokes.withIndex()) {
                if (t <= s.startMs) break
                val raw = ((t - s.startMs) / (s.endMs - s.startMs).toFloat()).coerceIn(0f, 1f)
                val k = if (i == 0 && !word.separate) penMotion(raw) else fade.getInterpolation(raw)
                if (k >= 1f) {
                    canvas.drawPath(s.path, pen)
                } else if (k > 0f) {
                    measure.setPath(s.path, false)
                    piece.reset()
                    measure.getSegment(0f, s.length * k, piece, true)
                    canvas.drawPath(piece, pen)
                }
            }
            if (layer >= 0) canvas.restoreToCount(layer)
        }

        when {
            t < word.writeMs -> postInvalidateOnAnimation()
            t < fadeFrom -> postInvalidateDelayed(fadeFrom - t)
            t < fadeFrom + FADE_MS -> postInvalidateOnAnimation()
            else -> postInvalidateDelayed(word.cycleMs - t)
        }
    }

    private fun penMotion(x: Float): Float = 0.6f * x + 0.4f * x * x * (3f - 2f * x)

    private fun toPath(a: FloatArray) = Path().apply {
        moveTo(a[0], a[1])
        var i = 2
        while (i + 5 < a.size) {
            cubicTo(a[i], a[i + 1], a[i + 2], a[i + 3], a[i + 4], a[i + 5])
            i += 6
        }
    }

    private companion object {
        const val HOLD_MS = 1500L

        const val FADE_MS = 850L

        const val GAP_MS = 350L
    }
}
