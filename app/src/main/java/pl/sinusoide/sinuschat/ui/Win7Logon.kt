package pl.sinusoide.sinuschat.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Win7LogonDrawable : Drawable() {

    private class Wave(val amp: Float, val length: Float, val phase: Float, val y: Float, val alpha: Float, val width: Float)

    private val waves = List(9) { k ->
        Wave(0.085f + k * 0.006f, 1.25f - k * 0.02f, 0.9f + k * 0.16f, 0.60f + k * 0.011f,
            if (k == 4) 0.55f else 0.10f + 0.03f * (k % 3), if (k == 4) 1.6f else 1f)
    } + listOf(
        Wave(0.05f, 0.9f, 2.6f, 0.30f, 0.12f, 1f),
        Wave(0.06f, 0.95f, 2.9f, 0.32f, 0.08f, 1f),
        Wave(0.04f, 1.7f, 0.3f, 0.86f, 0.10f, 1f)
    )

    private val bokeh = arrayOf(
        floatArrayOf(0.14f, 0.62f, 9f, 0.20f), floatArrayOf(0.22f, 0.55f, 4f, 0.35f), floatArrayOf(0.37f, 0.66f, 16f, 0.10f),
        floatArrayOf(0.58f, 0.58f, 5f, 0.30f), floatArrayOf(0.66f, 0.70f, 12f, 0.12f), floatArrayOf(0.79f, 0.61f, 3f, 0.40f),
        floatArrayOf(0.88f, 0.67f, 8f, 0.18f), floatArrayOf(0.31f, 0.28f, 6f, 0.14f), floatArrayOf(0.72f, 0.33f, 10f, 0.09f)
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var paths: List<Pair<Wave, Path>> = emptyList()
    private var builtFor = 0L

    private fun build(w: Float, h: Float) {
        val key = (w.toLong() shl 32) or h.toLong()
        if (key == builtFor) return
        builtFor = key
        paths = waves.map { wave ->
            val path = Path()
            var x = -20f
            path.moveTo(x, h * wave.y + h * wave.amp * sin(wave.phase))
            val step = max(4f, w / 160f)
            while (x < w + 20f) {
                x += step
                path.lineTo(x, h * wave.y + h * wave.amp * sin(2f * PI.toFloat() * x / (w * wave.length) + wave.phase))
            }
            wave to path
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val big = max(w, h)
        build(w, h)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(0xFF0A2F62.toInt(), 0xFF1356A3.toInt(), 0xFF1966B8.toInt(), 0xFF09306A.toInt()),
            floatArrayOf(0f, 0.42f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w * 0.5f, h * 0.6f, big * 0.6f,
            intArrayOf(0x8C7FD0FF.toInt(), 0x403C9BE8, 0x003C9BE8), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        val fade = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(0x00FFFFFF, Color.WHITE, Color.WHITE, 0x00FFFFFF), floatArrayOf(0f, 0.25f, 0.75f, 1f), Shader.TileMode.CLAMP)
        stroke.shader = fade
        val unit = big / 900f
        for ((wave, path) in paths) {
            val base = 1.6f * wave.width * unit
            for ((mult, a) in listOf(18f to 0.18f, 6f to 0.35f, 1f to 1f)) {
                stroke.strokeWidth = base * mult
                stroke.alpha = (255 * wave.alpha * a).toInt().coerceIn(0, 255)
                canvas.drawPath(path, stroke)
            }
        }
        stroke.shader = null
        for (s in bokeh) {
            val cx = w * s[0]
            val cy = h * s[1]
            val r = s[2] * big / 1200f * 2f
            val a = (255 * s[3]).toInt()
            paint.shader = RadialGradient(cx, cy, r,
                intArrayOf(Color.argb(a, 255, 255, 255), Color.argb((a * 0.4f).toInt(), 255, 255, 255), 0x00FFFFFF),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
        paint.shader = RadialGradient(w * 0.5f, h * 0.55f, big * 0.78f,
            intArrayOf(0x0000081C, 0x0000081C, 0x8A00081C.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

class Win7TileDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = 9f * density
        val px = density.coerceAtLeast(1f)
        rect.set(b)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(0x9EFFFFFF.toInt(), 0x57FFFFFF, 0x2EFFFFFF, 0x52FFFFFF),
            floatArrayOf(0f, 0.46f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px
        paint.color = 0xE6FFFFFF.toInt()
        rect.inset(px / 2f, px / 2f)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.color = 0x55002050
        rect.inset(px, px)
        canvas.drawRoundRect(rect, r - px, r - px, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class Win7PictureFrame(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(0x47FFFFFF, 0x0FFFFFFF, 0x00FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 0.46f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density.coerceAtLeast(1f)
        paint.color = 0xD9FFFFFF.toInt()
        rect.inset(paint.strokeWidth / 2f, paint.strokeWidth / 2f)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class Win7GoDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var pressed = false
    var danger = false
        set(value) { field = value; invalidateSelf() }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = min(b.width(), b.height()) / 2f
        val px = density.coerceAtLeast(1f)
        val c = when {
            danger -> intArrayOf(0xFFFFB49E.toInt(), 0xFFE2572F.toInt(), 0xFFA9250C.toInt(), 0xFFE86A3E.toInt())
            pressed -> intArrayOf(0xFF8CC4F0.toInt(), 0xFF1F6FC0.toInt(), 0xFF0E3F80.toInt(), 0xFF2C7FD0.toInt())
            else -> intArrayOf(0xFFB3E2FF.toInt(), 0xFF3896E6.toInt(), 0xFF15589F.toInt(), 0xFF4FB8F6.toInt())
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0xAA0A1E3C.toInt()
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = LinearGradient(0f, cy - r, 0f, cy + r, c, floatArrayOf(0f, 0.48f, 0.52f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r - px, paint)
        paint.shader = RadialGradient(cx, cy + r, r * 0.9f, (c[3] and 0x00FFFFFF) or (0xE6 shl 24), c[3] and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r - px, paint)
        canvas.save()
        canvas.scale(1f, 0.55f, cx, cy - r * 0.45f)
        paint.shader = LinearGradient(0f, cy - r * 1.2f, 0f, cy + r * 0.2f, 0xBFFFFFFF.toInt(), 0x1AFFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy - r * 0.45f, r * 0.78f, paint)
        canvas.restore()
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px
        paint.color = 0xE6FFFFFF.toInt()
        canvas.drawCircle(cx, cy, r - px * 1.5f, paint)
        val a = r * 0.36f
        line.strokeWidth = 2.6f * px
        for ((color, dy) in listOf(0x66001030 to px, Color.WHITE to 0f)) {
            line.color = color
            canvas.drawLine(cx - a, cy + dy, cx + a * 0.9f, cy + dy, line)
            canvas.drawLine(cx + a * 0.95f, cy + dy, cx + a * 0.05f, cy - a * 0.85f + dy, line)
            canvas.drawLine(cx + a * 0.95f, cy + dy, cx + a * 0.05f, cy + a * 0.85f + dy, line)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class Win7GlassButtonDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pressed = false

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val k = if (pressed) 0.55f else 1f
        val r = 3f * density
        val px = density.coerceAtLeast(1f)
        rect.set(b)
        paint.style = Paint.Style.FILL
        fun white(a: Float) = Color.argb((255 * a * k).toInt().coerceIn(0, 255), 255, 255, 255)
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(white(0.40f), white(0.18f), white(0.06f), white(0.20f)), floatArrayOf(0f, 0.48f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px
        paint.color = 0x99001A3C.toInt()
        rect.inset(px / 2f, px / 2f)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.color = 0x8CFFFFFF.toInt()
        rect.inset(px, px)
        canvas.drawRoundRect(rect, r - px, r - px, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class Win7CloseDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    private val rect = RectF()
    private var pressed = false

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = 3f * density
        val px = density.coerceAtLeast(1f)
        rect.set(b)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            if (pressed) intArrayOf(0xFFD38C7E.toInt(), 0xFFB04030.toInt(), 0xFF8F1E10.toInt(), 0xFFB53A28.toInt())
            else intArrayOf(0xFFE8A296.toInt(), 0xFFD7705E.toInt(), 0xFFC4382A.toInt(), 0xFFD9604A.toInt()),
            floatArrayOf(0f, 0.48f, 0.52f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px
        paint.color = 0xFF5E2A24.toInt()
        rect.inset(px / 2f, px / 2f)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.color = 0x80FFFFFF.toInt()
        rect.inset(px, px)
        canvas.drawRoundRect(rect, r - px, r - px, paint)
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val a = min(b.width(), b.height()) * 0.18f
        for ((color, w) in listOf(0x80400000.toInt() to 3.4f * px, Color.WHITE to 2f * px)) {
            line.color = color
            line.strokeWidth = w
            canvas.drawLine(cx - a, cy - a, cx + a, cy + a, line)
            canvas.drawLine(cx + a, cy - a, cx - a, cy + a, line)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
