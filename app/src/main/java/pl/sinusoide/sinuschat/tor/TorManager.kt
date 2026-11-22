package pl.sinusoide.sinuschat.tor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.torproject.jni.TorService

object TorManager {

    @Volatile var socksPort: Int = 9050
        private set

    fun updateSocksPort(port: Int) {
        if (port in 1..65535 && port != socksPort) socksPort = port
    }

    @Volatile var isReady: Boolean = false
        private set

    @Volatile var viaOrbot: Boolean = false
        private set

    private var orbotWatch: Runnable? = null

    @Volatile private var starting = false
    @Volatile private var running = false
    @Volatile private var desiredOn = false
    @Volatile private var startedInProcess = false
    @Volatile private var endedInProcess = false
    @Volatile private var torActive = false

    private var appContext: Context? = null
    private var receiverRegistered = false
    private val readyCallbacks = mutableListOf<() -> Unit>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReconcile: Runnable? = null

    private const val DEBOUNCE_MS = 1200L

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(TorService.EXTRA_STATUS)) {
                TorService.STATUS_STARTING, TorService.STATUS_ON -> {
                    torActive = true
                    if (!running) {
                        starting = false
                        running = true
                        appContext?.let { TorCircuits.attach(it) }
                        waitReady()
                    }
                    if (!desiredOn) scheduleReconcile()
                }
                TorService.STATUS_OFF -> {
                    if (!torActive && !running) return
                    torActive = false
                    endedInProcess = true
                    TorCircuits.detach()
                    isReady = false
                    running = false
                    starting = false
                    if (desiredOn && endedInProcess) scheduleReconcile()
                }
            }
        }
    }

    fun onReady(cb: () -> Unit) {
        synchronized(this) {
            if (isReady) { cb(); return }
            readyCallbacks.add(cb)
        }
    }

    private var waitToken = 0

    private fun waitReady() {
        val token = ++waitToken
        TorCircuits.awaitBootstrap(90_000L) {
            if (token == waitToken && running && desiredOn) fireReady()
        }
    }

    private fun fireReady() {
        appContext?.let { pl.sinusoide.sinuschat.util.SafeMode.stable(it, pl.sinusoide.sinuschat.util.SafeMode.Part.TOR) }
        val cbs: List<() -> Unit>
        synchronized(this) {
            isReady = true
            cbs = readyCallbacks.toList()
            readyCallbacks.clear()
        }
        cbs.forEach { runCatching { it() } }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        desiredOn = true
        scheduleReconcile()
    }

    fun stop() {
        appContext?.let { pl.sinusoide.sinuschat.util.SafeMode.end(it, pl.sinusoide.sinuschat.util.SafeMode.Part.TOR) }
        desiredOn = false
        waitToken++
        synchronized(this) {
            isReady = false
            readyCallbacks.clear()
        }
        scheduleReconcile()
    }

    private fun restartProcess(app: Context) {
        val prefs = app.getSharedPreferences("tor_manager", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("restart_at", 0L) < 120_000L) {
            Log.w("TorManager", "Tor zakończył się ponownie tuż po restarcie, czekam")
            return
        }
        prefs.edit().putLong("restart_at", now).commit()
        pl.sinusoide.sinuschat.util.SafeMode.end(app, pl.sinusoide.sinuschat.util.SafeMode.Part.TOR)
        Log.w("TorManager", "Tor zakończył się w tym procesie, restart aplikacji")
        if (pl.sinusoide.sinuschat.SinusApp.isForeground) {
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
            pl.sinusoide.sinuschat.util.RestartActivity.restartInto(app, launch, wipeDb = false)
        } else {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun scheduleReconcile() {
        pendingReconcile?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { reconcile() }
        pendingReconcile = r
        mainHandler.postDelayed(r, DEBOUNCE_MS)
    }

    private fun reconcile() {
        pendingReconcile = null
        val app = appContext ?: return

        if (desiredOn && !running && !starting) {
            if (pl.sinusoide.sinuschat.util.SafeMode.isDisabled(app, pl.sinusoide.sinuschat.util.SafeMode.Part.TOR)) {
                Log.w("TorManager", "Tor wyłączony przez SafeEngine")
                return
            }
            starting = true
            Thread({
                val orbot = OrbotTor.available(app)
                mainHandler.post {
                    starting = false
                    if (!desiredOn || running) return@post
                    if (orbot) useOrbot(app) else startEmbedded(app)
                }
            }, "tor-orbot-check").start()
        } else if (desiredOn && running && !starting && !isReady && viaOrbot) {
            fireReady()
        } else if (desiredOn && running && !starting && !isReady) {
            waitReady()
        } else if (!desiredOn && running && !starting && viaOrbot) {
            stopOrbot()
        } else if (!desiredOn && running && !starting) {
            running = false
            endedInProcess = true
            TorCircuits.detach()
            if (receiverRegistered) {
                runCatching { app.unregisterReceiver(statusReceiver) }
                receiverRegistered = false
            }
            runCatching { app.stopService(Intent(app, SinusTorService::class.java)) }
        } else if (!desiredOn && starting) {
            Log.d("TorManager", "stop w trakcie rozruchu — odłożone do STATUS_ON")
        }
    }

    private fun useOrbot(app: Context) {
        Log.i("TorManager", "Orbot działa, używam jego Tora")
        viaOrbot = true
        running = true
        torActive = true
        socksPort = OrbotTor.SOCKS_PORT
        TorCircuits.lastProgress = 100
        fireReady()
        watchOrbot(app)
    }

    private fun stopOrbot() {
        orbotWatch?.let { mainHandler.removeCallbacks(it) }
        orbotWatch = null
        viaOrbot = false
        running = false
        torActive = false
        TorCircuits.lastProgress = null
    }

    private fun watchOrbot(app: Context) {
        orbotWatch?.let { mainHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!viaOrbot) return
                Thread({
                    val alive = OrbotTor.socksAlive()
                    mainHandler.post {
                        if (!viaOrbot) return@post
                        if (alive) {
                            mainHandler.postDelayed(this, 15_000L)
                        } else {
                            Log.w("TorManager", "Orbot przestał działać, uruchamiam wbudowany Tor")
                            synchronized(this@TorManager) { isReady = false }
                            stopOrbot()
                            if (desiredOn) startEmbedded(app)
                        }
                    }
                }, "tor-orbot-watch").start()
            }
        }
        orbotWatch = r
        mainHandler.postDelayed(r, 15_000L)
    }

    private fun startEmbedded(app: Context) {
        if (startedInProcess) {
            if (endedInProcess) restartProcess(app)
            return
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                app, statusReceiver,
                IntentFilter(TorService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        starting = true
        try {
            pl.sinusoide.sinuschat.util.SafeMode.begin(app, pl.sinusoide.sinuschat.util.SafeMode.Part.TOR)
            app.startService(Intent(app, SinusTorService::class.java))
            startedInProcess = true
            mainHandler.postDelayed({
                if (desiredOn && !running && !endedInProcess) {
                    torActive = true
                    starting = false
                    running = true
                    TorCircuits.attach(app)
                    waitReady()
                }
            }, 5_000L)
        } catch (t: Throwable) {
            Log.e("TorManager", "Nie udało się wystartować TorService", t)
            starting = false
        }
    }
}
