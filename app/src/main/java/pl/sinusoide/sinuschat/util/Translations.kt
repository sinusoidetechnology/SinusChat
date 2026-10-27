package pl.sinusoide.sinuschat.util

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.duckduckgo.translatekit.ModelSpec
import com.duckduckgo.translatekit.TranslateKit
import com.duckduckgo.translatekit.TranslationModel
import org.json.JSONObject
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.data.MessageEntity
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object Translations {

    private const val TAG = "Translations"

    val LANGS = listOf("pl", "en", "cs", "uk", "de", "zh")
    const val YES = "yes"
    const val NO = "no"
    private const val MAX_TEXT = 20_000
    private const val MAX_LOADED = 2

    private const val PREFS = "sinuschat_translate"
    private const val K_ENABLED = "enabled"
    private const val K_ASK_SEND = "ask_send"
    private const val K_LANGS = "langs"
    private const val K_BLOCKED = "blocked_chats"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "translate").apply { isDaemon = true } }
    private val fetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "translate-fetch").apply { isDaemon = true } }
    private val downloading = ConcurrentHashMap<String, Float>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val detected = LruCache<String, Detected>(600)
    private val readyCache = ConcurrentHashMap<String, Boolean>()
    @Volatile private var engine = 0

    private val loaded = object : LinkedHashMap<String, TranslationModel>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslationModel>?): Boolean {
            if (size <= MAX_LOADED) return false
            try { eldest?.value?.close() } catch (_: Throwable) {}
            return true
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun supported(): Boolean =
        Build.VERSION.SDK_INT >= 28 && engine >= 0 &&
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    fun enabled(ctx: Context) = supported() && prefs(ctx).getBoolean(K_ENABLED, true)
    fun enabledSetting(ctx: Context) = prefs(ctx).getBoolean(K_ENABLED, true)
    fun setEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(K_ENABLED, v).apply() }

    fun askSend(ctx: Context) = prefs(ctx).getBoolean(K_ASK_SEND, true)
    fun setAskSend(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(K_ASK_SEND, v).apply() }

    fun decision(ctx: Context, conv: String, lang: String): String? = prefs(ctx).getString("d_${conv}_$lang", null)
    fun setDecision(ctx: Context, conv: String, lang: String, v: String) {
        prefs(ctx).edit().putString("d_${conv}_$lang", v).apply()
    }

    class BlockedChat(val id: String, val name: String, val other: String, val group: Boolean)

    @Volatile private var blockedCache: Map<String, BlockedChat>? = null

    private fun loadBlocked(ctx: Context): Map<String, BlockedChat> {
        blockedCache?.let { return it }
        val out = LinkedHashMap<String, BlockedChat>()
        try {
            val o = JSONObject(prefs(ctx).getString(K_BLOCKED, null) ?: "{}")
            for (k in o.keys()) {
                val e = o.optJSONObject(k) ?: continue
                out[k] = BlockedChat(k, e.optString("n"), e.optString("o"), e.optBoolean("g"))
            }
        } catch (_: Exception) {}
        blockedCache = out
        return out
    }

    fun isBlocked(ctx: Context, conv: String): Boolean = conv.isNotEmpty() && loadBlocked(ctx).containsKey(conv)

    fun blockedChats(ctx: Context): List<BlockedChat> = loadBlocked(ctx).values.toList().asReversed()

    fun setBlocked(ctx: Context, conv: String, blocked: Boolean, name: String = "", other: String = "", group: Boolean = false) {
        if (conv.isEmpty()) return
        val map = LinkedHashMap(loadBlocked(ctx))
        if (blocked) map[conv] = BlockedChat(conv, name, other, group) else map.remove(conv)
        val o = JSONObject()
        for ((k, b) in map) o.put(k, JSONObject().put("n", b.name).put("o", b.other).put("g", b.group))
        prefs(ctx).edit().putString(K_BLOCKED, o.toString()).apply()
        blockedCache = map
        notifyChanged()
    }

    fun peerLang(ctx: Context, conv: String): String? = prefs(ctx).getString("p_$conv", null)?.takeIf { it in LANGS }
    fun setPeerLang(ctx: Context, conv: String, lang: String) {
        if (lang !in LANGS || peerLang(ctx, conv) == lang) return
        prefs(ctx).edit().putString("p_$conv", lang).apply()
    }

    fun resetDecisions(ctx: Context) {
        val p = prefs(ctx)
        val ed = p.edit()
        p.all.keys.filter { it.startsWith("d_") || it.startsWith("p_") }.forEach { ed.remove(it) }
        ed.apply()
    }

    fun appLang(ctx: Context): String = LocaleManager.getEffective(ctx).substringBefore('-').takeIf { it in LANGS } ?: "en"

    fun name(ctx: Context, lang: String): String = ctx.getString(when (lang) {
        "pl" -> R.string.lang_polish
        "en" -> R.string.lang_english
        "cs" -> R.string.lang_czech
        "uk" -> R.string.lang_ukrainian
        "zh" -> R.string.lang_chinese_trad
        else -> R.string.lang_german
    })

    fun pair(ctx: Context, from: String, to: String) = "${name(ctx, from)} > ${name(ctx, to)}"

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    private fun dir(ctx: Context) = File(ctx.applicationContext.noBackupFilesDir, "translate").apply { mkdirs() }
    private fun file(ctx: Context, part: TranslationModels.Part) = File(dir(ctx), part.name)

    private fun partReady(ctx: Context, part: TranslationModels.Part): Boolean {
        val f = file(ctx, part)
        return f.isFile && f.length() == part.size
    }

    private fun selected(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(K_LANGS, emptySet()).orEmpty().filter { it in LANGS }.toSet()

    private fun setSelected(ctx: Context, langs: Set<String>) {
        prefs(ctx).edit().putStringSet(K_LANGS, langs).apply()
    }

    private fun packagesFor(ctx: Context, lang: String): Set<String> =
        setOf(lang, appLang(ctx)).filter { it != "en" }.toSet()

    private fun partsFor(ctx: Context, lang: String): List<TranslationModels.Part> =
        packagesFor(ctx, lang).flatMap { TranslationModels.pairsFor(it) }
            .filter { it.from == "en" || it.to == "en" }
            .flatMap { it.parts }.distinctBy { it.name }

    @Volatile private var cacheApp: String? = null

    private fun filesReady(ctx: Context, lang: String): Boolean {
        val app = appLang(ctx)
        if (cacheApp != app) { readyCache.clear(); cacheApp = app }
        readyCache[lang]?.let { return it }
        val parts = partsFor(ctx, lang)
        val ok = parts.all { partReady(ctx, it) }
        readyCache[lang] = ok
        return ok
    }

    fun choices(ctx: Context): List<String> {
        val app = appLang(ctx)
        return LANGS.filter { it != app }
    }

    fun isReady(ctx: Context, lang: String): Boolean {
        if (lang !in LANGS) return false
        if (lang == appLang(ctx)) return selected(ctx).any { filesReady(ctx, it) }
        return lang in selected(ctx) && filesReady(ctx, lang)
    }

    fun isDownloading(lang: String) = downloading.containsKey(lang)
    fun progress(lang: String): Float? = downloading[lang]

    fun downloadBytes(ctx: Context, lang: String): Long =
        partsFor(ctx, lang).filter { !partReady(ctx, it) }.sumOf { it.download }

    fun diskBytes(ctx: Context, lang: String): Long = partsFor(ctx, lang).sumOf { it.size }

    fun freedBytes(ctx: Context, lang: String): Long {
        val keep = (selected(ctx) - lang).flatMap { partsFor(ctx, it) }.map { it.name }.toSet()
        return partsFor(ctx, lang).filter { it.name !in keep && partReady(ctx, it) }.sumOf { it.size }
    }

    fun mb(bytes: Long): Int = ((bytes + 500_000) / 1_000_000).toInt()

    fun storedBytes(ctx: Context): Long = dir(ctx).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun downloadedLangs(ctx: Context): List<String> = choices(ctx).filter { isReady(ctx, it) }

    fun canTranslate(ctx: Context, from: String, to: String): Boolean {
        if (!supported() || from == to || from !in LANGS || to !in LANGS) return false
        val app = appLang(ctx)
        val chosen = selected(ctx)
        val foreign = setOf(from, to) - app
        if (foreign.isEmpty() || !foreign.all { it in chosen }) return false
        val parts = setOf(from, to).filter { it != "en" }
            .flatMap { TranslationModels.pairsFor(it) }.filter { it.from == "en" || it.to == "en" }.flatMap { it.parts }
        return parts.all { partReady(ctx, it) }
    }

    fun refresh(ctx: Context, done: (() -> Unit)? = null) {
        readyCache.clear()
        notifyChanged()
        done?.let { main.post(it) }
    }

    fun download(ctx: Context, lang: String, done: (Boolean) -> Unit) {
        if (lang !in LANGS || lang == appLang(ctx)) { done(false); return }
        if (downloading.putIfAbsent(lang, 0f) != null) return
        notifyChanged()
        val app = ctx.applicationContext
        val parts = partsFor(app, lang)
        fetcher.execute {
            val total = parts.sumOf { it.download }.coerceAtLeast(1L)
            var doneBytes = 0L
            var ok = true
            var lastPct = -1
            for (part in parts) {
                if (partReady(app, part)) { doneBytes += part.download; continue }
                val base = doneBytes
                val got = fetchPart(app, part) { f ->
                    val v = ((base + part.download * f) / total).coerceIn(0f, 1f)
                    downloading[lang] = v
                    val pct = (v * 100).toInt()
                    if (pct != lastPct) { lastPct = pct; notifyChanged() }
                }
                if (!got) { ok = false; break }
                doneBytes += part.download
                downloading[lang] = (doneBytes.toFloat() / total).coerceIn(0f, 1f)
                notifyChanged()
            }
            readyCache.clear()
            if (ok && filesReady(app, lang)) setSelected(app, selected(app) + lang)
            downloading.remove(lang)
            val ready = isReady(app, lang)
            notifyChanged()
            main.post { done(ready) }
        }
    }

    private fun fetchPart(ctx: Context, part: TranslationModels.Part, onProgress: (Float) -> Unit): Boolean {
        val target = file(ctx, part)
        val raw = File(target.parentFile, part.name + ".part")
        val tmp = File(target.parentFile, part.name + ".tmp")
        try {
            val code = pl.sinusoide.sinuschat.network.ApiClient.getToFile(ctx,
                TranslationModels.BASE_URL + part.name + ".gz", mapOf("Accept-Encoding" to "identity"), raw, onProgress)
            if (code != 200) { Log.w(TAG, "Pobieranie ${part.name}: HTTP $code"); return false }
            val gzip = raw.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte() }
            val digest = MessageDigest.getInstance("SHA-256")
            val input = if (gzip) java.util.zip.GZIPInputStream(raw.inputStream().buffered(256 * 1024), 256 * 1024)
                        else raw.inputStream().buffered(256 * 1024)
            var written = 0L
            input.use { inp ->
                tmp.outputStream().buffered(256 * 1024).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > part.size) return false
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (written != part.size || hash != part.sha256) {
                Log.w(TAG, "Zła suma ${part.name}")
                return false
            }
            target.delete()
            return tmp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "Pobieranie ${part.name}: ${e.javaClass.simpleName}")
            return false
        } finally {
            raw.delete()
            tmp.delete()
        }
    }

    fun delete(ctx: Context, lang: String, done: () -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val remaining = selected(app) - lang
            setSelected(app, remaining)
            val keep = remaining.flatMap { partsFor(app, it) }.map { it.name }.toSet()
            val drop = partsFor(app, lang).filter { it.name !in keep }
            val dropNames = drop.map { it.name }.toSet()
            synchronized(loaded) {
                TranslationModels.PAIRS.filter { p -> p.parts.any { it.name in dropNames } }
                    .forEach { p -> loaded.remove(p.code)?.let { m -> try { m.close() } catch (_: Throwable) {} } }
            }
            drop.forEach { file(app, it).delete() }
            readyCache.clear()
            notifyChanged()
            main.post(done)
        }
    }

    private fun ensureEngine(ctx: Context): Boolean {
        if (engine > 0) return true
        if (engine < 0 || Build.VERSION.SDK_INT < 28) return false
        return try {
            TranslateKit.init(ctx.applicationContext)
            engine = if (TranslateKit.isInitialized()) 1 else -1
            engine > 0
        } catch (t: Throwable) {
            Log.w(TAG, "Silnik tłumaczenia niedostępny: ${t.javaClass.simpleName}")
            engine = -1
            false
        }
    }

    private fun letters(text: String) = text.count { it.isLetter() }

    private fun han(text: String) = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    private fun words(text: String) = text.split(Regex("\\s+")).count { w -> w.count { it.isLetter() } >= 2 }

    fun hasLetters(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_TEXT || t.startsWith("geo:")) return false
        if (CodeSnippets.segments(t)?.any { it.code } == true) return false
        val url = LinkPreviewHelper.findUrl(t)
        if (url != null && t.replace(url, "").replace(url.removePrefix("https://"), "").count { it.isLetter() } < 2) return false
        return letters(t) >= 2
    }

    fun isLong(text: String): Boolean {
        val t = text.trim()
        return (letters(t) >= 8 && words(t) >= 2) || han(t) >= 4
    }

    fun worthTranslating(text: String): Boolean = hasLetters(text) && isLong(text)

    private fun cyrillic(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return letters.count { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC } * 2 > letters.length
    }

    class Detected(val lang: String?, val confidence: Float, val long: Boolean)

    private const val UK_ONLY = "іїєґ"
    private const val NOT_UK = "ыэъёўђјљњћџѓќѕ"

    internal fun cyrillicGuess(text: String, code: String, confidence: Float, long: Boolean): Detected {
        val t = text.lowercase()
        val ukSigns = t.any { it in UK_ONLY }
        val otherSigns = t.any { it in NOT_UK }
        return when {
            code == "uk" -> Detected("uk", maxOf(confidence, if (ukSigns) 0.9f else 0.6f), long)
            ukSigns && !otherSigns -> Detected("uk", 0.9f, long)
            otherSigns -> Detected(null, 0f, long)
            else -> Detected("uk", 0.6f, long)
        }
    }

    fun detectRaw(text: String, done: (Detected?) -> Unit) {
        if (!supported() || !hasLetters(text)) { done(null); return }
        val app = appContext ?: run { done(null); return }
        val long = isLong(text)
        worker.execute {
            val out = try {
                if (!ensureEngine(app)) null else {
                    val r = TranslateKit.detectLanguage(text.trim())
                    val code = r.language.lowercase().substringBefore('-').substringBefore('_')
                    if (cyrillic(text)) cyrillicGuess(text, code, r.confidence, long)
                    else if (code !in LANGS || code == "uk") Detected(null, 0f, long)
                    else Detected(code, r.confidence, long)
                }
            } catch (t: Throwable) { null }
            main.post { done(out) }
        }
    }

    fun detect(text: String, done: (String?) -> Unit) {
        detectRaw(text) { d -> done(d?.takeIf { it.long && it.confidence >= 0.5f }?.lang) }
    }

    @Volatile private var appContext: Context? = null

    fun attach(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    fun detectMessage(m: MessageEntity, done: (Detected?) -> Unit) {
        detected.get(m.id)?.let { done(it); return }
        detectRaw(m.text) { d ->
            if (d != null) detected.put(m.id, d)
            done(d)
        }
    }

    private fun model(ctx: Context, from: String, to: String): TranslationModel? {
        val p = TranslationModels.pair(from, to) ?: return null
        synchronized(loaded) { loaded[p.code]?.let { return it } }
        if (!p.parts.all { partReady(ctx, it) }) return null
        val lex = p.lex ?: return null
        val m = try {
            TranslateKit.loadModel(ModelSpec(
                sourceLang = from,
                targetLang = to,
                modelPath = file(ctx, p.model).absolutePath,
                vocabPaths = listOfNotNull(file(ctx, p.vocab).absolutePath, p.trgVocab?.let { file(ctx, it).absolutePath }),
                shortlistPath = file(ctx, lex).absolutePath,
                configYaml = null,
                numWorkers = 1))
        } catch (t: Throwable) {
            Log.w(TAG, "Model $from>$to: ${t.javaClass.simpleName}")
            null
        } ?: return null
        synchronized(loaded) { loaded[p.code] = m }
        return m
    }

    private fun hop(ctx: Context, text: String, from: String, to: String): String? {
        val m = model(ctx, from, to) ?: return null
        return m.translate(text, false).text.takeIf { it.isNotBlank() }
    }

    fun translate(ctx: Context, text: String, from: String, to: String, done: (String?) -> Unit) {
        if (!canTranslate(ctx, from, to)) { done(null); return }
        val app = ctx.applicationContext
        worker.execute {
            val out = try {
                if (!ensureEngine(app)) null
                else if (from == "en" || to == "en") hop(app, text, from, to)
                else hop(app, text, from, "en")?.let { hop(app, it, "en", to) }
            } catch (t: Throwable) {
                Log.w(TAG, "Tłumaczenie $from>$to: ${t.javaClass.simpleName}")
                null
            }
            main.post { done(out?.takeIf { it.trim() != text.trim() }) }
        }
    }

    class Shown(val translated: String, val original: String, val from: String?, val to: String?)

    fun view(m: MessageEntity): Shown? {
        m.trLocal?.let {
            val blocked = appContext?.let { c -> isBlocked(c, m.conversationId) } == true
            if (!blocked) return Shown(it, m.text, m.trFrom, m.trTo)
        }
        m.trOrig?.let { return Shown(m.text, it, m.trFrom, m.trTo) }
        return null
    }

    class Wire(val orig: String, val from: String, val to: String)

    fun toJson(orig: String, from: String, to: String): JSONObject =
        JSONObject().put("o", orig).put("f", from).put("t", to)

    fun parse(o: JSONObject?): Wire? {
        o ?: return null
        val orig = o.optString("o").takeIf { it.isNotBlank() }?.take(MAX_TEXT) ?: return null
        val from = o.optString("f").takeIf { it in LANGS } ?: return null
        val to = o.optString("t").takeIf { it in LANGS } ?: return null
        return Wire(orig, from, to)
    }

    fun parse(raw: String?): Wire? =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { parse(it.optJSONObject("tr") ?: it) }
}
