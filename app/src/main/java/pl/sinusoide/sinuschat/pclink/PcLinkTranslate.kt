package pl.sinusoide.sinuschat.pclink

import android.content.Context
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import pl.sinusoide.sinuschat.link.LinkFrame
import pl.sinusoide.sinuschat.link.TranslateChat
import pl.sinusoide.sinuschat.link.TranslateLang
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.Translations
import java.util.concurrent.ConcurrentHashMap

object PcLinkTranslate {

    private val seen = ConcurrentHashMap<String, MutableSet<String>>()
    private val offered = ConcurrentHashMap<String, String>()

    private fun seenIn(conv: String): MutableSet<String> = seen.getOrPut(conv) { ConcurrentHashMap.newKeySet() }

    fun reset(conv: String) {
        seen.remove(conv)
        offered.remove(conv)
    }

    fun scan(ctx: Context, conv: String, me: String, newestFirst: List<MessageEntity>, offer: (String, String) -> Unit) {
        AppExecutor.main { scanOnMain(ctx.applicationContext, conv, me, newestFirst, offer) }
    }

    private fun scanOnMain(ctx: Context, conv: String, me: String, rows: List<MessageEntity>, offer: (String, String) -> Unit) {
        if (!Translations.enabled(ctx) || Translations.isBlocked(ctx, conv)) return
        val app = Translations.appLang(ctx)
        val peer = Translations.peerLang(ctx, conv)
        val all = peer != null && peer != app &&
            Translations.decision(ctx, conv, peer) == Translations.YES && Translations.canTranslate(ctx, peer, app)
        val done = seenIn(conv)
        var n = 0
        for (m in rows) {
            if (!all && n >= 30) break
            if (m.isSystemMessage || m.senderHash == me || m.senderHash.isEmpty() ||
                m.type != MessageEntity.TYPE_TEXT || m.viewOnce) continue
            n++
            if (m.trOrig != null || m.trLocal != null) {
                val lang = m.trFrom
                if (lang != null && lang != app && done.add(m.id)) {
                    Translations.setPeerLang(ctx, conv, lang)
                    if (m.trOrig != null) consider(ctx, conv, lang, offer) {}
                }
                continue
            }
            if (!done.add(m.id)) continue
            Translations.detectMessage(m) { d -> onIncoming(ctx, conv, m, d, offer) }
        }
    }

    private fun onIncoming(ctx: Context, conv: String, m: MessageEntity, d: Translations.Detected?, offer: (String, String) -> Unit) {
        if (d == null) return
        val app = Translations.appLang(ctx)
        val sure = d.long || (d.confidence >= 0.9f && m.text.count { it.isLetter() } >= 5)
        if (sure) {
            val lang = d.lang?.takeIf { d.confidence >= 0.5f } ?: return
            if (lang == app) return
            Translations.setPeerLang(ctx, conv, lang)
            consider(ctx, conv, lang, offer) { translateIncoming(ctx, m, lang, app) }
            return
        }
        val peer = Translations.peerLang(ctx, conv) ?: return
        if (peer == app || Translations.decision(ctx, conv, peer) != Translations.YES) return
        if (d.lang == app && d.confidence >= 0.5f) return
        if (d.lang != null && d.lang != peer && d.confidence >= 0.8f) return
        if (Translations.canTranslate(ctx, peer, app)) translateIncoming(ctx, m, peer, app)
    }

    private fun consider(ctx: Context, conv: String, lang: String, offer: (String, String) -> Unit, onYes: () -> Unit) {
        when (Translations.decision(ctx, conv, lang)) {
            Translations.YES -> onYes()
            Translations.NO -> Unit
            else -> if (Translations.canTranslate(ctx, lang, Translations.appLang(ctx)) && offered.put(conv, lang) != lang) {
                offer(conv, lang)
            }
        }
    }

    private fun translateIncoming(ctx: Context, m: MessageEntity, from: String, to: String) {
        Translations.translate(ctx, m.text, from, to) { out ->
            if (out == null || Translations.isBlocked(ctx, m.conversationId)) return@translate
            AppExecutor.io {
                SinusChatDatabase.getInstance(ctx).messageDao().setLocalTranslation(m.id, out, from, to)
            }
        }
    }

    fun decide(ctx: Context, conv: String, lang: String, yes: Boolean) {
        if (lang !in Translations.LANGS) return
        Translations.setDecision(ctx, conv, lang, if (yes) Translations.YES else Translations.NO)
        reset(conv)
    }

    class SendOffer(val original: String, val translated: String, val from: String, val to: String)

    fun prepareSend(ctx: Context, conv: String, text: String, done: (SendOffer?) -> Unit) {
        val app = ctx.applicationContext
        AppExecutor.main {
            if (!Translations.enabled(app) || !Translations.askSend(app) || Translations.isBlocked(app, conv) ||
                !Translations.hasLetters(text)) { done(null); return@main }
            val peer = Translations.peerLang(app, conv)
            val mine = Translations.appLang(app)
            if (peer == null || peer == mine || Translations.decision(app, conv, peer) != Translations.YES) { done(null); return@main }
            Translations.detectRaw(text) { d ->
                val inPeer = d?.lang == peer && d.confidence >= (if (d.long) 0.5f else 0.8f)
                val other = d?.lang?.takeIf { it != mine && it != peer && d.long && d.confidence >= 0.8f &&
                    Translations.canTranslate(app, it, peer) }
                val from = other ?: mine
                if (inPeer || !Translations.canTranslate(app, from, peer)) { done(null); return@detectRaw }
                Translations.translate(app, text, from, peer) { out ->
                    done(out?.let { SendOffer(text, it, from, peer) })
                }
            }
        }
    }

    fun info(ctx: Context): LinkFrame.TranslateInfo {
        val names = { conv: String, fallback: String -> ContactCache.getName(ctx, conv)?.takeIf { it.isNotBlank() } ?: fallback }
        val supported = Translations.supported()
        val langs = if (!supported) emptyList() else Translations.choices(ctx)
            .filter { it != Translations.appLang(ctx) }
            .map { TranslateLang(it, Translations.isReady(ctx, it), Translations.mb(Translations.diskBytes(ctx, it))) }
        return LinkFrame.TranslateInfo(
            supported = supported,
            enabled = Translations.enabledSetting(ctx),
            askSend = Translations.askSend(ctx),
            appLang = Translations.appLang(ctx),
            langs = langs,
            blocked = Translations.blockedChats(ctx).map { TranslateChat(it.id, it.name.ifBlank { names(it.id, "") }) },
            decisions = Translations.decisions(ctx).map { (conv, lang, yes) ->
                TranslateChat(conv, chatName(ctx, conv), lang, yes)
            }
        )
    }

    private fun chatName(ctx: Context, conv: String): String {
        ContactCache.getName(ctx, conv)?.takeIf { it.isNotBlank() }?.let { return it }
        val me = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(ctx)
        val other = conv.split('_').takeIf { it.size == 2 && me in it }?.firstOrNull { it != me }
        return other?.let { ContactCache.getName(ctx, it) }.orEmpty()
    }

    fun apply(ctx: Context, f: LinkFrame.TranslateSet) {
        f.enabled?.let { Translations.setEnabled(ctx, it) }
        f.askSend?.let { Translations.setAskSend(ctx, it) }
        f.unblock?.let { conv ->
            AppExecutor.main { Translations.setBlocked(ctx, conv, false) }
            reset(conv)
        }
        val conv = f.conv
        val lang = f.lang
        val yes = f.yes
        if (conv != null && lang != null && yes != null) decide(ctx, conv, lang, yes)
    }
}
