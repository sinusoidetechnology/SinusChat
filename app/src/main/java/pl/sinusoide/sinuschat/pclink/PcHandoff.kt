package pl.sinusoide.sinuschat.pclink

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import pl.sinusoide.sinuschat.link.LinkFrame

object PcHandoff {

    const val ACTION = "pl.sinusoide.sinuschat.PC_HANDOFF"
    private const val TTL_MS = 10 * 60_000L
    private val SECTIONS = setOf(
        LinkFrame.SECTION_ACCOUNT, LinkFrame.SECTION_PRIVACY, LinkFrame.SECTION_INTERNET,
        LinkFrame.SECTION_BACKUP, LinkFrame.SECTION_REPORT_DESKTOP
    )

    @Volatile private var pending: String? = null
    @Volatile private var at = 0L

    fun request(ctx: Context, section: String) {
        if (section !in SECTIONS) return
        pending = section
        at = SystemClock.elapsedRealtime()
        val app = ctx.applicationContext
        pl.sinusoide.sinuschat.util.AppExecutor.main {
            app.sendBroadcast(Intent(ACTION).setPackage(app.packageName))
        }
    }

    fun take(): String? {
        val p = pending ?: return null
        pending = null
        return p.takeIf { SystemClock.elapsedRealtime() - at <= TTL_MS }
    }
}
