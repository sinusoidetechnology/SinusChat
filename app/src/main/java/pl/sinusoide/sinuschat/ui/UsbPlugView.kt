package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import pl.sinusoide.sinuschat.R
import kotlin.math.PI
import kotlin.math.sin

class UsbPlugView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val drive = Path()
    private val arrow = Path()
    private val ring = Path()
    private val check = Path()

    private val checkLine = Path().apply {
        moveTo(282f, 466f)
        lineTo(424f, 608f)
        lineTo(678f, 354f)
    }

    private val driveScaled = Path()
    private val arrowScaled = Path()
    private val ringScaled = Path()
    private val checkScaled = Path()
    private val lineScaled = Path()

    private val wedge = Path()
    private val piece = Path()
    private val inked = Path()
    private val measure = PathMeasure()
    private var lineLength = 0f
    private var signCx = 0f
    private var signCy = 0f
    private var signR = 0f

    private var ink = Color.WHITE
    private var accent = ThemeUi.accent(context)
    private var startNs = 0L
    private var doneNs = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nib = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
        strokeMiter = 4f
    }
    private val pen = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    var connected = false
        private set

    init {
        IconDrawView.vectorPath(context, R.drawable.ic_harddrive)?.let { drive.set(it) }
        IconDrawView.vectorPath(context, R.drawable.ic_bakuparrow)?.let { arrow.set(it) }
        val sign = R.drawable.ic_chceckbackup
        for (p in IconDrawView.shapesInside(context, sign, CHECK)) check.addPath(p)
        for (p in IconDrawView.shapesInside(context, sign, CHECK, outside = true)) ring.addPath(p)
    }

    fun setColors(inkColor: Int, accentColor: Int) {
        ink = inkColor
        accent = accentColor
        invalidate()
    }

    fun setConnected(on: Boolean) {
        if (connected == on) return
        connected = on
        doneNs = if (on) System.nanoTime() else 0L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val d = resources.displayMetrics.density

        val driveSize = minOf(w * 0.3f, h * 0.4f)
        place(drive, driveScaled, driveSize, w / 2f, driveSize * 0.56f, null)

        val signSize = minOf(w * 0.24f, h * 0.3f)
        signCx = w / 2f
        signCy = h - signSize * 0.62f - 6 * d
        signR = signSize / 2f
        place(arrow, arrowScaled, signSize, signCx, signCy, null)
        val m = Matrix()
        place(ring, ringScaled, signSize, signCx, signCy, m)
        check.transform(m, checkScaled)
        checkLine.transform(m, lineScaled)
        nib.strokeWidth = 91f * (signSize / 800f)
        measure.setPath(lineScaled, false)
        lineLength = measure.length
    }

    private fun place(src: Path, out: Path, size: Float, cx: Float, cy: Float, saveTo: Matrix?) {
        val b = RectF().also { src.computeBounds(it, true) }
        val scale = size / maxOf(b.width(), b.height()).coerceAtLeast(1f)
        val m = Matrix().apply {
            setTranslate(-b.centerX(), -b.centerY())
            postScale(scale, scale)
            postTranslate(cx, cy)
        }
        src.transform(m, out)
        saveTo?.set(m)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        if (startNs == 0L) startNs = System.nanoTime()
        val ms = (System.nanoTime() - startNs) / 1_000_000f

        fill.color = ink
        fill.alpha = 255
        canvas.drawPath(driveScaled, fill)

        val since = if (doneNs == 0L) -1f else (System.nanoTime() - doneNs) / 1_000_000f
        if (since < 0f) {
            val still = A11y.reducedMotion(context)
            val swing = if (still) 0f else sin(2f * PI.toFloat() * (ms % FLOAT_MS) / FLOAT_MS)
            canvas.save()
            canvas.translate(0f, RANGE * resources.displayMetrics.density * swing)
            canvas.drawPath(arrowScaled, fill)
            canvas.restore()
            if (isShown && !still) postInvalidateOnAnimation()
            return
        }

        val gone = (since / FADE_MS).coerceIn(0f, 1f)
        if (gone < 1f) {
            fill.alpha = (255 * (1f - gone)).toInt()
            canvas.drawPath(arrowScaled, fill)
            fill.alpha = 255
        }

        fill.color = accent
        val circle = pen.getInterpolation(((since - RING_FROM) / RING_MS).coerceIn(0f, 1f))
        if (circle > 0f) {
            if (circle >= 1f) {
                canvas.drawPath(ringScaled, fill)
            } else {
                wedge.rewind()
                wedge.moveTo(signCx, signCy)
                wedge.arcTo(RectF(signCx - signR * 3f, signCy - signR * 3f,
                    signCx + signR * 3f, signCy + signR * 3f), -90f, 360f * circle)
                wedge.close()
                canvas.save()
                canvas.clipPath(wedge)
                canvas.drawPath(ringScaled, fill)
                canvas.restore()
            }
        }

        val tick = pen.getInterpolation(((since - CHECK_FROM) / CHECK_MS).coerceIn(0f, 1f))
        if (tick > 0f) {
            if (tick >= 1f) {
                canvas.drawPath(checkScaled, fill)
            } else {
                piece.rewind()
                measure.setPath(lineScaled, false)
                measure.getSegment(0f, lineLength * tick, piece, true)
                inked.rewind()
                nib.getFillPath(piece, inked)
                inked.op(checkScaled, Path.Op.INTERSECT)
                canvas.drawPath(inked, fill)
            }
        }

        if (isShown && since < CHECK_FROM + CHECK_MS) postInvalidateOnAnimation()
    }

    private companion object {
        val CHECK = RectF(240f, 310f, 720f, 680f)

        const val FLOAT_MS = 1500f
        const val RANGE = 16f

        const val FADE_MS = 200f
        const val RING_FROM = 120f
        const val RING_MS = 620f
        const val CHECK_FROM = 700f
        const val CHECK_MS = 420f
    }
}
