package pl.sinusoide.sinuschat.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import org.webrtc.*
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.messenger.MessageRepository
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.network.WebSocketManager
import pl.sinusoide.sinuschat.ui.AuroraBackgroundView
import pl.sinusoide.sinuschat.ui.CallButtonView
import pl.sinusoide.sinuschat.ui.PulseAvatarView
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.PrivacySettings
import androidx.activity.enableEdgeToEdge

class CallActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }

    companion object {
        const val PERM_REQUEST = 100
        const val PERM_CAMERA = 101
        const val EXTRA_RESUME = "resume"
        private const val ANSWER_TIMEOUT_MS = 30_000L
        private const val RING_TIMEOUT_MS = 30_000L

        @Volatile var activeConversationId: String? = null

        fun startAsCaller(ctx: Context, conversationId: String, otherHash: String, otherName: String, isVideo: Boolean) {
            ctx.startActivity(Intent(ctx, CallActivity::class.java).apply {
                putExtra("conversationId", conversationId)
                putExtra("otherUserHash", otherHash)
                putExtra("otherUserName", otherName)
                putExtra("isVideo", isVideo)
                putExtra("isCaller", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        fun startAsCallee(ctx: Context, conversationId: String, otherHash: String, otherName: String, isVideo: Boolean, offerSDP: String) {
            ctx.startActivity(Intent(ctx, CallActivity::class.java).apply {
                putExtra("conversationId", conversationId)
                putExtra("otherUserHash", otherHash)
                putExtra("otherUserName", otherName)
                putExtra("isVideo", isVideo)
                putExtra("isCaller", false)
                putExtra("offerSDP", offerSDP)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private lateinit var conversationId: String
    private lateinit var otherUserHash: String
    private lateinit var otherUserName: String
    private var isVideo  = false
    private var isCaller = true
    private var offerSDP = ""

    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var bigVideo: TextureVideoRenderer
    private lateinit var pipVideo: TextureVideoRenderer
    private lateinit var pipFrame: FrameLayout
    private lateinit var swapFlash: View
    private lateinit var videoContainer: FrameLayout

    private var rtcManager: WebRTCManager? = null
    private val pendingCandidates = mutableListOf<JSONObject>()
    private var remoteDescSet = false
    private var renderersReady = false
    private var remoteVideoShown = false
    private var remoteTrack: VideoTrack? = null
    private var swapped = false
    private var swapping = false
    private var frontCamera = true
    private var cameraOn = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private var callSeconds = 0
    private var callStartedAtMs = System.currentTimeMillis()
    private var wasConnected = false
    private var connectedAtMs = 0L
    private var resumeOnly = false
    private var screenSharing = false
    private var remoteSharing = false
    private var videoBeforeShare = false
    private var cameraBeforeShare = false
    private var uiHidden = false
    private var inPip = false
    @Volatile private var lastRemoteFrameMs = 0L
    @Volatile private var remoteStalled = false

    private val remoteWatch = VideoSink {
        lastRemoteFrameMs = android.os.SystemClock.elapsedRealtime()
        if (remoteStalled) {
            remoteStalled = false
            mainHandler.post { if (!isDestroyed && !remoteVideoShown && remoteTrack != null) onRemoteVideoStarted() }
        }
    }

    private val remoteWatchdog = object : Runnable {
        override fun run() {
            val last = lastRemoteFrameMs
            if (remoteVideoShown && !remoteStalled && last > 0 &&
                android.os.SystemClock.elapsedRealtime() - last > 2500) onRemoteVideoStalled()
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun onRemoteVideoStalled() {
        remoteStalled = true
        remoteSharing = false
        remoteVideoShown = false
        hideRemoteVideoBanner()
        unswapNow()
        bigVideo.clearImage()
        routeVideo()
        applyMode(animate = true)
    }

    private val screenCapture = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) startScreenShare(data)
    }
    private var systemLogged = false
    private var callEnded = false
    private var userHangup = false
    private val timerRunnable = object : Runnable {
        override fun run() {
            callSeconds++
            tvTimer.text = String.format("%02d:%02d", callSeconds / 60, callSeconds % 60)
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_RESUME, false)) {
            resumeOnly = true
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        CallNotifier.cancel(this)
        conversationId = intent.getStringExtra("conversationId") ?: ""
        otherUserHash  = intent.getStringExtra("otherUserHash") ?: ""
        otherUserName  = intent.getStringExtra("otherUserName") ?: getString(R.string.caller_unknown)
        isVideo        = intent.getBooleanExtra("isVideo", false)
        isCaller       = intent.getBooleanExtra("isCaller", true)
        offerSDP       = intent.getStringExtra("offerSDP") ?: ""
        activeConversationId = conversationId

        buildUI()
        checkPermissionsAndStart()
        WebSocketManager.onCallEvent = { msg -> AppExecutor.main { handleSignal(msg) } }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (callEnded) finish() else if (!enterPip()) moveTaskToBack(true)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (resumeOnly) return
        OngoingCallService.stop(this)
        if (activeConversationId == conversationId) activeConversationId = null
        stopOutgoingRingtone()
        endAudioSession()
        mainHandler.removeCallbacksAndMessages(null)
        if (renderersReady) {
            try {
                remoteTrack?.removeSink(bigVideo); remoteTrack?.removeSink(pipVideo)
                rtcManager?.detachLocalVideo(bigVideo); rtcManager?.detachLocalVideo(pipVideo)
            } catch (_: Exception) {}
            bigVideo.release(); pipVideo.release()
        }
        try { remoteTrack?.removeSink(remoteWatch) } catch (_: Exception) {}
        rtcManager?.close()
        WebSocketManager.onCallEvent = null
        if (!callEnded) {
            android.util.Log.d("CallFlow", "onDestroy wysyła call_end (userHangup=$userHangup, wasConnected=$wasConnected)")
            sendSignal("call_end", JSONObject())
        }
        logCallSystemMessage()
    }

    private fun endCall() {
        if (callEnded) return
        callEnded = true
        userHangup = true
        sendSignal("call_end", JSONObject())
        finish()
    }

    private fun logCallSystemMessage() {
        if (systemLogged) return
        systemLogged = true
        if (conversationId.isEmpty()) return
        val ctx = applicationContext
        if (wasConnected) {
            val mm = callSeconds / 60
            val ss = callSeconds % 60
            val dur = String.format("%d:%02d", mm, ss)
            val label = (if (isVideo) getString(R.string.call_label_video) else getString(R.string.call_label_voice)) + " · " + dur
            pl.sinusoide.sinuschat.util.AppExecutor.io {
                MessageRepository.insertSystemMessage(ctx, conversationId, label, "call_done")
            }
        }

        val direction = when {
            isCaller -> pl.sinusoide.sinuschat.data.CallLogRepository.OUTGOING
            wasConnected -> pl.sinusoide.sinuschat.data.CallLogRepository.INCOMING
            else -> pl.sinusoide.sinuschat.data.CallLogRepository.MISSED
        }
        pl.sinusoide.sinuschat.data.CallLogRepository.log(
            ctx, otherUserHash, otherUserName, conversationId,
            isVideo, direction, if (wasConnected) callSeconds else 0, callStartedAtMs
        )
    }

    private fun checkPermissionsAndStart() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (isVideo) perms.add(Manifest.permission.CAMERA)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCall()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQUEST)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        if (rc == PERM_CAMERA) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) enableVideoNow()
            else PopupHelper.showError(this, getString(R.string.call_camera_permission))
            return
        }
        if (rc == PERM_REQUEST && results.all { it == PackageManager.PERMISSION_GRANTED }) startCall()
        else { tvStatus.text = getString(R.string.no_permission); mainHandler.postDelayed({ finish() }, 2000) }
    }

    private fun startCall() {
        pl.sinusoide.sinuschat.util.SafeEngine.guardTor(this, pl.sinusoide.sinuschat.util.SafeEngine.Risk.TOR_CALL,
            onCancel = {
                if (isCaller) { callEnded = true; systemLogged = true; finish() } else endCall()
            }
        ) { if (!isFinishing && !isDestroyed) startCallConfirmed() }
    }

    private fun startCallConfirmed() {
        if (!isCaller && CallNotifier.isCancelled(conversationId)) {
            systemLogged = true
            showEnd(R.string.call_peer_hung_up)
            return
        }
        WalkieTalkie.session?.stop()
        OngoingCallService.onHangUp = { endCall() }
        OngoingCallService.onToggleMic = { if (!isDestroyed) toggleMic() }
        OngoingCallService.onToggleSpeaker = { if (!isDestroyed) setSpeaker(!speakerOn) }
        OngoingCallService.start(this, otherUserName, isVideo)
        if (isVideo) setSpeaker(true)
        beginAudioSession()
        tvStatus.text = if (isCaller) getString(R.string.calling) else getString(R.string.connecting_server)
        if (!hasNetwork()) { failNoNetwork(); return }
        if (!WebSocketManager.isConnected()) WebSocketManager.connect(applicationContext)
        mainHandler.postDelayed(netWatch, 1000)
        if (!isCaller) mainHandler.postDelayed(answerTimeout, ANSWER_TIMEOUT_MS)
        AppExecutor.io {
            try {
                val app = applicationContext
                val turnTask = java.util.concurrent.FutureTask { CallPrep.turn(app) }
                Thread(turnTask, "call-turn").start()
                callChannel = CallPrep.channel(app, otherUserHash)
                val creds = try { turnTask.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null }
                if (creds == null) {
                    AppExecutor.main { failConnect() }
                    return@io
                }
                val iceServers = mutableListOf<PeerConnection.IceServer>()
                try {
                    val urlsArray = creds.optJSONArray("urls")
                    val username   = creds.optString("username")
                    val credential = creds.optString("credential")

                    if (urlsArray != null) {
                        for (i in 0 until urlsArray.length()) {
                            var url = urlsArray.getString(i)
                            if (url.startsWith("turns:")) {
                                url = url.substringBefore("?")
                            }
                            val builder = PeerConnection.IceServer.builder(url)
                            if ((url.startsWith("turn:") || url.startsWith("turns:")) && username.isNotEmpty()) {
                                builder.setUsername(username).setPassword(credential)
                            }
                            try {
                                iceServers.add(builder.createIceServer())
                            } catch (e: Exception) {
                                android.util.Log.e("CallActivity", "Pomijam zły ICE URL: $url", e)
                            }
                        }
                    }
                    android.util.Log.d("CallActivity", "ICE servers: ${iceServers.size}")
                } catch (e: Exception) {
                    android.util.Log.e("CallActivity", "TURN parse error", e)
                }
                AppExecutor.main {
                    try {
                        val options = PeerConnectionFactory.InitializationOptions
                            .builder(applicationContext)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
                        PeerConnectionFactory.initialize(options)

                        rtcManager = WebRTCManager(
                            context = this,
                            isVideoCall = isVideo,
                            onIceCandidate = { c ->
                                sendSignal("call_ice", JSONObject()
                                    .put("sdpMid", c.sdpMid)
                                    .put("sdpMLineIndex", c.sdpMLineIndex)
                                    .put("candidate", c.sdp))
                            },
                            onIceConnectionChange = { state -> AppExecutor.main { handleIce(state) } },
                            onRemoteVideoTrack = { track ->
                                AppExecutor.main {
                                    if (!isFinishing && !isDestroyed) {
                                        remoteTrack = track
                                        track.addSink(remoteWatch)
                                        routeVideo()
                                        onRemoteVideoStarted()
                                    }
                                }
                            },
                            onError = { err ->
                                AppExecutor.main {
                                    tvStatus.text = getString(R.string.error) + " WebRTC: $err"
                                    android.util.Log.e("CallActivity", "WebRTC error: $err")
                                }
                            }
                        )
                        initRenderers()
                        rtcManager?.initialize(iceServers)
                        if (isVideo) routeVideo()

                        if (isCaller) {
                            rtcManager?.createOffer { sdp ->
                                offerSent = sendSignal("call_offer", JSONObject()
                                    .put("sdpType", sdp.type.canonicalForm())
                                    .put("sdpDescription", sdp.description)
                                    .put("isVideo", isVideo))
                                startOutgoingRingtone()
                                mainHandler.removeCallbacks(ringTimeout)
                                mainHandler.postDelayed(ringTimeout, RING_TIMEOUT_MS)
                            }
                        } else {
                            rtcManager?.setRemoteDescription(SessionDescription(
                                SessionDescription.Type.fromCanonicalForm("offer"), offerSDP))
                            remoteDescSet = true; applyPending()
                            rtcManager?.createAnswer { sdp ->
                                sendSignal("call_answer", JSONObject()
                                    .put("sdpType", sdp.type.canonicalForm())
                                    .put("sdpDescription", sdp.description))
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CallActivity", "startCall UI error", e)
                        tvStatus.text = getString(R.string.error) + ": ${e.localizedMessage}"
                        PopupHelper.showError(this, getString(R.string.error_connection) + ": ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CallActivity", "startCall IO error", e)
                AppExecutor.main {
                    tvStatus.text = getString(R.string.error) + " IO: ${e.localizedMessage}"
                    PopupHelper.showError(this, getString(R.string.error) + ": ${e.message}")
                }
            }
        }
    }

    private fun initRenderers() {
        if (renderersReady) return
        val egl = rtcManager?.rootEglBase?.eglBaseContext ?: return
        bigVideo.init(egl)
        pipVideo.init(egl)
        renderersReady = true
    }

    private fun routeVideo() {
        if (!renderersReady) return
        val rtc = rtcManager ?: return
        val localTarget = if (swapped) bigVideo else pipVideo
        val remoteTarget = if (swapped) pipVideo else bigVideo
        rtc.detachLocalVideo(remoteTarget)
        remoteTrack?.removeSink(localTarget)
        if (screenSharing) rtc.detachLocalVideo(localTarget) else rtc.attachLocalVideo(localTarget)
        remoteTrack?.addSink(remoteTarget)
        localTarget.setMirror(frontCamera && !screenSharing)
        remoteTarget.setMirror(false)
        localTarget.setFit(false)
        remoteTarget.setFit(remoteSharing)
    }

    private fun requestScreenShare() {
        val rtc = rtcManager ?: return
        if (!wasConnected) { PopupHelper.show(this, getString(R.string.call_screen_wait)); return }
        if (!rtc.isNegotiationStable()) { PopupHelper.show(this, getString(R.string.call_video_busy)); return }
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java) ?: return
        try {
            screenCapture.launch(mpm.createScreenCaptureIntent())
        } catch (_: Exception) {
            PopupHelper.showError(this, getString(R.string.call_screen_failed))
        }
    }

    private fun startScreenShare(data: Intent) {
        val rtc = rtcManager ?: return
        if (screenSharing) return
        if (!OngoingCallService.allowScreenCapture()) {
            PopupHelper.showError(this, getString(R.string.call_screen_failed)); return
        }
        initRenderers()
        val wasVideo = isVideo
        val wasCamera = isVideo && cameraOn
        val started = rtc.startScreenShare(data) { runOnUiThread { stopScreenShare(toCamera = false) } }
        if (started == WebRTCManager.ShareStart.FAILED) {
            PopupHelper.showError(this, getString(R.string.call_screen_failed)); return
        }
        if (started == WebRTCManager.ShareStart.NEEDS_OFFER) {
            rtc.createOffer { sdp ->
                sendSignal("call_reoffer", JSONObject()
                    .put("sdpType", sdp.type.canonicalForm())
                    .put("sdpDescription", sdp.description)
                    .put("isVideo", true)
                    .put("screen", true))
            }
        }
        videoBeforeShare = wasVideo
        cameraBeforeShare = wasCamera
        screenSharing = true
        isVideo = true
        cameraOn = false
        unswapNow()
        routeVideo()
        btnScreen.active = true
        btnCam.setIcon(R.drawable.ic_call_video_off)
        btnCam.active = true
        pipFrame.isClickable = false
        pipFrame.animate().alpha(0f).setDuration(200).start()
        if (!speakerOn) setSpeaker(true)
        hideRemoteVideoBanner()
        applyMode(animate = true)
        OngoingCallService.update(this, true, connectedAtMs)
        sendSignal("call_screen", JSONObject().put("on", true))
        PopupHelper.show(this, getString(R.string.call_screen_on))
    }

    private fun stopScreenShare(toCamera: Boolean) {
        if (!screenSharing) return
        screenSharing = false
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val camera = (toCamera || cameraBeforeShare) && allowed
        rtcManager?.stopScreenShare(camera)
        cameraOn = camera
        isVideo = videoBeforeShare || camera
        if (camera) frontCamera = true
        routeVideo()
        btnScreen.active = false
        btnCam.setIcon(if (camera) R.drawable.ic_call_video_on else R.drawable.ic_call_video_off)
        btnCam.active = !camera
        pipFrame.isClickable = camera
        applyMode(animate = true)
        OngoingCallService.update(this, isVideo, connectedAtMs)
        sendSignal("call_screen", JSONObject().put("on", false).put("camera", camera))
        if (toCamera && !allowed) requestVideoUpgrade()
    }

    private fun onRemoteScreen(on: Boolean, camera: Boolean) {
        val changed = remoteSharing != on
        remoteSharing = on
        if (on) unswapNow()
        routeVideo()
        if (on) {
            hideRemoteVideoBanner()
            if (changed) PopupHelper.show(this, getString(R.string.call_screen_remote, otherUserName))
        } else if (camera) {
            if (!remoteVideoShown && remoteTrack != null) onRemoteVideoStarted()
            else if (!isVideo) showRemoteVideoBanner()
        } else if (remoteVideoShown) {
            remoteVideoShown = false
            unswapNow()
            applyMode(animate = true)
        }
    }

    private fun canSwap() = isVideo && remoteVideoShown && cameraOn && renderersReady

    private fun swapVideos() {
        if (swapping || !canSwap()) return
        swapping = true
        pipFrame.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        swapFlash.animate().cancel()
        swapFlash.animate().alpha(1f).setDuration(140).withEndAction {
            swapFlash.animate().alpha(0f).setDuration(320).start()
        }.start()
        pipFrame.animate().scaleX(0.8f).scaleY(0.8f).alpha(0f).setDuration(140)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                swapped = !swapped
                bigVideo.clearImage(); pipVideo.clearImage()
                routeVideo()
                pipFrame.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380)
                    .setInterpolator(OvershootInterpolator(1.6f))
                    .withEndAction { swapping = false }.start()
            }.start()
    }

    private fun unswapNow() {
        if (!swapped) return
        swapped = false
        bigVideo.clearImage(); pipVideo.clearImage()
        routeVideo()
    }

    private fun requestVideoUpgrade() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERM_CAMERA)
        } else enableVideoNow()
    }

    private fun enableVideoNow() {
        val rtc = rtcManager ?: return
        if (!rtc.isNegotiationStable()) {
            PopupHelper.show(this, getString(R.string.call_video_busy)); return
        }
        initRenderers()
        val needsRenegotiation = rtc.enableVideo()
        cameraOn = true
        isVideo = true
        OngoingCallService.update(this, true, connectedAtMs)
        routeVideo()
        if (needsRenegotiation) {
            rtc.createOffer { sdp ->
                sendSignal("call_reoffer", JSONObject()
                    .put("sdpType", sdp.type.canonicalForm())
                    .put("sdpDescription", sdp.description)
                    .put("isVideo", true))
            }
        } else {
            sendSignal("call_screen", JSONObject().put("on", false).put("camera", true))
        }
        setSpeaker(true)
        hideRemoteVideoBanner()
        applyMode(animate = true)
    }

    private fun onRemoteVideoStarted() {
        remoteVideoShown = true
        applyMode(animate = true)
        if (!isVideo && !remoteSharing) showRemoteVideoBanner()
    }

    private fun setUiHidden(hide: Boolean) {
        if (uiHidden == hide) return
        uiHidden = hide
        val chrome = listOf<View>(topBox, controls, scrimTop, scrimBottom)
        if (hide) {
            chrome.forEach { v -> v.animate().alpha(0f).setDuration(220).withEndAction { if (uiHidden) v.visibility = View.INVISIBLE }.start() }
            pipFrame.isClickable = false
            pipFrame.animate().alpha(0f).setDuration(220).start()
            remoteBanner?.animate()?.alpha(0f)?.setDuration(220)?.start()
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            topBox.visibility = View.VISIBLE
            controls.visibility = View.VISIBLE
            applyMode(animate = false)
            chrome.forEach { v -> v.animate().alpha(1f).setDuration(220).start() }
            val pipOn = cameraOn && !screenSharing
            pipFrame.isClickable = pipOn
            pipFrame.animate().alpha(if (pipOn) 1f else 0f).setDuration(220).start()
            remoteBanner?.animate()?.alpha(1f)?.setDuration(220)?.start()
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private var outgoingTone: android.media.MediaPlayer? = null
    private var outgoingVibrator: android.os.Vibrator? = null

    private fun startOutgoingRingtone() {
        if (!isCaller) return
        runOnUiThread {
            if (isFinishing || isDestroyed || callEnded || wasConnected) return@runOnUiThread
            stopOutgoingRingtone()
            val choice = pl.sinusoide.sinuschat.util.SoundPrefs.outgoing(this)
            if (choice == pl.sinusoide.sinuschat.util.SoundPrefs.Outgoing.TONE) {
                try {
                    outgoingTone = android.media.MediaPlayer().apply {
                        setAudioAttributes(android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                        setDataSource(this@CallActivity, pl.sinusoide.sinuschat.util.SoundPrefs.rawUri(this@CallActivity, choice.raw))
                        isLooping = true
                        setOnPreparedListener { mp -> if (outgoingTone === mp) mp.start() }
                        prepareAsync()
                    }
                } catch (_: Exception) { stopOutgoingRingtone() }
                return@runOnUiThread
            }
            try {
                outgoingVibrator = getSystemService(android.os.Vibrator::class.java)
                val pattern = longArrayOf(0, 300, 1700)
                outgoingVibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0))
            } catch (_: Exception) {}
        }
    }

    private fun stopOutgoingRingtone() {
        outgoingTone?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        outgoingTone = null
        try { outgoingVibrator?.cancel() } catch (_: Exception) {}
        outgoingVibrator = null
    }

    private val relayCheck = Runnable {
        if (wasConnected || isFinishing) return@Runnable
        if (!pl.sinusoide.sinuschat.util.PrivacySettings.isCallRelayEnabled(this)) return@Runnable
        if ((rtcManager?.localCandidates ?: 0) > 0) return@Runnable
        android.util.Log.w("CallActivity", "Tryb ukrytego IP: brak kandydatów z serwera TURN")
        tvStatus.text = getString(R.string.call_relay_unavailable)
        tvStatus.visibility = View.VISIBLE
    }

    private var routeChip: TextView? = null

    private val routeCheck = Runnable {
        rtcManager?.connectionRoute { local, remote ->
            AppExecutor.main { if (!isFinishing && !isDestroyed) showRoute(local, remote) }
        }
    }

    private fun showRoute(local: String?, remote: String?) {
        val chip = routeChip ?: return
        android.util.Log.d("CallActivity", "Trasa połączenia: moja=$local, rozmówcy=$remote")
        if (local == null) { chip.visibility = View.GONE; return }
        val dp = resources.displayMetrics.density
        val relayWanted = pl.sinusoide.sinuschat.util.PrivacySettings.isCallRelayEnabled(this)
        val (text, color, icon) = when {
            local == "relay" -> Triple(getString(R.string.call_route_relay), 0xFF3FB97A.toInt(), R.drawable.ic_shield)
            relayWanted -> Triple(getString(R.string.call_route_direct_warn), 0xFFE89A3C.toInt(), R.drawable.ic_warning)
            else -> Triple(getString(R.string.call_route_direct), 0xCCFFFFFF.toInt(), R.drawable.ic_encryptionerror)
        }
        chip.text = text
        chip.setTextColor(color)
        val iconPx = (15 * dp).toInt()
        val ic = androidx.core.content.ContextCompat.getDrawable(this, icon)?.mutate()?.apply {
            setBounds(0, 0, iconPx, iconPx)
            setTint(color)
        }
        chip.setCompoundDrawablesRelative(ic, null, null, null)
        chip.compoundDrawablePadding = (6 * dp).toInt()
        chip.background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor((color and 0x00FFFFFF) or 0x26000000)
            setStroke((1 * dp).toInt().coerceAtLeast(1), (color and 0x00FFFFFF) or 0x55000000)
        }
        if (chip.visibility != View.VISIBLE) {
            chip.alpha = 0f; chip.visibility = View.VISIBLE
            chip.animate().alpha(1f).setDuration(250).start()
        }
    }

    private fun handleIce(state: PeerConnection.IceConnectionState) {
        when (state) {
            PeerConnection.IceConnectionState.CHECKING -> {
                mainHandler.removeCallbacks(relayCheck)
                mainHandler.postDelayed(relayCheck, 15_000)
            }
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                mainHandler.removeCallbacks(relayCheck)
                mainHandler.removeCallbacks(routeCheck)
                mainHandler.postDelayed(routeCheck, 1500)
                stopOutgoingRingtone()
                if (!wasConnected) {
                    wasConnected = true
                    connectedAtMs = System.currentTimeMillis()
                    OngoingCallService.update(this, isVideo, connectedAtMs)
                    mainHandler.postDelayed(remoteWatchdog, 1000)
                    tvStatus.visibility = View.GONE
                    tvTimer.visibility = View.VISIBLE
                    mainHandler.post(timerRunnable)
                    avatarView?.ringing = false
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                } else {
                    tvStatus.visibility = View.GONE
                    tvTimer.visibility = View.VISIBLE
                }
                mainHandler.removeCallbacks(disconnectTimeout)
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                tvStatus.text = getString(R.string.reconnecting)
                tvStatus.visibility = View.VISIBLE
                tvTimer.visibility = View.GONE
                mainHandler.removeCallbacks(disconnectTimeout)
                mainHandler.postDelayed(disconnectTimeout, 12_000)
            }
            PeerConnection.IceConnectionState.FAILED,
            PeerConnection.IceConnectionState.CLOSED -> {
                stopOutgoingRingtone()
                tvStatus.text = getString(R.string.finished)
                tvStatus.visibility = View.VISIBLE
                tvTimer.visibility = View.GONE
                mainHandler.postDelayed({ finish() }, 1500)
            }
            else -> {}
        }
    }

    private val disconnectTimeout = Runnable {
        stopOutgoingRingtone()
        tvStatus.text = getString(R.string.finished)
        tvStatus.visibility = View.VISIBLE
        mainHandler.postDelayed({ finish() }, 1000)
    }

    private fun handleSignal(msg: JSONObject) {
        when (msg.optString("type")) {
            "call_answer" -> if (isCaller) {
                answered = true
                mainHandler.removeCallbacks(ringTimeout)
                rtcManager?.setRemoteDescription(SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(msg.optString("sdpType", "answer")),
                    msg.optString("sdpDescription")))
                remoteDescSet = true; applyPending()
            }
            "call_ice" -> {
                val c = IceCandidate(msg.optString("sdpMid"), msg.optInt("sdpMLineIndex"), msg.optString("candidate"))
                if (remoteDescSet) rtcManager?.addIceCandidate(c)
                else pendingCandidates.add(msg)
            }
            "call_reoffer" -> {
                val sdp = msg.optString("sdpDescription")
                if (msg.optBoolean("screen")) onRemoteScreen(true, false)
                if (sdp.isNotEmpty()) {
                    rtcManager?.answerRenegotiation(SessionDescription(SessionDescription.Type.OFFER, sdp)) { answer ->
                        sendSignal("call_reanswer", JSONObject()
                            .put("sdpType", answer.type.canonicalForm())
                            .put("sdpDescription", answer.description))
                    }
                }
            }
            "call_reanswer" -> {
                val sdp = msg.optString("sdpDescription")
                if (sdp.isNotEmpty()) {
                    rtcManager?.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }
            }
            "call_screen" -> onRemoteScreen(msg.optBoolean("on"), msg.optBoolean("camera", true))
            "call_end", "call_reject" -> {
                val reject = msg.optString("type") == "call_reject"
                val reason = msg.optString("reason")
                when {
                    wasConnected || callEnded -> {
                        callEnded = true
                        tvStatus.text = getString(R.string.finished); tvStatus.visibility = View.VISIBLE
                        finish()
                    }
                    reject && isCaller && reason == "timeout" -> showEnd(R.string.call_no_answer)
                    reject && isCaller && (reason.isEmpty() || reason == "declined") -> showEnd(R.string.call_peer_rejected)
                    !reject -> showEnd(R.string.call_peer_hung_up)
                    else -> {
                        callEnded = true
                        finish()
                    }
                }
            }
        }
    }

    private fun applyPending() {
        pendingCandidates.forEach { m ->
            rtcManager?.addIceCandidate(IceCandidate(m.optString("sdpMid"), m.optInt("sdpMLineIndex"), m.optString("candidate")))
        }
        pendingCandidates.clear()
    }

    @Volatile private var callChannel: CallCrypto.Channel? = null
    private var keyErrorShown = false

    private fun sendSignal(type: String, extra: JSONObject): Boolean {
        val ch = callChannel
        val body = when {
            ch != null -> CallCrypto.seal(this, ch, conversationId, type, extra)
            type in CallCrypto.SEALED && CallCrypto.isPinned(this, otherUserHash) -> null
            else -> extra
        }
        if (body == null) {
            android.util.Log.w("CallFlow", "Nie wysłano $type — brak klucza rozmówcy")
            if (!keyErrorShown) {
                keyErrorShown = true
                AppExecutor.main { if (!isFinishing) PopupHelper.showError(this, getString(R.string.no_e2e_key)) }
            }
            return false
        }
        val msg = JSONObject(body.toString()).put("type", type).put("conversationId", conversationId)
        android.util.Log.d("CallFlow", "WYSYŁAM $type (isCaller=$isCaller)")
        return WebSocketManager.send(msg)
    }

    @Volatile private var offerSent = false
    private var offlineSince = 0L

    private val netWatch = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || wasConnected || callEnded) return
            if (WebSocketManager.isConnected()) offlineSince = 0L
            else {
                val now = android.os.SystemClock.elapsedRealtime()
                if (offlineSince == 0L) offlineSince = now
                else if (now - offlineSince >= CallNetwork.offlineLimitMs(this@CallActivity)) { failNoNetwork(); return }
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    @Volatile private var answered = false

    private fun showEnd(res: Int) {
        if (isFinishing || isDestroyed) return
        callEnded = true
        mainHandler.removeCallbacks(netWatch)
        mainHandler.removeCallbacks(ringTimeout)
        mainHandler.removeCallbacks(answerTimeout)
        stopOutgoingRingtone()
        tvStatus.text = getString(res)
        tvStatus.visibility = View.VISIBLE
        tvTimer.visibility = View.GONE
        avatarView?.ringing = false
        mainHandler.postDelayed({ if (!isFinishing && !isDestroyed) finish() }, 2500)
    }

    private fun failConnect() {
        if (isFinishing || isDestroyed || callEnded || wasConnected) return
        sendSignal("call_end", JSONObject())
        showEnd(R.string.call_connect_failed)
    }

    private val ringTimeout = Runnable {
        if (isFinishing || isDestroyed || wasConnected || callEnded || answered) return@Runnable
        callEnded = true
        mainHandler.removeCallbacks(netWatch)
        stopOutgoingRingtone()
        sendSignal("call_end", JSONObject())
        tvStatus.text = getString(R.string.call_no_answer)
        tvStatus.visibility = View.VISIBLE
        tvTimer.visibility = View.GONE
        avatarView?.ringing = false
        mainHandler.postDelayed({ finish() }, 2500)
    }

    private val answerTimeout = Runnable {
        if (isFinishing || isDestroyed || wasConnected || callEnded) return@Runnable
        mainHandler.removeCallbacks(netWatch)
        tvStatus.text = getString(R.string.call_connect_failed)
        tvStatus.visibility = View.VISIBLE
        tvTimer.visibility = View.GONE
        avatarView?.ringing = false
        mainHandler.postDelayed({ finish() }, 2500)
    }

    private fun activeCaps(): android.net.NetworkCapabilities? {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(net)
    }

    private fun hasNetwork(): Boolean =
        activeCaps()?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun failNoNetwork() {
        if (isFinishing || isDestroyed || callEnded || wasConnected) return
        mainHandler.removeCallbacks(netWatch)
        stopOutgoingRingtone()
        WebSocketManager.dropQueuedCall(conversationId)
        if (isCaller && !offerSent) callEnded = true
        val wifi = activeCaps()?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        tvStatus.text = getString(if (wifi) R.string.call_no_net_wifi else R.string.call_no_net)
        tvStatus.visibility = View.VISIBLE
        tvTimer.visibility = View.GONE
        avatarView?.ringing = false
        PopupHelper.showError(this, getString(if (wifi) R.string.call_no_net_wifi_hint else R.string.call_no_net_hint))
        mainHandler.postDelayed({ finish() }, 3500)
    }

    private val dp get() = resources.displayMetrics.density
    private var avatarView: PulseAvatarView? = null
    private lateinit var topBox: LinearLayout
    private lateinit var scrimTop: View
    private lateinit var scrimBottom: View
    private lateinit var controls: LinearLayout
    private lateinit var btnMic: CallButtonView
    private lateinit var btnCam: CallButtonView
    private lateinit var btnScreen: CallButtonView
    private lateinit var btnSpk: CallButtonView
    private lateinit var flipColumn: View
    private var remoteBanner: View? = null
    private var speakerOn = false
    private var micOn = true
    private var audioSession = false
    private var savedAudioMode = android.media.AudioManager.MODE_NORMAL
    private var audioFocus: android.media.AudioFocusRequest? = null

    private lateinit var themePalette: pl.sinusoide.sinuschat.ui.LockPalette
    private lateinit var videoPalette: pl.sinusoide.sinuschat.ui.LockPalette
    private lateinit var tvName: TextView
    private lateinit var statusPill: FrameLayout
    private lateinit var controlsPanel: LinearLayout
    private lateinit var endButton: CallButtonView
    private var compactCall = false
    private val buttonLabels = mutableListOf<TextView>()
    private val glassButtons = mutableListOf<CallButtonView>()

    private fun buildUI() {
        themePalette = pl.sinusoide.sinuschat.ui.LockPalette.of(this)
        videoPalette = pl.sinusoide.sinuschat.ui.LockPalette.glass(themePalette.accent)
        val root = FrameLayout(this).apply { setBackgroundColor(themePalette.background) }
        setContentView(root)

        root.addView(AuroraBackgroundView(this, themePalette), FrameLayout.LayoutParams(-1, -1))

        videoContainer = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        root.addView(videoContainer, FrameLayout.LayoutParams(-1, -1))
        bigVideo = TextureVideoRenderer(this, "big")
        videoContainer.addView(bigVideo, FrameLayout.LayoutParams(-1, -1))
        videoContainer.setOnClickListener { setUiHidden(!uiHidden) }
        swapFlash = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        videoContainer.addView(swapFlash, FrameLayout.LayoutParams(-1, -1))

        val pipCorner = 30 * dp
        pipFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = pipCorner; setColor(0xFF141B24.toInt()) }
            foreground = GradientDrawable().apply {
                cornerRadius = pipCorner; setStroke((2 * dp).toInt(), 0x59FFFFFF)
            }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            contentDescription = getString(R.string.call_swap_video)
            setOnClickListener { swapVideos() }
            visibility = View.GONE
        }
        pipVideo = TextureVideoRenderer(this, "pip")
        pipFrame.addView(pipVideo, FrameLayout.LayoutParams(-1, -1))

        scrimTop = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0x00000000))
            visibility = View.GONE
        }
        root.addView(scrimTop, FrameLayout.LayoutParams(-1, (200 * dp).toInt(), Gravity.TOP))
        scrimBottom = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
            visibility = View.GONE
        }
        root.addView(scrimBottom, FrameLayout.LayoutParams(-1, (300 * dp).toInt(), Gravity.BOTTOM))

        root.addView(pipFrame, FrameLayout.LayoutParams((116 * dp).toInt(), (168 * dp).toInt()).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (110 * dp).toInt(); rightMargin = (16 * dp).toInt()
        })

        topBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(topBox, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        tvName = TextView(this).apply {
            text = otherUserName
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(8f, 0f, 2f, 0x66000000)
        }
        topBox.addView(tvName, LinearLayout.LayoutParams(-1, -2).apply { marginStart = (24 * dp).toInt(); marginEnd = (24 * dp).toInt() })

        val pill = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp; setColor(0x1FFFFFFF); setStroke((1 * dp).toInt(), 0x33FFFFFF)
            }
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
        }
        tvStatus = TextView(this).apply {
            text = getString(R.string.calling)
            textSize = 14f; setTextColor(0xE6FFFFFF.toInt()); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        tvTimer = TextView(this).apply {
            text = "00:00"
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            visibility = View.GONE
        }
        pill.addView(tvStatus); pill.addView(tvTimer)
        statusPill = pill
        topBox.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (10 * dp).toInt() })

        routeChip = TextView(this).apply {
            textSize = 12.5f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
            visibility = View.GONE
        }
        topBox.addView(routeChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * dp).toInt() })

        val avatarSize = (260 * dp).toInt()
        val avatar = PulseAvatarView(this, themePalette)
        val letter = AvatarHelper.createLetterBitmap(
            AvatarHelper.initialOf(otherUserName), AvatarHelper.getContactColor(this, otherUserHash), (160 * dp).toInt())
        avatar.setAvatar(letter)
        pl.sinusoide.sinuschat.util.ContactCache.getAvatarUrl(this, otherUserHash)?.takeIf { it.isNotEmpty() }?.let { url ->
            com.bumptech.glide.Glide.with(this).load(url).circleCrop()
                .placeholder(android.graphics.drawable.BitmapDrawable(resources, letter))
                .into(avatar.imageView())
        }
        avatarView = avatar
        root.addView(avatar, FrameLayout.LayoutParams(avatarSize, avatarSize, Gravity.CENTER).apply {
            bottomMargin = (70 * dp).toInt()
        })

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding((10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt())
        }
        controlsPanel = panel
        btnMic = CallButtonView(this, R.drawable.ic_mic, palette = themePalette)
        btnMic.setOnClickListener { toggleMic() }
        panel.addView(buttonColumn(btnMic, getString(R.string.call_btn_mic)), LinearLayout.LayoutParams(0, -2, 1f))

        btnCam = CallButtonView(this, R.drawable.ic_call_video_on, palette = themePalette)
        btnCam.setOnClickListener {
            val rtc = rtcManager ?: return@setOnClickListener
            if (screenSharing) { stopScreenShare(toCamera = true); return@setOnClickListener }
            if (!isVideo) { requestVideoUpgrade(); return@setOnClickListener }
            val on = rtc.toggleCamera()
            cameraOn = on
            btnCam.setIcon(if (on) R.drawable.ic_call_video_on else R.drawable.ic_call_video_off)
            btnCam.active = !on
            if (!on) unswapNow()
            pipFrame.isClickable = on
            pipFrame.animate().alpha(if (on) 1f else 0f).setDuration(200).start()
        }
        panel.addView(buttonColumn(btnCam, getString(R.string.call_btn_camera)), LinearLayout.LayoutParams(0, -2, 1f))

        btnScreen = CallButtonView(this, R.drawable.ic_screen_share, palette = themePalette)
        btnScreen.setOnClickListener {
            if (screenSharing) stopScreenShare(toCamera = false) else requestScreenShare()
        }
        panel.addView(buttonColumn(btnScreen, getString(R.string.call_btn_screen)), LinearLayout.LayoutParams(0, -2, 1f))

        val btnFlip = CallButtonView(this, R.drawable.ic_call_switch_camera, palette = themePalette)
        btnFlip.setOnClickListener {
            rtcManager?.switchCamera { front ->
                AppExecutor.main {
                    if (isDestroyed) return@main
                    frontCamera = front
                    (if (swapped) bigVideo else pipVideo).setMirror(front)
                }
            }
        }
        flipColumn = buttonColumn(btnFlip, getString(R.string.call_btn_flip))
        panel.addView(flipColumn, LinearLayout.LayoutParams(0, -2, 1f))

        btnSpk = CallButtonView(this, R.drawable.ic_call_speaker_off, palette = themePalette)
        btnSpk.setOnClickListener { setSpeaker(!speakerOn) }
        panel.addView(buttonColumn(btnSpk, getString(R.string.call_btn_speaker)), LinearLayout.LayoutParams(0, -2, 1f))

        controls.addView(panel, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = (16 * dp).toInt(); marginEnd = (16 * dp).toInt()
        })

        endButton = CallButtonView(this, R.drawable.ic_call_end, 0xFFE5484D.toInt(), themePalette).apply {
            contentDescription = getString(R.string.call_btn_end)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                endCall()
            }
        }
        controls.addView(endButton, LinearLayout.LayoutParams((80 * dp).toInt(), (80 * dp).toInt()).apply {
            topMargin = (22 * dp).toInt()
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val gap = ((if (compactCall) 12 else 28) * dp).toInt()
            topBox.setPadding(bars.left, bars.top + gap, bars.right, 0)
            controls.setPadding(bars.left, 0, bars.right, bars.bottom + gap)
            (pipFrame.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + ((if (compactCall) 16 else 110) * dp).toInt()
                rightMargin = bars.right + (16 * dp).toInt()
            }
            pipFrame.requestLayout()
            insets
        }

        applyOrientation()
        applyMode(animate = false)

        avatar.scaleX = 0.7f; avatar.scaleY = 0.7f; avatar.alpha = 0f
        avatar.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(600).setInterpolator(OvershootInterpolator(1.4f)).start()
        controls.translationY = 60 * dp; controls.alpha = 0f
        controls.animate().translationY(0f).alpha(1f).setStartDelay(150).setDuration(480)
            .setInterpolator(DecelerateInterpolator(1.8f)).start()
        topBox.alpha = 0f
        topBox.animate().alpha(1f).setStartDelay(80).setDuration(420).start()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!inPip && ::endButton.isInitialized) applyOrientation()
    }

    private fun applyOrientation() {
        val cfg = resources.configuration
        compactCall = cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenHeightDp in 1..479
        avatarView?.let { avatar ->
            val size = ((if (compactCall) (cfg.screenHeightDp - 250).coerceIn(96, 260) else 260) * dp).toInt()
            (avatar.layoutParams as FrameLayout.LayoutParams).apply {
                width = size; height = size
                bottomMargin = if (compactCall) 0 else (70 * dp).toInt()
            }
            avatar.requestLayout()
        }
        (pipFrame.layoutParams as FrameLayout.LayoutParams).apply {
            width = ((if (compactCall) 168 else 116) * dp).toInt()
            height = ((if (compactCall) 116 else 168) * dp).toInt()
        }
        pipFrame.requestLayout()
        val target = if (compactCall) controlsPanel else controls
        if (endButton.parent !== target) {
            (endButton.parent as? android.view.ViewGroup)?.removeView(endButton)
            if (compactCall) {
                val size = (62 * dp).toInt()
                controlsPanel.addView(endButton, LinearLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = (6 * dp).toInt(); marginEnd = (4 * dp).toInt()
                })
            } else {
                controls.addView(endButton, LinearLayout.LayoutParams((80 * dp).toInt(), (80 * dp).toInt()).apply {
                    topMargin = (22 * dp).toInt()
                })
            }
        }
        findViewById<View>(android.R.id.content)?.let { ViewCompat.requestApplyInsets(it) }
    }

    private fun buttonColumn(button: CallButtonView, label: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        button.contentDescription = label
        glassButtons.add(button)
        val size = minOf(62 * dp, (resources.displayMetrics.widthPixels - 52 * dp) / 5f - 6 * dp).toInt()
        addView(button, LinearLayout.LayoutParams(size, size))
        addView(TextView(this@CallActivity).apply {
            text = label
            textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
            maxLines = 1
            buttonLabels.add(this)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * dp).toInt() })
    }

    private fun toggleMic() {
        val manager = rtcManager ?: return
        micOn = manager.toggleMic()
        btnMic.setIcon(if (micOn) R.drawable.ic_mic else R.drawable.ic_mic_off)
        btnMic.active = !micOn
        OngoingCallService.setAudio(this, micOn, speakerOn)
        if (inPip) updatePip()
    }

    private fun beginAudioSession() {
        if (audioSession) return
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        savedAudioMode = am.mode
        val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .build()
        try { am.requestAudioFocus(focus) } catch (_: Exception) {}
        audioFocus = focus
        am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        audioSession = true
        routeSpeaker(am, speakerOn)
    }

    private fun endAudioSession() {
        if (!audioSession) return
        audioSession = false
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        try {
            routeSpeaker(am, false)
            am.mode = if (savedAudioMode == android.media.AudioManager.MODE_IN_COMMUNICATION)
                android.media.AudioManager.MODE_NORMAL else savedAudioMode
            audioFocus?.let { am.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {}
        audioFocus = null
    }

    private fun routeSpeaker(am: android.media.AudioManager, on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (on) am.availableCommunicationDevices
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { am.setCommunicationDevice(it) }
            else am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    private fun setSpeaker(on: Boolean) {
        speakerOn = on
        getSystemService(android.media.AudioManager::class.java)?.let { routeSpeaker(it, on) }
        btnSpk.setIcon(if (on) R.drawable.ic_call_speaker_on else R.drawable.ic_call_speaker_off)
        btnSpk.active = on
        OngoingCallService.setAudio(this, micOn, on)
    }

    private fun applyMode(animate: Boolean) {
        applyModeViews(animate)
        updatePip()
        if (inPip) hideChromeForPip()
    }

    private fun pipAllowed(): Boolean =
        !callEnded && !screenSharing && (isVideo || remoteVideoShown) &&
            pl.sinusoide.sinuschat.util.ExperimentalFeatures.isPipEnabled(this) &&
            pl.sinusoide.sinuschat.util.ExperimentalFeatures.isPipSupported(this)

    private fun pipParams(): android.app.PictureInPictureParams {
        val mute = getString(if (micOn) R.string.call_action_mute else R.string.call_action_unmute)
        val hangUp = getString(R.string.call_action_hang_up)
        val actions = listOf(
            android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(this, if (micOn) R.drawable.ic_mic else R.drawable.ic_mic_off),
                mute, mute, OngoingCallService.micIntent(this)),
            android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_call_end),
                hangUp, hangUp, OngoingCallService.hangUpIntent(this))
        )
        val builder = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(9, 16))
            .setActions(actions)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pipAllowed())
        }
        return builder.build()
    }

    private fun updatePip() {
        if (!pl.sinusoide.sinuschat.util.ExperimentalFeatures.isPipSupported(this)) return
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
    }

    private fun enterPip(): Boolean {
        if (!pipAllowed()) return false
        return try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) { false }
    }

    private fun hideChromeForPip() {
        listOf<View>(topBox, controls, scrimTop, scrimBottom).forEach { it.visibility = View.INVISIBLE }
        pipFrame.visibility = View.GONE
        remoteBanner?.alpha = 0f
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) enterPip()
    }

    override fun onResume() {
        super.onResume()
        if (!resumeOnly) updatePip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            if (swapped) unswapNow()
            if (uiHidden) setUiHidden(false)
            hideChromeForPip()
        } else {
            listOf<View>(topBox, controls, scrimTop, scrimBottom).forEach { it.visibility = View.VISIBLE; it.alpha = 1f }
            remoteBanner?.alpha = 1f
            applyOrientation()
            applyMode(animate = false)
        }
    }

    private fun applyModeViews(animate: Boolean) {
        val showVideo = isVideo || remoteVideoShown
        if (!showVideo && uiHidden) setUiHidden(false)
        videoContainer.visibility = if (showVideo) View.VISIBLE else View.GONE
        val pipOn = isVideo && !screenSharing
        val pipWasShown = pipFrame.visibility == View.VISIBLE
        pipFrame.visibility = if (pipOn) View.VISIBLE else View.GONE
        if (animate && pipOn && !pipWasShown && cameraOn) {
            pipFrame.scaleX = 0.6f; pipFrame.scaleY = 0.6f; pipFrame.alpha = 0f
            pipFrame.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
        }
        scrimTop.visibility = if (showVideo) View.VISIBLE else View.GONE
        scrimBottom.visibility = if (showVideo) View.VISIBLE else View.GONE
        applyChrome(showVideo)
        flipColumn.visibility = if (isVideo && !screenSharing) View.VISIBLE else View.GONE
        if (!isVideo) {
            btnCam.active = false
            btnCam.setIcon(R.drawable.ic_call_video_on)
        }

        val avatar = avatarView ?: return
        if (showVideo) {
            if (animate) avatar.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(250)
                .withEndAction { avatar.visibility = View.GONE }.start()
            else avatar.visibility = View.GONE
        } else {
            avatar.visibility = View.VISIBLE
        }
    }

    private fun applyChrome(overVideo: Boolean) {
        val pal = if (overVideo) videoPalette else themePalette
        glassButtons.forEach { it.palette = pal }
        tvName.setTextColor(pal.text)
        if (pal.dark) tvName.setShadowLayer(8f, 0f, 2f, 0x66000000) else tvName.setShadowLayer(0f, 0f, 0f, 0)
        tvStatus.setTextColor(if (pal.flow) 0xE6FFFFFF.toInt() else pal.textSecondary)
        tvTimer.setTextColor(pal.text)
        buttonLabels.forEach { it.setTextColor(pal.textSecondary) }
        statusPill.background = GradientDrawable().apply {
            cornerRadius = 16 * dp
            if (pal.flow) { setColor(0x1FFFFFFF); setStroke((1 * dp).toInt(), 0x33FFFFFF) }
            else { setColor(pal.fill); setStroke((1 * dp).toInt(), pal.rimTop) }
        }
        controlsPanel.background = GradientDrawable().apply {
            cornerRadius = 34 * dp
            if (pal.flow) { setColor(0x14FFFFFF); setStroke((1 * dp).toInt(), 0x29FFFFFF) }
            else { setColor(pal.fill); setStroke((1 * dp).toInt(), pal.rimTop) }
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !pal.dark
            isAppearanceLightNavigationBars = !pal.dark
        }
    }

    private fun showRemoteVideoBanner() {
        if (remoteBanner != null) return
        val root = window.decorView.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 22 * dp; setColor(0x33000000); setStroke((1 * dp).toInt(), 0x33FFFFFF)
            }
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            addView(TextView(this@CallActivity).apply {
                text = getString(R.string.call_remote_video_on, otherUserName)
                textSize = 14f; setTextColor(Color.WHITE)
                maxLines = 2
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (10 * dp).toInt() })
            addView(TextView(this@CallActivity).apply {
                text = getString(R.string.call_turn_on_my_camera)
                textSize = 14f; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable().apply {
                    cornerRadius = 18 * dp; setColor(pl.sinusoide.sinuschat.ui.ThemeUi.accent(this@CallActivity))
                }
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                isClickable = true
                setOnClickListener { requestVideoUpgrade() }
            })
        }
        remoteBanner = banner
        root.addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            marginStart = (16 * dp).toInt(); marginEnd = (16 * dp).toInt()
            topMargin = topBox.paddingTop + (100 * dp).toInt()
        })
        banner.alpha = 0f; banner.translationY = -20 * dp
        banner.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun hideRemoteVideoBanner() {
        val b = remoteBanner ?: return
        remoteBanner = null
        b.animate().alpha(0f).translationY(-20 * dp).setDuration(220)
            .withEndAction { (b.parent as? FrameLayout)?.removeView(b) }.start()
    }
}
