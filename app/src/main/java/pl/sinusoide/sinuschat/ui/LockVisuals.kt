package pl.sinusoide.sinuschat.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class LockPalette private constructor(
    val flow: Boolean,
    val dark: Boolean,
    val background: Int,
    val fill: Int,
    val fillPressed: Int,
    val rimTop: Int,
    val rimBottom: Int,
    val text: Int,
    val textSecondary: Int,
    val accent: Int,
    val accent2: Int,
    val onAccent: Int,
    val sparkCore: Int,
    val retro: Boolean = false
) {
    val orb: Int get() = if (dark) Color.WHITE else accent
    val onOrb: Int get() = if (dark) 0xFF101820.toInt() else onAccent

    companion object {
        fun isDark(ctx: Context): Boolean =
            (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        fun of(ctx: Context): LockPalette {
            val accent = ThemeUi.accent(ctx)
            if (ThemeUi.isFlow(ctx)) return glass(accent)
            if (pl.sinusoide.sinuschat.util.ThemeManager.isRetro(ctx)) return LockPalette(
                flow = false, dark = false,
                background = RetroUi.PANEL,
                fill = Color.WHITE, fillPressed = 0xFFC4E5F6.toInt(),
                rimTop = RetroUi.FIELD, rimBottom = RetroUi.FIELD,
                text = RetroUi.NAVY, textSecondary = 0xFF46607F.toInt(),
                accent = RetroUi.ACCENT, accent2 = RetroUi.ACCENT, onAccent = Color.WHITE,
                sparkCore = Color.BLACK, retro = true
            )
            val dark = isDark(ctx)
            val card = ThemeUi.card(ctx)
            val border = ThemeUi.border(ctx)
            return LockPalette(
                flow = false, dark = dark,
                background = ThemeUi.backgroundSolid(ctx),
                fill = card,
                fillPressed = blend(card, accent, 0.24f),
                rimTop = border, rimBottom = border,
                text = ThemeUi.textPrimary(ctx),
                textSecondary = ThemeUi.textSecondary(ctx),
                accent = accent, accent2 = accent, onAccent = ThemeUi.onAccent(ctx),
                sparkCore = if (dark) Color.WHITE else accent
            )
        }

        fun glass(accent: Int) = LockPalette(
            flow = true, dark = true,
            background = FlowBackdropDrawable.INK,
            fill = 0x17FFFFFF, fillPressed = 0x33FFFFFF,
            rimTop = 0x40FFFFFF, rimBottom = 0x0AFFFFFF,
            text = Color.WHITE, textSecondary = 0xB3FFFFFF.toInt(),
            accent = accent, accent2 = 0xFF7C5CFF.toInt(), onAccent = Color.WHITE,
            sparkCore = Color.WHITE
        )

        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
            return Color.argb(ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)),
                ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
        }
    }
}

class AuroraBackgroundView(context: Context, private val palette: LockPalette) : View(context) {

    private val dark = palette.dark
    private val animated = palette.flow
    private val basePaint = Paint()
    private val blobPaints = List(3) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val matrices = List(3) { Matrix() }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = System.nanoTime()
    private var running = false
    var glow = 0f
        set(value) { field = value; invalidate() }

