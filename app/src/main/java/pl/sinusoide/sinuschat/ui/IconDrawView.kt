package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import pl.sinusoide.sinuschat.R

class IconDrawView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    class Part(
        val path: Path,
        val width: Float,
        val accent: Boolean,
        val fromMs: Float,
        val toMs: Float,
        val cap: Paint.Cap = Paint.Cap.ROUND
    )

    class Pop(
        val path: Path,
        val fromMs: Float,
        val toMs: Float,
        val startScale: Float = 0.55f,
        val cut: Boolean = false
    )

    class Drawing(
        val content: RectF,
        val parts: List<Part>,
        val fill: Path? = null,
        val pops: List<Pop> = emptyList(),
        val exact: Boolean = false
    )

    private var drawing: Drawing? = null
    private var scaled: List<Path> = emptyList()
    private var scaledFill: Path? = null
    private var scaledOutside: Path? = null
    private val outsidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private var scaledPops: List<Path> = emptyList()
    private var popBounds: List<RectF> = emptyList()
    private var lengths = FloatArray(0)
    private var scale = 1f
    private var startNs = 0L
    private var ink = Color.WHITE
    private var accent = ThemeUi.accent(context)

    private val ease = PathInterpolator(0.45f, 0f, 0.2f, 1f)
    private val bounce = OvershootInterpolator(3f)
    private val popPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeMiter = 4f
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val piece = Path()
    private val measure = PathMeasure()

    private val strikeAxis = Path().apply { moveTo(83.5f, 140.5f); lineTo(819.5f, 876.5f) }
    private val gapAxis = Path().apply { moveTo(140.5f, 83.5f); lineTo(876.5f, 819.5f) }
    private var scaledStrike = Path()
    private var scaledGap = Path()
    private var strikeLen = 0f
    private var gapLen = 0f
    private var strikeFrom = 0f
    private var strikeTo = 0f
    private var strikeStartNs = 0L
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    fun strike(on: Boolean, animate: Boolean = true) {
        strikeFrom = strikeProgress()
        strikeTo = if (on) 1f else 0f
        strikeStartNs = if (animate) System.nanoTime() else 0L
        invalidate()
    }

    private fun strikeProgress(): Float {
        if (strikeStartNs == 0L) return strikeTo
        val t = ((System.nanoTime() - strikeStartNs) / 1_000_000f / STRIKE_MS).coerceIn(0f, 1f)
        return strikeFrom + (strikeTo - strikeFrom) * ease.getInterpolation(t)
    }

    private fun drawStrike(canvas: Canvas, k: Float) {
        if (k <= 0f) return
        gapPaint.strokeWidth = 84f * scale
        piece.reset()
        measure.setPath(scaledGap, false)
        measure.getSegment(0f, gapLen * k, piece, true)
        canvas.drawPath(piece, gapPaint)
        line.strokeWidth = 80.6f * scale
        line.strokeCap = Paint.Cap.BUTT
        line.color = ink
        piece.reset()
        measure.setPath(scaledStrike, false)
        measure.getSegment(0f, strikeLen * k, piece, true)
        canvas.drawPath(piece, line)
    }

    fun setColors(inkColor: Int, accentColor: Int) {
        ink = inkColor
        accent = accentColor
        layoutDrawing()
        invalidate()
    }

    fun setDrawing(next: Drawing) {
        drawing = next
        layoutDrawing()
        startNs = System.nanoTime()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutDrawing()
        if (ow == 0 || oh == 0) startNs = System.nanoTime()
    }

    private fun layoutDrawing() {
        val d = drawing ?: return
        if (width == 0 || height == 0) return
        scale = minOf(width, height) * 0.6f / maxOf(d.content.width(), d.content.height())
        val m = Matrix().apply {
            setTranslate(-d.content.centerX(), -d.content.centerY())
            postScale(scale, scale)
            postTranslate(width / 2f, height / 2f)
        }
        scaled = d.parts.map { p -> Path().also { p.path.transform(m, it) } }
        scaledStrike = Path().also { strikeAxis.transform(m, it) }
        scaledGap = Path().also { gapAxis.transform(m, it) }
        measure.setPath(scaledStrike, false); strikeLen = measure.length
        measure.setPath(scaledGap, false); gapLen = measure.length
        scaledFill = d.fill?.let { f -> Path().also { f.transform(m, it) } }
        scaledOutside = if (!d.exact) null else scaledFill?.let { f ->
            Path(f).apply {
                fillType = if (f.fillType == Path.FillType.EVEN_ODD) Path.FillType.INVERSE_EVEN_ODD
                    else Path.FillType.INVERSE_WINDING
            }
        }
        scaledPops = d.pops.map { p -> Path().also { p.path.transform(m, it) } }
        popBounds = scaledPops.map { p -> RectF().also { p.computeBounds(it, true) } }
        lengths = FloatArray(scaled.size) { i ->
            measure.setPath(scaled[i], false)
            var total = 0f
            do { total += measure.length } while (measure.nextContour())
            total
        }
        val r = minOf(width, height) / 2f
        glow.shader = RadialGradient(width / 2f, height / 2f, r,
            intArrayOf(withAlpha(accent, 0.12f), withAlpha(accent, 0.035f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val d = drawing ?: return
        if (scaled.isEmpty() && scaledPops.isEmpty()) return
        if (startNs == 0L) startNs = System.nanoTime()
        val ms = (System.nanoTime() - startNs) / 1_000_000f
        val end = maxOf(d.parts.maxOfOrNull { it.toMs } ?: 0f, d.pops.maxOfOrNull { it.toMs } ?: 0f)

        val g = span(ms, end - 350f, end + 350f)
        if (g > 0f) {
            glow.alpha = (255 * g).toInt()
            canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, glow)
        }

        val fill = scaledFill
        val sk = strikeProgress()
        if (fill != null || sk > 0f) {
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            drawParts(canvas, d, ms)
            val outside = scaledOutside
            if (outside != null) canvas.drawPath(outside, outsidePaint)
            else if (fill != null) canvas.drawPath(fill, mask)
            drawStrike(canvas, sk)
            canvas.restoreToCount(layer)
        } else {
            drawParts(canvas, d, ms)
        }

        val cuts = d.pops.any { it.cut }
        val popLayer = if (cuts) canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null) else -1
        for ((i, p) in d.pops.withIndex()) {
            val k = span(ms, p.fromMs, p.toMs)
            if (k <= 0f) continue
            val paint = if (p.cut) cutPaint else popPaint
            paint.color = ink
            if (k >= 1f) {
                paint.alpha = 255
                canvas.drawPath(scaledPops[i], paint)
                continue
            }
            val b = popBounds[i]
            val sc = p.startScale + (1f - p.startScale) * bounce.getInterpolation(k)
            paint.alpha = (255 * minOf(1f, k * 3f)).toInt()
            canvas.save()
            canvas.scale(sc, sc, b.centerX(), b.centerY())
            canvas.drawPath(scaledPops[i], paint)
            canvas.restore()
        }
        if (popLayer >= 0) canvas.restoreToCount(popLayer)

        val striking = strikeStartNs != 0L &&
            (System.nanoTime() - strikeStartNs) / 1_000_000f < STRIKE_MS + 50f
        if (ms < end + 350f || striking) postInvalidateOnAnimation()
    }

    private fun drawParts(canvas: Canvas, d: Drawing, ms: Float) {
        for ((i, p) in d.parts.withIndex()) {
            val k = ease.getInterpolation(span(ms, p.fromMs, p.toMs))
            if (k <= 0f) continue
            line.strokeWidth = p.width * scale
            line.strokeCap = p.cap
            line.color = if (p.accent) accent else ink
            if (k >= 1f) {
                canvas.drawPath(scaled[i], line)
            } else {
                measure.setPath(scaled[i], false)
                piece.reset()
                measure.getSegment(0f, lengths[i] * k, piece, true)
                canvas.drawPath(piece, line)
            }
        }
    }

    private fun span(ms: Float, from: Float, to: Float) = ((ms - from) / (to - from)).coerceIn(0f, 1f)

    private fun withAlpha(color: Int, a: Float) =
        (color and 0x00FFFFFF) or ((255 * a).toInt() shl 24)

    companion object {

        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        private const val EDGE = 6f

        private const val STRIKE_MS = 700f

        fun ink(ctx: Context): Int =
            if (ThemeUi.isDark(ctx) || ThemeUi.isFlow(ctx)) Color.WHITE else ThemeUi.textPrimary(ctx)

        private fun pathData(ctx: Context, resId: Int): List<String> = runCatching {
            val out = ArrayList<String>()
            val xml = ctx.resources.getXml(resId)
            try {
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG && xml.name == "path") {
                        xml.getAttributeValue(ANDROID_NS, "pathData")?.let { out += it }
                    }
                }
            } finally {
                xml.close()
            }
            out
        }.getOrDefault(emptyList())

        fun vectorPath(ctx: Context, resId: Int): Path? = runCatching {
            val out = Path()
            for (data in pathData(ctx, resId)) out.addPath(PathParser.createPathFromPathData(data))
            out.takeUnless { it.isEmpty }
        }.getOrNull()

        internal fun shapesInside(ctx: Context, resId: Int, inside: RectF, outside: Boolean = false): List<Path> {
            val found = ArrayList<Pair<Float, Path>>()
            for (data in pathData(ctx, resId)) {
                for (piece in data.split(Regex("(?=M)")).filter { it.isNotBlank() }) {
                    val p = runCatching { PathParser.createPathFromPathData(piece) }.getOrNull() ?: continue
                    val b = RectF().also { p.computeBounds(it, true) }
                    if (b.width() > 0f && inside.contains(b) != outside) found += b.left to p
                }
            }
            return found.sortedBy { it.first }.map { it.second }
        }

        fun chatSetup(ctx: Context): Drawing {
            val bubble = Path().apply {
                moveTo(120f, 740f)
                lineTo(120f, 160f)
                arcTo(RectF(120f, 120f, 200f, 200f), 180f, 90f)
                lineTo(800f, 120f)
                arcTo(RectF(760f, 120f, 840f, 200f), 270f, 90f)
                lineTo(840f, 640f)
                arcTo(RectF(760f, 600f, 840f, 680f), 0f, 90f)
                lineTo(223f, 680f)
                lineTo(120f, 783f)
                close()
            }
            fun bar(y: Float, x2: Float) = Path().apply { moveTo(240f - EDGE, y); lineTo(x2 + EDGE, y) }
            return Drawing(
                RectF(80f, 80f, 880f, 880f),
                listOf(
                    Part(bubble, 80f + 2 * EDGE, accent = false, fromMs = 150f, toMs = 1250f),
                    Part(bar(280f, 720f), 80f + 2 * EDGE, accent = true, fromMs = 1150f, toMs = 1550f, cap = Paint.Cap.BUTT),
                    Part(bar(400f, 720f), 80f + 2 * EDGE, accent = true, fromMs = 1450f, toMs = 1850f, cap = Paint.Cap.BUTT),
                    Part(bar(520f, 560f), 80f + 2 * EDGE, accent = true, fromMs = 1750f, toMs = 2100f, cap = Paint.Cap.BUTT)
                ),
                vectorPath(ctx, R.drawable.ic_chatsetup)
            )
        }

        fun language(ctx: Context): Drawing {
            val ring = Path().apply {
                val o = RectF(120f, 120f, 840f, 840f)
                arcTo(o, -90f, 180f, true)
                arcTo(o, 90f, 180f)
                close()
            }
            val r = 597.3f
            fun meridian(cx: Float, start: Float, sweep: Float) = Path().apply {
                arcTo(RectF(cx - r, 480f - r, cx + r, 480f + r), start, sweep, true)
            }
            fun parallel(y: Float) = Path().apply { moveTo(130f, y); lineTo(830f, y) }
            return Drawing(
                RectF(80f, 80f, 880f, 880f),
                listOf(
                    Part(ring, 80f + 2 * EDGE, accent = false, fromMs = 150f, toMs = 1150f),
                    Part(meridian(937.3f, -142.94f, -74.12f), 80f + 2 * EDGE, accent = false, fromMs = 1050f, toMs = 1500f),
                    Part(meridian(22.7f, -37.06f, 74.12f), 80f + 2 * EDGE, accent = false, fromMs = 1200f, toMs = 1650f),
                    Part(parallel(360f), 80f + 2 * EDGE, accent = false, fromMs = 1500f, toMs = 1850f, cap = Paint.Cap.BUTT),
                    Part(parallel(600f), 80f + 2 * EDGE, accent = false, fromMs = 1700f, toMs = 2050f, cap = Paint.Cap.BUTT)
                ),
                vectorPath(ctx, R.drawable.ic_language)
            )
        }

        fun translate(ctx: Context): Drawing {
            val flat = Paint.Cap.BUTT
            val bar = Path().apply { moveTo(40f - EDGE, 200f); lineTo(680f + EDGE, 200f) }
            val stem = Path().apply { moveTo(360f, 80f - EDGE); lineTo(360f, 200f) }
            val sweep = Path().apply {
                moveTo(532f, 203f)
                lineTo(524f, 232f)
                quadTo(504f, 303f, 466f, 371f)
                quadTo(427f, 438f, 390f, 475f)
                lineTo(369f, 496f)
            }
            val hook = Path().apply {
                moveTo(225f, 293f)
                lineTo(232f, 312f)
                quadTo(254f, 368f, 278f, 405f)
                quadTo(302.5f, 442f, 334f, 474f)
                lineTo(355f, 495f)
            }
            val legLeft = Path().apply { moveTo(381f, 483f); lineTo(114f, 750f) }
            val legRight = Path().apply { moveTo(354f, 495f); lineTo(527f, 672f) }
            val aLeft = Path().apply { moveTo(509.4f, 903.5f); lineTo(697.4f, 390.6f) }
            val aRight = Path().apply { moveTo(702.6f, 390.6f); lineTo(890.6f, 903.5f) }
            val aBar = Path().apply { moveTo(590f, 723f); lineTo(810f, 723f) }
            val whole = vectorPath(ctx, R.drawable.ic_translate)
            return Drawing(
                RectF(40f, 80f, 924f, 880f),
                listOf(
                    Part(bar, 88f, accent = false, fromMs = 150f, toMs = 600f, cap = flat),
                    Part(stem, 88f, accent = false, fromMs = 450f, toMs = 750f, cap = flat),
                    Part(sweep, 84f + 2 * EDGE, accent = false, fromMs = 750f, toMs = 1150f, cap = flat),
                    Part(hook, 84f + 2 * EDGE, accent = false, fromMs = 900f, toMs = 1250f, cap = flat),
                    Part(legLeft, 80f + 2 * EDGE, accent = false, fromMs = 1150f, toMs = 1500f, cap = flat),
                    Part(legRight, 80f + 2 * EDGE, accent = false, fromMs = 1300f, toMs = 1600f, cap = flat),
                    Part(aLeft, 76f + 2 * EDGE, accent = true, fromMs = 1650f, toMs = 1950f, cap = flat),
                    Part(aRight, 76f + 2 * EDGE, accent = true, fromMs = 1850f, toMs = 2150f, cap = flat),
                    Part(aBar, 72f + 2 * EDGE, accent = true, fromMs = 2100f, toMs = 2350f, cap = flat)
                ),
                whole,
                pops = if (whole != null) listOf(Pop(whole, 2300f, 2550f, startScale = 1f)) else emptyList(),
                exact = true
            )
        }

        fun mail(ctx: Context): Drawing {
            val tl = RectF(120f, 200f, 200f, 280f)
            val tr = RectF(760f, 200f, 840f, 280f)
            val br = RectF(760f, 680f, 840f, 760f)
            val bl = RectF(120f, 680f, 200f, 760f)
            val top = Path().apply {
                arcTo(tl, 225f, 45f, true)
                lineTo(800f, 200f)
                arcTo(tr, 270f, 90f)
                lineTo(840f, 720f)
                arcTo(br, 0f, 45f)
            }
            val bottom = Path().apply {
                arcTo(tl, 225f, -45f, true)
                lineTo(120f, 720f)
                arcTo(bl, 180f, -90f)
                lineTo(800f, 760f)
                arcTo(br, 90f, -45f)
            }
            val flap = Path().apply {
                moveTo(130f, 261.25f)
                lineTo(480f, 480f)
                lineTo(830f, 261.25f)
            }
            return Drawing(
                RectF(80f, 160f, 880f, 800f),
                listOf(
                    Part(bottom, 80f + 2 * EDGE, accent = false, fromMs = 150f, toMs = 1400f),
                    Part(top, 80f + 2 * EDGE, accent = false, fromMs = 150f, toMs = 1400f),
                    Part(flap, 68f + 2 * EDGE, accent = false, fromMs = 150f, toMs = 1400f)
                ),
                vectorPath(ctx, R.drawable.ic_mail)
            )
        }

        fun wifi(ctx: Context): Drawing {
            val bars = shapesInside(ctx, R.drawable.ic_wifi, RectF(-20f, -20f, 980f, 980f))
                .sortedByDescending { p -> RectF().also { p.computeBounds(it, true) }.top }
            return Drawing(
                RectF(0f, 280f, 960f, 840f),
                emptyList(),
                pops = bars.mapIndexed { i, p ->
                    Pop(p, 140f + i * 280f, 620f + i * 280f, startScale = 0.55f)
                }
            )
        }

        fun speaker(ctx: Context): Drawing {
            val bodyArea = RectF(100f, 140f, 500f, 820f)
            val body = Path().apply {
                for (p in shapesInside(ctx, R.drawable.ic_call_speaker_on, bodyArea)) addPath(p)
            }
            val waves = shapesInside(ctx, R.drawable.ic_call_speaker_on, bodyArea, outside = true)
                .sortedBy { p -> RectF().also { p.computeBounds(it, true) }.width() }
            return Drawing(
                RectF(80f, 80f, 880f, 880f),
                emptyList(),
                pops = listOf(Pop(body, 100f, 560f, startScale = 0.82f)) +
                    waves.mapIndexed { i, p ->
                        Pop(p, 620f + i * 280f, 1100f + i * 280f, startScale = 0.55f)
                    }
            )
        }

        fun palette(ctx: Context): Drawing {
            val dotsArea = RectF(180f, 220f, 780f, 540f)
            val dots = shapesInside(ctx, R.drawable.ic_palette, dotsArea)
            val body = Path().apply {
                for (p in shapesInside(ctx, R.drawable.ic_palette, dotsArea, outside = true)) addPath(p)
            }
            return Drawing(
                RectF(80f, 80f, 880f, 880f),
                emptyList(),
                pops = listOf(Pop(body, 100f, 540f, startScale = 0.82f)) +
                    dots.mapIndexed { i, p ->
                        Pop(p, 600f + i * 230f, 950f + i * 230f, startScale = 0f, cut = true)
                    }
            )
        }

        fun pinSetup(ctx: Context): Drawing {
            val inner = RectF(180f, 260f, 780f, 700f)
            val digits = shapesInside(ctx, R.drawable.ic_setuppin, inner)
            val frame = Path().apply {
                for (p in shapesInside(ctx, R.drawable.ic_setuppin, inner, outside = true)) addPath(p)
            }
            return Drawing(
                RectF(80f, 160f, 880f, 800f),
                emptyList(),
                pops = listOf(Pop(frame, 100f, 560f, startScale = 0.82f)) +
                    digits.mapIndexed { i, p -> Pop(p, 620f + i * 280f, 980f + i * 280f) }
            )
        }
        private fun poly(vararg xy: Float) = Path().apply {
            moveTo(xy[0], xy[1])
            var i = 2
            while (i + 1 < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }
        }

        fun bluetooth(ctx: Context): Drawing = Drawing(
            RectF(2f, 2f, 22f, 22f),
            listOf(
                Part(poly(5.7f, 5.7f, 12.4f, 12.4f, 16.4f, 16.3f, 11.6f, 21.4f, 11.5f, 12.2f), 2.4f, accent = false, fromMs = 120f, toMs = 1500f),
                Part(poly(5.7f, 18.3f, 12.4f, 11.6f, 16.4f, 7.7f, 11.6f, 2.6f, 11.5f, 11.8f), 2.4f, accent = false, fromMs = 120f, toMs = 1500f)
            ),
            vectorPath(ctx, R.drawable.ic_bluetooth),
            pops = listOfNotNull(vectorPath(ctx, R.drawable.ic_bluetooth)?.let { Pop(it, 1500f, 1501f, startScale = 1f) }),
            exact = true
        )

        fun nearby(ctx: Context): Drawing = Drawing(
            RectF(2f, 2f, 22f, 22f),
            listOf(
                Part(Path().apply { moveTo(12f, 2.6f); lineTo(21.4f, 12f); lineTo(12f, 21.4f); lineTo(2.6f, 12f); close() },
                    2.8f, accent = false, fromMs = 120f, toMs = 1150f),
                Part(poly(7.2f, 12f, 16.8f, 12f), 9.4f, accent = false, fromMs = 1100f, toMs = 1500f, cap = Paint.Cap.BUTT)
            ),
            vectorPath(ctx, R.drawable.ic_nearby),
            pops = listOfNotNull(vectorPath(ctx, R.drawable.ic_nearby)?.let { Pop(it, 1500f, 1501f, startScale = 1f) }),
            exact = true
        )

        fun connected(ctx: Context): Drawing = Drawing(
            RectF(1f, 3f, 23f, 21f),
            listOf(
                Part(poly(12.2f, 16f, 4f, 16f, 4f, 5f, 20f, 5f, 20f, 7.2f), 2.6f, accent = false, fromMs = 120f, toMs = 1000f),
                Part(poly(1.6f, 19f, 12.4f, 19f), 2.6f, accent = false, fromMs = 900f, toMs = 1200f),
                Part(poly(15f, 10f, 15f, 19f, 21f, 19f, 21f, 9f, 15f, 9f, 15f, 10.5f), 2.6f, accent = false, fromMs = 1150f, toMs = 1850f),
                Part(poly(18f, 11.75f, 18f, 11.8f), 2.4f, accent = false, fromMs = 1850f, toMs = 2000f)
            ),
            vectorPath(ctx, R.drawable.ic_connected),
            pops = listOfNotNull(vectorPath(ctx, R.drawable.ic_connected)?.let { Pop(it, 2000f, 2001f, startScale = 1f) }),
            exact = true
        )

        fun distance(ctx: Context): Drawing = Drawing(
            RectF(1f, 6f, 23f, 18f),
            listOf(
                Part(poly(12f, 12f, 4.6f, 12f), 2.6f, accent = false, fromMs = 120f, toMs = 620f, cap = Paint.Cap.BUTT),
                Part(poly(12f, 12f, 19.4f, 12f), 2.6f, accent = false, fromMs = 120f, toMs = 620f, cap = Paint.Cap.BUTT),
                Part(poly(7.7f, 7.6f, 3.3f, 12f, 7.7f, 16.4f), 2.8f, accent = false, fromMs = 560f, toMs = 1060f),
                Part(poly(16.3f, 7.6f, 20.7f, 12f, 16.3f, 16.4f), 2.8f, accent = false, fromMs = 560f, toMs = 1060f)
            ),
            vectorPath(ctx, R.drawable.ic_distance),
            pops = listOfNotNull(vectorPath(ctx, R.drawable.ic_distance)?.let { Pop(it, 1060f, 1061f, startScale = 1f) }),
            exact = true
        )
    }
}
