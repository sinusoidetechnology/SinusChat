package pl.sinusoide.sinuschat.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.Shapeable
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.ThemeManager

object RetroShape {

    private const val RADIUS_DP = 2f

    fun install(activity: Activity) {
        if (!ThemeManager.isRetro(activity)) return
        activity.window?.peekDecorView()?.let { attach(it) }
    }

    fun dialog(dialog: Dialog) {
        if (!ThemeManager.isRetro(dialog.context)) return
        dialog.window?.decorView?.let { attach(it) }
    }

    private fun attach(root: View) {
        if (root.getTag(R.id.retro_shape_root) == true) return
        root.setTag(R.id.retro_shape_root, true)
        val radius = RADIUS_DP * root.resources.displayMetrics.density
        var pending = true
        val layout = ViewTreeObserver.OnGlobalLayoutListener { pending = true }
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (pending) { pending = false; walk(root, radius) }
            true
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(layout)
        root.viewTreeObserver.addOnPreDrawListener(preDraw)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(layout)
                v.viewTreeObserver.removeOnPreDrawListener(preDraw)
                v.setTag(R.id.retro_shape_root, null)
            }
        })
        root.invalidate()
    }

    private fun isPicture(v: View): Boolean {
        if (v.id == View.NO_ID) return false
        val name = try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { return false }
        return name.contains("avatar") || name.contains("photo")
    }

    private fun walk(v: View, radius: Float) {
        if (!isPicture(v)) {
            val bg = v.background
            if (bg != null && v.getTag(R.id.retro_shape_bg) !== bg) {
                v.setTag(R.id.retro_shape_bg, bg)
                square(bg, radius)
            }
            if ((v is Shapeable || v is NavigationBarView) && v.getTag(R.id.retro_shape_root) != true) {
                v.setTag(R.id.retro_shape_root, true)
                if (v is Shapeable) {
                    try {
                        v.shapeAppearanceModel = v.shapeAppearanceModel.withCornerSize(radius)
                    } catch (_: IllegalStateException) {
                    }
                }
                if (v is NavigationBarView) v.itemActiveIndicatorShapeAppearance =
                    ShapeAppearanceModel.builder().setAllCornerSizes(radius).build()
            }
            if (v is MaterialSwitch && v.getTag(R.id.retro_shape_root) != true) {
                v.setTag(R.id.retro_shape_root, true)
                checkbox(v)
            }
            if (v is MaterialCheckBox && v.getTag(R.id.retro_shape_root) != true) {
                v.setTag(R.id.retro_shape_root, true)
                v.buttonTintList = null
                v.isUseMaterialThemeColors = false
                v.buttonDrawable = RetroCheckDrawable(v.resources.displayMetrics.density)
            }
            if (v.clipToOutline && v.outlineProvider !== ViewOutlineProvider.BACKGROUND &&
                v.outlineProvider !== ViewOutlineProvider.BOUNDS && v.outlineProvider !== ViewOutlineProvider.PADDED_BOUNDS) {
                v.outlineProvider = ViewOutlineProvider.BOUNDS
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), radius)
    }

    private fun checkbox(v: MaterialSwitch) {
        val density = v.resources.displayMetrics.density
        v.trackTintList = null
        v.thumbTintList = null
        v.trackDecorationTintList = null
        v.thumbIconDrawable = null
        v.trackDecorationDrawable = null
        v.thumbDrawable = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setSize((RetroCheckDrawable.SIZE / 2f * density).toInt(), (RetroCheckDrawable.SIZE * density).toInt())
        }
        v.trackDrawable = RetroCheckDrawable(density)
        v.switchMinWidth = 0
        v.showText = false
        v.requestLayout()
        v.invalidate()
    }

    private fun square(d: Drawable, radius: Float) {
        when (d) {
            is GradientDrawable -> if (d.shape != GradientDrawable.OVAL &&
                (d.cornerRadius > radius || d.cornerRadii != null)) {
                d.mutate()
                d.cornerRadius = radius
            }
            is MaterialShapeDrawable -> d.shapeAppearanceModel = d.shapeAppearanceModel.withCornerSize(radius)
            is LayerDrawable -> for (i in 0 until d.numberOfLayers) d.getDrawable(i)?.let { square(it, radius) }
            is DrawableContainer -> (d.constantState as? DrawableContainer.DrawableContainerState)
                ?.children?.forEach { child -> child?.let { square(it, radius) } }
            is DrawableWrapper -> d.drawable?.let { square(it, radius) }
        }
    }
}
