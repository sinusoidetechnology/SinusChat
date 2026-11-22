package pl.sinusoide.sinuschat.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.ThemeManager

object FlowGlass {

    fun install(activity: Activity) {
        if (!ThemeManager.isFlow(activity)) return
        val decor = activity.window?.peekDecorView() ?: return
        attach(decor)
    }

    fun show(dialog: Dialog) {
        dialog.show()
        RetroShape.dialog(dialog)
        A11y.dialog(dialog)
        if (!ThemeManager.isFlow(dialog.context)) return
        dialog.window?.decorView?.let { attach(it) }
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            fun apply() {
                if (sheet.background is FlowSheetDrawable) return
                sheet.backgroundTintList = null
                sheet.background = FlowSheetDrawable(sheet.context)
            }
            apply()
            sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
        }
    }

    fun check(v: View, selected: Boolean, animate: Boolean? = null) {
        if (!ThemeManager.isFlow(v.context)) return
        val existing = v.getTag(R.id.flow_check) as? GlassCheckDrawable
        val drawable = existing ?: GlassCheckDrawable(v.context, ThemeUi.accent(v.context), !selected)
        v.setTag(R.id.flow_check, drawable)
        v.backgroundTintList = null
        v.background = drawable
        (v as? ImageView)?.setColorFilter(Color.WHITE)
        val changed = drawable.selected != selected
        val animated = animate == true || (animate == null && existing != null && changed)
        if (!animated) {
            drawable.setSelectedNow(selected)
            return
        }
        drawable.selected = selected
        if (selected) (v as? ImageView)?.imageAlpha = 0
        pendingToggles.removeAll { it.view === v }
        pendingToggles += Toggle(v, drawable, selected)
        if (!flushPosted) {
            flushPosted = true
            Handler(Looper.getMainLooper()).post { flushToggles() }
        }
    }

    private class Toggle(val view: View, val drawable: GlassCheckDrawable, val selected: Boolean)
    private val pendingToggles = mutableListOf<Toggle>()
    private var flushPosted = false

    private fun flushToggles() {
        flushPosted = false
        val list = pendingToggles.toList()
        pendingToggles.clear()
        val on = list.filter { it.selected }
        val off = list.filter { !it.selected }
        if (on.size == 1 && off.size == 1 && slide(off[0], on[0])) return
        list.forEach { single(it) }
    }

    private fun single(t: Toggle) {
        t.drawable.animateTo(t.selected)
        if (t.selected) {
            bounce(t.view, 300)
            fadeInIcon(t.view, 260)
        }
    }

    private fun slide(from: Toggle, to: Toggle): Boolean {
        val a = from.view; val b = to.view
        if (!a.isAttachedToWindow || !b.isAttachedToWindow || a.width == 0 || b.width == 0) return false
        val root = a.rootView as? ViewGroup ?: return false
        if (b.rootView !== root) return false
        val la = IntArray(2); val lb = IntArray(2); val lr = IntArray(2)
        a.getLocationInWindow(la); b.getLocationInWindow(lb); root.getLocationInWindow(lr)
        val fx = la[0] - lr[0] + a.width / 2f; val fy = la[1] - lr[1] + a.height / 2f
        val tx = lb[0] - lr[0] + b.width / 2f; val ty = lb[1] - lr[1] + b.height / 2f
        val r = to.drawable.beadRadius().takeIf { it > 0f } ?: return false
        val density = a.resources.displayMetrics.density
        val dist = kotlin.math.hypot(tx - fx, ty - fy)
        val bead = LiquidBeadDrawable(density, to.drawable.accent, fx, fy, tx, ty, r)
        bead.setBounds(0, 0, root.width, root.height)
        root.overlay.add(bead)
        from.drawable.showEmpty()
        to.drawable.showEmpty()
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (300 + dist / density * 0.9f).toLong().coerceIn(320L, 620L)
            interpolator = android.view.animation.PathInterpolator(0.45f, 0f, 0.2f, 1f)
            addUpdateListener { bead.progress = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    root.overlay.remove(bead)
                    if (to.drawable.selected) {
                        to.drawable.land()
                        bounce(b, 0)
                        fadeInIcon(b, 0)
                    }
                }
            })
            start()
        }
        return true
    }

    private fun bounce(v: View, delay: Long) {
        v.animate().cancel()
        v.animate().scaleX(0.84f).scaleY(0.84f).setStartDelay(delay).setDuration(90).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(380)
                .setInterpolator(android.view.animation.OvershootInterpolator(3.2f)).start()
        }.start()
    }

    private fun fadeInIcon(v: View, delay: Long) {
        val iv = v as? ImageView ?: return
        iv.imageAlpha = 0
        android.animation.ValueAnimator.ofInt(0, 255).apply {
            duration = 200; startDelay = delay
            addUpdateListener { iv.imageAlpha = it.animatedValue as Int }
            start()
        }
    }

    private fun attach(root: View) {
        if (root.getTag(R.id.flow_glass_seen) == true) return
        root.setTag(R.id.flow_glass_seen, true)
        var pending = true
        val layout = ViewTreeObserver.OnGlobalLayoutListener { pending = true }
        val scroll = ViewTreeObserver.OnScrollChangedListener { pending = true }
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (pending && ThemeManager.isFlow(root.context)) { pending = false; walk(root) }
            true
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(layout)
        root.viewTreeObserver.addOnScrollChangedListener(scroll)
        root.viewTreeObserver.addOnPreDrawListener(preDraw)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(layout)
                v.viewTreeObserver.removeOnScrollChangedListener(scroll)
                v.viewTreeObserver.removeOnPreDrawListener(preDraw)
                v.setTag(R.id.flow_glass_seen, null)
            }
        })
        root.invalidate()
    }

    private fun walk(v: View) {
        if (v.getTag(R.id.flow_glass_keep) == true) return
        if (v.id == R.id.chat_recycler) return
        when (v) {
            is MaterialSwitch -> styleSwitch(v)
            is FloatingActionButton -> styleFab(v)
            else -> convertBackground(v)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    private fun convertBackground(v: View) {
        val raw = v.background ?: return
        if (raw is GlassDrawable) return
        val bg = (raw as? GradientDrawable)
            ?: (raw as? android.graphics.drawable.RippleDrawable)?.takeIf { it.numberOfLayers > 0 }
                ?.getDrawable(0) as? GradientDrawable
            ?: return
        if (v.getTag(R.id.flow_glass_seen) === raw) return
        v.setTag(R.id.flow_glass_seen, raw)

        val color = v.backgroundTintList?.defaultColor
            ?: bg.color?.defaultColor
            ?: bg.colors?.takeIf { it.isNotEmpty() }?.let { LockPalette.blend(it.first(), it.last(), 0.5f) }
            ?: return

        val icon = hasIcon(v)
        val interactive = v.isClickable || (icon && clickableAncestor(v, 2))
        val oval = bg.shape == GradientDrawable.OVAL

        val tint: Int? = when {
            Color.alpha(color) >= 0xD0 && isVivid(color) && interactive -> color
            ThemeUi.isGlassColor(color) && icon && oval && interactive -> null
            ThemeUi.isGlassColor(color) && icon && v.isClickable -> null
            Color.alpha(color) in 0x10..0x70 && isVivid(color or 0xFF000000.toInt()) && icon && v.isClickable -> null
            else -> return
        }
        val radii = try { bg.cornerRadii } catch (_: Exception) { null }
        v.backgroundTintList = null
        v.background = GlassDrawable(v.resources.displayMetrics.density, tint, oval, bg.cornerRadius, radii)
        v.elevation = 0f
        v.setTag(R.id.flow_glass_seen, v.background)
        if (v.isClickable) pressable(v)
    }

    fun glass(v: View, radiusDp: Float, oval: Boolean = false, tint: Int? = null, press: Boolean = true) {
        if (!ThemeManager.isFlow(v.context)) return
        val d = v.resources.displayMetrics.density
        v.backgroundTintList = null
        v.background = GlassDrawable(d, tint, oval, radiusDp * d, null)
        v.elevation = 0f
        v.setTag(R.id.flow_glass_seen, v.background)
        if (press && v.isClickable) pressable(v)
    }

    fun bar(v: View, radiusDp: Float, hMarginDp: Float = 8f, vMarginDp: Float = 4f) {
        if (!ThemeManager.isFlow(v.context)) return
        glass(v, radiusDp, press = false)
        val d = v.resources.displayMetrics.density
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = (hMarginDp * d).toInt(); it.marginEnd = (hMarginDp * d).toInt()
            it.topMargin = (vMarginDp * d).toInt(); it.bottomMargin = (vMarginDp * d).toInt()
            v.layoutParams = it
        }
    }

    fun button(b: android.widget.Button, radiusDp: Float = 24f, tint: Int? = null) {
        if (!ThemeManager.isFlow(b.context)) return
        val d = b.resources.displayMetrics.density
        (b as? com.google.android.material.button.MaterialButton)?.let { it.insetTop = 0; it.insetBottom = 0 }
        b.background = GlassDrawable(d, tint ?: ThemeUi.accent(b.context), false, radiusDp * d, null)
        b.backgroundTintList = null
        b.stateListAnimator = null
        b.elevation = 0f
        b.setTextColor(Color.WHITE)
        b.setTag(R.id.flow_glass_seen, b.background)
        pressable(b)
    }

    fun pressable(v: View, scale: Float = 0.93f) {
        if (v.stateListAnimator != null) return
        fun set(to: Float, ms: Long, interp: android.animation.TimeInterpolator) =
            android.animation.AnimatorSet().apply {
                playTogether(
                    android.animation.ObjectAnimator.ofFloat(v, View.SCALE_X, to),
                    android.animation.ObjectAnimator.ofFloat(v, View.SCALE_Y, to))
                duration = ms
                interpolator = interp
            }
        v.stateListAnimator = android.animation.StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled),
                set(scale, 90, android.view.animation.DecelerateInterpolator()))
            addState(intArrayOf(), set(1f, 340, android.view.animation.OvershootInterpolator(2.4f)))
        }
    }

    fun bubble(ctx: android.content.Context, mine: Boolean): Drawable {
        val d = ctx.resources.displayMetrics.density
        val big = 16 * d; val small = 4 * d
        val radii = if (mine) floatArrayOf(big, big, big, big, small, small, big, big)
            else floatArrayOf(small, small, big, big, big, big, big, big)
        return GlassBubbleDrawable(d, if (mine) ThemeUi.accent(ctx) else null, radii)
    }

    private fun isVivid(c: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        return hsv[1] >= 0.28f && hsv[2] >= 0.38f
    }

    private fun hasIcon(v: View): Boolean {
        fun isIcon(iv: View) = iv is ImageView && iv.drawable.let { it is VectorDrawable || it is VectorDrawableCompat }
        if (isIcon(v)) return true
        if (v is ViewGroup && v.childCount in 1..3) {
            for (i in 0 until v.childCount) if (isIcon(v.getChildAt(i))) return true
        }
        return false
    }

    private fun clickableAncestor(v: View, levels: Int): Boolean {
        var p = v.parent as? View
        repeat(levels) {
            val cur = p ?: return false
            if (cur.isClickable) return true
            p = cur.parent as? View
        }
        return false
    }

    private fun styleSwitch(s: MaterialSwitch) {
        if (s.getTag(R.id.flow_glass_seen) == true) return
        s.setTag(R.id.flow_glass_seen, true)
        s.thumbTintList = null
        s.trackTintList = null
        s.trackDecorationTintList = null
        s.thumbIconDrawable = null
        s.trackDecorationDrawable = null
        s.trackDrawable = GlassSwitchTrack(s.context, ThemeUi.accent(s.context))
        s.thumbDrawable = GlassSwitchThumb(s.context)
        (s.parent as? ViewGroup)?.clipChildren = false
        s.refreshDrawableState()
    }

    private fun styleFab(f: FloatingActionButton) {
        if (f.getTag(R.id.flow_glass_seen) == true) return
        f.setTag(R.id.flow_glass_seen, true)
        val accent = f.backgroundTintList?.defaultColor ?: ThemeUi.accent(f.context)
        f.backgroundTintList = ColorStateList.valueOf(
            LockPalette.blend((accent and 0x00FFFFFF) or 0x66000000, 0x33FFFFFF, 0.35f))
        f.imageTintList = ColorStateList.valueOf(Color.WHITE)
        f.compatElevation = 0f
        f.compatPressedTranslationZ = 0f
        f.post {
            val r = f.shapeAppearanceModel.topLeftCornerSize.getCornerSize(RectF(0f, 0f, f.width.toFloat(), f.height.toFloat()))
            f.foreground = GlassDrawable(f.resources.displayMetrics.density, null, false, r, null, fillOnly = false, rimOnly = true)
        }
    }
}

