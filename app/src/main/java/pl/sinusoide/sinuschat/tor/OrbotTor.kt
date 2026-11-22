package pl.sinusoide.sinuschat.tor

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket

object OrbotTor {

    const val PACKAGE = "org.torproject.android"
    const val SOCKS_PORT = 9050

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Throwable) { false }

    fun socksAlive(): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 1500)
            s.soTimeout = 1500
            val out = s.getOutputStream()
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            val input = s.getInputStream()
            input.read() == 5 && input.read() == 0
        }
    } catch (_: Throwable) { false }

    fun available(ctx: Context): Boolean = installed(ctx) && socksAlive()
}
