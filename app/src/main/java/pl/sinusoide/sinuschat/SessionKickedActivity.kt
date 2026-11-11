package pl.sinusoide.sinuschat

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import pl.sinusoide.sinuschat.util.AccountWiper
import pl.sinusoide.sinuschat.util.ThemeManager
import androidx.activity.enableEdgeToEdge

class SessionKickedActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_kicked)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener((findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0)) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<pl.sinusoide.sinuschat.ui.SessionMoveView>(R.id.iv_session_anim)
            ?.setColors(android.graphics.Color.WHITE, pl.sinusoide.sinuschat.ui.ThemeUi.accent(this))

        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { moveTaskToBack(true) }
            })

        val button = findViewById<Button>(R.id.btn_understood)
        button.setOnClickListener {
            button.isEnabled = false
            check { active ->
                button.isEnabled = true
                when (active) {
                    false -> wipeAndExit()
                    else -> pl.sinusoide.sinuschat.util.PopupHelper.show(this, getString(R.string.session_moved_check_failed))
                }
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recheck = object : Runnable {
        override fun run() {
            check(null)
            handler.postDelayed(this, RECHECK_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(recheck)
        handler.post(recheck)
    }

    override fun onPause() {
        handler.removeCallbacks(recheck)
        super.onPause()
    }

    private fun check(then: ((Boolean?) -> Unit)?) {
        val app = applicationContext
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            val active = pl.sinusoide.sinuschat.network.ApiClient.stillActive(app)
            pl.sinusoide.sinuschat.util.AppExecutor.main {
                if (isFinishing || isDestroyed) return@main
                if (active == true) restore() else then?.invoke(active)
            }
        }
    }

    private fun restore() {
        handler.removeCallbacks(recheck)
        pl.sinusoide.sinuschat.util.LinkLog.add(this, "kick", "fałszywe przeniesienie sesji, wracam do aplikacji")
        pl.sinusoide.sinuschat.network.ApiClient.resetSession()
        pl.sinusoide.sinuschat.network.WebSocketManager.clearKick(applicationContext)
        startActivity(android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private companion object {
        const val RECHECK_MS = 15_000L
    }

    private fun wipeAndExit() {
        AccountWiper.wipeAndRestart(applicationContext)
        finishAffinity()
    }
}
