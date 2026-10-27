package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import pl.sinusoide.sinuschat.util.LocaleManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

class DayDividerDecoration(
    ctx: Context,
    private val timeAt: (Int) -> Long?
) : RecyclerView.ItemDecoration() {

    private val d = ctx.resources.displayMetrics.density
    private val space = (40 * d).toInt()
    private val zone = ZoneId.systemDefault()
    private val locale = Locale.forLanguageTag(LocaleManager.getEffective(ctx))
    private val labels = HashMap<Long, String>()
    private val color = ThemeUi.textSecondary(ctx)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12.5f, ctx.resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = this@DayDividerDecoration.color
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private fun dayAt(position: Int): Long? {
        val ms = timeAt(position)?.takeIf { it > 0L } ?: return null
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
    }

    private fun headerDay(position: Int): Long? {
        if (position == RecyclerView.NO_POSITION) return null
        val day = dayAt(position) ?: return null
        val prev = if (position > 0) dayAt(position - 1) else null
        return if (prev == day) null else day
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (headerDay(parent.getChildAdapterPosition(view)) != null) outRect.top = space
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val day = headerDay(parent.getChildAdapterPosition(child)) ?: continue
            val lp = child.layoutParams as RecyclerView.LayoutParams
            val cy = child.top - lp.topMargin - space / 2f + child.translationY
            drawDivider(c, parent, cy, label(day), child.alpha)
        }
    }

    private fun label(day: Long): String = labels.getOrPut(day) {
        val date = LocalDate.ofEpochDay(day)
        val skeleton = if (date.year == LocalDate.now(zone).year) "dMMMM" else "dMMMMy"
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        val millis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(millis))
    }

    private fun drawDivider(c: Canvas, parent: RecyclerView, cy: Float, text: String, alpha: Float) {
        val cx = parent.width / 2f
        val half = textPaint.measureText(text) / 2f
        val gap = 10 * d
        val left = parent.paddingLeft + 18 * d
        val right = parent.width - parent.paddingRight - 18 * d
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        textPaint.alpha = a
        drawWave(c, left, cx - half - gap, cy, cx, fadeAtStart = true, alpha = a)
        drawWave(c, cx + half + gap, right, cy, cx, fadeAtStart = false, alpha = a)
        c.drawText(text, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun drawWave(c: Canvas, x0: Float, x1: Float, cy: Float, cx: Float, fadeAtStart: Boolean, alpha: Int) {
        if (x1 - x0 < 12 * d) return
        val amp = 2.6f * d
        val wave = 22 * d
        fun y(x: Float) = cy - amp * sin(2 * PI * abs(x - cx) / wave).toFloat()
        path.reset()
        path.moveTo(x0, y(x0))
        var x = x0
        val step = 1.5f * d
        while (x < x1) {
            x = min(x + step, x1)
            path.lineTo(x, y(x))
        }
        val solid = (color and 0x00FFFFFF) or ((Color.alpha(color) * 0.55f * alpha / 255f).toInt() shl 24)
        val clear = color and 0x00FFFFFF
        wavePaint.shader = LinearGradient(x0, 0f, x1, 0f,
            if (fadeAtStart) clear else solid, if (fadeAtStart) solid else clear, Shader.TileMode.CLAMP)
        c.drawPath(path, wavePaint)
    }
}
