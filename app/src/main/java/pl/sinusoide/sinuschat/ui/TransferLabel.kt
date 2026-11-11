package pl.sinusoide.sinuschat.ui

import android.content.Context
import android.widget.TextView
import pl.sinusoide.sinuschat.util.TransferStats

class TransferLabel(context: Context, private val id: String, private val fallback: String) : TextView(context) {

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (isAttachedToWindow) postDelayed(this, REFRESH_MS)
        }
    }

    init {
        refresh()
    }

    private fun refresh() {
        val shown = TransferStats.label(context, id) ?: fallback
        if (text?.toString() != shown) text = shown
        visibility = if (shown.isEmpty()) GONE else VISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val REFRESH_MS = 500L
    }
}
