package pl.sinusoide.sinuschat.util

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import pl.sinusoide.sinuschat.MainActivity

object HomeNav {

    private fun chatsBelow(a: Activity): Boolean? = try {
        val am = a.getSystemService(ActivityManager::class.java)
        val info = am?.appTasks?.mapNotNull { runCatching { it.taskInfo }.getOrNull() }?.firstOrNull {
            val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.taskId else @Suppress("DEPRECATION") it.persistentId
            id == a.taskId
        }
        info?.baseActivity?.className?.let { it == MainActivity::class.java.name }
    } catch (_: Exception) { null }

    fun closeToChats(a: Activity) {
        if (a.isTaskRoot) a.startActivity(Intent(a, MainActivity::class.java))
        a.finish()
    }

    fun backToChats(a: Activity) {
        if (chatsBelow(a) == false) {
            a.startActivity(Intent(a, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } else {
            a.startActivity(Intent(a, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            a.finish()
        }
    }
}
