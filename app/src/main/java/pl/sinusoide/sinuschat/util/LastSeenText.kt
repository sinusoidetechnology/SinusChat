package pl.sinusoide.sinuschat.util

import android.content.Context
import android.view.View
import android.widget.TextView
import pl.sinusoide.sinuschat.R

object LastSeenText {

    fun ago(context: Context, ms: Long, short: Boolean = false): String {
        val r = context.resources
        val diff = ServerTime.ageMs(ms)
        if (diff < 60_000) return context.getString(R.string.just_now)
        val minutes = (diff / 60_000).toInt()
        if (minutes < 60) return if (short) context.getString(R.string.ago_min_short, minutes)
            else r.getQuantityString(R.plurals.minutes_ago, minutes, minutes)
        val hours = (diff / 3_600_000).toInt()
        if (hours < 24) return if (short) context.getString(R.string.ago_hour_short, hours)
            else r.getQuantityString(R.plurals.hours_ago, hours, hours)
        val days = (diff / 86_400_000L).toInt()
        if (days < 30) return r.getQuantityString(R.plurals.days_ago, days, days)
        val months = days / 30
        if (months < 12) return if (short) context.getString(R.string.ago_month_short, months)
            else r.getQuantityString(R.plurals.months_ago, months, months)
        val years = (days / 365).coerceAtLeast(1)
        return r.getQuantityString(R.plurals.years_ago, years, years)
    }

    fun options(context: Context, ms: Long): List<String> {
        val prefix = context.getString(R.string.last_seen_prefix).trim()
        val full = ago(context, ms)
        val short = ago(context, ms, short = true)
        val all = listOf("$prefix $full", "$prefix $short", context.getString(R.string.last_seen_short, short), short).distinct()
        return if (context.resources.configuration.fontScale > 1f && all.size > 1) all.drop(1) else all
    }

    fun show(view: TextView, ms: Long) {
        val options = options(view.context, ms)
        view.setTag(R.id.last_seen_options, options)
        fit(view, options)
        if (view.width > 0 && !view.isLayoutRequested) return
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this)
                v.post {
                    @Suppress("UNCHECKED_CAST")
                    val latest = view.getTag(R.id.last_seen_options) as? List<String> ?: return@post
                    if (latest.contains(view.text?.toString())) fit(view, latest)
                }
            }
        })
    }

    private fun fit(view: TextView, options: List<String>) {
        val room = view.width - view.totalPaddingLeft - view.totalPaddingRight
        val pick = if (room <= 0) options.first()
            else options.firstOrNull { view.paint.measureText(it) <= room } ?: options.last()
        if (view.text?.toString() != pick) view.text = pick
    }
}
