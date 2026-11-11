package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import pl.sinusoide.sinuschat.R
import kotlin.math.PI
import kotlin.math.sin

class DistanceWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var ink = Color.WHITE
    private var accent = ThemeUi.accent(context)
    private var target = 1f
    private var shown = 1f
    private var locked = false
    private var lockedAtNs = 0L
    private var lastNs = 0L
    private val startNs = System.nanoTime()

    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val computer = IconDrawView.vectorPath(context, R.drawable.ic_pc) ?: Path()
    private val phone = IconDrawView.vectorPath(context, R.drawable.ic_new_phone) ?: Path()
    private val lock = IconDrawView.vectorPath(context, R.drawable.ic_lock) ?: Path()
    private val tmp = Path()
    private val m = Matrix()

    fun setColors(inkColor: Int, accentColor: Int) {
        ink = inkColor
        accent = accentColor
        invalidate()
    }

    fun setStrength(value: Float) {
        target = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun setLocked(on: Boolean) {
        if (on && !locked) lockedAtNs = System.nanoTime()
        locked = on
        invalidate()
    }

    private fun icon(canvas: Canvas, src: Path, viewport: Float, cx: Float, cy: Float, size: Float, color: Int, alpha: Float) {
        val s = size / viewport
        m.setTranslate(-viewport / 2f, -viewport / 2f)
        m.postScale(s, s)
        m.postTranslate(cx, cy)
        tmp.reset()
        src.transform(m, tmp)
        fill.color = color
        fill.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        canvas.drawPath(tmp, fill)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceAtMost(0.1f)
        lastNs = now
        shown += (target - shown) * (1f - Math.exp(-dt * 3.0).toFloat())
        val t = (now - startNs) / 1e9f
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        val iconSize = minOf(h * 0.34f, w * 0.16f)
        val left = w * 0.14f
        val far = 1f - shown
        val right = w * (0.62f + 0.24f * far)
        val lockK = if (locked) ((now - lockedAtNs) / 4.5e8f).coerceIn(0f, 1f) else 0f

        val pulse = (t % 1.6f) / 1.6f
        ring.color = accent
        ring.strokeWidth = iconSize * 0.05f
        ring.alpha = (110 * (1f - pulse) * (1f - lockK)).toInt()
        canvas.drawCircle(left, cy, iconSize * (0.7f + 0.9f * pulse), ring)

        val x0 = left + iconSize * 0.75f
        val x1 = right - iconSize * 0.6f
        if (x1 > x0 && lockK < 1f) {
            val amp = h * (0.05f + 0.13f * shown)
            val waves = 2.5f + 2.5f * far
            path.reset()
            val steps = 80
            for (i in 0..steps) {
                val k = i / steps.toFloat()
                val x = x0 + (x1 - x0) * k
                val env = sin(PI * k).toFloat()
                val y = cy + amp * env * sin(2f * PI.toFloat() * (waves * k - t * 0.9f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            wave.color = accent
            wave.strokeWidth = iconSize * (0.06f + 0.06f * shown)
            wave.alpha = (255 * (0.25f + 0.75f * shown) * (1f - lockK)).toInt()
            if (shown < 0.35f) {
                val dash = iconSize * 0.18f
                wave.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dash, dash * (1.5f - shown * 2f)), t * 40f)
            } else {
                wave.pathEffect = null
            }
            canvas.drawPath(path, wave)
        }

        icon(canvas, computer, 960f, left, cy, iconSize, ink, 1f)
        icon(canvas, phone, 960f, right, cy, iconSize * 0.9f, ink, 1f - 0.4f * lockK)
        if (lockK > 0f) {
            val s = iconSize * (0.4f + 0.6f * lockK)
            icon(canvas, lock, 960f, (left + right) / 2f, cy, s, accent, lockK)
        }
        postInvalidateOnAnimation()
    }
}
