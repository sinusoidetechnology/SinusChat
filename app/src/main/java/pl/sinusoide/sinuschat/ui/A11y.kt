package pl.sinusoide.sinuschat.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import pl.sinusoide.sinuschat.BuildConfig
import pl.sinusoide.sinuschat.R

object A11y {

    private const val TAG = "A11y"

    @Volatile private var motionAt = 0L
    @Volatile private var motionOff = false

    fun reducedMotion(ctx: Context): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - motionAt > 2000L) {
            motionOff = try {
                Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            } catch (_: Throwable) { false }
            motionAt = now
        }
        return motionOff
    }

    fun enabled(ctx: Context): Boolean {
        val am = ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return false
        return am.isEnabled && am.isTouchExplorationEnabled
    }

    fun announce(activity: Activity, text: CharSequence) {
        if (!enabled(activity)) return
        val root = activity.window?.decorView ?: return
        @Suppress("DEPRECATION")
        root.announceForAccessibility(text)
    }

    fun install(activity: Activity) {
        activity.window?.peekDecorView()?.let { attach(it, activity.javaClass.simpleName) }
    }

    fun dialog(dialog: Dialog) {
        dialog.window?.decorView?.let { attach(it, "Dialog") }
    }

    private fun attach(root: View, owner: String) {
        if (root.getTag(R.id.a11y_root) == true) return
        root.setTag(R.id.a11y_root, true)
        var pending = true
        var last = 0L
        val layout = ViewTreeObserver.OnGlobalLayoutListener { pending = true }
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (pending || now - last > 700L) {
                pending = false
                last = now
                runCatching { walk(root, owner) }
            }
            true
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(layout)
        root.viewTreeObserver.addOnPreDrawListener(preDraw)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(layout)
                v.viewTreeObserver.removeOnPreDrawListener(preDraw)
                v.setTag(R.id.a11y_root, null)
            }
        })
    }

    private fun walk(v: View, owner: String) {
        if (v.visibility != View.VISIBLE) return
        if (BuildConfig.DEBUG) audit(v, owner)
        if (v is TextView && v !is EditText) fixContrast(v, owner)
        if (v is ViewGroup) {
            if (v.isClickable && v.getTag(R.id.a11y_row) == null) toggleRow(v)
            for (i in 0 until v.childCount) walk(v.getChildAt(i), owner)
            return
        }
        if (isOwnDrawing(v) && !v.isClickable && v.contentDescription.isNullOrEmpty() &&
            v.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (BuildConfig.DEBUG && v.isClickable && v.isShown && v.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO &&
            v.contentDescription.isNullOrEmpty() && (v !is TextView || (v.text.isNullOrBlank() && v.hint.isNullOrBlank())) && v.getTag(R.id.a11y_logged) == null) {
            v.setTag(R.id.a11y_logged, true)
            val id = if (v.id != View.NO_ID) runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() else null
            Log.w(TAG, "bez etykiety: $owner ${v.javaClass.name} ${id ?: ""}")
        }
    }

    private fun bgColor(start: View): Int? {
        var v: View? = start
        while (v != null) {
            val d = v.background
            if (d != null) {
                val c = layerColor(d) ?: return null
                if (android.graphics.Color.alpha(c) >= 250) return c
                if (android.graphics.Color.alpha(c) > 8) return null
            }
            v = v.parent as? View
        }
        val page = ThemeUi.attrOrNull(start.context, R.attr.sinuschat_background) ?: return null
        return if (android.graphics.Color.alpha(page) >= 250) page else null
    }

    private fun layerColor(d: android.graphics.drawable.Drawable): Int? = when (d) {
        is android.graphics.drawable.ColorDrawable -> d.color
        is android.graphics.drawable.GradientDrawable -> if (d.colors == null) (d.color?.defaultColor ?: 0) else null
        is com.google.android.material.shape.MaterialShapeDrawable -> d.fillColor?.defaultColor ?: 0
        is android.graphics.drawable.RippleDrawable -> {
            val content = (0 until d.numberOfLayers).filter { d.getId(it) != android.R.id.mask }
            if (content.isEmpty()) 0 else layerColor(d.getDrawable(content.first()))
        }
        is android.graphics.drawable.InsetDrawable -> d.drawable?.let { layerColor(it) } ?: 0
        is android.graphics.drawable.StateListDrawable -> d.current?.let { if (it === d) null else layerColor(it) } ?: 0
        else -> null
    }

    private fun lum(c: Int): Double {
        fun ch(x: Int): Double { val v = x / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(android.graphics.Color.red(c)) + 0.7152 * ch(android.graphics.Color.green(c)) + 0.0722 * ch(android.graphics.Color.blue(c))
    }

    private fun ratio(a: Int, b: Int): Double {
        val l1 = lum(a); val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun blend(fg: Int, bg: Int): Int {
        val a = android.graphics.Color.alpha(fg) / 255.0
        fun m(f: Int, b: Int) = (f * a + b * (1 - a)).toInt()
        return android.graphics.Color.rgb(m(android.graphics.Color.red(fg), android.graphics.Color.red(bg)),
            m(android.graphics.Color.green(fg), android.graphics.Color.green(bg)), m(android.graphics.Color.blue(fg), android.graphics.Color.blue(bg)))
    }

    private fun mix(c: Int, to: Int, t: Double): Int {
        fun m(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return android.graphics.Color.rgb(m(android.graphics.Color.red(c), to), m(android.graphics.Color.green(c), to), m(android.graphics.Color.blue(c), to))
    }

    private fun fixContrast(v: TextView, owner: String) {
        if (v.text.isNullOrBlank() || !v.isEnabled || v.alpha < 0.5f || v.shadowRadius > 0f) return
        val current = v.currentTextColor
        if (v.getTag(R.id.a11y_fixed) == current) return
        val own = lum(current)
        if (own > 0.85 || own < 0.01) return
        val bg = bgColor(v) ?: return
        val fg = blend(current, bg)
        val sp = v.textSize / v.resources.displayMetrics.density
        val large = sp >= 18f || (sp >= 14f && v.typeface?.isBold == true)
        val need = if (large) 3.0 else 4.5
        if (ratio(fg, bg) >= need) return
        val toward = if (lum(bg) > 0.18) 0 else 255
        var t = 0.0
        var out = fg
        while (t < 1.0 && ratio(out, bg) < need) {
            t += 0.05
            out = mix(fg, toward, t)
        }
        if (BuildConfig.DEBUG) Log.w(TAG, "kontrast poprawiony: $owner \"${v.text.toString().take(24)}\" ${Integer.toHexString(fg)} -> ${Integer.toHexString(out)} na ${Integer.toHexString(bg)}")
        v.setTextColor(out)
        v.setTag(R.id.a11y_fixed, out)
    }

    private fun audit(v: View, owner: String) {
        if (!v.isShown || v.getTag(R.id.a11y_audited) != null) return
        val dens = v.resources.displayMetrics.density
        if (v.isClickable && v.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO && v.width > 0 &&
            (v.width < 44 * dens || v.height < 44 * dens)) {
            v.setTag(R.id.a11y_audited, true)
            val id = if (v.id != View.NO_ID) runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: "" else ""
            Log.w(TAG, "maly cel: $owner ${v.javaClass.simpleName} $id ${(v.width / dens).toInt()}x${(v.height / dens).toInt()}dp")
        }
    }

    private fun isOwnDrawing(v: View): Boolean {
        if (v is TextView || v is ImageView) return false
        return v.javaClass.name.startsWith("pl.sinusoide.sinuschat.")
    }

    private fun findToggle(g: ViewGroup, depth: Int): CompoundButton? {
        var found: CompoundButton? = null
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            val hit = when {
                c.visibility != View.VISIBLE -> null
                c is CompoundButton -> c
                c is ViewGroup && depth > 0 && !c.isClickable -> findToggle(c, depth - 1)
                c is EditText -> return null
                else -> null
            }
            if (hit != null) {
                if (found != null) return null
                found = hit
            }
        }
        return found
    }

    private fun toggleRow(row: ViewGroup) {
        val toggle = findToggle(row, 2)
        if (toggle == null) {
            row.setTag(R.id.a11y_row, false)
            return
        }
        row.setTag(R.id.a11y_row, true)
        toggle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val role = if (toggle is CheckBox || toggle.javaClass.name.contains("Check")) CheckBox::class.java.name
            else Switch::class.java.name
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = role
                info.isCheckable = true
                info.isChecked = toggle.isChecked
                info.isEnabled = host.isEnabled && toggle.isEnabled
            }
        }
    }
}
