package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import pl.sinusoide.sinuschat.NotificationSettingsActivity
import pl.sinusoide.sinuschat.util.PrivacySettings
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.UserStatusManager
import pl.sinusoide.sinuschat.util.NotificationPrefs
import pl.sinusoide.sinuschat.util.PopupHelper

class PrivacyOptionsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val dp = resources.displayMetrics.density

    private val cCard = ThemeUi.card(context)
    private val cPrimary = ThemeUi.textPrimary(context)
    private val cSecondary = ThemeUi.textSecondary(context)
    private val cAccent = ThemeUi.accent(context)
    private val cGreen = ContextCompat.getColor(context, R.color.sinuschat_success)
    private val cOrange = 0xFFE67E22.toInt()

    private var discreetSubtitle: TextView? = null
    private var torSwitch: MaterialSwitch? = null
    private var torSubtitle: TextView? = null
    private var qrRow: LinearLayout? = null
    private var emailRow: LinearLayout? = null
    private var routeRow: LinearLayout? = null
    private var routeDivider: View? = null
    private var routeSubtitle: TextView? = null

    var showTorRoute: Boolean = false
        set(value) { field = value; refresh() }

    private val footer: TextView

    init {
        orientation = VERTICAL

        addView(sectionTitle(context.getString(R.string.priv_section_visibility)))
        addView(card().apply {
            addView(switchRow(R.drawable.ic_check_double, cAccent,
                R.string.priv_read_status_title, R.string.priv_read_status_desc,
                PrivacySettings.isReadStatusDisabled(context)) {
                PrivacySettings.setReadStatusDisabled(context, it)
            })
            addView(divider())
            addView(switchRow(R.drawable.ic_edit, cAccent,
                R.string.priv_typing_status_title, R.string.priv_typing_status_desc,
                PrivacySettings.isTypingStatusDisabled(context)) {
                PrivacySettings.setTypingStatusDisabled(context, it)
            })
            addView(divider())
            addView(switchRow(R.drawable.ic_person, cAccent,
                R.string.priv_activity_status_title, R.string.priv_activity_status_desc,
                PrivacySettings.isActivityStatusDisabled(context)) { checked ->
                PrivacySettings.setActivityStatusDisabled(context, checked)
                if (checked) UserStatusManager.goInvisibleNow(context)
                else UserStatusManager.onForeground(context)
            })
        })

        addView(sectionTitle(context.getString(R.string.priv_section_contact)))
        addView(card().apply {
            addView(switchRow(R.drawable.ic_block, 0xFFE5484D.toInt(),
                R.string.priv_blocknew_title, R.string.priv_blocknew_desc,
                PrivacySettings.isBlockNewEnabled(context)) {
                PrivacySettings.setBlockNewEnabled(context, it)
                applyBlockNewLock(animate = true)
            })
        })

        addView(sectionTitle(context.getString(R.string.priv_section_screen)))
        addView(card().apply {
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt())
                background = ContextCompat.getDrawable(context, R.drawable.bg_menu_ripple)
                isClickable = true
                setOnClickListener {
                    context.startActivity(pl.sinusoide.sinuschat.util.SettingsHighlight.intent(
                        context, NotificationSettingsActivity::class.java, R.string.notif_msg_preview))
                }
                addView(iconTile(R.drawable.ic_notifications, cOrange))
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(TextView(context).apply {
                        text = context.getString(R.string.priv_discreet_title)
                        textSize = 16f; setTextColor(cPrimary)
                    })
                    discreetSubtitle = TextView(context).apply {
                        textSize = 13f; setTextColor(cSecondary); setPadding(0, (2 * dp).toInt(), 0, 0)
                    }
                    addView(discreetSubtitle)
                }, LayoutParams(0, -2, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_chevron_right); setColorFilter(cSecondary)
                }, LayoutParams((20 * dp).toInt(), (20 * dp).toInt()))
            })
            addView(divider())
            addView(switchRow(R.drawable.ic_reply, cOrange,
                R.string.priv_notif_reply_title, R.string.priv_notif_reply_desc,
                PrivacySettings.isNotificationReplyEnabled(context)) {
                PrivacySettings.setNotificationReplyEnabled(context, it)
            })
            addView(divider())
            addView(switchRow(R.drawable.ic_chat_dashed, cOrange,
                R.string.priv_hidelast_title, R.string.priv_hidelast_desc,
                PrivacySettings.isHideLastMessageEnabled(context)) {
                PrivacySettings.setHideLastMessageEnabled(context, it)
            })
            addView(divider())
            addView(switchRow(R.drawable.ic_sys_screenshot, cOrange,
                R.string.priv_capture_title, R.string.priv_capture_desc,
                PrivacySettings.isCaptureBlockEnabled(context)) {
                PrivacySettings.setCaptureBlockEnabled(context, it)
            })
            addView(divider())
            addView(switchRow(R.drawable.ic_keyboard, cOrange,
                R.string.priv_incognito_kb_title, R.string.priv_incognito_kb_desc,
                PrivacySettings.isIncognitoKeyboard(context)) {
                PrivacySettings.setIncognitoKeyboard(context, it)
            })
            addView(divider())
            qrRow = switchRow(R.drawable.ic_qr_code, cOrange,
                R.string.priv_qr_title, R.string.priv_qr_desc,
                PrivacySettings.isQrProtectionEnabled(context)) {
                PrivacySettings.setQrProtectionEnabled(context, it)
            }
            addView(qrRow)
        })

        addView(sectionTitle(context.getString(R.string.priv_section_network)))
        addView(card().apply {
            if (!pl.sinusoide.sinuschat.setup.AnonAccount.isCurrent(context)) {
                emailRow = switchRow(R.drawable.ic_mail, cGreen,
                    R.string.priv_emailblock_title, R.string.priv_emailblock_desc,
                    PrivacySettings.isEmailBlockEnabled(context)) {
                    PrivacySettings.setEmailBlockEnabled(context, it)
                    PrivacySettings.syncPrivacyWithServer(context)
                }
                addView(emailRow)
                addView(divider())
            }
            val torRow = switchRow(R.drawable.ic_internet, cGreen,
                R.string.tor_routing, R.string.priv_tor_desc,
                PrivacySettings.isTorEnabled(context)) { toggleTor(it) }
            torSwitch = torRow.findViewWithTag("switch")
            torSubtitle = torRow.findViewWithTag("subtitle")
            addView(torRow)
            routeDivider = divider().also { addView(it) }
            routeRow = navRow(R.drawable.ic_location, cGreen, R.string.tor_route_title) {
                context.startActivity(Intent(context, pl.sinusoide.sinuschat.tor.TorRouteActivity::class.java))
            }.also { addView(it) }
            addView(divider())
            addView(switchRow(R.drawable.ic_call_answer, cGreen,
                R.string.priv_call_relay_title, R.string.priv_call_relay_desc,
                PrivacySettings.isCallRelayChosen(context)) {
                PrivacySettings.setCallRelayEnabled(context, it)
            })
        })

        footer = TextView(context).apply {
            text = context.getString(R.string.priv_footer)
            textSize = 12.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding((12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt(), 0)
        }
        addView(footer)

        refresh()
    }

    fun setFooterVisible(visible: Boolean) {
        footer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun refresh() {
        discreetSubtitle?.text = context.getString(when (NotificationPrefs.preview(context)) {
            NotificationPrefs.Preview.HIDDEN -> R.string.priv_discreet_state_hidden
            NotificationPrefs.Preview.NAME -> R.string.priv_discreet_state_name
            NotificationPrefs.Preview.FULL -> R.string.priv_discreet_state_full
        })
        val tor = PrivacySettings.isTorEnabled(context)
        torSwitch?.let { sw ->
            if (sw.isChecked != tor) { sw.tag = "sync"; sw.isChecked = tor; sw.tag = "switch" }
        }
        torSubtitle?.text = context.getString(when {
            tor && pl.sinusoide.sinuschat.util.SafeMode.isDisabled(context, pl.sinusoide.sinuschat.util.SafeMode.Part.TOR) -> R.string.priv_tor_safe_mode_desc
            tor -> R.string.priv_tor_on_desc
            else -> R.string.priv_tor_desc
        })
        val route = showTorRoute && tor
        routeRow?.visibility = if (route) View.VISIBLE else View.GONE
        routeDivider?.visibility = if (route) View.VISIBLE else View.GONE
        if (route) {
            routeSubtitle?.text = context.getString(R.string.tor_route_sub)
            pl.sinusoide.sinuschat.tor.TorCircuits.route { r ->
                pl.sinusoide.sinuschat.tor.TorRouteActivity.summary(context, r)?.let { routeSubtitle?.text = it }
            }
        }
        applyBlockNewLock(animate = false)
    }

    private fun navRow(icon: Int, iconColor: Int, titleRes: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            background = ContextCompat.getDrawable(context, R.drawable.bg_menu_ripple)
            isClickable = true
            visibility = View.GONE
            setOnClickListener { onClick() }
            addView(iconTile(icon, iconColor))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(TextView(context).apply {
                    text = context.getString(titleRes); textSize = 15.5f; setTextColor(cPrimary)
                })
                routeSubtitle = TextView(context).apply {
                    textSize = 12.5f; setTextColor(cSecondary)
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                }
                addView(routeSubtitle)
            }, LayoutParams(0, -2, 1f).apply { marginEnd = (8 * dp).toInt() })
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right); setColorFilter(cSecondary)
            }, LayoutParams((20 * dp).toInt(), (20 * dp).toInt()))
        }

    private fun applyBlockNewLock(animate: Boolean) {
        val locked = PrivacySettings.isBlockNewEnabled(context)
        lockRow(qrRow, locked, shown = if (locked) false else PrivacySettings.isQrProtectionEnabled(context),
            descRes = if (locked) R.string.priv_locked_blocknew_off else R.string.priv_qr_desc, animate = animate)
        lockRow(emailRow, locked, shown = if (locked) true else PrivacySettings.isEmailBlockEnabled(context),
            descRes = if (locked) R.string.priv_locked_blocknew_on else R.string.priv_emailblock_desc, animate = animate)
    }

    private fun lockRow(row: LinearLayout?, locked: Boolean, shown: Boolean, descRes: Int, animate: Boolean) {
        row ?: return
        val sw = row.findViewWithTag<MaterialSwitch>("switch") ?: return
        if (sw.isChecked != shown) { sw.tag = "sync"; sw.isChecked = shown; sw.tag = "switch" }
        sw.isEnabled = !locked
        row.isClickable = !locked
        row.findViewWithTag<TextView>("subtitle")?.text = context.getString(descRes)
        val alpha = if (locked) 0.45f else 1f
        row.animate().cancel()
        if (animate) row.animate().alpha(alpha).setDuration(220).start() else row.alpha = alpha
    }

    private fun toggleTor(enabled: Boolean) {
        val ctx = context
        if (enabled) {
            pl.sinusoide.sinuschat.util.SafeMode.enable(ctx, pl.sinusoide.sinuschat.util.SafeMode.Part.TOR)
            PrivacySettings.setTorEnabled(ctx, true)
            ctx.startActivity(Intent(ctx, pl.sinusoide.sinuschat.tor.TorStartupActivity::class.java))
            pl.sinusoide.sinuschat.tor.TorManager.start(ctx)
            pl.sinusoide.sinuschat.network.WebSocketManager.disconnect()
            pl.sinusoide.sinuschat.tor.TorManager.onReady {
                post { pl.sinusoide.sinuschat.network.WebSocketManager.connect(ctx) }
            }
        } else {
            PrivacySettings.setTorEnabled(ctx, false)
            pl.sinusoide.sinuschat.tor.TorManager.stop()
            (ctx as? android.app.Activity)?.let { PopupHelper.show(it, ctx.getString(R.string.tor_off)) }
            pl.sinusoide.sinuschat.network.WebSocketManager.disconnect()
            pl.sinusoide.sinuschat.network.WebSocketManager.connect(ctx)
        }
        refresh()
    }

    private fun card() = LinearLayout(context).apply {
        orientation = VERTICAL
        background = ThemeUi.rounded(context, cCard, 18f)
        clipToOutline = true
        layoutParams = LayoutParams(-1, -2)
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(ThemeUi.divider(context))
        layoutParams = LayoutParams(-1, (1 * dp).toInt().coerceAtLeast(1)).apply {
            marginStart = (68 * dp).toInt()
        }
    }

    private fun sectionTitle(text: String) = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 12f; setTextColor(cAccent); typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        setPadding((6 * dp).toInt(), (22 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun iconTile(icon: Int, color: Int) = ImageView(context).apply {
        setImageResource(icon); setColorFilter(color)
        background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor((color and 0x00FFFFFF) or 0x24000000)
        }
        val p = (9 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
            marginEnd = (14 * dp).toInt()
        }
    }

    private fun switchRow(
        icon: Int, iconColor: Int, titleRes: Int, descRes: Int,
        initial: Boolean, onChange: (Boolean) -> Unit
    ): LinearLayout {
        val sw = MaterialSwitch(context).apply {
            isChecked = initial
            tag = "switch"
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, 0xFF8AA0B8.toInt()))
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(cAccent, 0xFF2A3A4A.toInt()))
            setOnCheckedChangeListener { v, checked -> if (v.tag != "sync") onChange(checked) }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt())
            background = ContextCompat.getDrawable(context, R.drawable.bg_menu_ripple)
            isClickable = true
            setOnClickListener { sw.isChecked = !sw.isChecked }
            addView(iconTile(icon, iconColor))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(TextView(context).apply {
                    text = context.getString(titleRes); textSize = 15.5f; setTextColor(cPrimary)
                })
                addView(TextView(context).apply {
                    text = context.getString(descRes); textSize = 12.5f; setTextColor(cSecondary)
                    tag = "subtitle"
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }, LayoutParams(0, -2, 1f).apply { marginEnd = (8 * dp).toInt() })
            addView(sw)
        }
    }
}