    private val colors = IntArray(3) { FlowBackdropDrawable.CYAN }
    private val alphas = if (dark) floatArrayOf(0.20f, 0.14f, 0.10f) else floatArrayOf(0.30f, 0.22f, 0.20f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!animated) {
            basePaint.shader = null
            basePaint.color = palette.background
            return
        }
        basePaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            if (dark) 0xFF07202B.toInt() else 0xFFF5F8FC.toInt(),
            if (dark) FlowBackdropDrawable.INK else 0xFFE6EDF6.toInt(), Shader.TileMode.CLAMP)
        val r = maxOf(w, h) * 0.55f
        for (i in 0 until 3) {
            val c = colors[i]
            val a = (255 * alphas[i]).toInt()
            blobPaints[i].shader = RadialGradient(0f, 0f, r,
                intArrayOf(Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        running = isVisible
        if (running) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, basePaint)
        if (!animated) {
            if (glow > 0.01f) {
                val c = palette.accent
                glowPaint.shader = RadialGradient(w / 2f, h * 0.3f, maxOf(w, h) * 0.6f,
                    intArrayOf(Color.argb((90 * glow).toInt(), Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, glowPaint)
            }
            return
        }
        val t = (System.nanoTime() - start) / 1e9f
        val orbits = arrayOf(
            floatArrayOf(0.28f, 0.22f, 0.21f, 0.17f, 0.0f),
            floatArrayOf(0.78f, 0.38f, 0.17f, 0.23f, 2.1f),
            floatArrayOf(0.45f, 0.80f, 0.13f, 0.19f, 4.2f)
        )
        for (i in 0 until 3) {
            val o = orbits[i]
            val cx = w * (o[0] + 0.18f * sin(t * o[2] + o[4]))
            val cy = h * (o[1] + 0.12f * cos(t * o[3] + o[4]))
            val s = 1f + 0.12f * sin(t * 0.3f + i) + glow * 0.35f
            matrices[i].reset()
            matrices[i].setScale(s, s)
            matrices[i].postTranslate(cx, cy)
            blobPaints[i].shader?.setLocalMatrix(matrices[i])
            canvas.drawRect(0f, 0f, w, h, blobPaints[i])
        }
        if (running && !A11y.reducedMotion(context)) postInvalidateDelayed(33)
    }
}

class LockRingView(context: Context, private val palette: LockPalette) : FrameLayout(context) {

    private val accent = palette.accent
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val icon = ImageView(context)
    private val start = System.nanoTime()
    private var running = false
    private var sweep = 110f
    private var arcColor = accent
    private var success = 0f

    init {
        setWillNotDraw(false)
        clipChildren = false
        icon.setImageResource(pl.sinusoide.sinuschat.R.drawable.ic_lock)
        icon.setColorFilter(palette.text)
        addView(icon, LayoutParams((34 * d).toInt(), (34 * d).toInt(), Gravity.CENTER))
    }

    fun setDanger(danger: Boolean) {
        arcColor = if (danger) 0xFFE5484D.toInt() else accent
        invalidate()
    }

    fun playSuccess() {
        arcColor = 0xFF3FB97A.toInt()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420; interpolator = DecelerateInterpolator()
            addUpdateListener { success = it.animatedValue as Float; sweep = 110f + 250f * success; invalidate() }
            start()
        }
        icon.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(140).withEndAction {
            icon.setImageResource(pl.sinusoide.sinuschat.R.drawable.ic_lock_open)
            icon.setColorFilter(0xFF3FB97A.toInt())
            icon.animate().scaleX(1.1f).scaleY(1.1f).alpha(1f).setDuration(220)
                .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        }.start()
    }

    fun shake() {
        android.animation.ObjectAnimator.ofFloat(icon, "rotation", 0f, -14f, 12f, -8f, 5f, 0f).apply { duration = 420 }.start()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        running = isVisible
        if (running) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - 6 * d
        val hc = if (success > 0f) 0xFF3FB97A.toInt() else arcColor
        val haloAlpha = if (palette.flow) 70 else 45
        halo.shader = RadialGradient(cx, cy, r * 1.6f,
            intArrayOf(Color.argb((haloAlpha + 90 * success).toInt(), Color.red(hc), Color.green(hc), Color.blue(hc)), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.6f, halo)
        fill.color = palette.fill
        canvas.drawCircle(cx, cy, r, fill)
        rim.shader = LinearGradient(0f, cy - r, 0f, cy + r, palette.rimTop, palette.rimBottom, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, rim)
        val t = (System.nanoTime() - start) / 1e9f
        val angle = (t * 90f) % 360f
        rect.set(cx - r - 5 * d, cy - r - 5 * d, cx + r + 5 * d, cy + r + 5 * d)
        arc.shader = SweepGradient(cx, cy,
            intArrayOf(Color.TRANSPARENT, arcColor, Color.TRANSPARENT), floatArrayOf(0f, sweep / 360f, 1f)).also {
            val m = Matrix(); m.setRotate(angle, cx, cy); it.setLocalMatrix(m)
        }
        canvas.drawArc(rect, angle, sweep, false, arc)
        super.onDraw(canvas)
        if (running && !A11y.reducedMotion(context)) postInvalidateDelayed(33)
    }
}

class PinFieldView(
    context: Context,
    private val palette: LockPalette,
    private val placeholder: String,
    private val showDots: Boolean = true
) : View(context) {

    private val accent = palette.accent

    private val dotBorn = mutableListOf<Long>()
    private var removedAt = 0L
    private var removedIndex = -1
    private var layoutCount = 0f
    private var layoutAnim: ValueAnimator? = null
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun animateLayoutTo(target: Int) {
        layoutAnim?.cancel()
        layoutAnim = ValueAnimator.ofFloat(layoutCount, target.toFloat()).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { layoutCount = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private data class Spark(val x: Float, val born: Long, val fading: Boolean)

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f * d }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
    }
    private val rect = RectF()
    private val sparks = mutableListOf<Spark>()
    private val random = java.util.Random()
    private var hasInput = false
    private var placeholderAlpha = 1f
    private var stateColor = 0
    private var stateMix = 0f
    private var lastX = -1f

    private var entered = 0

    init {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        describe()
    }

    private fun describe() {
        contentDescription = if (entered == 0) context.getString(pl.sinusoide.sinuschat.R.string.a11y_pin_empty)
            else resources.getQuantityString(pl.sinusoide.sinuschat.R.plurals.a11y_pin_entered, entered, entered)
    }

    fun onDigit() {
        entered++
        describe()
        if (showDots) {
            dotBorn.add(System.nanoTime())
            removedIndex = -1
            animateLayoutTo(dotBorn.size)
            setHasInput(true)
            invalidate()
            return
        }
        val margin = 26 * d
        var x: Float
        var tries = 0
        do {
            x = margin + random.nextFloat() * (width - 2 * margin).coerceAtLeast(1f)
            tries++
        } while (lastX >= 0 && kotlin.math.abs(x - lastX) < width * 0.18f && tries < 6)
        lastX = x
        sparks.add(Spark(x, System.nanoTime(), false))
        setHasInput(true)
        invalidate()
    }

    fun onDelete(nowEmpty: Boolean) {
        entered = if (nowEmpty) 0 else (entered - 1).coerceAtLeast(0)
        describe()
        if (showDots) {
            if (dotBorn.isNotEmpty()) {
                removedIndex = dotBorn.size - 1
                removedAt = System.nanoTime()
                dotBorn.removeAt(dotBorn.size - 1)
                animateLayoutTo(dotBorn.size)
            }
            if (nowEmpty) setHasInput(false)
            invalidate()
            return
        }
        sparks.add(Spark(width / 2f, System.nanoTime(), true))
        if (nowEmpty) setHasInput(false)
        invalidate()
    }

    fun clear() {
        entered = 0
        describe()
        if (showDots && dotBorn.isNotEmpty()) {
            dotBorn.clear()
            removedIndex = -1
            animateLayoutTo(0)
        }
        setHasInput(false); invalidate()
    }

    private fun setHasInput(value: Boolean) {
        if (hasInput == value) return
        hasInput = value
        ValueAnimator.ofFloat(placeholderAlpha, if (value) 0f else 1f).apply {
            duration = 200
            addUpdateListener { placeholderAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun flash(color: Int) {
        stateColor = color
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 900; startDelay = 150
            addUpdateListener { stateMix = it.animatedValue as Float; invalidate() }
            start()
        }
        stateMix = 1f
        invalidate()
    }

    fun holdColor(color: Int) { stateColor = color; stateMix = 1f; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (palette.retro) { drawRetro(canvas); return }
        val w = width.toFloat(); val h = height.toFloat()
        val r = h / 2f
        rect.set(1 * d, 1 * d, w - 1 * d, h - 1 * d)
        fill.color = palette.fill
        canvas.drawRoundRect(rect, r, r, fill)
        val top = palette.rimTop
        val rimTop = if (stateMix > 0f) blend(top, stateColor, stateMix) else top
        val rimBottom = if (stateMix > 0f) blend(palette.rimBottom, stateColor, stateMix * 0.8f)
                        else palette.rimBottom
        rim.shader = LinearGradient(0f, 0f, 0f, h, rimTop, rimBottom, Shader.TileMode.CLAMP)
        rim.strokeWidth = (1.3f + stateMix * 1.2f) * d
        canvas.drawRoundRect(rect, r, r, rim)

        if (placeholderAlpha > 0.01f) {
            text.color = palette.text
            text.alpha = (110 * placeholderAlpha).toInt()
            canvas.drawText(placeholder, w / 2f, h / 2f - (text.descent() + text.ascent()) / 2f, text)
        }

        val now = System.nanoTime()
        if (showDots) { drawDots(canvas, w, h, now); return }
        val it = sparks.iterator()
        var alive = false
        while (it.hasNext()) {
            val s = it.next()
            val life = ((now - s.born) / 1e9f) / (if (s.fading) 0.35f else 0.62f)
            if (life >= 1f) { it.remove(); continue }
            alive = true
            val grow = if (s.fading) 1f - life else DecelerateInterpolator(2f).getInterpolation(min(1f, life * 2.2f))
            val alpha = if (s.fading) (1f - life) * 0.6f else (1f - life)
            val core = (if (s.fading) 4f else 5f) * d * grow
            val c = if (s.fading) palette.text else accent
            spark.shader = RadialGradient(s.x, h / 2f, core * 4f,
                intArrayOf(Color.argb((170 * alpha).toInt(), Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(s.x, h / 2f, core * 4f, spark)
            spark.shader = null
            val sc = palette.sparkCore
            spark.color = Color.argb((255 * alpha).toInt(), Color.red(sc), Color.green(sc), Color.blue(sc))
            canvas.drawCircle(s.x, h / 2f, core * 0.55f, spark)
        }
        if (alive) postInvalidateOnAnimation()
    }

    private fun drawRetro(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val sw = (1f + stateMix) * d
        rect.set(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f)
        fill.shader = null
        fill.color = Color.WHITE
        canvas.drawRoundRect(rect, 2 * d, 2 * d, fill)
        rim.shader = null
        rim.color = if (stateMix > 0f) blend(palette.rimTop, stateColor, stateMix) else if (isSelected) RetroUi.FOCUS else palette.rimTop
        rim.strokeWidth = sw
        canvas.drawRoundRect(rect, 2 * d, 2 * d, rim)
        if (!showDots) {
            if (hasInput) {
                dotPaint.color = Color.BLACK
                canvas.drawRect(8 * d, h * 0.3f, 9.5f * d, h * 0.7f, dotPaint)
            }
            return
        }
        val spacing = 13f * d
        dotPaint.shader = null
        dotPaint.color = Color.BLACK
        dotPaint.alpha = 255
        dotBorn.indices.forEach { i ->
            canvas.drawCircle(10 * d + i * spacing, h / 2f, 3.6f * d, dotPaint)
        }
    }

    private fun drawDots(canvas: Canvas, w: Float, h: Float, now: Long) {
        val spacing = 21f * d
        val cy = h / 2f
        fun xAt(i: Int) = w / 2f + (i - (layoutCount - 1f) / 2f) * spacing
        val glowColor = if (stateMix > 0f) blend(accent, stateColor, stateMix) else accent
        val coreColor = palette.sparkCore
        val seconds = now / 1e9f

        fun spark(x: Float, coreR: Float, glowR: Float, glowAlpha: Float, coreAlpha: Float) {
            if (glowAlpha > 0.01f) {
                dotPaint.shader = RadialGradient(x, cy, glowR,
                    intArrayOf(Color.argb((255 * glowAlpha).toInt().coerceIn(0, 255),
                        Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(x, cy, glowR, dotPaint)
                dotPaint.shader = null
            }
            dotPaint.color = coreColor
            dotPaint.alpha = (255 * coreAlpha).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, cy, coreR, dotPaint)
            dotPaint.alpha = 255
        }

        dotBorn.forEachIndexed { i, born ->
            val x = xAt(i)
            val age = (now - born) / 1e9f
            val t = (age / 0.45f).coerceIn(0f, 1f)
            val appear = DecelerateInterpolator(2f).getInterpolation(min(1f, age / 0.16f))
            val breathe = if (t >= 1f) 0.5f + 0.5f * sin(seconds * 2.4f + i * 0.9f) else 0.5f
            val coreR = 3.2f * d * appear
            val glowR = (13f + 9f * (1f - t)) * d * appear + breathe * 1.5f * d
            val glowA = 0.42f + 0.33f * (1f - t) + 0.08f * breathe
            spark(x, coreR, glowR, glowA * appear, appear)
        }

        if (removedIndex >= 0) {
            val t = ((now - removedAt) / 1e9f / 0.32f).coerceIn(0f, 1f)
            if (t < 1f) {
                val fade = 1f - t
                spark(xAt(removedIndex), 3.2f * d * fade, 13f * d * (1f + 0.4f * t), 0.42f * fade, fade)
            } else removedIndex = -1
        }
        if (dotBorn.isNotEmpty() || removedIndex >= 0) postInvalidateOnAnimation()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.argb(ch(Color.alpha(a), 255), ch(Color.red(a), Color.red(b)),
            ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
}

class GlassKeyView(
    context: Context,
    private val digit: String?,
    private val letters: String?,
    iconRes: Int?,
    private val glass: Boolean,
    private val palette: LockPalette,
    private val iconTint: Int? = null,
    private val sparkle: Boolean = false
) : View(context) {

    private val accent = palette.accent
    private val startNanos = System.nanoTime()
    private var visibleNow = false

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleNow = isVisible
        if (sparkle && isVisible) invalidate()
    }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 31f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        color = palette.text
    }
    private val lettersPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 9.5f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.22f
        color = (palette.text and 0x00FFFFFF) or 0x99000000.toInt()
    }
    private val icon: Drawable? = iconRes?.let { ContextCompat.getDrawable(context, it)?.mutate() }
    private var press = 0f
    private var pressAnim: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        icon?.setTint(when {
            sparkle -> if (palette.dark) Color.WHITE else accent
            else -> iconTint ?: palette.text
        })
        contentDescription = digit
    }

    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        if (was == pressed) return
        if (palette.retro) { press = if (pressed) 1f else 0f; invalidate(); return }
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(press, if (pressed) 1f else 0f).apply {
            duration = if (pressed) 70 else 320
            interpolator = DecelerateInterpolator()
            addUpdateListener { press = it.animatedValue as Float; invalidate() }
            start()
        }
        animate().scaleX(if (pressed) 0.92f else 1f).scaleY(if (pressed) 0.92f else 1f)
            .setDuration(if (pressed) 70 else 280)
            .setInterpolator(if (pressed) DecelerateInterpolator() else android.view.animation.OvershootInterpolator(2.2f))
            .start()
    }

    private val box = RectF()

    private fun drawRetro(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val sw = d.coerceAtLeast(1f)
        box.set(sw / 2f, sw / 2f, width - sw / 2f, height - sw / 2f)
        RetroUi.drawButton(canvas, box, press > 0f, d, fill, rim)
        if (digit != null) {
            digitPaint.typeface = Typeface.DEFAULT
            digitPaint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
            canvas.drawText(digit, cx, cy - (digitPaint.descent() + digitPaint.ascent()) / 2f, digitPaint)
        }
        icon?.let {
            val s = 22f * d
            it.setTint(RetroUi.NAVY)
            it.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            it.alpha = 255
            it.draw(canvas)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (palette.retro) { drawRetro(canvas); return }
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - 2 * d
        if (sparkle) {
            val t = (System.nanoTime() - startNanos) / 1e9f
            val breathe = 0.5f + 0.5f * sin(t * 2.4f)
            val glowR = r * (0.78f + 0.06f * breathe + 0.35f * press)
            val a = (0.30f + 0.10f * breathe + 0.40f * press).coerceAtMost(1f)
            glow.shader = RadialGradient(cx, cy, glowR,
                intArrayOf(Color.argb((255 * a).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, glowR, glow)
            icon?.let {
                val s = 30f * d * (1f + 0.08f * press)
                it.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
                it.alpha = 255
                it.draw(canvas)
            }
            if (visibleNow && !A11y.reducedMotion(context)) postInvalidateDelayed(33)
            return
        }
        if (press > 0.01f && palette.flow) {
            glow.shader = RadialGradient(cx, cy, r * 1.5f,
                intArrayOf(Color.argb((110 * press).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 1.5f, glow)
        }
        if (glass) {
            fill.color = blend(palette.fill, palette.fillPressed, press)
            canvas.drawCircle(cx, cy, r, fill)
            rim.shader = LinearGradient(0f, cy - r, 0f, cy + r,
                palette.rimTop, palette.rimBottom, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r - rim.strokeWidth / 2f, rim)
        }
        if (digit != null) {
            val dy = if (letters != null) -5f * d else 0f
            canvas.drawText(digit, cx, cy - (digitPaint.descent() + digitPaint.ascent()) / 2f + dy, digitPaint)
            if (letters != null) canvas.drawText(letters, cx, cy + 17f * d, lettersPaint)
        }
        icon?.let {
            val s = (if (glass) 28f else 26f) * d
            it.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            it.alpha = (255 * (0.85f + 0.15f * press)).toInt()
            it.draw(canvas)
        }
    }

    private fun blend(a: Int, b: Int, t: Float) = LockPalette.blend(a, b, t)
}
