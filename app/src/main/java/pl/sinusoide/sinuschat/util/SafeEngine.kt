package pl.sinusoide.sinuschat.util

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.ui.ThemeUi

object SafeEngine {

    enum class Risk(val title: Int, val message: Int, val action: Int) {
        OPEN_LINK(R.string.safe_link_title, R.string.safe_link_msg, R.string.safe_link_action),
        LINK_PREVIEW(R.string.safe_preview_title, R.string.safe_preview_msg, R.string.safe_preview_action),
        OPEN_FILE(R.string.safe_open_file_title, R.string.safe_open_file_msg, R.string.safe_open_file_action),
        SAVE_FILE(R.string.safe_save_file_title, R.string.safe_save_file_msg, R.string.safe_save_file_action),
        OPEN_MAP(R.string.safe_map_title, R.string.safe_map_msg, R.string.safe_map_action),
        SEND_LOCATION(R.string.safe_location_title, R.string.safe_location_msg, R.string.safe_location_action),
        SHARE_INVITE(R.string.safe_share_title, R.string.safe_share_msg, R.string.safe_share_action),
        SHARE_GROUP_INVITE(R.string.safe_share_title, R.string.safe_share_group_msg, R.string.safe_share_action),
        CLOUD_BACKUP(R.string.safe_backup_title, R.string.safe_backup_msg, R.string.safe_backup_action),
        TOR_MODEL_DOWNLOAD(R.string.safe_tor_model_title, R.string.safe_tor_model_msg, R.string.safe_tor_model_action),
        TOR_CALL(R.string.safe_tor_call_title, R.string.safe_tor_call_msg, R.string.safe_tor_call_action)
    }

    fun isEnabled(activity: Activity): Boolean = NotificationPrefs.safetyPrompts(activity)

    fun guard(activity: Activity, risk: Risk, subject: String? = null, onCancel: (() -> Unit)? = null, onProceed: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!isEnabled(activity)) { onProceed(); return }
        show(activity, risk, subject.orEmpty(), onCancel, onProceed)
    }

    fun guardTor(activity: Activity, risk: Risk, onCancel: (() -> Unit)? = null, onProceed: () -> Unit) {
        if (!PrivacySettings.isTorEnabled(activity)) { onProceed(); return }
        guard(activity, risk, null, onCancel, onProceed)
    }

    fun openLink(activity: Activity, url: String) {
        val uri = try { Uri.parse(url) } catch (_: Exception) { null } ?: return
        val open = {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                PopupHelper.showError(activity, activity.getString(R.string.link_open_failed))
            }
        }
        if (isExempt(url)) open() else guard(activity, Risk.OPEN_LINK, uri.host ?: url) { open() }
    }

    private val SINUSOIDE_PAGES = mapOf(
        "ko-fi.com" to listOf("/sinusoide"),
        "patreon.com" to listOf("/cw/sinusoidetechnology", "/sinusoidetechnology")
    )

    fun isExempt(url: String): Boolean {
        val uri = try { java.net.URI(url).normalize() } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false
        if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != 80 && uri.port != 443)) return false
        val host = uri.host?.lowercase()?.trimEnd('.')?.removePrefix("www.") ?: return false
        if (host == "sinusoide.pl" || host.endsWith(".sinusoide.pl")) return true
        val path = (uri.path ?: "").lowercase().trimEnd('/')
        return SINUSOIDE_PAGES[host]?.any { path == it || path.startsWith("$it/") } == true
    }

    private fun show(activity: Activity, risk: Risk, subject: String, onCancel: (() -> Unit)?, onProceed: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val accent = ThemeUi.accent(activity)
        val primary = ThemeUi.textPrimary(activity)
        val secondary = ThemeUi.textSecondary(activity)
        val neutral = (primary and 0x00FFFFFF) or 0x1F000000

        val title = activity.getString(risk.title, subject)
        var message = activity.getString(risk.message)
        if (risk == Risk.LINK_PREVIEW && PrivacySettings.isTorEnabled(activity)) {
            message += " " + activity.getString(R.string.safe_preview_tor)
        }
        val whyTitle = activity.getString(R.string.safe_why_title)
        val whyMessage = activity.getString(R.string.safe_why_msg)

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 24 * d
                setColor(ThemeUi.sheet(activity) or 0xFF000000.toInt())
                setStroke(px(1f).coerceAtLeast(1), ThemeUi.border(activity))
            }
            setPadding(px(20f), px(16f), px(20f), px(20f))
        }

        val head = FrameLayout(activity)
        val tile = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(lighten(accent), accent)).apply { cornerRadius = 18 * d }
            elevation = 4 * d
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_privacy_warning)
                setColorFilter(Color.WHITE)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(px(42f), px(42f), Gravity.CENTER))
        }
        head.addView(tile, FrameLayout.LayoutParams(px(74f), px(74f), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = px(12f)
        })
        val help = TextView(activity).apply {
            text = "?"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primary)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(neutral) }
            contentDescription = whyTitle
            isClickable = true
            isFocusable = true
        }
        head.addView(help, FrameLayout.LayoutParams(px(30f), px(30f), Gravity.END or Gravity.TOP))
        card.addView(head, LinearLayout.LayoutParams(-1, -2))

        val titleView = TextView(activity).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primary)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.1f)
        }
        card.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })

        val messageView = TextView(activity).apply {
            text = message
            textSize = 14f
            setTextColor(secondary)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }
        card.addView(messageView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })

        fun button(label: String, filled: Boolean) = TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (filled) ThemeUi.onAccent(activity) else primary)
            background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(if (filled) accent else neutral) }
            isClickable = true
            isFocusable = true
            maxLines = 1
        }
        val cancel = button(activity.getString(R.string.cancel), false)
        val proceed = button(activity.getString(risk.action), true)
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancel, LinearLayout.LayoutParams(0, px(46f), 1f).apply { marginEnd = px(5f) })
            addView(proceed, LinearLayout.LayoutParams(0, px(46f), 1f).apply { marginStart = px(5f) })
        }
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22f) })

        var why = false
        help.setOnClickListener {
            why = !why
            titleView.text = if (why) whyTitle else title
            messageView.text = if (why) whyMessage else message
            help.setTextColor(if (why) ThemeUi.onAccent(activity) else primary)
            (help.background as? GradientDrawable)?.setColor(if (why) accent else neutral)
        }
        var proceeded = false
        cancel.setOnClickListener { dialog.dismiss() }
        proceed.setOnClickListener {
            proceeded = true
            dialog.dismiss()
            onProceed()
        }
        dialog.setOnDismissListener { if (!proceeded) onCancel?.invoke() }

        dialog.setContentView(card)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = minOf(activity.resources.displayMetrics.widthPixels - px(48f), px(360f))
            w.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.55f)
        }
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.alpha = 0f
        dialog.setOnShowListener {
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        try { dialog.show() } catch (_: Exception) {}
    }

    private fun lighten(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1] * 0.75f
        hsv[2] = minOf(1f, hsv[2] * 1.2f)
        return Color.HSVToColor(hsv)
    }
}
