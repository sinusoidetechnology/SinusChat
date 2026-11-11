package pl.sinusoide.sinuschat.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.PrivacySettings
import java.util.concurrent.CopyOnWriteArraySet

class GroupCallSession private constructor(
    private val app: Context,
    val groupId: String,
    val kind: String,
    val title: String
) {

    companion object {
        private const val TAG = "GroupCall"
        private const val PING_MS = 15_000L
        private const val TICK_MS = 5_000L
        private const val ALONE_MS = 15 * 60_000L
        private const val IDLE_MS = 90 * 60_000L
        const val PROMPT_MS = 30_000L
        const val LEFT_ALONE = "alone"
        const val LEFT_IDLE = "idle"

        @Volatile var current: GroupCallSession? = null
            private set

        fun start(context: Context, groupId: String, kind: String, title: String, starting: Boolean): GroupCallSession {
            current?.let { if (it.groupId == groupId && !it.ended) return it }
            val s = GroupCallSession(context.applicationContext, groupId, kind, title)
            current = s
            s.begin(starting)
            return s
        }
    }

    inner class Peer(val hash: String) {
        var pc: PeerConnection? = null
        var state: PeerConnection.IceConnectionState = PeerConnection.IceConnectionState.NEW
        var remoteVideo: VideoTrack? = null
        var remoteSet = false
        val pendingIce = mutableListOf<IceCandidate>()
        val outIce = JSONArray()
        var flushPosted = false
        var offered = false
        var missingSince = 0L
        var videoOn = false
        var screenOn = false
        var needsRenego = false
        var mediaSent = false
        var videoSender: org.webrtc.RtpSender? = null
        val connected get() = state == PeerConnection.IceConnectionState.CONNECTED ||
            state == PeerConnection.IceConnectionState.COMPLETED
    }

    val me: String = SetupActivity.getUserPhoneHash(app)
    val isVideo = kind == GroupCallHub.KIND_VIDEO
    val eglBase: EglBase = EglBase.create()
    private val main = Handler(Looper.getMainLooper())
    private var factory: PeerConnectionFactory? = null
    private var audioTrack: AudioTrack? = null
    var localVideo: VideoTrack? = null
        private set
    private var capturer: CameraVideoCapturer? = null
    private var screenCapturer: org.webrtc.VideoCapturer? = null
    private var cameraBeforeShare = false
    var sharing = false
        private set
    private var videoSource: VideoSource? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private var ready = false
    private var lastJoinAt = 0L
    private val pendingSignals = mutableListOf<Pair<String, String>>()
    private val secrets = HashMap<String, ByteArray>()
    private val waitingSecret = HashMap<String, MutableList<() -> Unit>>()
    val peers = LinkedHashMap<String, Peer>()
    var members: List<String> = emptyList()
        private set
    var micOn = true
        private set
    val audio = CallAudio(app) {
        OngoingCallService.setAudio(app, micOn, speakerOn)
        changed()
    }
    val speakerOn: Boolean get() = audio.speakerOn
    var cameraOn = isVideo
        private set
    var frontCamera = true
        private set
    var connectedSince = 0L
        private set
    val createdAt = System.currentTimeMillis()
    var initiator = false
        private set
    var ended = false
        private set
    var error: String? = null
        private set
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var audioSession = false
    private var savedMode = 0
    private var focus: android.media.AudioFocusRequest? = null

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { main.post { listeners.forEach { it() } } }

    private val pinger = object : Runnable {
        override fun run() {
            if (ended) return
            GroupCallHub.send("gcall_ping", groupId)
            main.postDelayed(this, PING_MS)
        }
    }

    private var lastActiveAt = android.os.SystemClock.elapsedRealtime()
    private var aloneSince = 0L
    var idlePromptAt = 0L
        private set

    fun noteActivity() {
        lastActiveAt = android.os.SystemClock.elapsedRealtime()
        if (idlePromptAt != 0L) {
            idlePromptAt = 0L
            GroupCallIdle.cancelNotice(app)
            changed()
        }
    }

    fun confirmActive() = noteActivity()

    private fun checkSpeaking() {
        val pc = peers.values.firstOrNull { it.connected }?.pc ?: return
        try {
            pc.getStats { report ->
                val level = report.statsMap.values.firstOrNull { it.type == "media-source" && it.members["kind"] == "audio" }
                    ?.members?.get("audioLevel") as? Double ?: 0.0
                if (level > 0.03) main.post { if (micOn && !ended) noteActivity() }
            }
        } catch (_: Exception) {}
    }

    private val activityWatch = object : Runnable {
        override fun run() {
            if (ended) return
            val now = android.os.SystemClock.elapsedRealtime()
            val others = members.count { it != me }
            if (others == 0) {
                if (aloneSince == 0L) aloneSince = now
                else if (now - aloneSince >= ALONE_MS) { leaveFor(LEFT_ALONE); return }
            } else aloneSince = 0L
            if (cameraOn || sharing) lastActiveAt = now
            else if (micOn && others > 0) checkSpeaking()
            if (idlePromptAt == 0L) {
                if (others > 0 && now - lastActiveAt >= IDLE_MS) {
                    idlePromptAt = now
                    if (GroupCallActivity.visibleGroup != groupId) GroupCallIdle.notice(app, groupId, kind, title, PROMPT_MS)
                    changed()
                }
            } else if (now - idlePromptAt >= PROMPT_MS) { leaveFor(LEFT_IDLE); return }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun leaveFor(reason: String) {
        GroupCallIdle.cancelNotice(app)
        if (GroupCallActivity.visibleGroup != groupId)
            GroupCallIdle.left(app, title, if (reason == LEFT_IDLE) pl.sinusoide.sinuschat.R.string.gcall_left_idle else pl.sinusoide.sinuschat.R.string.gcall_left_alone)
        fail(reason)
    }

    private var offlineSince = 0L
    private val netWatch = object : Runnable {
        override fun run() {
            if (ended || connectedSince > 0L) return
            if (pl.sinusoide.sinuschat.network.WebSocketManager.isConnected()) offlineSince = 0L
            else {
                val now = android.os.SystemClock.elapsedRealtime()
                if (offlineSince == 0L) offlineSince = now
                else if (now - offlineSince >= CallNetwork.offlineLimitMs(app)) { fail(CallNetwork.OFFLINE); return }
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun begin(starting: Boolean) {
        initiator = starting
        if (!pl.sinusoide.sinuschat.network.WebSocketManager.isConnected())
            pl.sinusoide.sinuschat.network.WebSocketManager.connect(app)
        main.postDelayed(netWatch, 1000)
        main.postDelayed(activityWatch, TICK_MS)
        OngoingCallService.start(app, title, isVideo)
        OngoingCallService.onHangUp = { main.post { leave() } }
        OngoingCallService.onToggleMic = { main.post { toggleMic() } }
        OngoingCallService.onToggleSpeaker = { main.post { toggleSpeaker() } }
        beginAudio()
        AppExecutor.io {
            val servers = loadIceServers()
            main.post {
                if (ended) return@post
                iceServers = servers
                if (!initFactory()) { fail("init"); return@post }
                ready = true
                lastJoinAt = System.currentTimeMillis()
                GroupCallHub.send(if (starting) "gcall_start" else "gcall_join", groupId,
                    JSONObject().put("kind", kind))
                main.postDelayed(pinger, PING_MS)
                GroupCallHub.takeBuffered(groupId).forEach { onSignal(it.optString("from"), it.optString("payload")) }
                pendingSignals.toList().also { pendingSignals.clear() }.forEach { (f, p) -> onSignal(f, p) }
                GroupCallHub.state(groupId)?.let { onState(it) }
                changed()
            }
        }
    }

    private fun loadIceServers(): List<PeerConnection.IceServer> {
        val out = mutableListOf<PeerConnection.IceServer>()
        try {
            val creds = ApiClient.getObject("/api/turn-credentials", app) ?: return out
            val urls = creds.optJSONArray("urls") ?: return out
            val user = creds.optString("username")
            val pass = creds.optString("credential")
            for (i in 0 until urls.length()) {
                var url = urls.getString(i)
                if (url.startsWith("turns:")) url = url.substringBefore("?")
                val b = PeerConnection.IceServer.builder(url)
                if ((url.startsWith("turn:") || url.startsWith("turns:")) && user.isNotEmpty()) b.setUsername(user).setPassword(pass)
                try { out.add(b.createIceServer()) } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.w(TAG, "ICE: ${e.message}") }
        return out
    }

    private fun initFactory(): Boolean = try {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app)
            .setEnableInternalTracer(false).createInitializationOptions())
        val hw = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(hw)
            .setUseHardwareNoiseSuppressor(hw)
            .createAudioDeviceModule()
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        audioTrack = f.createAudioTrack("ga", f.createAudioSource(MediaConstraints())).apply { setEnabled(true) }
        if (isVideo) {
            ensureVideoTrack(f)
            if (!startCameraCapturer()) cameraOn = false
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "factory: ${e.message}", e)
        false
    }

    private fun ensureVideoTrack(f: PeerConnectionFactory): Boolean {
        if (localVideo != null) return false
        val source = f.createVideoSource(false)
        videoSource = source
        captureHelper = SurfaceTextureHelper.create("GroupCapture", eglBase.eglBaseContext)
        localVideo = f.createVideoTrack("gv", source).apply { setEnabled(true) }
        return true
    }

    private fun startCameraCapturer(): Boolean {
        val source = videoSource ?: return false
        val helper = captureHelper ?: return false
        return try {
            val enumerator = Camera2Enumerator(app)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: return false
            val cam = enumerator.createCapturer(name, null) ?: return false
            cam.initialize(helper, app, source.capturerObserver)
            cam.startCapture(640, 480, 24)
            capturer = cam
            frontCamera = enumerator.isFrontFacing(name)
            true
        } catch (e: Exception) {
            Log.w(TAG, "camera: ${e.message}")
            false
        }
    }

    private fun stopCameraCapturer() {
        val cam = capturer ?: return
        capturer = null
        try { cam.stopCapture() } catch (_: Exception) {}
        try { cam.dispose() } catch (_: Exception) {}
    }

    private fun tuneVideoSender(sender: org.webrtc.RtpSender?) {
        sender ?: return
        try {
            val params = sender.parameters
            params.encodings.forEach {
                it.maxBitrateBps = if (sharing) 1_500_000 else 600_000
                it.maxFramerate = if (sharing) 15 else 24
            }
            sender.parameters = params
        } catch (_: Exception) {}
    }

    private fun attachVideoToPeers() {
        val track = localVideo ?: return
        for (peer in peers.values) {
            val pc = peer.pc ?: continue
            if (peer.videoSender == null) {
                peer.videoSender = pc.addTrack(track, listOf("g"))
                tuneVideoSender(peer.videoSender)
                renegotiate(peer)
            }
        }
    }

    private fun broadcastMedia() {
        for (peer in peers.values) sendMedia(peer)
    }

    private fun sendMedia(peer: Peer) {
        peer.mediaSent = true
        signal(peer.hash, JSONObject().put("t", "media").put("v", cameraOn || sharing).put("s", sharing))
    }

    private fun renegotiate(peer: Peer) {
        val pc = peer.pc ?: return
        if (!peer.remoteSet || pc.signalingState() != PeerConnection.SignalingState.STABLE) {
            peer.needsRenego = true
            return
        }
        peer.needsRenego = false
        pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        main.post { signal(peer.hash, JSONObject().put("t", "offer").put("r", true).put("sdp", sdp.description)) }
                    }
                }, sdp)
            }
        }, constraints())
    }

    fun onState(state: GroupCallHub.State?) {
        if (ended || !ready) return
        if (state == null) {
            GroupCallHub.send("gcall_start", groupId, JSONObject().put("kind", kind))
            return
        }
        members = state.members
        if (me !in members) {
            val now = System.currentTimeMillis()
            if (now - lastJoinAt > 3_000L) {
                lastJoinAt = now
                GroupCallHub.send("gcall_join", groupId, JSONObject().put("kind", kind))
            }
            changed()
            return
        }
        for (m in members) {
            if (m == me || peers.containsKey(m)) continue
            val p = createPeer(m) ?: continue
            if (me < m) offer(p)
        }
        val now = System.currentTimeMillis()
        for ((hash, peer) in peers.entries.toList()) {
            if (hash in members) { peer.missingSince = 0L; continue }
            if (!peer.connected) { closePeer(hash); continue }
            if (peer.missingSince == 0L) peer.missingSince = now
            if (now - peer.missingSince > 45_000L) closePeer(hash)
            else main.postDelayed({ GroupCallHub.state(groupId)?.let { onState(it) } }, 46_000L)
        }
        if (members.size > 1 && connectedSince == 0L && peers.values.any { it.connected }) connectedSince = System.currentTimeMillis()
        changed()
    }

    private fun createPeer(hash: String): Peer? {
        val f = factory ?: return null
        val peer = Peer(hash)
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (PrivacySettings.isCallRelayEnabled(app))
                PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val pc = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) { main.post { queueIce(peer, c) } }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { main.post { onIce(peer, s) } }
            override fun onTrack(t: RtpTransceiver) {
                val track = t.receiver.track()
                if (track is VideoTrack) main.post { peer.remoteVideo = track; changed() }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(r: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        }) ?: return null
        audioTrack?.let { pc.addTrack(it, listOf("g")) }
        localVideo?.let { track ->
            peer.videoSender = pc.addTrack(track, listOf("g"))
            tuneVideoSender(peer.videoSender)
        }
        peer.pc = pc
        peers[hash] = peer
        return peer
    }

    private fun constraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    private fun offer(peer: Peer) {
        val pc = peer.pc ?: return
        peer.offered = true
        pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        main.post { signal(peer.hash, JSONObject().put("t", "offer").put("sdp", sdp.description)) }
                    }
                }, sdp)
            }
        }, constraints())
    }

    private fun queueIce(peer: Peer, c: IceCandidate) {
        if (peers[peer.hash] !== peer) return
        peer.outIce.put(JSONObject().put("m", c.sdpMid).put("i", c.sdpMLineIndex).put("c", c.sdp))
        if (peer.flushPosted) return
        peer.flushPosted = true
        main.postDelayed({
            peer.flushPosted = false
            if (peer.outIce.length() == 0 || peers[peer.hash] !== peer) return@postDelayed
            val batch = JSONArray(peer.outIce.toString())
            while (peer.outIce.length() > 0) peer.outIce.remove(0)
            signal(peer.hash, JSONObject().put("t", "ice").put("c", batch))
        }, 300L)
    }

    private fun onIce(peer: Peer, s: PeerConnection.IceConnectionState) {
        if (peers[peer.hash] !== peer) return
        peer.state = s
        if (peer.connected && connectedSince == 0L) connectedSince = System.currentTimeMillis()
        if (peer.connected && !peer.mediaSent) sendMedia(peer)
        if (connectedSince > 0L) OngoingCallService.update(app, isVideo, connectedSince)
        if (s == PeerConnection.IceConnectionState.FAILED && me < peer.hash) {
            main.postDelayed({
                if (ended || peers[peer.hash] !== peer || peer.hash !in members) return@postDelayed
                closePeer(peer.hash)
                createPeer(peer.hash)?.let { offer(it) }
                changed()
            }, 2_000L)
        }
        changed()
    }

    private fun withSecret(hash: String, action: (ByteArray) -> Unit) {
        secrets[hash]?.let { action(it); return }
        val queue = waitingSecret[hash]
        if (queue != null) { queue.add { secrets[hash]?.let(action) }; return }
        waitingSecret[hash] = mutableListOf({ secrets[hash]?.let(action) })
        E2EManager.getSharedSecretForUser(app, hash) { secret ->
            main.post {
                if (secret != null) secrets[hash] = secret
                waitingSecret.remove(hash)?.forEach { it() }
            }
        }
    }

    private fun signal(to: String, body: JSONObject) {
        if (ended) return
        body.put("g", groupId).put("f", me)
        withSecret(to) { key ->
            val payload = E2EManager.encrypt(body.toString(), key) ?: return@withSecret
            GroupCallHub.send("gcall_signal", groupId, JSONObject().put("to", to).put("payload", payload))
        }
    }

    fun onSignal(from: String, payload: String) {
        if (ended || from.isEmpty() || from == me || payload.isEmpty()) return
        if (!ready) { pendingSignals.add(from to payload); return }
        withSecret(from) { key ->
            val text = E2EManager.decrypt(payload, key) ?: return@withSecret
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return@withSecret
            if (o.optString("g") != groupId || o.optString("f") != from) return@withSecret
            handleSignal(from, o)
        }
    }

    private fun handleSignal(from: String, o: JSONObject) {
        when (o.optString("t")) {
            "offer" -> {
                val existing = peers[from]
                if (o.optBoolean("r") && existing?.pc != null && existing.remoteSet) {
                    acceptOffer(existing, o.optString("sdp"), renegotiation = true)
                    return
                }
                if (me < from && existing?.offered == true && existing.connected != true) return
                existing?.let { if (it.remoteSet || it.offered) closePeer(from) }
                val peer = peers[from] ?: createPeer(from) ?: return
                acceptOffer(peer, o.optString("sdp"), renegotiation = false)
                changed()
            }
            "answer" -> {
                val peer = peers[from] ?: return
                val pc = peer.pc ?: return
                pc.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        main.post {
                            peer.remoteSet = true
                            drainIce(peer)
                            if (peer.needsRenego) renegotiate(peer)
                        }
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, o.optString("sdp")))
            }
            "media" -> {
                val peer = peers[from] ?: return
                peer.videoOn = o.optBoolean("v")
                peer.screenOn = o.optBoolean("s")
                changed()
            }
            "ice" -> {
                val peer = peers[from] ?: createPeer(from) ?: return
                val arr = o.optJSONArray("c") ?: return
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val cand = IceCandidate(c.optString("m"), c.optInt("i"), c.optString("c"))
                    if (peer.remoteSet) peer.pc?.addIceCandidate(cand) else peer.pendingIce.add(cand)
                }
            }
        }
    }

    private fun acceptOffer(peer: Peer, sdp: String, renegotiation: Boolean) {
        val pc = peer.pc ?: return
        val apply = {
            pc.setRemoteDescription(object : SdpAdapter() {
                override fun onSetSuccess() {
                    main.post {
                        peer.remoteSet = true
                        drainIce(peer)
                        pc.createAnswer(object : SdpAdapter() {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                pc.setLocalDescription(object : SdpAdapter() {
                                    override fun onSetSuccess() {
                                        main.post {
                                            signal(peer.hash, JSONObject().put("t", "answer").put("sdp", answer.description))
                                            if (peer.needsRenego) renegotiate(peer)
                                        }
                                    }
                                }, answer)
                            }
                        }, constraints())
                    }
                }
            }, SessionDescription(SessionDescription.Type.OFFER, sdp))
        }
        if (renegotiation && pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            if (me < peer.hash) return
            peer.needsRenego = true
            pc.setLocalDescription(object : SdpAdapter() {
                override fun onSetSuccess() { main.post { apply() } }
            }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
            return
        }
        apply()
    }

    private fun drainIce(peer: Peer) {
        val pc = peer.pc ?: return
        peer.pendingIce.forEach { pc.addIceCandidate(it) }
        peer.pendingIce.clear()
    }

    private fun closePeer(hash: String) {
        val peer = peers.remove(hash) ?: return
        try { peer.pc?.close(); peer.pc?.dispose() } catch (_: Exception) {}
        peer.pc = null
        changed()
    }

    fun toggleMic() {
        noteActivity()
        micOn = !micOn
        audioTrack?.setEnabled(micOn)
        OngoingCallService.setAudio(app, micOn, speakerOn)
        changed()
    }

    fun toggleSpeaker() {
        noteActivity()
        audio.toggleSpeaker()
    }

    fun selectRoute(route: CallAudio.Route) {
        noteActivity()
        audio.select(route)
    }

    fun toggleCamera() {
        noteActivity()
        if (sharing) { stopScreenShare(toCamera = true); return }
        val f = factory ?: return
        if (cameraOn) {
            cameraOn = false
            stopCameraCapturer()
            localVideo?.setEnabled(false)
            broadcastMedia()
            changed()
            return
        }
        ensureVideoTrack(f)
        if (capturer == null && !startCameraCapturer()) { changed(); return }
        cameraOn = true
        localVideo?.setEnabled(true)
        attachVideoToPeers()
        broadcastMedia()
        audio.preferSpeaker()
        changed()
    }

    fun startScreenShare(data: android.content.Intent): Boolean {
        noteActivity()
        val f = factory ?: return false
        if (sharing) return true
        cameraBeforeShare = cameraOn
        stopCameraCapturer()
        ensureVideoTrack(f)
        val source = videoSource ?: return false
        val helper = captureHelper ?: return false
        val metrics = app.resources.displayMetrics
        val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
        val w = (metrics.widthPixels * scale).toInt() and 1.inv()
        val h = (metrics.heightPixels * scale).toInt() and 1.inv()
        return try {
            val screen = org.webrtc.ScreenCapturerAndroid(data, object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() { main.post { if (sharing) stopScreenShare(toCamera = false) } }
            })
            screen.initialize(helper, app, source.capturerObserver)
            screen.startCapture(w, h, 15)
            screenCapturer = screen
            sharing = true
            cameraOn = false
            localVideo?.setEnabled(true)
            peers.values.forEach { tuneVideoSender(it.videoSender) }
            attachVideoToPeers()
            broadcastMedia()
            audio.preferSpeaker()
            changed()
            true
        } catch (e: Exception) {
            Log.w(TAG, "screen: ${e.message}")
            false
        }
    }

    fun stopScreenShare(toCamera: Boolean) {
        if (!sharing) return
        sharing = false
        val screen = screenCapturer
        screenCapturer = null
        try { screen?.stopCapture() } catch (_: Exception) {}
        try { screen?.dispose() } catch (_: Exception) {}
        peers.values.forEach { tuneVideoSender(it.videoSender) }
        val allowed = androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val camera = (toCamera || cameraBeforeShare) && allowed && startCameraCapturer()
        cameraOn = camera
        localVideo?.setEnabled(camera)
        broadcastMedia()
        changed()
    }

    fun switchCamera() {
        noteActivity()
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) { main.post { frontCamera = front; changed() } }
            override fun onCameraSwitchError(e: String?) {}
        })
    }

    fun onError(reason: String) {
        error = reason
        leave()
    }

    private fun fail(reason: String) {
        error = reason
        leave()
    }

    fun leave() {
        if (ended) return
        ended = true
        main.removeCallbacks(pinger)
        main.removeCallbacks(netWatch)
        main.removeCallbacks(activityWatch)
        GroupCallIdle.cancelNotice(app)
        GroupCallHub.send("gcall_leave", groupId)
        peers.keys.toList().forEach { closePeer(it) }
        try {
            capturer?.stopCapture()
            screenCapturer?.stopCapture()
        } catch (_: Exception) {}
        try {
            screenCapturer?.dispose()
            capturer?.dispose()
            captureHelper?.dispose()
            videoSource?.dispose()
            localVideo?.dispose()
            audioTrack?.dispose()
            factory?.dispose()
        } catch (_: Exception) {}
        capturer = null; localVideo = null; audioTrack = null; factory = null
        OngoingCallService.stop(app)
        endAudio()
        if (current === this) current = null
        changed()
        main.postDelayed({ try { eglBase.release() } catch (_: Exception) {} }, 500L)
    }

    private fun beginAudio() {
        if (audioSession) return
        val am = app.getSystemService(android.media.AudioManager::class.java) ?: return
        savedMode = am.mode
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .build()
        try { am.requestAudioFocus(request) } catch (_: Exception) {}
        focus = request
        am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        audioSession = true
        audio.start(isVideo)
    }

    private fun endAudio() {
        if (!audioSession) return
        audioSession = false
        audio.stop()
        val am = app.getSystemService(android.media.AudioManager::class.java) ?: return
        try {
            am.mode = if (savedMode == android.media.AudioManager.MODE_IN_COMMUNICATION)
                android.media.AudioManager.MODE_NORMAL else savedMode
            focus?.let { am.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {}
        focus = null
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) { Log.w(TAG, "sdp create: $e") }
        override fun onSetFailure(e: String?) { Log.w(TAG, "sdp set: $e") }
    }
}
