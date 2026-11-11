package pl.sinusoide.sinuschat.call

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.AvatarHelper
import androidx.activity.enableEdgeToEdge

class IncomingCallActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }


    companion object {
        const val ACTION_CALL_CANCELLED = "pl.sinusoide.sinuschat.CALL_CANCELLED"

        fun show(ctx: Context, conversationId: String, fromHash: String, fromName: String, isVideo: Boolean, offerSDP: String) {
            CallNotifier.showIncomingCall(ctx, conversationId, fromHash, fromName, isVideo, offerSDP)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var conversationId = ""
    private var fromHash = ""
    private var fromName = ""
    private var isVideo = false
    private var offerSDP = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        showOverLockScreen()
        CallNotifier.cancel(this, stopRinging = false)

        readIntent(intent)
        buildUI()

        val filter = android.content.IntentFilter(ACTION_CALL_CANCELLED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(callCancelledReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(callCancelledReceiver, filter)
        }

        if (CallNotifier.isCancelled(conversationId)) {
            CallNotifier.clearCancelled(conversationId)
            CallNotifier.cancel(this)
            finish()
            return
        }

        if (intent.getBooleanExtra("autoAnswer", false)) {
            answer(); return
        }

        startRinging()

        handler.postDelayed({
            if (CallNotifier.timeout(this, conversationId)) finish() else reject(declined = false)
        }, 37_000)
    }

    private val callCancelledReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            val convId = i?.getStringExtra("conversationId")
            if (convId == null || convId == conversationId) {
                stopRinging()
                CallNotifier.cancel(this@IncomingCallActivity)
                finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        if (intent.getBooleanExtra("autoAnswer", false)) answer()
    }

    private fun readIntent(i: Intent) {
        conversationId = i.getStringExtra("conversationId") ?: conversationId
        fromHash = i.getStringExtra("fromHash") ?: fromHash
        fromName = i.getStringExtra("fromName") ?: fromName
        isVideo = i.getBooleanExtra("isVideo", isVideo)
        offerSDP = i.getStringExtra("offerSDP") ?: offerSDP
    }

    private var phoneLocked = false

    private fun showOverLockScreen() {
        phoneLocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopRinging()
        CallNotifier.cancel(this)
        try { unregisterReceiver(callCancelledReceiver) } catch (_: Exception) {}
    }

    private fun startRinging() {
        CallRinger.start(this, conversationId, pl.sinusoide.sinuschat.util.NotificationPrefs.silentCall(this))
    }

    private fun stopRinging() {
        CallRinger.stop(conversationId)
    }

    private fun answer() {
        if (CallNotifier.isCancelled(conversationId)) {
            stopRinging()
            CallNotifier.cancel(this)
            finish()
            return
        }
        stopRinging()
        CallNotifier.answered(conversationId)
        CallNotifier.cancel(this)
        CallActivity.startAsCallee(this, conversationId, fromHash, fromName, isVideo, offerSDP)
        finish()
    }

    private fun reject(declined: Boolean = true) {
        pl.sinusoide.sinuschat.pclink.PcLinkCalls.onRingStopped(conversationId,
            if (declined) pl.sinusoide.sinuschat.link.LinkFrame.CALL_DECLINED else pl.sinusoide.sinuschat.link.LinkFrame.CALL_NO_ANSWER)
        stopRinging()
        CallNotifier.cancel(this)
        if (fromHash.isNotEmpty()) CallNotifier.showMissedCall(this, conversationId, fromHash, fromName, isVideo, declined)
        pl.sinusoide.sinuschat.network.WebSocketManager.send(
            org.json.JSONObject().put("type", "call_reject").put("conversationId", conversationId)
                .put("reason", if (declined) "declined" else "timeout")
        )
        finish()
    }

    private var bounce: Runnable? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        bounce?.let { handler.removeCallbacks(it) }
        bounce = null
        buildUI()
    }

    private fun buildUI() {
        val dp = resources.displayMetrics.density
        if (fromName.isEmpty()) fromName = getString(R.string.caller_unknown)
        val letter = AvatarHelper.createLetterBitmap(
            AvatarHelper.initialOf(fromName), AvatarHelper.getContactColor(this, fromHash), (150 * dp).toInt())
        val built = IncomingCallScreen.build(
            this, handler, phoneLocked, isVideo,
            getString(if (isVideo) R.string.incoming_video_call else R.string.incoming_voice_call),
            fromName, getString(R.string.app_name), letter,
            loadAvatar = { img ->
                pl.sinusoide.sinuschat.util.ContactCache.getAvatarUrl(this, fromHash)?.takeIf { it.isNotEmpty() }?.let { url ->
                    com.bumptech.glide.Glide.with(this).load(url).circleCrop()
                        .placeholder(android.graphics.drawable.BitmapDrawable(resources, letter))
                        .into(img)
                }
            },
            onAnswer = { answer() },
            onDecline = { reject() })
        setContentView(built.root)
        bounce = built.bounce
    }
}
