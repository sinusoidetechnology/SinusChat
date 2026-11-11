package pl.sinusoide.sinuschat.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class PulseAvatarView(context: Context, private val palette: LockPalette) : FrameLayout(context) {

    private val accent = palette.accent

    private val d = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val image = ImageView(context)
    private val start = System.nanoTime()
    private var visibleNow = false

    var ringing = true
        set(value) { field = value; invalidate() }

    init {
        setWillNotDraw(false)
        clipChildren = false
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.clipToOutline = true
        image.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        addView(image)
    }

    fun setAvatar(bitmap: Bitmap) { image.setImageBitmap(bitmap) }
    fun setAvatar(drawable: Drawable) { image.setImageDrawable(drawable) }
    fun imageView(): ImageView = image

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val size = (min(width, height) * 0.62f).toInt()
        val left = (width - size) / 2
        val top = (height - size) / 2
        image.layout(left, top, left + size, top + size)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleNow = isVisible
        if (isVisible) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val base = min(width, height) * 0.31f
        val t = (System.nanoTime() - start) / 1e9f

        if (ringing) {
            for (i in 0 until 3) {
                val phase = ((t / 1.8f) + i / 3f) % 1f
                val r = base + (min(width, height) / 2f - base) * phase
                val a = (1f - phase) * 0.55f
                ringPaint.strokeWidth = (2.5f - 1.5f * phase) * d
                ringPaint.color = Color.argb((255 * a).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
        }
        val breathe = 0.5f + 0.5f * sin(t * (if (ringing) 3.2f else 1.6f))
        val glowR = base * (1.45f + 0.08f * breathe)
        glowPaint.shader = RadialGradient(cx, cy, glowR,
            intArrayOf(Color.argb((110 + 40 * breathe).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, glowR, glowPaint)
        super.onDraw(canvas)
        if (visibleNow) postInvalidateDelayed(33)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) * 0.31f
        rimPaint.shader = if (palette.flow) LinearGradient(0f, cy - r, 0f, cy + r, 0x66FFFFFF, 0x10FFFFFF, Shader.TileMode.CLAMP)
            else LinearGradient(0f, cy - r, 0f, cy + r, palette.rimTop, palette.rimTop, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r - rimPaint.strokeWidth / 2f, rimPaint)
    }
}

class CallButtonView(
    context: Context,
    iconRes: Int,
    private val solidColor: Int? = null,
    palette: LockPalette = LockPalette.of(context)
) : View(context) {

    var palette: LockPalette = palette
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var icon: Drawable? = ContextCompat.getDrawable(context, iconRes)?.mutate()
    private var press = 0f
    private var activeMix = 0f
    private var pressAnim: ValueAnimator? = null

    var active = false
        set(value) {
            if (field == value) return
            field = value
            ValueAnimator.ofFloat(activeMix, if (value) 1f else 0f).apply {
                duration = 220; interpolator = DecelerateInterpolator()
                addUpdateListener { activeMix = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    private var arrowIcon: Drawable? = null

    var arrow = false
        set(value) {
            if (field == value) return
            field = value
            if (value && arrowIcon == null) arrowIcon = ContextCompat.getDrawable(context, pl.sinusoide.sinuschat.R.drawable.ic_chevron_down)?.mutate()
            invalidate()
        }

    var arrowOpen = false
        set(value) { if (field != value) { field = value; invalidate() } }

    init { isClickable = true; isFocusable = true }

    fun setIcon(res: Int) {
        icon = ContextCompat.getDrawable(context, res)?.mutate()
        invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        if (was == pressed) return
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(press, if (pressed) 1f else 0f).apply {
            duration = if (pressed) 70 else 300
            addUpdateListener { press = it.animatedValue as Float; invalidate() }
            start()
        }
        animate().scaleX(if (pressed) 0.9f else 1f).scaleY(if (pressed) 0.9f else 1f)
            .setDuration(if (pressed) 70 else 280)
            .setInterpolator(if (pressed) DecelerateInterpolator() else OvershootInterpolator(2.2f)).start()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - 3 * d
        val glowColor = solidColor ?: (if (palette.dark) Color.WHITE else palette.accent)
        if (solidColor != null || press > 0.01f) {
            val a = if (solidColor != null) 0.35f + 0.3f * press else 0.18f * press
            glow.shader = RadialGradient(cx, cy, r * 1.45f,
                intArrayOf(Color.argb((255 * a).toInt(), Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)), Color.TRANSPARENT),
                floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 1.45f, glow)
        }
        if (solidColor != null) {
            fill.shader = LinearGradient(0f, cy - r, 0f, cy + r,
                lighten(solidColor, 0.12f), darken(solidColor, 0.12f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, fill)
            fill.shader = null
        } else {
            val base = blend(palette.fill, palette.fillPressed, press)
            val activeFill = if (palette.flow) 0xF2FFFFFF.toInt() else palette.orb
            fill.color = blend(base, activeFill, activeMix)
            canvas.drawCircle(cx, cy, r, fill)
        }
        if (solidColor == null) {
            rim.shader = LinearGradient(0f, cy - r, 0f, cy + r, palette.rimTop, palette.rimBottom, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r - rim.strokeWidth / 2f, rim)
        } else if (palette.flow) {
            rim.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0x47FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r - rim.strokeWidth / 2f, rim)
        }

        icon?.let {
            val s = r * 0.9f
            it.setTint(if (solidColor == null) blend(palette.text, if (palette.flow) 0xFF101820.toInt() else palette.onOrb, activeMix) else Color.WHITE)
            it.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            it.draw(canvas)
        }

        if (arrow && solidColor == null) arrowIcon?.let {
            val br = r * 0.32f
            val bx = cx + r * 0.7f
            val by = cy - r * 0.7f
            fill.shader = null
            fill.color = if (palette.flow) 0xF2FFFFFF.toInt() else palette.orb
            canvas.drawCircle(bx, by, br, fill)
            it.setTint(if (palette.flow) 0xFF101820.toInt() else palette.onOrb)
            val s = br * 2.1f
            canvas.save()
            if (!arrowOpen) canvas.rotate(180f, bx, by)
            it.setBounds((bx - s / 2).toInt(), (by - s / 2).toInt(), (bx + s / 2).toInt(), (by + s / 2).toInt())
            it.draw(canvas)
            canvas.restore()
        }
    }

    private fun lighten(c: Int, f: Float) = blend(c, Color.WHITE, f)
    private fun darken(c: Int, f: Float) = blend(c, Color.BLACK, f)
}

private fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
    return Color.argb(ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)),
        ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
}

class ArcAnswerView(
    context: Context,
    private val palette: LockPalette,
    private val onAnswer: () -> Unit,
    private val onDecline: () -> Unit
) : View(context) {

    private val accent = palette.accent
    private val d = resources.displayMetrics.density
    private val green = 0xFF2FBF71.toInt()
    private val red = 0xFFE5484D.toInt()
    private val dark = palette.onOrb

    private val maxDeg = 105f
    private val trigger = 0.9f

    private val channel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val channelRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val handset = ContextCompat.getDrawable(context, pl.sinusoide.sinuschat.R.drawable.ic_call_answer)?.mutate()
    private val hangup = ContextCompat.getDrawable(context, pl.sinusoide.sinuschat.R.drawable.ic_call_end)?.mutate()
    private val answerIcon = ContextCompat.getDrawable(context, pl.sinusoide.sinuschat.R.drawable.ic_call_answer)?.mutate()
    private val declineIcon = ContextCompat.getDrawable(context, pl.sinusoide.sinuschat.R.drawable.ic_call_end)?.mutate()
    private val answerText = context.getString(pl.sinusoide.sinuschat.R.string.answer)
    private val declineText = context.getString(pl.sinusoide.sinuschat.R.string.decline)
    private val hintText = context.getString(pl.sinusoide.sinuschat.R.string.call_slide_hint)

    private val oval = RectF()
    private val tubeSrc = android.graphics.Path()
    private val tubeOutline = android.graphics.Path()
    private val start = System.nanoTime()

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var orbR = 0f
    private var endR = 0f

    private var angle = 0f
    private var accAngle = 0f
    private var lastRaw = 0f
    private var dragging = false
    private var done = false
    private var fired = false
    private var zone = 0
    private var grab = 0f
    private var burst = -1f
    private var burstSide = 1
    private var visibleNow = true
    private var angleAnim: ValueAnimator? = null
    private var grabAnim: ValueAnimator? = null

    init {
        contentDescription = hintText
        androidx.core.view.ViewCompat.addAccessibilityAction(this, answerText) { _, _ ->
            if (!done) complete(1); true
        }
        androidx.core.view.ViewCompat.addAccessibilityAction(this, declineText) { _, _ ->
            if (!done) complete(-1); true
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        orbR = 31 * d
        endR = 27 * d
        val labelSpace = 32 * d
        val endDrop = cos(Math.toRadians(180.0 - maxDeg)).toFloat()
        val endSpread = sin(Math.toRadians(maxDeg.toDouble())).toFloat()
        radius = min(
            (w / 2f - endR - 10 * d) / endSpread,
            (h - orbR * 1.8f - endR - labelSpace) / (1f + endDrop)
        ).coerceAtLeast(40 * d)
        cx = w / 2f
        cy = radius * endDrop + endR + labelSpace

        channel.strokeWidth = orbR * 2f + 10 * d
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        tubeSrc.reset()
        tubeSrc.addArc(oval, 90f - maxDeg, maxDeg * 2f)
        tubeOutline.reset()
        @Suppress("DEPRECATION")
        channel.getFillPath(tubeSrc, tubeOutline)

        val half = channel.strokeWidth / 2f
        channel.shader = if (palette.flow) LinearGradient(0f, cy - radius * endDrop - half, 0f, cy + radius + half,
            0x26FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP) else null
        channel.color = palette.fill
        channelRim.shader = LinearGradient(0f, cy - radius * endDrop - half, 0f, cy + radius + half,
            if (palette.flow) 0x40FFFFFF else palette.rimTop,
            if (palette.flow) 0x0FFFFFFF else palette.rimBottom, Shader.TileMode.CLAMP)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleNow = isVisible
        if (isVisible) invalidate()
    }

    private fun px(a: Float) = cx + radius * sin(Math.toRadians(a.toDouble())).toFloat()
    private fun py(a: Float) = cy + radius * cos(Math.toRadians(a.toDouble())).toFloat()

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        val t = (System.nanoTime() - start) / 1e9f
        val p = abs(angle) / maxDeg
        val side = if (angle >= 0f) 1 else -1
        val sideColor = if (side > 0) green else red
        val calm = (1f - min(1f, p * 4f)) * (1f - grab * 0.7f)

        canvas.drawPath(tubeSrc, channel)
        canvas.drawPath(tubeOutline, channelRim)
        if (p > 0.01f) {
            progress.strokeWidth = channel.strokeWidth
            progress.color = withAlpha(sideColor, 0.12f + 0.28f * p)
            canvas.drawArc(oval, 90f, -angle, false, progress)
        }

        if (calm > 0.01f && !done) {
            for (s in intArrayOf(-1, 1)) {
                for (i in 0 until 3) {
                    val phase = ((t * 0.5f) + i / 3f) % 1f
                    val a = s * maxDeg * (0.24f + 0.66f * phase)
                    val alpha = sin(phase * Math.PI).toFloat() * calm
                    drawSpark(canvas, px(a), py(a), 2.2f * d, if (s > 0) green else red, alpha)
                }
            }
        }

        val breath = 0.5f + 0.5f * sin(t * 2.6f)
        for (s in intArrayOf(-1, 1)) {
            val a = s * maxDeg
            val x = px(a); val y = py(a)
            val c = if (s > 0) green else red
            val near = if (side == s) p else 0f
            val hot = ((near - 0.55f) / 0.35f).coerceIn(0f, 1f)
            val r = endR * (1f + 0.14f * hot)
            val gr = r * (1.9f + 0.25f * breath)
            glow.shader = RadialGradient(x, y, gr,
                intArrayOf(withAlpha(c, 0.18f + 0.55f * hot + 0.08f * breath), Color.TRANSPARENT),
                floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, gr, glow)
            solid.shader = null
            solid.color = blend(if (palette.flow) 0x1AFFFFFF else palette.fillPressed, c, hot)
            canvas.drawCircle(x, y, r, solid)
            ring.strokeWidth = 1.2f * d
            ring.color = blend(if (palette.flow) 0x33FFFFFF else palette.rimTop, Color.WHITE, hot * 0.6f)
            canvas.drawCircle(x, y, r - ring.strokeWidth / 2f, ring)
            (if (s > 0) answerIcon else declineIcon)?.let {
                val sz = r * 1.02f
                it.setTint(blend(c, Color.WHITE, hot))
                it.setBounds((x - sz / 2).toInt(), (y - sz / 2).toInt(), (x + sz / 2).toInt(), (y + sz / 2).toInt())
                it.draw(canvas)
            }
            text.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
            text.color = blend(palette.textSecondary, palette.text, hot)
            canvas.drawText(if (s > 0) answerText else declineText, x, y - endR - 11 * d, text)
        }

        if (calm > 0.01f && !done) {
            text.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
            val maxW = radius * 1.15f
            val tw = text.measureText(hintText)
            if (tw > maxW) text.textSize *= maxW / tw
            text.color = withAlpha(palette.text, 0.62f * calm)
            canvas.drawText(hintText, cx, cy + radius * 0.3f, text)
        }

        val ox = px(angle); val oy = py(angle)
        val bp = if (burst >= 0f) burst else 0f
        val orbScale = (1f + 0.1f * grab + 0.05f * breath * (1f - grab) * calm) * (1f - 0.35f * bp)
        val orbAlpha = 1f - bp
        val core = blend(palette.orb, sideColor, min(1f, p * 1.3f))
        val glowColor = blend(accent, sideColor, p)
        val gR = orbR * orbScale * (1.85f + 0.2f * breath)
        glow.shader = RadialGradient(ox, oy, gR,
            intArrayOf(withAlpha(glowColor, (0.38f + 0.2f * breath + 0.25f * grab) * orbAlpha), Color.TRANSPARENT),
            floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(ox, oy, gR, glow)

        val oR = orbR * orbScale
        solid.shader = LinearGradient(0f, oy - oR, 0f, oy + oR,
            withAlpha(blend(core, Color.WHITE, 0.25f), orbAlpha),
            withAlpha(blend(core, if (palette.dark) 0xFF9FB2C8.toInt() else Color.BLACK, 0.18f), orbAlpha), Shader.TileMode.CLAMP)
        canvas.drawCircle(ox, oy, oR, solid)
        solid.shader = null

        var wiggle = 0f
        if (!dragging && !done && p < 0.02f) {
            val ph = (t % 1.6f) / 1.6f
            if (ph < 0.32f) {
                val k = ph / 0.32f
                wiggle = sin(k * Math.PI * 5).toFloat() * 16f * (1f - k)
            }
        }
        val iconTint = blend(dark, Color.WHITE, ((p - 0.22f) / 0.3f).coerceIn(0f, 1f))
        val iconSize = oR * 1.08f * (1f + 0.1f * p)
        val hangMix = if (side < 0) ((p - 0.4f) / 0.45f).coerceIn(0f, 1f) else 0f
        val rot = if (side > 0) -30f * p else 135f * p
        handset?.let {
            it.setTint(iconTint)
            it.alpha = (255 * (1f - hangMix) * orbAlpha).toInt()
            drawRotated(canvas, it, ox, oy, iconSize, rot + wiggle)
        }
        if (hangMix > 0f) hangup?.let {
            it.setTint(iconTint)
            it.alpha = (255 * hangMix * orbAlpha).toInt()
            drawRotated(canvas, it, ox, oy, iconSize * 1.05f, rot - 135f)
        }

        if (burst >= 0f) {
            val bx = px(burstSide * maxDeg); val by = py(burstSide * maxDeg)
            val c = if (burstSide > 0) green else red
            ring.strokeWidth = (3f * (1f - burst) + 0.5f) * d
            ring.color = withAlpha(c, 1f - burst)
            canvas.drawCircle(bx, by, endR * (1.1f + 2.4f * burst), ring)
            for (k in 0 until 12) {
                val ang = Math.toRadians((k * 30f + 15f * burstSide).toDouble())
                val dist = endR * (1.2f + 2.8f * burst)
                drawSpark(canvas, bx + (cos(ang) * dist).toFloat(), by + (sin(ang) * dist).toFloat(),
                    (2.6f * (1f - burst) + 0.6f) * d, c, 1f - burst)
            }
        }

        if (visibleNow && (!done || burst in 0f..0.999f)) postInvalidateOnAnimation()
    }

    private fun drawRotated(canvas: Canvas, dr: Drawable, x: Float, y: Float, size: Float, deg: Float) {
        canvas.save()
        canvas.rotate(deg, x, y)
        dr.setBounds((x - size / 2).toInt(), (y - size / 2).toInt(), (x + size / 2).toInt(), (y + size / 2).toInt())
        dr.draw(canvas)
        canvas.restore()
        dr.alpha = 255
    }

    private fun drawSpark(canvas: Canvas, x: Float, y: Float, core: Float, color: Int, alpha: Float) {
        if (alpha <= 0.01f) return
        val gr = core * 4.2f
        glow.shader = RadialGradient(x, y, gr,
            intArrayOf(withAlpha(color, 0.75f * alpha), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, gr, glow)
        solid.shader = null
        solid.color = withAlpha(palette.sparkCore, alpha)
        canvas.drawCircle(x, y, core, solid)
    }

    private fun rawAngle(x: Float, y: Float): Float =
        Math.toDegrees(kotlin.math.atan2((x - cx).toDouble(), (y - cy).toDouble())).toFloat()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (done) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (kotlin.math.hypot(e.x - px(angle), e.y - py(angle)) > orbR * 1.45f) return false
                angleAnim?.cancel()
                dragging = true
                lastRaw = rawAngle(e.x, e.y)
                accAngle = angle
                animateGrab(1f)
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                if (kotlin.math.hypot(e.x - cx, e.y - cy) > radius * 0.3f) {
                    val raw = rawAngle(e.x, e.y)
                    var delta = raw - lastRaw
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    lastRaw = raw
                    accAngle += delta.coerceIn(-45f, 45f)
                    angle = accAngle.coerceIn(-maxDeg, maxDeg)
                    val z = when {
                        angle >= maxDeg * trigger -> 1
                        angle <= -maxDeg * trigger -> -1
                        else -> 0
                    }
                    if (z != zone) {
                        zone = z
                        if (z != 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                animateGrab(0f)
                if (e.actionMasked == MotionEvent.ACTION_UP && zone != 0) complete(zone)
                else springBack()
            }
        }
        return true
    }

    private fun animateGrab(to: Float) {
        grabAnim?.cancel()
        grabAnim = ValueAnimator.ofFloat(grab, to).apply {
            duration = if (to > grab) 160 else 320
            interpolator = if (to > grab) DecelerateInterpolator() else OvershootInterpolator(2f)
            addUpdateListener { grab = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun complete(s: Int) {
        done = true
        zone = s
        burstSide = s
        if (android.os.Build.VERSION.SDK_INT >= 30) performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        angleAnim?.cancel()
        angleAnim = ValueAnimator.ofFloat(angle, s * maxDeg).apply {
            duration = 130
            interpolator = DecelerateInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 420
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { burst = it.animatedValue as Float; invalidate() }
                        start()
                    }
                    postDelayed({ fire(s) }, 180)
                }
            })
            start()
        }
    }

    private fun fire(s: Int) {
        if (fired) return
        fired = true
        if (s > 0) onAnswer() else onDecline()
    }

    private fun springBack() {
        zone = 0
        angleAnim?.cancel()
        angleAnim = ValueAnimator.ofFloat(angle, 0f).apply {
            duration = 560
            interpolator = OvershootInterpolator(1.8f)
            addUpdateListener { angle = it.animatedValue as Float; accAngle = angle; invalidate() }
            start()
        }
    }

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb((Color.alpha(c) * a.coerceIn(0f, 1f)).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}
