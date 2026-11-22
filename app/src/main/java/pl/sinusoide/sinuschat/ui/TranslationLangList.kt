package pl.sinusoide.sinuschat.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.NetworkSettings
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.Translations

class TranslationLangList(
    private val activity: Activity,
    private val container: LinearLayout,
    private val cPrimary: Int,
    private val cSecondary: Int,
    private val cAccent: Int,
    private val cGreen: Int,
    private val cRed: Int,
    private val cOrange: Int
) {

    private class LangRow(val view: View, val status: TextView, val state: Int)

    private val rows = LinkedHashMap<String, LangRow>()
    private val dp get() = activity.resources.displayMetrics.density

    private fun rowState(lang: String) = when {
        Translations.isDownloading(lang) -> 1
        Translations.isReady(activity, lang) -> 2
        else -> 0
    }

    private fun statusText(lang: String): String = when (rowState(lang)) {
        1 -> activity.getString(R.string.translate_lang_downloading, ((Translations.progress(lang) ?: 0f) * 100).toInt())
        2 -> activity.getString(R.string.translate_lang_ready, Translations.mb(Translations.diskBytes(activity, lang)))
        else -> Translations.downloadBytes(activity, lang).let { left ->
            if (left == 0L) activity.getString(R.string.translate_lang_free)
            else activity.getString(R.string.translate_lang_missing, Translations.mb(left))
        }
    }

    fun refresh() {
        if (activity.isFinishing || activity.isDestroyed) return
        val langs = Translations.choices(activity)
        if (rows.keys.toList() != langs) {
            container.removeAllViews()
            rows.clear()
            langs.forEachIndexed { i, lang ->
                if (i > 0) container.addView(divider())
                val row = langRow(lang)
                rows[lang] = row
                container.addView(row.view)
            }
            return
        }
        for (lang in langs) {
            val old = rows[lang] ?: continue
            if (old.state == rowState(lang)) {
                val text = statusText(lang)
                if (old.status.text.toString() != text) old.status.text = text
                continue
            }
            val idx = container.indexOfChild(old.view)
            if (idx < 0) continue
            val row = langRow(lang)
            rows[lang] = row
            container.removeViewAt(idx)
            container.addView(row.view, idx)
            row.view.alpha = 0f
            row.view.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun divider() = View(activity).apply {
        setBackgroundColor(ThemeUi.divider(activity))
        layoutParams = LinearLayout.LayoutParams(-1, (1 * dp).toInt().coerceAtLeast(1)).apply {
            marginStart = (68 * dp).toInt()
        }
    }

    private fun langRow(lang: String): LangRow {
        val state = rowState(lang)
        val ready = state == 2
        val busy = state == 1
        val name = Translations.name(activity, lang)
        val color = when {
            ready -> cGreen
            busy -> cAccent
            else -> cSecondary
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }
        row.addView(TextView(activity).apply {
            text = lang.uppercase()
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(if (ready) cGreen else cAccent)
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(((if (ready) cGreen else cAccent) and 0x00FFFFFF) or 0x24000000)
            }
        }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
        val status = TextView(activity).apply {
            text = statusText(lang)
            textSize = 12.5f; setTextColor(color)
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = name
                textSize = 15.5f; setTextColor(cPrimary)
            })
            addView(status)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * dp).toInt() })

        when {
            busy -> row.addView(SineProgressRing(activity).apply {
                this.color = cAccent
                disc = 0
                showCancel = false
                stopColor = cRed
                isClickable = true
                progress = { Translations.progress(lang) ?: 1f }
                contentDescription = activity.getString(R.string.download_stop_desc, name)
                setOnClickListener { confirmStop(lang, name) }
            }, LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply { marginEnd = (4 * dp).toInt() })
            ready -> row.addView(actionPill(R.drawable.ic_delete, activity.getString(R.string.translate_delete), cRed, filled = false) {
                confirmDelete(lang, name)
            })
            else -> row.addView(actionPill(R.drawable.ic_download, activity.getString(R.string.translate_download), cAccent, filled = true) {
                startDownload(lang, name)
            })
        }
        return LangRow(row, status, state)
    }

    private fun actionPill(icon: Int, label: String, color: Int, filled: Boolean, onClick: () -> Unit) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                if (filled) setColor(color)
                else {
                    setColor((color and 0x00FFFFFF) or 0x14000000)
                    setStroke((1 * dp).toInt().coerceAtLeast(1), (color and 0x00FFFFFF) or 0x66000000)
                }
            }
            setPadding((12 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            val fg = if (filled) ThemeUi.onAccent(activity) else color
            addView(ImageView(activity).apply {
                setImageResource(icon); setColorFilter(fg)
            }, LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt()).apply { marginEnd = (6 * dp).toInt() })
            addView(TextView(activity).apply {
                text = label
                textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(fg)
            })
        }

    private fun startDownload(lang: String, name: String) {
        when {
            Translations.downloadBytes(activity, lang) == 0L -> download(lang, name)
            NetworkSettings.connection(activity) == NetworkSettings.Connection.NONE ->
                PopupHelper.showError(activity, activity.getString(R.string.offline_mode_no_connection))
            NetworkSettings.isMetered(activity) -> ConfirmSheet.show(activity,
                icon = R.drawable.ic_download,
                color = cOrange,
                title = activity.getString(R.string.translate_mobile_title),
                message = activity.getString(R.string.translate_mobile_desc, name,
                    Translations.mb(Translations.downloadBytes(activity, lang))),
                confirm = activity.getString(R.string.translate_download),
                onConfirm = { download(lang, name) })
            else -> download(lang, name)
        }
    }

    private fun download(lang: String, name: String) {
        Translations.download(activity, lang) { ok ->
            if (activity.isFinishing || activity.isDestroyed) return@download
            if (ok) PopupHelper.showSuccess(activity, activity.getString(R.string.translate_downloaded, name))
            else if (Translations.wasCancelled(lang)) PopupHelper.show(activity, activity.getString(R.string.download_stopped, name))
            else PopupHelper.showError(activity, activity.getString(R.string.translate_failed))
            refresh()
        }
        refresh()
    }

    private fun confirmStop(lang: String, name: String) {
        ConfirmSheet.show(activity,
            icon = R.drawable.ic_stop,
            color = cRed,
            title = activity.getString(R.string.download_stop_title),
            message = activity.getString(R.string.download_stop_msg, name),
            confirm = activity.getString(R.string.download_stop),
            dismiss = activity.getString(R.string.download_keep),
            onConfirm = { Translations.cancel(lang); refresh() })
    }

    private fun confirmDelete(lang: String, name: String) {
        ConfirmSheet.show(activity,
            icon = R.drawable.ic_delete,
            color = cRed,
            title = activity.getString(R.string.translate_delete_title),
            message = Translations.freedBytes(activity, lang).let { freed ->
                if (freed > 0L) activity.getString(R.string.translate_delete_desc, name, Translations.mb(freed))
                else activity.getString(R.string.translate_delete_shared, name)
            },
            confirm = activity.getString(R.string.translate_delete),
            onConfirm = { Translations.delete(activity, lang) { refresh() } })
    }
}