class GlassDrawable(
    private val density: Float,
    private val tint: Int?,
    private val oval: Boolean,
    private val radius: Float,
    private val radii: FloatArray?,
    @Suppress("unused") private val fillOnly: Boolean = false,
    private val rimOnly: Boolean = false,
    private val emphasis: Boolean = false
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = (if (emphasis) 2.5f else 1f) * density
    }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var pressed = false

    private fun buildPath(inset: Float) {
        path.reset()
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        when {
            oval -> path.addOval(rect, Path.Direction.CW)
            radii != null -> path.addRoundRect(rect, radii, Path.Direction.CW)
            else -> path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val h = bounds.height().toFloat()
        if (!rimOnly) {
            buildPath(0f)
            fill.shader = null
            fill.color = if (tint != null) {
                val a = if (pressed) 0x70 else if (emphasis) 0x8C else 0x52
                (tint and 0x00FFFFFF) or (a shl 24)
            } else if (pressed) 0x38FFFFFF else 0x1FFFFFFF
            canvas.drawPath(path, fill)
            if (tint != null) { fill.color = if (pressed) 0x24FFFFFF else 0x14FFFFFF; canvas.drawPath(path, fill) }
        }
        buildPath(0f)
        shine.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.top + h * 0.55f,
            0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.top + h * 0.55f, shine)
        canvas.restore()
        val half = rim.strokeWidth / 2f
        buildPath(half)
        val top = when {
            emphasis -> 0xE6FFFFFF.toInt()
            tint != null -> LockPalette.blend(0x80FFFFFF.toInt(), (tint and 0x00FFFFFF) or 0x80000000.toInt(), 0.35f)
            else -> 0x73FFFFFF
        }
        val bottom = if (emphasis) 0x66FFFFFF else 0x14FFFFFF
        rim.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawPath(path, rim)
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_pressed)
        if (now == pressed) return false
        pressed = now
        invalidateSelf()
        return true
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        when {
            oval -> outline.setOval(b)
            radii != null -> outline.setRoundRect(b, radii.firstOrNull() ?: 0f)
            else -> outline.setRoundRect(b, radius)
        }
        outline.alpha = 0f
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class GlassBubbleDrawable(
    private val density: Float,
    private val tint: Int?,
    private val radii: FloatArray
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private val path = Path()
    private val rect = RectF()
    private var pressed = false

    private fun build(inset: Float) {
        path.reset()
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        val r = if (inset == 0f) radii else FloatArray(8) { (radii[it] - inset).coerceAtLeast(0f) }
        path.addRoundRect(rect, r, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val top = bounds.top.toFloat(); val bottom = bounds.bottom.toFloat()
        build(0f)
        fill.shader = if (tint != null) {
            val rgb = tint and 0x00FFFFFF
            val deep = LockPalette.blend(tint, 0xFF000000.toInt(), 0.25f) and 0x00FFFFFF
            LinearGradient(0f, top, 0f, bottom,
                rgb or ((if (pressed) 0xC4 else 0xA8) shl 24), deep or ((if (pressed) 0xAA else 0x8C) shl 24),
                Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, top, 0f, bottom,
                if (pressed) 0x3DFFFFFF else 0x2BFFFFFF, if (pressed) 0x26FFFFFF else 0x14FFFFFF,
                Shader.TileMode.CLAMP)
        }
        canvas.drawPath(path, fill)

        val shineH = minOf(bounds.height() * 0.5f, 28f * density)
        shine.shader = LinearGradient(0f, top, 0f, top + shineH,
            if (tint != null) 0x33FFFFFF else 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(bounds.left.toFloat(), top, bounds.right.toFloat(), top + shineH, shine)
        canvas.restore()

        val half = rim.strokeWidth / 2f
        build(half)
        rim.shader = LinearGradient(0f, top, 0f, bottom,
            if (tint != null) 0x8CFFFFFF.toInt() else 0x66FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawPath(path, rim)
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_pressed)
        if (now == pressed) return false
        pressed = now
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
