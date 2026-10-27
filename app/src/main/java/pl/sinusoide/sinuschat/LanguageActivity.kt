package pl.sinusoide.sinuschat

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import pl.sinusoide.sinuschat.messenger.MessageListenerService
import pl.sinusoide.sinuschat.ui.startWithFade
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.ThemeManager

class LanguageActivity : AppCompatActivity() {

    private val dp get() = resources.displayMetrics.density

    private var cBg = 0; private var cSurface = 0; private var cCard = 0
    private var cPrimary = 0; private var cSecondary = 0; private var cAccent = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    private fun attr(id: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(this, id) ?: fallback
    }

    private fun flagFor(code: String): String = when (code) {
        "pl" -> "🇵🇱"
        "en" -> "🇬🇧"
        "cs" -> "🇨🇿"
        "uk" -> "🇺🇦"
        "de" -> "🇩🇪"
        LocaleManager.LANG_CHINESE_TRAD -> "🇨🇳"
        "es" -> "🇪🇸"
        "fr" -> "🇫🇷"
        "it" -> "🇮🇹"
        else -> "🏳️"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pl.sinusoide.sinuschat.util.SettingsHighlight.apply(this)

        cBg = attr(R.attr.sinuschat_background, 0xFF0E1621.toInt())
        cSurface = attr(R.attr.sinuschat_surface, 0xFF17212B.toInt())
        cCard = attr(R.attr.sinuschat_surface_elevated, 0xFF1D2A38.toInt())
        cPrimary = attr(R.attr.sinuschat_text_primary, Color.WHITE)
        cSecondary = attr(R.attr.sinuschat_text_secondary, 0xFF7F91A4.toInt())
        cAccent = pl.sinusoide.sinuschat.ui.ThemeUi.accent(this)

        buildUi()
    }

