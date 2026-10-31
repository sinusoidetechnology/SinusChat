package pl.sinusoide.sinuschat.tor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object TorCircuits {

    private const val TAG = "TorCircuits"
    private const val PREFS = "sinuschat_tor_route"
    private const val K_DISTINCT = "distinct_countries"
    private const val MAX_CLOSES_PER_MIN = 20
    private const val OUR_HOST = "sinusoide.pl"

    class Hop(val fingerprint: String, val nickname: String, val ip: String?, val country: String?)
    class Route(val id: String, val hops: List<Hop>) {
        val duplicateCountry: String? get() {
            val seen = HashSet<String>()
            for (h in hops) { val c = h.country ?: continue; if (!seen.add(c)) return c }
            return null
        }
    }

    private class Circ(val id: String, val status: String, val path: List<Pair<String, String>>, val purpose: String?)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tor-route").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val ips = LruCache<String, String>(800)
    private val closes = ArrayDeque<Long>()

    @Volatile private var service: TorService? = null
    @Volatile private var hooked: TorControlConnection? = null
    @Volatile private var appCtx: Context? = null
    private var bound = false
    private var retries = 0

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun distinct(ctx: Context) = prefs(ctx).getBoolean(K_DISTINCT, false)

    fun setDistinct(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(K_DISTINCT, on).apply()
        if (on) worker.execute { enforceAll() }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
            service?.let { s -> runCatching { TorManager.updateSocksPort(s.socksPort) } }
            retries = 0
            worker.execute { hook() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            hooked = null
            notifyChanged()
        }
    }

    fun attach(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        main.post {
            if (!bound) bound = try {
                app.bindService(Intent(app, SinusTorService::class.java), connection, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) { false }
            worker.execute { hook() }
        }
    }

    fun detach() {
        val work = Runnable {
            val app = appCtx
            if (bound && app != null) runCatching { app.unbindService(connection) }
            bound = false
            service = null
            hooked = null
            lastProgress = null
            notifyChanged()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) work.run() else main.post(work)
    }

    @Volatile var lastProgress: Int? = null

    private fun bootstrapProgress(): Int? = try {
        control()?.getInfo("status/bootstrap-phase")?.let {
            Regex("PROGRESS=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
    } catch (_: Throwable) { null }

    fun awaitBootstrap(timeoutMs: Long, onDone: () -> Unit) {
        val start = SystemClock.elapsedRealtime()
        var seen = false
        val tick = object : Runnable {
            override fun run() {
                worker.execute {
                    val p = bootstrapProgress()
                    if (p != null) { seen = true; lastProgress = p }
                    val elapsed = SystemClock.elapsedRealtime() - start
                    val done = (p != null && p >= 100) || elapsed > timeoutMs || (!seen && elapsed > 15_000L)
                    notifyChanged()
                    if (done) main.post(onDone) else main.postDelayed(this, 500L)
                }
            }
        }
        main.post(tick)
    }

    fun available(): Boolean = control() != null

    private fun control(): TorControlConnection? = try { service?.torControlConnection } catch (_: Throwable) { null }

    private fun hook() {
        val c = control()
        if (c == null) {
            if (service != null && retries++ < 10) main.postDelayed({ worker.execute { hook() } }, 2000L)
            return
        }
        if (hooked === c) return
        hooked = c
        service?.let { s -> runCatching { TorManager.updateSocksPort(s.socksPort) } }
        try {
            c.addRawEventListener { keyword, data -> if (keyword == "CIRC") onCirc(data) }
        } catch (t: Throwable) {
            Log.w(TAG, "Nie mogę słuchać obwodów: ${t.javaClass.simpleName}")
        }
        val ctx = appCtx
        if (ctx != null && distinct(ctx)) enforceAll()
        notifyChanged()
    }

    private fun onCirc(data: String) {
        val c = parseCirc(data) ?: return
        if (c.status == "BUILT") {
            val ctx = appCtx
            if (ctx != null && distinct(ctx)) worker.execute { check(c) }
        }
        if (c.status == "BUILT" || c.status == "CLOSED" || c.status == "FAILED") notifyChanged()
    }

    private fun parseCirc(line: String): Circ? {
        val t = line.trim().split(' ')
        if (t.size < 2) return null
        val path = t.getOrNull(2)?.takeIf { it.startsWith("$") }?.split(',')?.mapNotNull { hop ->
            val s = hop.removePrefix("$")
            val cut = s.indexOfFirst { it == '~' || it == '=' }
            val fp = (if (cut < 0) s else s.substring(0, cut)).uppercase()
            val nick = if (cut < 0) "" else s.substring(cut + 1)
            if (fp.length == 40) fp to nick else null
        }.orEmpty()
        val purpose = t.firstOrNull { it.startsWith("PURPOSE=") }?.substringAfter('=')
        return Circ(t[0], t[1], path, purpose)
    }

    private fun ipOf(fp: String): String? {
        ips.get(fp)?.let { return it.ifEmpty { null } }
        val info = try { control()?.getInfo("ns/id/$fp") } catch (_: Throwable) { null }
        val r = info?.lineSequence()?.firstOrNull { it.startsWith("r ") }
        val ip = r?.split(' ')?.firstOrNull { Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(it) }
        ips.put(fp, ip.orEmpty())
        return ip
    }

    private fun resolve(c: Circ): Route? {
        val ctx = appCtx ?: return null
        if (c.path.isEmpty()) return null
        val hops = c.path.map { (fp, nick) ->
            val ip = ipOf(fp)
            Hop(fp, nick, ip, ip?.let { TorGeo.country(ctx, it) })
        }
        return Route(c.id, hops)
    }

    private fun circuits(): List<Circ> {
        val raw = try { control()?.getInfo("circuit-status") } catch (_: Throwable) { null } ?: return emptyList()
        return raw.lineSequence().mapNotNull { parseCirc(it) }.toList()
    }

    private fun ourCircuitId(): String? {
        val raw = try { control()?.getInfo("stream-status") } catch (_: Throwable) { null } ?: return null
        return raw.lineSequence().map { it.trim().split(' ') }
            .filter { it.size >= 4 && it[1] == "SUCCEEDED" && it[3].substringBeforeLast(':').endsWith(OUR_HOST) }
            .map { it[2] }
            .lastOrNull()
    }

    private fun general(c: Circ) = c.status == "BUILT" && (c.purpose == null || c.purpose == "GENERAL") && c.path.size >= 3

    private fun currentBlocking(): Route? {
        val all = circuits()
        val ours = ourCircuitId()?.let { id -> all.firstOrNull { it.id == id && it.status == "BUILT" } }
        val pick = ours ?: all.filter { general(it) }.maxByOrNull { it.id.toLongOrNull() ?: 0L }
        return pick?.let { resolve(it) }
    }

    fun route(done: (Route?) -> Unit) {
        worker.execute {
            val r = try { currentBlocking() } catch (t: Throwable) { null }
            main.post { done(r) }
        }
    }

    fun newRoute(done: (Boolean) -> Unit) {
        worker.execute {
            val ok = try {
                val c = control() ?: throw IllegalStateException()
                val ours = ourCircuitId()
                c.signal("NEWNYM")
                if (ours != null) c.closeCircuit(ours, false)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Nowa trasa: ${t.javaClass.simpleName}")
                false
            }
            main.post { done(ok) }
        }
    }

    private fun mayClose(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (closes.isNotEmpty() && now - closes.first() > 60_000L) closes.removeFirst()
        if (closes.size >= MAX_CLOSES_PER_MIN) return false
        closes.addLast(now)
        return true
    }

    private fun check(c: Circ) {
        if (!general(c)) return
        val r = resolve(c) ?: return
        val dup = r.duplicateCountry ?: return
        if (!mayClose()) return
        try {
            control()?.closeCircuit(c.id, false)
            Log.d(TAG, "Zamknięto obwód ${c.id}: dwa węzły w $dup")
        } catch (t: Throwable) {
            Log.w(TAG, "Zamknięcie obwodu: ${t.javaClass.simpleName}")
        }
    }

    private fun enforceAll() {
        circuits().filter { general(it) }.forEach { check(it) }
    }
}
