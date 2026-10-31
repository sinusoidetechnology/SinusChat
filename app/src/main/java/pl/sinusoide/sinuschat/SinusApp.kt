package pl.sinusoide.sinuschat

import android.app.Application
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import pl.sinusoide.sinuschat.crypto.DbKeyManager
import pl.sinusoide.sinuschat.messenger.PendingLink
import pl.sinusoide.sinuschat.messenger.QrLinkActivity
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.tor.TorManager
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.PrivacySettings
import pl.sinusoide.sinuschat.util.ThemeManager
import pl.sinusoide.sinuschat.util.UserStatusManager

class SinusApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        if (pl.sinusoide.sinuschat.util.RestartActivity.isRestartProcess()) return
        val crashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            pl.sinusoide.sinuschat.util.LinkLog.crash(this, e)
            crashHandler?.uncaughtException(t, e)
        }
        ThemeManager.applyNightMode(this)
        
        DbKeyManager.dropLegacyBio(this)
        DbKeyManager.initTransparent(this)
        if (android.os.Build.VERSION.SDK_INT < 28 || Application.getProcessName() == packageName) {
            pl.sinusoide.sinuschat.util.SafeMode.checkOnStart(this)
            pl.sinusoide.sinuschat.data.SinusChatDatabase.warmUp(this)
            if (SetupActivity.isSetupDone(this)) pl.sinusoide.sinuschat.pclink.PcLinkServer.init(this)
        }
        pl.sinusoide.sinuschat.util.Translations.attach(this)

        pl.sinusoide.sinuschat.push.PushManager.initIfFcm(this)

        UserStatusManager.init(
            SetupActivity.getUserPhoneHash(this), this)
            
        if (PrivacySettings.isTorEnabled(this)) {
            TorManager.start(this)
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isForeground = true
                LockManager.onAppForeground(this@SinusApp)
                val app = this@SinusApp
                val svc = pl.sinusoide.sinuschat.messenger.MessageListenerService
                if (SetupActivity.isSetupDone(app) && SetupActivity.getUserPhoneHash(app).isNotEmpty() &&
                    (svc.onDemand(app) || !svc.isRunning)) {
                    svc.start(app)
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                isForeground = false
                UserStatusManager.onBackground(this@SinusApp)
                LockManager.onAppBackground()
            }
        })

        androidx.core.content.ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!LockManager.hasPin(c)) return
                LockManager.onScreenOff()
                val a = top?.get() ?: return
                if (!isForeground || a.isFinishing || exempt(a) || DbKeyManager.needsPinUnlock(a)) return
                if (a is QrLinkActivity) relock(a, "screen_off") else cover(a)
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(a: Activity, s: Bundle?) { theme(a) }
            override fun onActivityCreated(a: Activity, s: Bundle?) {
                if (android.os.Build.VERSION.SDK_INT < 29) theme(a)
                check(a)
            }
            override fun onActivityResumed(a: Activity) {
                top = java.lang.ref.WeakReference(a)
                if (check(a)) {
                    resumePendingLink(a)
                    offerUpdate(a)
                }
            }
            override fun onActivityStarted(a: Activity) {
                watchContent(a)
                if (a !is pl.sinusoide.sinuschat.call.IncomingCallActivity && !(a is pl.sinusoide.sinuschat.call.GroupCallActivity &&
                        a.intent?.getIntExtra("mode", -1) == pl.sinusoide.sinuschat.call.GroupCallActivity.MODE_INCOMING))
                    UserStatusManager.onForegroundIfIdle(this@SinusApp)
                if (a is pl.sinusoide.sinuschat.util.CropActivity) return
                ThemeManager.applyBackdrop(a)
                pl.sinusoide.sinuschat.ui.FlowGlass.install(a)
                pl.sinusoide.sinuschat.ui.RetroShape.install(a)
                if (a is pl.sinusoide.sinuschat.call.CallActivity || a is pl.sinusoide.sinuschat.call.GroupCallActivity || a is SetupActivity ||
                    a is pl.sinusoide.sinuschat.messenger.QrScanActivity ||
                    a is pl.sinusoide.sinuschat.messenger.ViewOnceActivity) return
                val window = a.window ?: return
                val light = !pl.sinusoide.sinuschat.ui.ThemeUi.isDark(a)
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                if (top?.get() === a) top = null
            }

            private fun theme(a: Activity) {
                if (a is pl.sinusoide.sinuschat.util.CropActivity) return
                ThemeManager.applyTheme(a)
            }

            private fun watchContent(a: Activity) {
                val content = a.window?.peekDecorView()?.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
                if (content.getTag(R.id.insets_watch) == true) return
                content.setTag(R.id.insets_watch, true)
                content.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View, child: View) {
                        child.post { androidx.core.view.ViewCompat.requestApplyInsets(child) }
                    }
                    override fun onChildViewRemoved(parent: View, child: View) {}
                })
            }

            private fun check(a: Activity): Boolean {
                if (exempt(a)) return false
                LockManager.expireIfIdle(a)
                val dbLocked = DbKeyManager.needsPinUnlock(a)
                val needsPin = LockManager.hasPin(a) && (!LockManager.sessionUnlocked || dbLocked)

                if (!needsPin) {
                    if (hidden.remove(a) != null) a.window?.peekDecorView()?.visibility = View.VISIBLE
                    return true
                }
                if (a is QrLinkActivity) {
                    relock(a, if (dbLocked) "db" else "session")
                    return false
                }
                if (dbLocked) {
                    ScreenLockActivity.start(a, ScreenLockActivity.MODE_UNLOCK)
                    a.finish()
                    return false
                }
                cover(a)
                return false
            }
        })
    }

    private fun relock(a: Activity, reason: String) {
        PendingLink.keep(a.intent?.data)
        pl.sinusoide.sinuschat.util.LinkLog.add(a, "lock", reason)
        ScreenLockActivity.start(a, ScreenLockActivity.MODE_UNLOCK)
        a.finish()
    }

    private fun resumePendingLink(a: Activity) {
        if (a is QrLinkActivity || a.isFinishing || !SetupActivity.isSetupDone(a) || SetupActivity.wizardStep(a) != null) return
        if (PendingLink.resume(a)) pl.sinusoide.sinuschat.util.LinkLog.add(a, "resume", a.javaClass.simpleName)
    }

    private fun offerUpdate(a: Activity) {
        if (a is QrLinkActivity || a.isFinishing || !SetupActivity.isSetupDone(a) || SetupActivity.wizardStep(a) != null) return
        pl.sinusoide.sinuschat.util.UpdateChecker.check(a) { showPendingUpdate() }
        pl.sinusoide.sinuschat.util.UpdateChecker.takePending(a)?.let { UpdateActivity.open(a, it) }
    }

    private fun showPendingUpdate() {
        val a = top?.get() ?: return
        val resumed = (a as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState
            ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true
        if (!isForeground || !resumed || a.isFinishing || exempt(a)) return
        if (LockManager.hasPin(a) && (!LockManager.sessionUnlocked || DbKeyManager.needsPinUnlock(a))) return
        offerUpdate(a)
    }

    private val hidden = java.util.WeakHashMap<Activity, Boolean>()

    @Volatile private var top: java.lang.ref.WeakReference<Activity>? = null

    private fun exempt(a: Activity): Boolean =
        a is ScreenLockActivity || a is SetupActivity || a is UpdateActivity ||
        a is pl.sinusoide.sinuschat.call.IncomingCallActivity ||
        a is pl.sinusoide.sinuschat.call.CallActivity ||
        a is pl.sinusoide.sinuschat.call.GroupCallActivity ||
        ((a is RecoveryKeyActivity || a is pl.sinusoide.sinuschat.messenger.QrScanActivity) &&
            a.intent?.getBooleanExtra(RecoveryKeyActivity.EXTRA_UNLOCK, false) == true)

    private fun cover(a: Activity) {
        a.window?.peekDecorView()?.let {
            it.visibility = View.INVISIBLE
            hidden[a] = true
        }
        ScreenLockActivity.cover(a)
    }

    companion object {
        @Volatile var isForeground = false
    }
}
