package pl.sinusoide.sinuschat.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.WindowCompat
import pl.sinusoide.sinuschat.util.ThemeManager

object RetroUi {

    const val ACCENT = 0xFF2470C8.toInt()
    const val FOCUS = 0xFF3C7FB1.toInt()
    const val GLASS_TOP = 0xFFC6DDF6.toInt()
    const val GLASS_MID = 0xFF94B9E6.toInt()
    const val GLASS_LINE = 0xFF6F94C6.toInt()
    const val CHROME = 0xFFE4EDF8.toInt()
    const val PANEL = 0xFFF5F9FD.toInt()
    const val SECTION = 0xFFD6E8F8.toInt()
    const val FRAME = 0xFFA5BCD9.toInt()
    const val FIELD = 0xFFABADB3.toInt()
    const val ROW_MINE = 0xFFD6EAFC.toInt()
    const val ROW_OTHER = 0xFFFFFFFF.toInt()
    const val NAVY = 0xFF1B2B40.toInt()
    const val TIME_MINE = 0xFF4E6D93.toInt()
    const val CLOSE = 0xFFC75050.toInt()

    fun active(ctx: Context): Boolean = ThemeManager.isRetro(ctx)

    private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    fun titleBar(ctx: Context): Drawable = AeroGlassDrawable(ctx.resources.displayMetrics.density)

    fun statusArea(): Drawable = GradientDrawable().apply { setColor(GLASS_TOP) }

    fun bottomBar(ctx: Context): Drawable {
        val fill = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(PANEL, 0xFFDCE8F5.toInt()))
        val line = GradientDrawable().apply { setColor(FRAME) }
        return LayerDrawable(arrayOf(line, fill)).apply {
            setLayerInset(1, 0, dp(ctx, 1f).toInt(), 0, 0)
        }
    }

    fun sunken(ctx: Context): Drawable = GradientDrawable().apply {
        setColor(0xFFFFFFFF.toInt())
        cornerRadius = dp(ctx, 2f)
        setStroke(dp(ctx, 1f).toInt(), FIELD)
    }

    fun button(ctx: Context): Drawable = Win7ButtonDrawable(ctx.resources.displayMetrics.density, false)

    fun pressed(ctx: Context): Drawable = Win7ButtonDrawable(ctx.resources.displayMetrics.density, true)

    private val NORMAL_STOPS = intArrayOf(0xFFF2F2F2.toInt(), 0xFFEBEBEB.toInt(), 0xFFDDDDDD.toInt(), 0xFFCFCFCF.toInt())
    private val PRESSED_STOPS = intArrayOf(0xFFE5F4FC.toInt(), 0xFFC4E5F6.toInt(), 0xFF98D1EF.toInt(), 0xFF68B3DB.toInt())
    private val SPLIT = floatArrayOf(0f, 0.5f, 0.5f, 1f)

    fun drawButton(canvas: Canvas, box: RectF, pressed: Boolean, density: Float, fill: Paint, rim: Paint) {
        val r = 3f * density
        val sw = density.coerceAtLeast(1f)
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(0f, box.top, 0f, box.bottom,
            if (pressed) PRESSED_STOPS else NORMAL_STOPS, SPLIT, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, r, r, fill)
        fill.shader = null
        rim.style = Paint.Style.STROKE
        rim.shader = null
        rim.strokeWidth = sw
        rim.color = 0xCCFFFFFF.toInt()
        val inner = RectF(box.left + sw, box.top + sw, box.right - sw, box.bottom - sw)
        canvas.drawRoundRect(inner, r - sw, r - sw, rim)
        rim.color = if (pressed) 0xFF2C628B.toInt() else 0xFF707070.toInt()
        canvas.drawRoundRect(box, r, r, rim)
    }

    fun styleTitleBar(bar: View) {
        bar.background = titleBar(bar.context)
        darken(bar, true)
    }

    private fun isPicture(v: View): Boolean {
        if (v.id == View.NO_ID) return false
        val name = try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { return false }
        return name.contains("avatar") || name.contains("photo") || name.contains("image")
    }

    private fun darken(v: View, root: Boolean) {
        val bg = v.background
        if (!root && bg != null && bg !is android.graphics.drawable.RippleDrawable) return
        when (v) {
            is EditText -> return
            is TextView -> {
                v.setTextColor(NAVY)
                v.setShadowLayer(6f * v.resources.displayMetrics.density, 0f, 0f, 0xE6FFFFFF.toInt())
            }
            is ImageView -> if (v.drawable !is BitmapDrawable && !isPicture(v)) v.setColorFilter(NAVY)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) darken(v.getChildAt(i), false)
    }

    fun lightStatusIcons(activity: Activity) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars = false
    }

    fun darkStatusIcons(activity: Activity) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars = true
    }
}

class Win7ButtonDrawable(private val density: Float, private val pressed: Boolean) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val sw = density.coerceAtLeast(1f)
        box.set(b.left + sw / 2f, b.top + sw / 2f, b.right - sw / 2f, b.bottom - sw / 2f)
        RetroUi.drawButton(canvas, box, pressed, density, fill, rim)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; rim.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class AeroGlassDrawable(private val density: Float, private val line: Boolean = true) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        paint.shader = LinearGradient(0f, 0f, 0f, h, RetroUi.GLASS_TOP, RetroUi.GLASS_MID, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val lean = 0.62f * h
        val period = maxOf(w, 360f * density)
        var base = -lean
        while (base < w + lean) {
            for (s in STREAKS) {
                val x = base + s[0] * period
                val sw = s[1] * density * 0.7f
                path.reset()
                path.moveTo(x, 0f)
                path.lineTo(x + sw, 0f)
                path.lineTo(x + sw - lean, h)
                path.lineTo(x - lean, h)
                path.close()
                paint.color = android.graphics.Color.argb((255 * s[2] * 1.4f).toInt().coerceAtMost(255), 255, 255, 255)
                canvas.drawPath(path, paint)
            }
            base += period
        }
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.5f, 0x8CFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h * 0.5f, paint)
        paint.shader = null
        if (line) {
            paint.color = RetroUi.GLASS_LINE
            canvas.drawRect(0f, h - density.coerceAtLeast(1f), w, h, paint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        val STREAKS = arrayOf(
            floatArrayOf(0.02f, 26f, 0.10f), floatArrayOf(0.09f, 9f, 0.16f), floatArrayOf(0.15f, 54f, 0.06f),
            floatArrayOf(0.27f, 14f, 0.13f), floatArrayOf(0.33f, 34f, 0.07f), floatArrayOf(0.46f, 7f, 0.18f),
            floatArrayOf(0.52f, 46f, 0.06f), floatArrayOf(0.63f, 18f, 0.12f), floatArrayOf(0.71f, 30f, 0.08f),
            floatArrayOf(0.82f, 10f, 0.15f), floatArrayOf(0.88f, 40f, 0.06f), floatArrayOf(0.97f, 16f, 0.11f)
        )
    }
}
