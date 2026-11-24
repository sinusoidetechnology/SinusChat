package pl.sinusoide.sinuschat.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock

object NetState {

    const val OK = 0
    const val NO_NETWORK = 1
    const val NO_INTERNET = 2

    private const val SERVER_FRESH_MS = 60_000L

    @Volatile private var lastServerOk = 0L
    @Volatile private var lastServerFail = 0L

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun listen(l: () -> Unit) { listeners.add(l) }

    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    fun serverReached() {
        val was = serverFailing()
        lastServerOk = SystemClock.elapsedRealtime()
        if (was) listeners.forEach { it() }
    }

    fun serverFailed() {
        val was = serverFailing()
        lastServerFail = SystemClock.elapsedRealtime()
        if (!was) listeners.forEach { it() }
    }

    private fun serverFresh(): Boolean =
        lastServerOk > 0 && SystemClock.elapsedRealtime() - lastServerOk < SERVER_FRESH_MS

    private fun serverFailing(): Boolean = lastServerFail > lastServerOk

    fun state(ctx: Context): Int {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return OK
        val caps = try { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } } catch (_: Exception) { null }
            ?: return NO_NETWORK
        return state(caps)
    }

    fun state(caps: NetworkCapabilities): Int = when {
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NO_NETWORK
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> OK
        serverFresh() -> OK
        serverFailing() -> NO_INTERNET
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> NO_INTERNET
        else -> OK
    }
}
