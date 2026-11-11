package pl.sinusoide.sinuschat.util

import android.content.Context

object ChatPins {

    private const val PREFS = "sinuschat_chat_pins"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): Map<String, Long> =
        prefs(ctx).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    fun isPinned(ctx: Context, conversationId: String) = prefs(ctx).contains(conversationId)

    fun setPinned(ctx: Context, conversationId: String, pinned: Boolean) {
        val ed = prefs(ctx).edit()
        if (pinned) ed.putLong(conversationId, System.currentTimeMillis()) else ed.remove(conversationId)
        ed.apply()
        pl.sinusoide.sinuschat.pclink.PcLinkHub.refreshChats()
    }
}
