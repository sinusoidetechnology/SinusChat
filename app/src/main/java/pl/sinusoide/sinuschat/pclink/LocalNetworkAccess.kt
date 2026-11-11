package pl.sinusoide.sinuschat.pclink

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat

object LocalNetworkAccess {

    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val SDK_ENFORCED = 37

    @Volatile var nsdDenied = false

    fun applies(): Boolean = Build.VERSION.SDK_INT >= SDK_ENFORCED

    fun blocked(ctx: Context): Boolean {
        if (!applies()) return false
        if (nsdDenied) return true
        if (ContextCompat.checkSelfPermission(ctx, PERMISSION) != PackageManager.PERMISSION_GRANTED) return true
        return runCatching {
            val op = AppOpsManager.permissionToOp(PERMISSION) ?: return false
            val mode = ctx.getSystemService(AppOpsManager::class.java)
                ?.unsafeCheckOpNoThrow(op, Process.myUid(), ctx.packageName) ?: return false
            mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED
        }.getOrDefault(false)
    }
}
