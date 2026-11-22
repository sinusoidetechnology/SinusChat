package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.DynamicColors
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.ThemeManager

class ThemePickerView(
    context: Context,
    private val onSelect: (String) -> Unit
) : LinearLayout(context) {

    private val dp get() = resources.displayMetrics.density

    private data class Palette(
        val background: Int, val surface: Int, val bubbleOther: Int,
        val bubbleMine: Int, val text: Int, val accent: Int,
        val aurora: Boolean = false,
        val glass: Boolean = false
    )

    private fun attr(ctx: Context, id: Int, fallback: Int): Int =
        ThemeUi.attrOrNull(ctx, id) ?: fallback

    init {
        orientation = VERTICAL
        build()
    }

    private fun build() {
        val cCard = attr(context, R.attr.sinuschat_surface_elevated, 0xFF1D2A38.toInt())
        val cPrimary = attr(context, R.attr.sinuschat_text_primary, Color.WHITE)
        val cSecondary = attr(context, R.attr.sinuschat_text_secondary, 0xFF7F91A4.toInt())
        val accent = ThemeUi.accent(context)
        val current = ThemeManager.getCurrent(context)

        addView(TextView(context).apply {
            text = context.getString(R.string.app_theme_title).uppercase()
            textSize = 12f; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            setPadding((6 * dp).toInt(), (18 * dp).toInt(), 0, (10 * dp).toInt())
        })

        val dynamicAvailable = DynamicColors.isDynamicColorAvailable()
        val options = listOf(
            Triple(ThemeManager.THEME_FLOW, R.string.theme_flow_drop, flowPalette()),
            Triple(ThemeManager.THEME_DEFAULT, R.string.current_dark, darkPalette()),
            Triple(ThemeManager.THEME_LIGHT, R.string.light, lightPalette()),
            Triple(ThemeManager.THEME_DYNAMIC, R.string.dynamic_color, dynamicPalette()),
            Triple(ThemeManager.THEME_RETRO, R.string.theme_retro, retroPalette())
        )
        options.chunked(2).forEachIndexed { r, pair ->
            val tiles = LinearLayout(context).apply { orientation = HORIZONTAL }
            pair.forEachIndexed { i, (key, nameRes, palette) ->
                val enabled = key != ThemeManager.THEME_DYNAMIC || dynamicAvailable
                tiles.addView(themeTile(key, context.getString(nameRes), palette, key == current, enabled,
                    cCard, cPrimary, cSecondary, accent),
                    LayoutParams(0, -2, 1f).apply {
                        if (i > 0) marginStart = (12 * dp).toInt()
                    })
            }
            if (pair.size == 1) tiles.addView(View(context), LayoutParams(0, 1, 1f).apply {
                marginStart = (12 * dp).toInt()
            })
            addView(tiles, LayoutParams(-1, -2).apply {
                if (r > 0) topMargin = (12 * dp).toInt()
            })
        }

        val (titleRes, descRes) = when (current) {
            ThemeManager.THEME_FLOW -> R.string.theme_flow_drop to R.string.theme_desc_flow
            ThemeManager.THEME_LIGHT -> R.string.light to R.string.theme_desc_light
            ThemeManager.THEME_DYNAMIC -> R.string.dynamic_color to R.string.theme_desc_dynamic
            ThemeManager.THEME_RETRO -> R.string.theme_retro to R.string.theme_desc_retro
            else -> R.string.current_dark to R.string.theme_desc_dark
        }
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            background = ThemeUi.rounded(context, cCard, 18f)
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            addView(TextView(context).apply {
                text = context.getString(R.string.theme_selected, context.getString(titleRes))
                textSize = 15.5f; setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = context.getString(descRes)
                textSize = 13.5f; setTextColor(cSecondary)
                setLineSpacing(0f, 1.2f)
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })
        }, LayoutParams(-1, -2).apply { topMargin = (18 * dp).toInt() })

        if (!dynamicAvailable) {
            addView(TextView(context).apply {
                text = context.getString(R.string.theme_dynamic_unavailable)
                textSize = 12.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
                setPadding((12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt(), 0)
            })
        }
    }

    private fun themeTile(
        key: String, name: String, p: Palette, selected: Boolean, enabled: Boolean,
        cardColor: Int, textColor: Int, subColor: Int, accent: Int
    ): View {
        val tile = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                setColor(cardColor)
                val idle = if (ThemeManager.isFlow(context)) ThemeUi.GLASS_RIM else 0x00000000
                setStroke(((if (selected) 2 else 1) * dp).toInt(), if (selected) accent else idle)
            }
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            alpha = if (enabled) 1f else 0.45f
            isClickable = enabled
            if (enabled) setOnClickListener { if (!selected) onSelect(key) }
            contentDescription = name
        }

        val frame = FrameLayout(context)
        frame.addView(miniChat(p), FrameLayout.LayoutParams(-1, (168 * dp).toInt()))
        if (selected) {
            frame.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check); setColorFilter(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
                val pd = (4 * dp).toInt(); setPadding(pd, pd, pd, pd)
                FlowGlass.check(this, true)
            }, FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt(), Gravity.TOP or Gravity.END).apply {
                setMargins(0, (6 * dp).toInt(), (6 * dp).toInt(), 0)
            })
        }
        tile.addView(frame, LayoutParams(-1, -2))

        tile.addView(TextView(context).apply {
            text = name; textSize = 14f; setTextColor(if (selected) accent else textColor)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        tile.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (selected) setColor(accent) else { setColor(0x00000000); setStroke((2 * dp).toInt(), subColor) }
            }
            FlowGlass.check(this, selected)
        }, LayoutParams((12 * dp).toInt(), (12 * dp).toInt()).apply { topMargin = (6 * dp).toInt() })
        return tile
    }

    private fun miniChat(p: Palette): View {
        val r = 12 * dp
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            background = if (p.aurora) FlowBackdropDrawable(context)
            else GradientDrawable().apply { cornerRadius = r; setColor(p.background) }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            clipToOutline = true
        }
        root.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (p.glass) background = AeroGlassDrawable(dp) else setBackgroundColor(p.surface)
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.accent) }
            }, LayoutParams((12 * dp).toInt(), (12 * dp).toInt()).apply { marginEnd = (5 * dp).toInt() })
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 3 * dp; setColor((p.text and 0x00FFFFFF) or 0x99000000.toInt())
                }
            }, LayoutParams((34 * dp).toInt(), (5 * dp).toInt()))
        }, LayoutParams(-1, (24 * dp).toInt()))

        val chat = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding((6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
        }
        chat.addView(bubble(p.bubbleOther, 0.62f, 18, Gravity.START))
        chat.addView(bubble(p.bubbleMine, 0.55f, 18, Gravity.END))
        chat.addView(bubble(p.bubbleOther, 0.42f, 14, Gravity.START))
        chat.addView(bubble(p.bubbleMine, 0.35f, 14, Gravity.END))
        root.addView(chat, LayoutParams(-1, 0, 1f))

        root.addView(FrameLayout(context).apply {
            if (p.glass) background = RetroUi.bottomBar(context) else setBackgroundColor(p.surface)
            setPadding((6 * dp).toInt(), (5 * dp).toInt(), (6 * dp).toInt(), (5 * dp).toInt())
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 7 * dp; setColor((p.text and 0x00FFFFFF) or 0x1F000000)
                }
            }, FrameLayout.LayoutParams(-1, -1))
        }, LayoutParams(-1, (22 * dp).toInt()))
        return root
    }

    private fun bubble(color: Int, widthFraction: Float, heightDp: Int, gravity: Int): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        val b = View(context).apply {
            background = GradientDrawable().apply { cornerRadius = 7 * dp; setColor(color) }
        }
        val bubbleLp = LayoutParams(0, (heightDp * dp).toInt(), widthFraction)
        val spaceLp = LayoutParams(0, 1, 1f - widthFraction)
        if (gravity == Gravity.END) {
            row.addView(View(context), spaceLp); row.addView(b, bubbleLp)
        } else {
            row.addView(b, bubbleLp); row.addView(View(context), spaceLp)
        }
        row.layoutParams = LayoutParams(-1, -2).apply { bottomMargin = (5 * dp).toInt() }
        return row
    }

    private fun flowPalette() = Palette(
        background = FlowBackdropDrawable.INK, surface = 0x1AFFFFFF,
        bubbleOther = 0x26FFFFFF, bubbleMine = 0x735288C1,
        text = Color.WHITE, accent = 0xFF7AA6D9.toInt(), aurora = true)

    private fun darkPalette() = Palette(
        background = 0xFF0E1621.toInt(), surface = 0xFF17212B.toInt(),
        bubbleOther = 0xFF182533.toInt(), bubbleMine = ContextCompat.getColor(context, R.color.sinuschat_bubble_mine),
        text = Color.WHITE, accent = 0xFF5288C1.toInt())

    private fun lightPalette() = Palette(
        background = 0xFFD6DDE3.toInt(), surface = Color.WHITE,
        bubbleOther = Color.WHITE, bubbleMine = ContextCompat.getColor(context, R.color.sinuschat_bubble_mine),
        text = 0xFF0E1621.toInt(), accent = 0xFF5288C1.toInt())

    private fun retroPalette() = Palette(
        background = RetroUi.CHROME, surface = RetroUi.GLASS_MID,
        bubbleOther = RetroUi.ROW_OTHER, bubbleMine = RetroUi.ROW_MINE,
        text = RetroUi.NAVY, accent = RetroUi.ACCENT, glass = true)

    private fun dynamicPalette(): Palette {
        val base = ContextThemeWrapper(context, R.style.Theme_SinusChat_Dynamic)
        val ctx = DynamicColors.wrapContextIfAvailable(base)
        return Palette(
            background = attr(ctx, R.attr.sinuschat_background, 0xFF1B1B1F.toInt()),
            surface = attr(ctx, R.attr.sinuschat_surface, 0xFF2A2A2F.toInt()),
            bubbleOther = attr(ctx, R.attr.sinuschat_bubble_other, 0xFF3A3A45.toInt()),
            bubbleMine = ContextCompat.getColor(context, R.color.sinuschat_bubble_mine),
            text = attr(ctx, R.attr.sinuschat_text_primary, Color.WHITE),
            accent = attr(ctx, androidx.appcompat.R.attr.colorPrimary, 0xFF8888FF.toInt()))
    }
}
