package pl.sinusoide.sinuschat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.ThemeManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class SlideNavBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    var onTabSelected: ((Int) -> Unit)? = null

    private data class Tab(val iconRes: Int, val label: String)

    private var tabs: List<Tab> = buildList {
        add(Tab(R.drawable.ic_chat, context.getString(R.string.chats_title)))
        add(Tab(R.drawable.ic_call_answer, context.getString(R.string.calls_title)))
        if (pl.sinusoide.sinuschat.util.ExperimentalFeatures.isAiEnabled(context)) {
            add(Tab(R.drawable.ic_sinia, "Sinia"))
        }
        add(Tab(R.drawable.ic_settings, context.getString(R.string.settings)))
    }

    private val density = resources.displayMetrics.density
    private val flow = !isInEditMode && ThemeManager.isFlow(context)
    private val retro = !isInEditMode && ThemeManager.isRetro(context)
    private var colorActive = 0
    private var colorInactive = 0

    private val row = LinearLayout(context)

    private val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capsuleRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capsuleRect = RectF()
    private val pillRect = RectF()
    private val shineRect = RectF()

    private var selectedIndex = 0

    private val cells = mutableListOf<LinearLayout>()
    private val icons = mutableListOf<ImageView>()
    private val labels = mutableListOf<TextView>()
    private val badges = mutableListOf<TextView>()
    private val badgeCounts = mutableListOf<Int>()

    private var cx = 0f
    private var vx = 0f
    private var targetCx = 0f
    private var scale = 1f
    private var vScale = 0f
    private var targetScale = 1f
    private var posStiffness = 420f
    private var posDamping = 0.78f
    private var scaleStiffness = 420f
    private var scaleDamping = 0.65f
    private var lastFrameNanos = 0L
    private var animating = false
    private var initialized = false
    private var edgePull = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val grabDelayMs = 140L
    private var downOnPill = false
    private var dragging = false
    private var downX = 0f
    private var grabOffset = 0f
    private var hoverIndex = 0
    private val grabRunnable = Runnable { startGrab() }

    private val lensShader: Any? = if (flow && Build.VERSION.SDK_INT >= 33) RuntimeShader(LENS_SHADER) else null
    private var lensEffectOn = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false

        val tv = android.util.TypedValue()
        if (flow) {
            background = null
            colorActive = Color.WHITE
            colorInactive = 0x99FFFFFF.toInt()
            val capH = (12 * density).toInt(); val capV = (6 * density).toInt()
            setPadding(capH + (5 * density).toInt(), 0, capH + (5 * density).toInt(), 0)
            row.setPadding(0, capV + (5 * density).toInt(), 0, capV + (5 * density).toInt())
            capsulePaint.color = 0x17FFFFFF
            capsuleRimPaint.strokeWidth = 1f * density
            pillPaint.color = 0x1FFFFFFF
        } else if (retro) {
            background = RetroUi.bottomBar(context)
            colorActive = RetroUi.NAVY
            colorInactive = 0xFF3D5E8C.toInt()
            val p = (3 * density).toInt()
            setPadding(p, (4 * density).toInt(), p, (3 * density).toInt())
        } else {
            colorActive = pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(context, androidx.appcompat.R.attr.colorPrimary) ?: Color.parseColor("#5288C1")
            colorInactive = pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(context, R.attr.sinuschat_text_muted) ?: Color.parseColor("#7A8B9E")
            setPadding((6 * density).toInt(), (7 * density).toInt(), (6 * density).toInt(), (7 * density).toInt())
            pillPaint.color = (colorActive and 0x00FFFFFF) or 0x2A000000
            linePaint.color = pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(context, R.attr.sinuschat_border) ?: 0x1FFFFFFF
            linePaint.strokeWidth = density.coerceAtLeast(1f)
        }
        rimPaint.strokeWidth = 1f * density

        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        row.clipToPadding = false
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        tabs.forEachIndexed { index, tab -> addCell(index, tab) }
        post { applyState(animateIcon = false); snapToSelected(); invalidate() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addCell(index: Int, tab: Tab) {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            contentDescription = tab.label
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if (index == selectedIndex) { if (!retro) bounceIcon(index) } else select(index, notify = true)
            }
            if (retro) {
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    val m = (2 * density).toInt()
                    setMargins(m, 0, m, 0)
                }
                background = RetroUi.button(context)
            } else setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(240)
                            .setInterpolator(OvershootInterpolator(2f)).start()
                }
                false
            }
        }

        val iconSize = (24 * density).toInt()
        val textOnly = tab.iconRes == 0
        val iconFrame = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        val icon = ImageView(context).apply { if (!textOnly) setImageResource(tab.iconRes) }
        iconFrame.addView(icon, LayoutParams(iconSize, iconSize, Gravity.CENTER))

        val badge = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = (17 * density).toInt()
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 9 * density
                setColor(Color.parseColor("#E5484D"))
            }
            visibility = GONE
            translationX = 12 * density
            translationY = -6 * density
        }
        iconFrame.addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, (17 * density).toInt(), Gravity.CENTER))

        val label = TextView(context).apply {
            text = tab.label
            textSize = if (textOnly) 14.5f else 11f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, if (textOnly) (5 * density).toInt() else (4 * density).toInt(), 0,
                if (textOnly) (5 * density).toInt() else 0)
        }
        if (!textOnly) cell.addView(iconFrame, LinearLayout.LayoutParams(iconSize * 2, iconSize))
        cell.addView(label, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(cell)
        cells.add(cell); icons.add(icon); labels.add(label); badges.add(badge); badgeCounts.add(0)
    }

    fun setTabs(items: List<Pair<Int, String>>, selected: Int = 0) {
        removeCallbacks(grabRunnable)
        dragging = false
        tabs = items.map { Tab(it.first, it.second) }
        row.removeAllViews()
        cells.clear(); icons.clear(); labels.clear(); badges.clear(); badgeCounts.clear()
        tabs.forEachIndexed { index, tab -> addCell(index, tab) }
        selectedIndex = selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        hoverIndex = selectedIndex
        initialized = false
        post { applyState(animateIcon = false); snapToSelected(); invalidate() }
    }

    fun setSelected(index: Int) {
        if (index == selectedIndex || dragging) return
        select(index, notify = false)
    }

    fun setBadge(index: Int, count: Int) {
        if (index !in badges.indices) return
        val badge = badges[index]
        val old = badgeCounts[index]
        badgeCounts[index] = count
        if (count <= 0) {
            if (badge.visibility == VISIBLE) {
                badge.animate().scaleX(0f).scaleY(0f).setDuration(160)
                    .withEndAction { badge.visibility = GONE; badge.scaleX = 1f; badge.scaleY = 1f }.start()
            }
            return
        }
        badge.text = if (count > 99) "99+" else count.toString()
        if (badge.visibility != VISIBLE) {
            badge.visibility = VISIBLE
            badge.scaleX = 0f; badge.scaleY = 0f
            badge.animate().scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(OvershootInterpolator(3f)).start()
        } else if (count > old) {
            badge.animate().scaleX(1.25f).scaleY(1.25f).setDuration(110).withEndAction {
                badge.animate().scaleX(1f).scaleY(1f).setDuration(200)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
        }
    }

    private fun select(index: Int, notify: Boolean) {
        selectedIndex = index
        hoverIndex = index
        applyState(animateIcon = true)
        if (retro) {
            if (notify) onTabSelected?.invoke(index)
            return
        }
        if (flow) setPositionSpring(stiffness = 360f, damping = 0.8f)
        else setPositionSpring(stiffness = 520f, damping = 0.86f)
        targetCx = cellCenter(index)
        kick()
        if (notify) onTabSelected?.invoke(index)
    }

    private fun applyState(animateIcon: Boolean) {
        val lit = if (dragging) hoverIndex else selectedIndex
        icons.forEachIndexed { i, iv -> iv.setColorFilter(if (i == lit) colorActive else colorInactive) }
        labels.forEachIndexed { i, tv ->
            val on = i == lit
            tv.setTextColor(if (on) colorActive else colorInactive)
            tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        if (retro) {
            cells.forEachIndexed { i, c -> c.background = if (i == lit) RetroUi.pressed(context) else RetroUi.button(context) }
            return
        }
        if (animateIcon) bounceIcon(selectedIndex)
    }

    private fun bounceIcon(index: Int) {
        val icon = icons.getOrNull(index) ?: return
        if (dragging) return
        icon.animate().cancel()
        icon.scaleX = 0.86f; icon.scaleY = 0.86f
        icon.animate().scaleX(1f).scaleY(1f)
            .setDuration(360).setInterpolator(OvershootInterpolator(2.6f)).start()
    }

    private fun cellWidth(): Float =
        if (cells.isEmpty()) 0f else (width - paddingLeft - paddingRight).toFloat() / cells.size

    private fun baseWidth(): Float = (cellWidth() * 0.78f).coerceIn(56 * density, 92 * density)

    private fun cellCenter(index: Int): Float = paddingLeft + cellWidth() * index + cellWidth() / 2f

    private fun snapToSelected() {
        if (width == 0 || cells.isEmpty()) return
        cx = cellCenter(selectedIndex); targetCx = cx; vx = 0f
        scale = 1f; targetScale = 1f; vScale = 0f
        edgePull = 0f
        initialized = true
        invalidate()
    }

    private fun setPositionSpring(stiffness: Float, damping: Float) {
        posStiffness = stiffness; posDamping = damping
    }

    private fun setScaleSpring(stiffness: Float, damping: Float) {
        scaleStiffness = stiffness; scaleDamping = damping
    }

    private fun kick() {
        if (!animating) {
            animating = true
            lastFrameNanos = 0L
        }
        postInvalidateOnAnimation()
    }

    private fun stepPhysics(): Boolean {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 1f / 30f)
        lastFrameNanos = now
        val steps = 4
        val h = dt / steps
        val cPos = 2f * posDamping * sqrt(posStiffness)
        val cScale = 2f * scaleDamping * sqrt(scaleStiffness)
        repeat(steps) {
            val a = -posStiffness * (cx - targetCx) - cPos * vx
            vx += a * h
            cx += vx * h
            val aS = -scaleStiffness * (scale - targetScale) - cScale * vScale
            vScale += aS * h
            scale += vScale * h
        }
        if (!dragging) edgePull *= (1f - min(1f, dt * 14f))
        val settled = abs(cx - targetCx) < 0.3f && abs(vx) < 4f &&
            abs(scale - targetScale) < 0.002f && abs(vScale) < 0.01f && abs(edgePull) < 0.3f
        if (settled && !dragging) {
            cx = targetCx; vx = 0f; scale = targetScale; vScale = 0f; edgePull = 0f
        }
        return settled
    }

    private fun isOnPill(x: Float): Boolean {
        val half = baseWidth() * scale / 2f + 6 * density
        return abs(x - cx) <= half
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!flow) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downOnPill = cells.isNotEmpty() && isOnPill(ev.x)
                if (downOnPill) postDelayed(grabRunnable, grabDelayMs)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) return true
                if (downOnPill && abs(ev.x - downX) > touchSlop) {
                    removeCallbacks(grabRunnable)
                    startGrab()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(grabRunnable)
                downOnPill = false
                if (dragging) { releaseGrab(); return true }
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> dragTo(ev.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseGrab()
        }
        return true
    }

    private fun startGrab() {
        if (!flow || dragging || !downOnPill || cells.isEmpty()) return
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        cells.forEach { it.isPressed = false; it.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
        grabOffset = downX - cx
        hoverIndex = selectedIndex
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setScaleSpring(stiffness = 520f, damping = 0.58f)
        targetScale = 1.2f
        setPositionSpring(stiffness = 900f, damping = 0.9f)
        applyState(animateIcon = false)
        kick()
    }

    private fun dragTo(x: Float) {
        val half = baseWidth() / 2f
        val minC = paddingLeft + half
        val maxC = width - paddingRight - half
        var c = x - grabOffset
        edgePull = when {
            c < minC -> -(minC - c)
            c > maxC -> c - maxC
            else -> 0f
        }
        if (c < minC) c = minC - (minC - c) * 0.12f
        if (c > maxC) c = maxC + (c - maxC) * 0.12f
        targetCx = c

        val idx = ((c - paddingLeft) / cellWidth()).toInt().coerceIn(0, cells.size - 1)
        if (idx != hoverIndex) {
            hoverIndex = idx
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            applyState(animateIcon = false)
        }
        kick()
    }

    private fun releaseGrab() {
        dragging = false
        downOnPill = false
        parent?.requestDisallowInterceptTouchEvent(false)
        setPositionSpring(stiffness = 300f, damping = 0.72f)
        setScaleSpring(stiffness = 380f, damping = 0.7f)
        targetCx = cellCenter(hoverIndex)
        targetScale = 1f
        val target = hoverIndex
        if (target != selectedIndex) {
            selectedIndex = target
            applyState(animateIcon = false)
            onTabSelected?.invoke(target)
        } else {
            applyState(animateIcon = false)
        }
        kick()
    }

    private var lensTouchedViews = false

    private fun lensStrength(): Float {
        if (!flow) return 0f
        val lift = ((scale - 1f) / 0.2f).coerceIn(0f, 1f)
        val motion = (abs(vx) / 2600f).coerceIn(0f, 0.7f)
        val pull = (abs(edgePull) / (40 * density)).coerceIn(0f, 1f)
        return max(max(lift, motion), pull)
    }

    private fun updateLensEffect(strength: Float) {
        if (Build.VERSION.SDK_INT < 33 || lensShader == null) return
        val shader = lensShader as RuntimeShader
        if (strength < 0.01f) {
            if (lensEffectOn) { row.setRenderEffect(null); lensEffectOn = false }
            return
        }
        shader.setFloatUniform("center", pillRect.centerX() - row.left, pillRect.centerY() - row.top)
        shader.setFloatUniform("halfSize", pillRect.width() / 2f, pillRect.height() / 2f)
        shader.setFloatUniform("radius", pillRect.height() / 2f)
        shader.setFloatUniform("strength", strength)
        row.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        lensEffectOn = true
    }

    private fun applyLensTransforms(strength: Float) {
        if (strength <= 0.001f) {
            if (lensTouchedViews) {
                icons.forEach { it.scaleX = 1f; it.scaleY = 1f }
                labels.forEach { it.scaleX = 1f; it.scaleY = 1f }
                lensTouchedViews = false
            }
            return
        }
        lensTouchedViews = true
        val cw = cellWidth()
        val shaderDoesRefraction = Build.VERSION.SDK_INT >= 33
        icons.forEachIndexed { i, icon ->
            val dist = abs(cellCenter(i) - cx)
            val t = max(0f, 1f - dist / (cw * 0.7f))
            val s = 1f + (if (shaderDoesRefraction) 0.06f else 0.22f) * t * strength
            icon.scaleX = s; icon.scaleY = s
        }
        if (shaderDoesRefraction) return
        val band = 22 * density
        labels.forEachIndexed { i, label ->
            val c = cellCenter(i)
            val left = pillRect.left; val right = pillRect.right
            if (c < left - band || c > right + band) {
                label.scaleX = 1f; label.scaleY = 1f; return@forEachIndexed
            }
            val toLeft = abs(c - left); val toRight = abs(right - c)
            val nearRight = toRight < toLeft
            val prox = (1f - min(toLeft, toRight) / band).coerceIn(0f, 1f)
            val inside = c in left..right
            val mag = if (inside) 0.12f * strength else 0f
            label.pivotX = if (nearRight) label.width.toFloat() else 0f
            label.pivotY = label.height / 2f
            label.scaleX = 1f + mag + 0.45f * prox * strength
            label.scaleY = 1f + mag - 0.08f * prox * strength
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!dragging) snapToSelected()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(grabRunnable)
        animating = false
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (retro || cells.isEmpty() || width == 0) { super.dispatchDraw(canvas); return }
        if (!initialized) snapToSelected()

        if (animating) {
            val settled = stepPhysics()
            if (settled && !dragging) animating = false else postInvalidateOnAnimation()
        }

        if (flow) drawCapsule(canvas)
        else canvas.drawLine(0f, 0f, width.toFloat(), 0f, linePaint)

        val baseTop = (row.top + row.paddingTop).toFloat() + 1 * density
        val baseBottom = (row.bottom - row.paddingBottom).toFloat() - 1 * density
        val baseH = baseBottom - baseTop
        val cy = (baseTop + baseBottom) / 2f

        val stretch = if (flow) min(0.28f, abs(vx) / 5200f) else 0f
        val pull = if (flow) edgePull.coerceIn(-60 * density, 60 * density) * 0.55f else 0f
        val w = baseWidth() * scale * (1f + stretch) + abs(pull)
        val pullSquash = if (flow) min(0.18f, abs(pull) / (160 * density)) else 0f
        val h = min(baseH * scale * (1f - stretch * 0.45f - pullSquash), height - 2 * density)
        val centerX = cx + pull / 2f
        pillRect.set(centerX - w / 2f, cy - h / 2f, centerX + w / 2f, cy + h / 2f)
        val r = h / 2f

        val strength = lensStrength()
        if (flow) {
            val lift = ((scale - 1f) / 0.2f).coerceIn(0f, 1f)
            pillPaint.color = Color.argb((0x1F + 0x10 * lift).toInt(), 255, 255, 255)
        }
        canvas.drawRoundRect(pillRect, r, r, pillPaint)

        if (flow) {
            updateLensEffect(strength)
            applyLensTransforms(strength)
        }

        super.dispatchDraw(canvas)

        if (flow) drawGlass(canvas, r, h)
    }

    private fun drawCapsule(canvas: Canvas) {
        val insetH = 12 * density; val insetV = 6 * density
        capsuleRect.set(insetH, insetV, width - insetH, height - insetV)
        val cr = capsuleRect.height() / 2f
        canvas.drawRoundRect(capsuleRect, cr, cr, capsulePaint)
        capsuleRimPaint.shader = LinearGradient(0f, capsuleRect.top, 0f, capsuleRect.bottom,
            0x4DFFFFFF, 0x0FFFFFFF, Shader.TileMode.CLAMP)
        val half = capsuleRimPaint.strokeWidth / 2f
        shineRect.set(capsuleRect.left + half, capsuleRect.top + half, capsuleRect.right - half, capsuleRect.bottom - half)
        canvas.drawRoundRect(shineRect, cr - half, cr - half, capsuleRimPaint)
    }

    private fun drawGlass(canvas: Canvas, r: Float, h: Float) {
        val lift = ((scale - 1f) / 0.2f).coerceIn(0f, 1f)
        val rimTop = 60 + (70 * lift).toInt()
        rimPaint.shader = LinearGradient(0f, pillRect.top, 0f, pillRect.bottom,
            Color.argb(rimTop, 255, 255, 255), Color.argb(14, 255, 255, 255), Shader.TileMode.CLAMP)
        val inset = rimPaint.strokeWidth / 2f
        shineRect.set(pillRect.left + inset, pillRect.top + inset, pillRect.right - inset, pillRect.bottom - inset)
        canvas.drawRoundRect(shineRect, r - inset, r - inset, rimPaint)

        if (lift > 0.01f) {
            shinePaint.shader = LinearGradient(0f, pillRect.top, 0f, pillRect.top + h * 0.55f,
                Color.argb((40 * lift).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            shineRect.set(pillRect.left + 4 * density, pillRect.top + 1.5f * density,
                pillRect.right - 4 * density, pillRect.top + h * 0.55f)
            canvas.drawRoundRect(shineRect, r * 0.9f, r * 0.9f, shinePaint)
        }
    }

    private companion object {
        const val LENS_SHADER = """
            uniform shader content;
            uniform float2 center;
            uniform float2 halfSize;
            uniform float radius;
            uniform float strength;

            float sdRoundRect(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + float2(r, r);
                return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
            }

            half4 main(float2 coord) {
                float2 d = coord - center;
                float sd = sdRoundRect(d, halfSize, radius);
                if (sd >= 0.0) {
                    return content.eval(coord);
                }
                float band = min(halfSize.x, halfSize.y) * 0.62;
                float t = clamp(-sd / max(band, 1.0), 0.0, 1.0);
                float s = t * t * (3.0 - 2.0 * t);
                float kIn = 1.0 / (1.0 + 0.17 * strength);
                float kRim = kIn - 0.15 * strength;
                float k = mix(kRim, kIn, s);
                float fringe = (1.0 - s) * 0.011 * strength;
                half4 cg = content.eval(center + d * k);
                half4 cr = content.eval(center + d * (k - fringe));
                half4 cb = content.eval(center + d * (k + fringe));
                half a = max(cg.a, max(cr.a, cb.a));
                return half4(cr.r, cg.g, cb.b, a);
            }
        """
    }
}
