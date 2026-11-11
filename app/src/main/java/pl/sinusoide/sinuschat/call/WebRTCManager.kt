package pl.sinusoide.sinuschat.call

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class WebRTCManager(
    private val context: Context,
    private var isVideoCall: Boolean,
    val onIceCandidate: (IceCandidate) -> Unit,
    val onIceConnectionChange: (PeerConnection.IceConnectionState) -> Unit,
    val onRemoteVideoTrack: (VideoTrack) -> Unit,
    val onError: (String) -> Unit
) {
    private val TAG = "WebRTCManager"

    val rootEglBase: EglBase = EglBase.create()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var videoSender: RtpSender? = null

    enum class ShareStart { FAILED, STARTED, NEEDS_OFFER }

    var isScreenSharing = false
        private set

    var isMicEnabled = true
        private set
    var isCameraEnabled = isVideoCall
        private set
    var frontFacing = true
        private set
    @Volatile private var cameraBroken = false
    private var lastCameraRestart = 0L

    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String?) { Log.w(TAG, "camera error: $error"); cameraBroken = true }
        override fun onCameraDisconnected() { Log.w(TAG, "camera disconnected"); cameraBroken = true }
        override fun onCameraFreezed(error: String?) { Log.w(TAG, "camera frozen: $error"); cameraBroken = true }
        override fun onCameraOpening(name: String?) {}
        override fun onFirstFrameAvailable() { cameraBroken = false }
        override fun onCameraClosed() {}
    }

    companion object {
        @Volatile private var globalInitDone = false
    }

    fun initialize(iceServers: List<PeerConnection.IceServer>) {
        if (!globalInitDone) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            globalInitDone = true
        }

        val hardwareAudio = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(hardwareAudio)
            .setUseHardwareNoiseSuppressor(hardwareAudio)
            .createAudioDeviceModule()

        val eglContext = rootEglBase.eglBaseContext
        val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (pl.sinusoide.sinuschat.util.PrivacySettings.isCallRelayEnabled(context))
                PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceCandidatePoolSize = 2
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        Log.d(TAG, "Tworzę PeerConnection z ${iceServers.size} serwerami ICE")
        iceServers.forEach { Log.d(TAG, "  ICE: ${it.urls}") }
        peerConnection = try {
            factory?.createPeerConnection(rtcConfig, pcObserver())
        } catch (e: Exception) {
            Log.e(TAG, "createPeerConnection wyjątek: ${e.message}", e)
            null
        }
        if (peerConnection == null) {
            Log.w(TAG, "PeerConnection null — próbuję z minimalnym zestawem ICE")
            val safe = factory
            if (safe != null) {
                peerConnection = try {
                    safe.createPeerConnection(rtcConfig, pcObserver())
                } catch (e: Exception) {
                    Log.e(TAG, "druga próba wyjątek: ${e.message}", e); null
                }
            }
        }
        if (peerConnection == null) { onError("PeerConnection init failed"); return }

        addLocalTracks()
    }

    @Volatile var localCandidates = 0
        private set

    private fun pcObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            localCandidates++
            val type = Regex("typ (\\w+)").find(c.sdp)?.groupValues?.get(1)
                ?: if (c.sdp.contains("relay")) "relay"
                else if (c.sdp.contains("srflx")) "srflx"
                else if (c.sdp.contains("host")) "host" else "?"
            Log.d(TAG, "ICE candidate: $type | ${c.sdp.take(60)}")
            this@WebRTCManager.onIceCandidate(c)
        }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ICE: $s")
            this@WebRTCManager.onIceConnectionChange(s)
        }
        override fun onIceConnectionReceivingChange(r: Boolean) {}
        override fun onTrack(t: RtpTransceiver) {
            val track = t.receiver.track()
            if (track is VideoTrack) this@WebRTCManager.onRemoteVideoTrack(track)
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering: $s (kandydatów: $localCandidates)")
        }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {}
        override fun onSelectedCandidatePairChanged(e: CandidatePairChangeEvent?) {}
    }

    private fun addLocalTracks() {
        val audioSource = factory?.createAudioSource(MediaConstraints())
        localAudioTrack = factory?.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(true)
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }

        if (isVideoCall) addLocalVideoTrack()
    }

    fun enableVideo(): Boolean {
        if (localVideoTrack != null) {
            if (!isCameraEnabled) toggleCamera()
            return false
        }
        isVideoCall = true
        isCameraEnabled = true
        addLocalVideoTrack()
        return localVideoTrack != null
    }

    fun isNegotiationStable(): Boolean =
        peerConnection?.signalingState() == PeerConnection.SignalingState.STABLE

    private fun helper(): SurfaceTextureHelper =
        captureHelper ?: SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext).also { captureHelper = it }

    private fun addLocalVideoTrack(capturer: VideoCapturer? = createCameraCapturer(), width: Int = 1280, height: Int = 720, fps: Int = 30) {
        run {
            try {
                if (capturer == null) return
                videoSource = factory?.createVideoSource(false)
                capturer.initialize(helper(), context, videoSource?.capturerObserver)
                capturer.startCapture(width, height, fps)
                videoCapturer = capturer
                localVideoTrack = factory?.createVideoTrack("video0", videoSource)
                localVideoTrack?.setEnabled(true)
                localVideoTrack?.let { track ->
                    val sender = peerConnection?.addTrack(track, listOf("stream0"))
                    videoSender = sender
                    sender?.let { s ->
                        val params = s.parameters
                        params.encodings.forEach { enc ->
                            enc.maxBitrateBps = 2_000_000
                            enc.maxFramerate = 30
                            try { enc.networkPriority = 3 } catch (_: Exception) {}
                        }
                        try {
                            params.degradationPreference =
                                RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                        } catch (_: Exception) {}
                        s.parameters = params
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera init error", e)
            }
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) == frontFacing } ?: names.firstOrNull() ?: return null
        frontFacing = enumerator.isFrontFacing(name)
        cameraBroken = false
        return enumerator.createCapturer(name, cameraEvents)
    }

    fun attachLocalVideo(renderer: VideoSink) {
        localVideoTrack?.addSink(renderer)
    }

    fun detachLocalVideo(renderer: VideoSink) {
        localVideoTrack?.removeSink(renderer)
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
        }
        peerConnection?.createOffer(sdpObserver(onSuccess), constraints)
    }

    fun answerRenegotiation(offer: SessionDescription, onAnswer: (SessionDescription) -> Unit) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p: SessionDescription?) {}
            override fun onSetSuccess() {
                peerConnection?.createAnswer(sdpObserver(onAnswer), MediaConstraints())
            }
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) { Log.e(TAG, "reoffer setRemote fail: $e") }
        }, offer)
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit) {
        peerConnection?.createAnswer(sdpObserver(onSuccess), MediaConstraints())
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p: SessionDescription?) {}
            override fun onSetSuccess() { Log.d(TAG, "Remote SDP set") }
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) { Log.e(TAG, "setRemote fail: $e") }
        }, sdp)
    }

    fun connectionRoute(cb: (local: String?, remote: String?) -> Unit) {
        val pc = peerConnection ?: return cb(null, null)
        try {
            pc.getStats { report ->
                val all = report.statsMap
                var pairId = all.values.firstOrNull { it.type == "transport" }
                    ?.members?.get("selectedCandidatePairId") as? String
                if (pairId == null) pairId = all.values.firstOrNull {
                    it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true
                }?.id
                val pair = pairId?.let { all[it] }
                fun typeOf(key: String) = (pair?.members?.get(key) as? String)
                    ?.let { all[it]?.members?.get("candidateType") as? String }
                cb(typeOf("localCandidateId"), typeOf("remoteCandidateId"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getStats: ${e.message}")
            cb(null, null)
        }
    }

    fun addIceCandidate(c: IceCandidate) {
        peerConnection?.addIceCandidate(c)
    }

    fun toggleMic(): Boolean {
        isMicEnabled = !isMicEnabled
        localAudioTrack?.setEnabled(isMicEnabled)
        return isMicEnabled
    }

    fun toggleCamera(): Boolean {
        if (isCameraEnabled) {
            isCameraEnabled = false
            localVideoTrack?.setEnabled(false)
            if (!isScreenSharing) stopCapturer()
        } else {
            isCameraEnabled = startCamera()
            localVideoTrack?.setEnabled(isCameraEnabled)
        }
        return isCameraEnabled
    }

    fun ensureCamera(): Boolean {
        if (!isCameraEnabled || isScreenSharing || localVideoTrack == null) return false
        if (videoCapturer != null && !cameraBroken) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCameraRestart < 3000) return false
        lastCameraRestart = now
        Log.d(TAG, "Restart kamery")
        return startCamera()
    }

    private fun startCamera(): Boolean {
        val source = videoSource ?: return false
        stopCapturer()
        val camera = createCameraCapturer() ?: return false
        return try {
            camera.initialize(helper(), context, source.capturerObserver)
            camera.startCapture(1280, 720, 30)
            videoCapturer = camera
            true
        } catch (e: Exception) {
            Log.e(TAG, "Camera restart error", e)
            camera.dispose()
            false
        }
    }

    private fun stopCapturer() {
        val c = videoCapturer ?: return
        videoCapturer = null
        try { c.stopCapture() } catch (_: InterruptedException) {}
        c.dispose()
    }

    private fun tuneSender(maxBitrate: Int, maxFps: Int, pref: RtpParameters.DegradationPreference) {
        val s = videoSender ?: return
        try {
            val params = s.parameters
            params.encodings.forEach { enc ->
                enc.maxBitrateBps = maxBitrate
                enc.maxFramerate = maxFps
            }
            params.degradationPreference = pref
            s.parameters = params
        } catch (_: Exception) {}
    }

    fun startScreenShare(permission: Intent, onStopped: () -> Unit): ShareStart {
        val metrics = context.resources.displayMetrics
        val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
        val w = (metrics.widthPixels * scale).toInt() and 1.inv()
        val h = (metrics.heightPixels * scale).toInt() and 1.inv()
        val capturer = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() {
            override fun onStop() {
                if (isScreenSharing) onStopped()
            }
        })
        return try {
            val result = if (localVideoTrack == null) {
                isVideoCall = true
                addLocalVideoTrack(capturer, w, h, 24)
                if (localVideoTrack == null) return ShareStart.FAILED
                ShareStart.NEEDS_OFFER
            } else {
                stopCapturer()
                capturer.initialize(helper(), context, videoSource?.capturerObserver)
                capturer.startCapture(w, h, 24)
                videoCapturer = capturer
                localVideoTrack?.setEnabled(true)
                ShareStart.STARTED
            }
            isCameraEnabled = false
            isScreenSharing = true
            tuneSender(3_000_000, 24, RtpParameters.DegradationPreference.BALANCED)
            peerConnection?.setBitrate(300_000, 1_500_000, 3_000_000)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Screen share error", e)
            ShareStart.FAILED
        }
    }

    fun stopScreenShare(resumeCamera: Boolean) {
        if (!isScreenSharing) return
        isScreenSharing = false
        stopCapturer()
        tuneSender(2_000_000, 30, RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE)
        isCameraEnabled = resumeCamera && startCamera()
        localVideoTrack?.setEnabled(isCameraEnabled)
    }

    fun switchCamera(onSwitched: ((Boolean) -> Unit)? = null) {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                frontFacing = isFrontCamera
                onSwitched?.invoke(isFrontCamera)
            }
            override fun onCameraSwitchError(error: String?) { Log.w(TAG, "switchCamera: $error") }
        })
    }

    fun close() {
        try {
            isScreenSharing = false
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            captureHelper?.dispose()
            videoSource?.dispose()
            localAudioTrack?.dispose()
            localVideoTrack?.dispose()
            peerConnection?.close()
            peerConnection?.dispose()
            factory?.dispose()
            rootEglBase.release()
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
    }

    private fun sdpObserver(onSuccess: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            peerConnection?.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(p: SessionDescription?) {}
                override fun onSetSuccess() { onSuccess(sdp) }
                override fun onCreateFailure(e: String?) {}
                override fun onSetFailure(e: String?) { Log.e(TAG, "setLocal fail: $e") }
            }, sdp)
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {
            Log.e(TAG, "create SDP fail: $e")
            onError(e ?: "SDP error")
        }
        override fun onSetFailure(e: String?) {}
    }
}