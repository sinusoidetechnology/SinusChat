package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import androidx.core.content.ContextCompat
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.ThemeManager

object ThemeUi {

    fun attr(ctx: Context, id: Int, fallback: Int): Int = attrOrNull(ctx, id) ?: fallback

    fun attrOrNull(ctx: Context, id: Int): Int? {
        val tv = TypedValue()
        if (!ctx.theme.resolveAttribute(id, tv, true)) return null
        if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return tv.data
        if (tv.resourceId != 0) {
            return try { ContextCompat.getColorStateList(ctx, tv.resourceId)?.defaultColor } catch (_: Exception) { null }
        }
        return null
    }

    fun isFlow(ctx: Context): Boolean = ThemeManager.isFlow(ctx)

    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun accent(ctx: Context): Int =
        attr(ctx, androidx.appcompat.R.attr.colorPrimary, ContextCompat.getColor(ctx, R.color.sinuschat_primary))

    fun onAccent(ctx: Context): Int =
        attr(ctx, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)

    fun textPrimary(ctx: Context) = attr(ctx, R.attr.sinuschat_text_primary, Color.WHITE)
    fun textSecondary(ctx: Context) = attr(ctx, R.attr.sinuschat_text_secondary, 0xFF7F91A4.toInt())
    fun textMuted(ctx: Context) = attr(ctx, R.attr.sinuschat_text_muted, 0xFF7A8B9E.toInt())
    fun surface(ctx: Context) = attr(ctx, R.attr.sinuschat_surface, 0xFF17212B.toInt())
    fun card(ctx: Context) = attr(ctx, R.attr.sinuschat_surface_elevated, 0xFF1D2A38.toInt())
    fun border(ctx: Context) = attr(ctx, R.attr.sinuschat_border, 0xFF233240.toInt())

    fun backgroundSolid(ctx: Context) = attr(ctx, R.attr.sinuschat_background_solid, 0xFF0E1621.toInt())

    fun inset(ctx: Context): Int = if (isFlow(ctx)) 0x2E000000 else backgroundSolid(ctx)

    fun sheet(ctx: Context) = attr(ctx, R.attr.sinuschat_sheet, 0xFF1D2A38.toInt())

    fun divider(ctx: Context): Int = when {
        isFlow(ctx) -> 0x1FFFFFFF
        isDark(ctx) -> 0x14FFFFFF
        else -> 0x1A000000
    }

    fun handle(ctx: Context): Int = (textSecondary(ctx) and 0x00FFFFFF) or 0x55000000

    const val GLASS_RIM = 0x2EFFFFFF

    fun rounded(ctx: Context, color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        val d = ctx.resources.displayMetrics.density
        cornerRadius = (if (ThemeManager.isRetro(ctx)) minOf(radiusDp, 6f) else radiusDp) * d
        setColor(color)
        if (isFlow(ctx) && isGlass(color)) setStroke((1 * d).toInt().coerceAtLeast(1), GLASS_RIM)
    }

    fun isGlassColor(color: Int): Boolean = isGlass(color)

    private fun isGlass(color: Int): Boolean =
        Color.alpha(color) in 1..0x60 && Color.red(color) == 255 && Color.green(color) == 255 && Color.blue(color) == 255
}

class ThemeColors(ctx: Context) {
    val flow = ThemeUi.isFlow(ctx)
    val dark = ThemeUi.isDark(ctx)
    val bg = ThemeUi.attr(ctx, R.attr.sinuschat_background, 0xFF0E1621.toInt())
    val surface = ThemeUi.surface(ctx)
    val card = ThemeUi.card(ctx)
    val input = ThemeUi.attr(ctx, R.attr.sinuschat_surface_variant, 0xFF162431.toInt())
    val border = ThemeUi.border(ctx)
    val divider = ThemeUi.divider(ctx)
    val sheet = ThemeUi.sheet(ctx)
    val text = ThemeUi.textPrimary(ctx)
    val text2 = ThemeUi.textSecondary(ctx)
    val muted = ThemeUi.textMuted(ctx)
    val accent = ThemeUi.accent(ctx)
    val onAccent = ThemeUi.onAccent(ctx)
}

class FlowBackdropDrawable(@Suppress("UNUSED_PARAMETER") ctx: Context) : Drawable() {

    private val base = Paint()
    private val glows = List(SPOTS.size) { Paint(Paint.ANTI_ALIAS_FLAG) }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        base.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.top + h,
            intArrayOf(INK_2, INK, INK), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        for ((i, s) in SPOTS.withIndex()) {
            val c = Color.argb((255 * s[4]).toInt(), 42, 224, 245)
            glows[i].shader = RadialGradient(0f, 0f, 1f, intArrayOf(c, c and 0x00FFFFFF),
                floatArrayOf(0f, s[5]), Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(w * s[0], h * s[1])
                    postTranslate(bounds.left + w * s[2], bounds.top + h * s[3])
                })
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, base)
        for (p in glows) canvas.drawRect(b, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        const val INK = 0xFF04121A.toInt()
        const val INK_2 = 0xFF07202B.toInt()
        const val CYAN = 0xFF2AE0F5.toInt()

        private val SPOTS = arrayOf(
            floatArrayOf(1.20f, 0.55f, 0.00f, 0.00f, 0.16f, 0.55f),
            floatArrayOf(0.90f, 0.45f, 0.55f, 0.06f, 0.08f, 0.60f),
            floatArrayOf(0.75f, 0.30f, 1.00f, 0.42f, 0.11f, 0.70f),
            floatArrayOf(0.85f, 0.30f, 0.05f, 0.80f, 0.10f, 0.70f),
            floatArrayOf(0.90f, 0.35f, 0.90f, 1.00f, 0.07f, 0.70f)
        )
    }
}
