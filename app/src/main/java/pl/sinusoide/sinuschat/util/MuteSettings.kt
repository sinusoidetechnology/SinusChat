package pl.sinusoide.sinuschat.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object MuteSettings {

    private const val PREFS = "sinuschat_mute"

    fun setMutedUntil(context: Context, conversationId: String, untilMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("mute_$conversationId", untilMs).apply()
        pl.sinusoide.sinuschat.pclink.PcLinkHub.refreshChats()
    }

    fun getMutedUntil(context: Context, conversationId: String): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            prefs.getLong("mute_$conversationId", 0L)
        } catch (e: Exception) {
            if (prefs.getBoolean("mute_$conversationId", false)) Long.MAX_VALUE else 0L
        }
    }

    fun isMuted(context: Context, conversationId: String): Boolean {
        val until = getMutedUntil(context, conversationId)
        return until == Long.MAX_VALUE || until > System.currentTimeMillis()
    }

    fun changeMessage(context: Context, wasMutedUntil: Long, newUntil: Long): String? {
        val now = System.currentTimeMillis()
        val wasMuted = wasMutedUntil == Long.MAX_VALUE || wasMutedUntil > now
        return when {
            newUntil == 0L || (newUntil != Long.MAX_VALUE && newUntil <= now) ->
                if (wasMuted) context.getString(pl.sinusoide.sinuschat.R.string.mute_turned_off) else null
            newUntil == Long.MAX_VALUE ->
                context.getString(pl.sinusoide.sinuschat.R.string.muted_forever)
            else ->
                context.getString(pl.sinusoide.sinuschat.R.string.muted_for, durationLabel(context, newUntil - now))
        }
    }

    fun durationLabel(context: Context, ms: Long): String {
        val min = Math.round(ms / 60_000.0).coerceAtLeast(1)
        val hours = Math.round(ms / 3_600_000.0)
        val days = Math.round(ms / 86_400_000.0)
        val weeks = ms / (7 * 86_400_000.0)
        val res = context.resources
        return when {
            min < 60 -> res.getQuantityString(pl.sinusoide.sinuschat.R.plurals.duration_minutes, min.toInt(), min)
            hours < 24 -> res.getQuantityString(pl.sinusoide.sinuschat.R.plurals.duration_hours, hours.toInt(), hours)
            Math.abs(weeks - Math.round(weeks)) < 0.02 && Math.round(weeks) >= 1 ->
                res.getQuantityString(pl.sinusoide.sinuschat.R.plurals.duration_weeks,
                    Math.round(weeks).toInt(), Math.round(weeks))
            else -> res.getQuantityString(pl.sinusoide.sinuschat.R.plurals.duration_days, days.toInt(), days)
        }
    }

    fun label(context: Context, untilMs: Long): String {
        val now = System.currentTimeMillis()
        return when {
            untilMs == 0L || untilMs < now -> context.getString(pl.sinusoide.sinuschat.R.string.mute_off)
            untilMs == Long.MAX_VALUE -> context.getString(pl.sinusoide.sinuschat.R.string.mute_status_always)
            else -> {
                val date = Date(untilMs)
                val format = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                context.getString(pl.sinusoide.sinuschat.R.string.mute_status_until, format.format(date))
            }
        }
    }

    fun showCustomMutePicker(context: Context, onPicked: (Long) -> Unit) {
        val units = arrayOf("godziny", "dni", "tygodnie")
        val unitMs = longArrayOf(3600_000L, 86400_000L, 7 * 86400_000L)

        val row = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            val p = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            gravity = android.view.Gravity.CENTER
        }
        val numberPicker = android.widget.NumberPicker(context).apply {
            minValue = 1; maxValue = 365; value = 1
        }
        val unitPicker = android.widget.NumberPicker(context).apply {
            minValue = 0; maxValue = units.size - 1
            displayedValues = units
            value = 1
        }
        val lp = android.widget.LinearLayout.LayoutParams(
            (120 * context.resources.displayMetrics.density).toInt(),
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        row.addView(numberPicker, lp)
        row.addView(unitPicker, lp)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(context, pl.sinusoide.sinuschat.R.style.Theme_SinusChat_Dialog)
            .setTitle(context.getString(pl.sinusoide.sinuschat.R.string.mute_custom))
            .setView(row)
            .setPositiveButton("OK") { _, _ ->
                val duration = numberPicker.value.toLong() * unitMs[unitPicker.value]
                onPicked(System.currentTimeMillis() + duration)
            }
            .setNegativeButton(context.getString(pl.sinusoide.sinuschat.R.string.cancel), null)
            .show()
    }
}
