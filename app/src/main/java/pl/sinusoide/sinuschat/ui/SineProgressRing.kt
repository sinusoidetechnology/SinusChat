package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class SineProgressRing(context: Context) : View(context) {

    var progress: () -> Float? = { null }
    var color: Int = Color.WHITE
        set(value) { field = value; invalidate() }
    var disc: Int = 0x73000000
    var stopColor: Int = 0
        set(value) { field = value; invalidate() }
    var showCancel = true
        set(value) {
            field = value
            contentDescription = if (value) context.getString(pl.sinusoide.sinuschat.R.string.cancel) else null
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val start = System.nanoTime()
    private var shown = 0f

    init {
        isClickable = true
        contentDescription = context.getString(pl.sinusoide.sinuschat.R.string.cancel)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f; val cy = h / 2f
        val size = min(w, h)
        val stroke = max(2f * d, size * 0.055f)
        val amp0 = size * 0.05f
        val r = size / 2f - stroke - amp0 - 1f
        val t = (System.nanoTime() - start) / 1e9f

        if (Color.alpha(disc) > 0) {
            fill.color = disc
            canvas.drawCircle(cx, cy, size / 2f, fill)
        }
        track.color = (color and 0x00FFFFFF) or 0x38000000
        track.strokeWidth = stroke * 0.6f
        canvas.drawCircle(cx, cy, r, track)

        val p = progress()
        val startAngle: Float
        val sweep: Float
        val amp: Float
        if (p == null) {
            startAngle = (t * 280f) % 360f - 90f
            sweep = 80f
            amp = amp0
        } else {
            shown += (p.coerceIn(0f, 1f) - shown) * 0.12f
            startAngle = -90f
            sweep = max(14f, 360f * shown)
            amp = amp0 * (1f - 0.6f * shown)
        }

        val waves = 9f
        val phase = t * 7f
        val steps = max(28, (sweep / 3f).toInt())
        path.reset()
        for (i in 0..steps) {
            val deg = startAngle + sweep * i / steps
            val rad = Math.toRadians(deg.toDouble()).toFloat()
            val rr = r + amp * sin(rad * waves - phase)
            val x = cx + rr * cos(rad)
            val y = cy + rr * sin(rad)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        wave.color = color
        wave.strokeWidth = stroke
        canvas.drawPath(path, wave)

        if (stopColor != 0) {
            val k = size * 0.12f
            fill.color = stopColor
            canvas.drawRoundRect(cx - k, cy - k, cx + k, cy + k, k * 0.3f, k * 0.3f, fill)
        } else if (showCancel) {
            val k = size * 0.13f
            cross.color = color
            cross.strokeWidth = stroke * 0.9f
            canvas.drawLine(cx - k, cy - k, cx + k, cy + k, cross)
            canvas.drawLine(cx - k, cy + k, cx + k, cy - k, cross)
        }
        if (isShown) postInvalidateOnAnimation()
    }
}