    private fun buildUi() {
        val current = LocaleManager.getCurrent(this)
        val effective = LocaleManager.resolve(current)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * dp).toInt()
            setBackgroundColor(cSurface)
            elevation = 2 * dp
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(cPrimary)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p)
            background = ContextCompat.getDrawable(this@LanguageActivity, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        header.addView(TextView(this).apply {
            text = getString(R.string.app_language); textSize = 20f
            setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * dp).toInt(), 0, 0, 0)
        })
        outer.addView(header)
        ViewCompat.setOnApplyWindowInsetsListener(header) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding((12 * dp).toInt(), bars.top, (12 * dp).toInt(), 0)
            insets
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (28 * dp).toInt())
        }
        val scroll = ScrollView(this).apply { addView(content) }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom); insets
        }

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(cCard, 20f)
            setPadding((20 * dp).toInt(), (22 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            addView(TextView(this@LanguageActivity).apply {
                text = flagFor(effective); textSize = 34f; gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor((cAccent and 0x00FFFFFF) or 0x26000000)
                }
            }, LinearLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt()).apply {
                bottomMargin = (12 * dp).toInt()
            })
            addView(TextView(this@LanguageActivity).apply {
                text = LocaleManager.displayName(effective); textSize = 19f
                setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            })
            addView(TextView(this@LanguageActivity).apply {
                text = getString(if (current == LocaleManager.LANG_SYSTEM) R.string.lang_hero_system
                                 else R.string.lang_hero_desc)
                textSize = 13f; setTextColor(cSecondary); gravity = Gravity.CENTER
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })
        })

        content.addView(sectionTitle(getString(R.string.lang_section_available)))
        content.addView(card().apply {
            val system = LocaleManager.systemLanguage()
            addView(optionRow(
                leading = iconTile(R.drawable.ic_language),
                title = getString(R.string.lang_system),
                subtitle = getString(R.string.lang_system_desc, LocaleManager.displayName(system)),
                selected = current == LocaleManager.LANG_SYSTEM || current == system
            ) { confirmChange(LocaleManager.LANG_SYSTEM) })
            fun sub(native: String, res: Int) =
                getString(res).let { if (it == native) getString(R.string.lang_builtin_status) else it }
            val languages = listOf(
                LocaleManager.LANG_POLISH to R.string.lang_polish,
                LocaleManager.LANG_ENGLISH to R.string.lang_english,
                LocaleManager.LANG_CZECH to R.string.lang_czech,
                LocaleManager.LANG_UKRAINIAN to R.string.lang_ukrainian,
                LocaleManager.LANG_GERMAN to R.string.lang_german,
                LocaleManager.LANG_CHINESE_TRAD to R.string.lang_chinese_trad
            )
            for ((code, res) in languages) {
                if (code == system) continue
                val native = LocaleManager.displayName(code)
                addView(divider())
                addView(optionRow(flagTile(code), native, sub(native, res), current == code) { confirmChange(code) })
            }
        })

        content.addView(TextView(this).apply {
            text = getString(R.string.lang_footer)
            textSize = 12.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding((12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt(), 0)
        })

        setContentView(outer)
    }

    private fun optionRow(
        leading: View, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((14 * dp).toInt(), (13 * dp).toInt(), (14 * dp).toInt(), (13 * dp).toInt())
        background = ContextCompat.getDrawable(this@LanguageActivity, R.drawable.bg_menu_ripple)
        isClickable = true
        setOnClickListener { if (!selected) onClick() }
        addView(leading)
        addView(LinearLayout(this@LanguageActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@LanguageActivity).apply {
                text = title; textSize = 15.5f; setTextColor(cPrimary)
                if (selected) typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@LanguageActivity).apply {
                text = subtitle; textSize = 12.5f; setTextColor(cSecondary)
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * dp).toInt() })
        addView(checkCircle(selected), LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))
    }

    private fun flagTile(code: String) = TextView(this).apply {
        text = flagFor(code); textSize = 20f; gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor((cSecondary and 0x00FFFFFF) or 0x1F000000)
        }
        layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
            marginEnd = (14 * dp).toInt()
        }
    }

    private fun iconTile(icon: Int) = ImageView(this).apply {
        setImageResource(icon); setColorFilter(cAccent)
        background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor((cAccent and 0x00FFFFFF) or 0x24000000)
        }
        val p = (9 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
            marginEnd = (14 * dp).toInt()
        }
    }

    private fun checkCircle(selected: Boolean) = FrameLayout(this).apply {
        addView(ImageView(this@LanguageActivity).apply {
            val p = (4 * dp).toInt(); setPadding(p, p, p, p)
            if (selected) {
                setImageResource(R.drawable.ic_check); setColorFilter(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cAccent) }
            } else {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke((2 * dp).toInt(), (cSecondary and 0x00FFFFFF) or 0x80000000.toInt())
                }
            }
            pl.sinusoide.sinuschat.ui.FlowGlass.check(this, selected)
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun confirmChange(code: String) {
        val target = LocaleManager.resolve(code)
        if (target == LocaleManager.getEffective(this)) {
            LocaleManager.setCurrent(this, code)
            buildUi()
            return
        }

        val t = LocaleManager.localized(this, target)
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
        }
        root.addView(View(this).apply { background = rounded(pl.sinusoide.sinuschat.ui.ThemeUi.handle(context), 3f) },
            LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply {
                bottomMargin = (20 * dp).toInt()
            })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@LanguageActivity).apply {
                text = flagFor(LocaleManager.getEffective(this@LanguageActivity)); textSize = 26f
                gravity = Gravity.CENTER; alpha = 0.6f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor((cSecondary and 0x00FFFFFF) or 0x1F000000)
                }
            }, LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()))
            addView(ImageView(this@LanguageActivity).apply {
                setImageResource(R.drawable.ic_chevron_right); setColorFilter(cSecondary)
            }, LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply {
                marginStart = (10 * dp).toInt(); marginEnd = (10 * dp).toInt()
            })
            addView(TextView(this@LanguageActivity).apply {
                text = flagFor(target); textSize = 30f; gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor((cAccent and 0x00FFFFFF) or 0x33000000)
                }
            }, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()))
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (16 * dp).toInt() })

        root.addView(TextView(this).apply {
            text = t.getString(R.string.lang_change_q, LocaleManager.displayName(target))
            textSize = 20f; setTextColor(cPrimary)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = t.getString(R.string.lang_change_desc)
            textSize = 14f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (22 * dp).toInt())
        })
        root.addView(TextView(this).apply {
            text = t.getString(R.string.lang_change_btn)
            textSize = 16f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = rounded(cAccent, 26f)
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            isClickable = true
            setOnClickListener {
                sheet.dismiss()
                applyLanguage(code)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = t.getString(R.string.cancel)
            textSize = 15f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))
        sheet.setContentView(root)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun applyLanguage(code: String) {
        LocaleManager.setCurrent(this, code)

        try {
            val refreshIntent = Intent(this, MessageListenerService::class.java)
                .setAction(MessageListenerService.ACTION_REFRESH_NOTIF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(refreshIntent)
            else startService(refreshIntent)
        } catch (_: Exception) {}

        startWithFade(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    private fun rounded(color: Int, radiusDp: Float) = pl.sinusoide.sinuschat.ui.ThemeUi.rounded(this, color, radiusDp)

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 18f)
        clipToOutline = true
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(pl.sinusoide.sinuschat.ui.ThemeUi.divider(context))
        layoutParams = LinearLayout.LayoutParams(-1, (1 * dp).toInt().coerceAtLeast(1)).apply {
            marginStart = (68 * dp).toInt()
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f; setTextColor(cAccent); typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        setPadding((6 * dp).toInt(), (22 * dp).toInt(), 0, (8 * dp).toInt())
    }
}
