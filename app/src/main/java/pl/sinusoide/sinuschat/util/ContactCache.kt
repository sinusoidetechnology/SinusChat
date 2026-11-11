package pl.sinusoide.sinuschat.util

import android.content.Context

object ContactCache {

    private const val PREFS = "sinuschat_contact_cache"

    fun saveName(context: Context, hash: String, name: String) {
        if (name.isEmpty() || hash.isEmpty() || name.startsWith("p1:")) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("n_$hash", name).apply()
    }

    fun getName(context: Context, hash: String): String? =
        getNick(context, hash) ?: realName(context, hash)

    fun realName(context: Context, hash: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("n_$hash", null)

    fun getNick(context: Context, hash: String): String? =
        if (hash.isEmpty()) null
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("nick_$hash", null)?.takeIf { it.isNotBlank() }

    fun setNick(context: Context, hash: String, nick: String?) {
        if (hash.isEmpty()) return
        val clean = nick?.trim()?.take(MAX_NICK)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().also {
            if (clean.isNullOrEmpty()) it.remove("nick_$hash") else it.putString("nick_$hash", clean)
        }.apply()
        context.sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS")
            .setPackage(context.packageName))
        pl.sinusoide.sinuschat.pclink.PcLinkHub.refreshChats()
    }

    fun display(context: Context, hash: String, name: String?): String? =
        getNick(context, hash) ?: name

    const val MAX_NICK = 50

    fun saveAvatarUrl(context: Context, hash: String, url: String?) {
        if (hash.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString("av_$hash", null)
        prefs.edit().also {
            if (!url.isNullOrEmpty()) it.putString("av_$hash", url)
            else it.remove("av_$hash")
        }.apply()
        if (old != url?.takeIf { it.isNotEmpty() }) pl.sinusoide.sinuschat.pclink.PcLinkHub.onAvatarChanged(hash)
    }

    fun storedAvatarUrl(context: Context, hash: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("av_$hash", null)

    fun getAvatarUrl(context: Context, hash: String): String? =
        pl.sinusoide.sinuschat.crypto.ProfileCrypto.displayAvatar(context, hash,
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("av_$hash", null))

    fun bestName(context: Context, hash: String, freshName: String? = null): String {
        val fresh = pl.sinusoide.sinuschat.crypto.ProfileCrypto.openName(context, hash, freshName)
        if (!fresh.isNullOrEmpty() && fresh != "Ładowanie...") {
            saveName(context, hash, fresh)
            return getNick(context, hash) ?: fresh
        }
        return getName(context, hash) ?: "Kontakt"
    }
}
