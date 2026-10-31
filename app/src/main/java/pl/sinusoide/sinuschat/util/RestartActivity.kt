package pl.sinusoide.sinuschat.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.core.content.IntentCompat
import java.io.File

class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(EXTRA_PID, -1)
        if (mainPid > 0 && mainPid != Process.myPid()) {
            Process.killProcess(mainPid)
            val proc = File("/proc/$mainPid")
            var waited = 0
            while (proc.exists() && waited < 1500) {
                Thread.sleep(50)
                waited += 50
            }
        }
        if (intent.getBooleanExtra(EXTRA_WIPE_DB, false)) deleteDatabase(DB_NAME)
        IntentCompat.getParcelableExtra(intent, EXTRA_NEXT, Intent::class.java)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
        Process.killProcess(Process.myPid())
    }

    companion object {
        private const val EXTRA_NEXT = "next"
        private const val EXTRA_PID = "pid"
        private const val EXTRA_WIPE_DB = "wipe_db"
        private const val DB_NAME = "sinuschat_messages.db"

        fun restartInto(context: Context, next: Intent, wipeDb: Boolean) {
            context.startActivity(Intent(context, RestartActivity::class.java)
                .putExtra(EXTRA_NEXT, next)
                .putExtra(EXTRA_PID, Process.myPid())
                .putExtra(EXTRA_WIPE_DB, wipeDb)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Process.killProcess(Process.myPid())
        }

        fun isRestartProcess(): Boolean {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
            else try { File("/proc/self/cmdline").readText().trimEnd('\u0000', ' ', '\n') } catch (_: Exception) { "" }
            return name.endsWith(":restart")
        }
    }
}
