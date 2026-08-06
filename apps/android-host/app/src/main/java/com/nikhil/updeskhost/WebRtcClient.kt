package com.nikhil.updeskhost

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.Camera1Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Owns the WebRTC peer connection for one session. The host is the *offerer*
 * (it owns the screen), matching the desktop flow: accept -> capture -> offer.
 *
 * ICE servers mirror the cloud config (Google STUN + your coturn TURN) so media
 * traverses across networks.
 */
class WebRtcClient(
    private val context: Context,
    private val onLocalIce: (JSONObject) -> Unit,
    private val onOfferReady: (String) -> Unit,
    private val onChat: (String) -> Unit = {},
) {
    private val eglBase: EglBase = EglBase.create()
    private lateinit var factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var helper: SurfaceTextureHelper? = null
    private var inputChannel: DataChannel? = null
    private var fsChannel: DataChannel? = null
    private var controlChannel: DataChannel? = null
    private var fileTransfer: FileTransfer? = null
    private var videoSender: org.webrtc.RtpSender? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var audioTrack: org.webrtc.AudioTrack? = null

    // Capture geometry (downscaled for latency) — kept so we can restart the
    // capturer on wake without recomputing.
    private var capW = 0
    private var capH = 0
    private var capFps = 30
    // Recovers the frozen frame after the screen sleeps: the mirrored display
    // stops feeding frames while off, so we restart capture when it powers on.
    private var screenReceiver: BroadcastReceiver? = null

    // ---- Adaptive bitrate (mirrors the desktop host) ----
    // The Android host previously set a FIXED high bitrate ceiling with no
    // congestion response, so at native resolution it overshot the link → WebRTC
    // shed resolution (blurry) and queued frames (laggy). This loop starts modest,
    // climbs only when the link is healthy, and backs off hard on loss/RTT — the
    // same recipe that made the desktop host sharp AND smooth.
    private val adaptHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var adaptRunnable: Runnable? = null
    private var bitrateCeiling = 10_000_000
    private var maxFpsCurrent = 30
    private var currentBitrate = 0
    private var lastStatsLost = -1L
    private var lastStatsSent = -1L

    // Mid-session recovery: the host is the offerer, so it drives ICE restarts
    // when the P2P path drops (Wi-Fi flap, network switch, NAT rebinding).
    private val recoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var iceRestarting = false

    private val iceServers = listOf(
        // Several STUN servers so a direct (low-latency) path is found more often;
        // TURN below is the fallback for symmetric NATs.
        PeerConnection.IceServer.builder(
            listOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302",
                "stun:stun2.l.google.com:19302",
                "stun:stun3.l.google.com:19302",
                "stun:stun4.l.google.com:19302",
            )
        ).createIceServer(),
        PeerConnection.IceServer.builder(
            listOf(
                "turn:up-desk.online:3478?transport=udp",
                "turn:up-desk.online:3478?transport=tcp",
            )
        ).setUsername("updesk").setPassword("updesk_turn_9fKq2mXz7L").createIceServer(),
    )

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
    }

    /**
     * Start a session: build the screen-capture track from the MediaProjection
     * permission [projectionData], create the peer connection, and emit an offer.
     */
    fun startSession(projectionData: Intent, widthPx: Int, heightPx: Int, withAudio: Boolean = false) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                onLocalIce(
                    JSONObject()
                        .put("sdpMid", c.sdpMid)
                        .put("sdpMLineIndex", c.sdpMLineIndex)
                        .put("candidate", c.sdp)
                )
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                when (s) {
                    PeerConnection.IceConnectionState.FAILED -> restartIce()
                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        // Often self-heals within a few seconds — only restart if
                        // it's still broken after a short grace period.
                        recoveryHandler.postDelayed({
                            val st = pc?.iceConnectionState()
                            if (st == PeerConnection.IceConnectionState.DISCONNECTED ||
                                st == PeerConnection.IceConnectionState.FAILED) restartIce()
                        }, 4000)
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                when (newState) {
                    PeerConnection.PeerConnectionState.FAILED -> restartIce()
                    PeerConnection.PeerConnectionState.CONNECTED -> iceRestarting = false
                    else -> {}
                }
            }
        }) ?: return

        // Screen capture -> VideoSource -> VideoTrack.
        capturer = ScreenCapturerAndroid(projectionData, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { /* user revoked casting */ }
        })
        videoSource = factory.createVideoSource(false)
        helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        capturer!!.initialize(helper, context, videoSource!!.capturerObserver)
        // Downscale the capture (RustDesk-style): encoding a phone's full native
        // resolution (e.g. 1080x2400) is the biggest latency cost. Cap the long
        // edge so the encoder + link have far less to do — a big latency win with
        // little visible loss when viewed on a desktop.
        // Adapt to the host's own uplink: on cellular the phone's UPLOAD is weak
        // and jittery, so cap resolution/bitrate to stay smooth instead of choking
        // the link (which shows up as lag). On Wi-Fi, go sharp.
        val cellular = isCellular()
        val maxEdge = if (cellular) MOBILE_CAPTURE_EDGE else MAX_CAPTURE_EDGE
        val (cw, ch) = scaledCapture(widthPx, heightPx, maxEdge)
        capW = cw; capH = ch
        // 60fps on Wi-Fi/direct for buttery-smooth cursor/scroll (the biggest
        // smoothness lever); 30 on cellular where a relay can't carry 60.
        capFps = if (cellular) 30 else 60
        capturer!!.startCapture(capW, capH, capFps)
        videoTrack = factory.createVideoTrack("screen", videoSource).apply { setEnabled(true) }
        videoSender = pc!!.addTrack(videoTrack, listOf("updesk-stream"))
        applyQuality(if (cellular) "mobile" else "high")
        // Nudge the bandwidth estimator: WebRTC's BWE starts low (~300 kbps) and
        // ramps slowly, so even with a high ceiling the ACTUAL bitrate can sit low
        // for the first many seconds → soft/blurry. Give it a higher START and a
        // MIN floor so the picture is sharp from the outset and never starves. On
        // cellular keep the floor off so a weak relay can still back off freely.
        val startBr = minOf(if (cellular) 2_500_000 else 4_000_000, bitrateCeiling)
        val minBr: Int? = if (cellular) null else 1_500_000
        runCatching { pc!!.setBitrate(minBr, startBr, bitrateCeiling) }
        registerScreenRecovery()

        // Optional microphone audio (standard WebRTC mic capture). Guarded so an
        // audio failure — permission, device, codec — never breaks the screen share.
        if (withAudio) {
            try {
                audioSource = factory.createAudioSource(MediaConstraints())
                audioTrack = factory.createAudioTrack("audio", audioSource).apply { setEnabled(true) }
                pc!!.addTrack(audioTrack, listOf("updesk-stream"))
            } catch (t: Throwable) { audioTrack = null; audioSource = null }
        }

        // Input channel: the controller sends taps/keys here; we inject them via
        // the Accessibility service (if the user has enabled it).
        inputChannel = pc!!.createDataChannel("input", DataChannel.Init())
        inputChannel!!.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
                // Root path (custody) if enabled + available, else Accessibility.
                if (RootInput.enabled && RootInput.available) RootInput.handle(json)
                else InputAccessibilityService.instance?.handleInput(json)
            }
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
        })

        // File-system channel: remote file browser (list dirs, download files).
        fsChannel = pc!!.createDataChannel("fs", DataChannel.Init())
        fileTransfer = FileTransfer(fsChannel!!)
        fsChannel!!.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return // controller only sends JSON requests
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
                fileTransfer?.onMessage(json)
            }
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
        })

        // Control channel: quality requests (clipboard/chat handled elsewhere).
        controlChannel = pc!!.createDataChannel("control", DataChannel.Init())
        controlChannel!!.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                val m = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
                when (m.optString("kind")) {
                    "quality" -> applyQuality(m.optString("profile"))
                    "chat" -> onChat(m.optString("text"))
                    "clipboard" -> setDeviceClipboard(m.optString("text"))
                    "vpn" -> sendControl(NetworkInfo.vpn(context).put("kind", "vpn-result"))
                    "netinfo" -> sendControl(NetworkInfo.info(context).put("kind", "netinfo-result"))
                }
            }
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                // Announce we're an Android host once the control channel opens, so
                // the controller can reveal the phone Back/Home/Recents buttons.
                if (controlChannel?.state() == DataChannel.State.OPEN) {
                    sendControl(
                        JSONObject().put("kind", "perms").put("os", "android")
                            .put("input", true).put("clipboard", true).put("file", true)
                    )
                }
            }
        })

        // Create and send the initial offer.
        emitOffer(iceRestart = false)
    }

    /**
     * Build an SDP offer and hand it to signaling. When [iceRestart] is true the
     * offer carries fresh ICE credentials, forcing both peers to re-gather —
     * this is how a dropped media path recovers in place, without rebuilding the
     * session or its data channels.
     */
    private fun emitOffer(iceRestart: Boolean) {
        val p = pc ?: return
        val constraints = MediaConstraints()
        if (iceRestart) constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                // Force H.264: on Android the HARDWARE H.264 encoder is far higher
                // quality + more efficient than the default hardware VP8 path (the
                // reason the screen looked soft AND laggy vs the desktop hosts,
                // which use software VP8/openh264). VP8 stays as a fallback PT.
                val fixed = SessionDescription(desc.type, preferCodec(desc.description, "H264"))
                p.setLocalDescription(EmptySdpObserver(), fixed)
                onOfferReady(fixed.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    // Reorder the video m-line so [codec]'s payload types come first, forcing
    // WebRTC to negotiate it (other codecs stay as fallbacks). SDP munging is the
    // portable way to prefer a codec on Android WebRTC across library versions.
    private fun preferCodec(sdp: String, codec: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val mIdx = lines.indexOfFirst { it.startsWith("m=video ") }
        if (mIdx < 0) return sdp
        val rtpmap = Regex("^a=rtpmap:(\\d+) ${codec}/90000", RegexOption.IGNORE_CASE)
        val pts = lines.mapNotNull { rtpmap.find(it)?.groupValues?.get(1) }
        if (pts.isEmpty()) return sdp // codec not offered — leave SDP untouched
        val parts = lines[mIdx].split(" ")
        if (parts.size <= 3) return sdp
        val header = parts.subList(0, 3)
        val payloads = parts.subList(3, parts.size)
        val reordered = pts + payloads.filter { it !in pts }
        lines[mIdx] = (header + reordered).joinToString(" ")
        return lines.joinToString("\r\n")
    }

    /** Recover a degraded/failed connection by renegotiating ICE. Guarded with a
     *  cooldown so transient flapping can't trigger a restart storm. */
    private fun restartIce() {
        if (iceRestarting || pc == null) return
        iceRestarting = true
        emitOffer(iceRestart = true)
        recoveryHandler.postDelayed({ iceRestarting = false }, 8000)
    }

    fun onRemoteAnswer(sdp: String) {
        pc?.setRemoteDescription(EmptySdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun onRemoteIce(candidate: JSONObject) {
        val c = IceCandidate(
            candidate.optString("sdpMid"),
            candidate.optInt("sdpMLineIndex"),
            candidate.optString("candidate"),
        )
        pc?.addIceCandidate(c)
    }

    /** Send a chat message to the controller over the control channel. */
    fun sendChat(text: String) {
        sendControl(JSONObject().put("kind", "chat").put("text", text))
    }

    /** Send any control-channel JSON message to the controller. */
    private fun sendControl(o: JSONObject) {
        val ch = controlChannel ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(o.toString().toByteArray(Charsets.UTF_8)), false))
    }

    // Controller-requested encoder profile (bitrate) — helps on slow mobile links.
    // Controller -> device clipboard paste. (Device -> controller isn't possible
    // from the background: Android 10+ blocks apps that aren't focused/an IME
    // from reading the clipboard, so this is intentionally one-directional.)
    private fun setDeviceClipboard(text: String) {
        if (text.isEmpty()) return
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("updesk", text))
        } catch (_: Throwable) {}
    }

    private fun applyQuality(profile: String) {
        // The profile sets the CEILING; the adaptive loop moves the live bitrate
        // between a floor and that ceiling based on measured loss/RTT.
        val (ceiling, maxFps) = when (profile) {
            "saver" -> 1_000_000 to 15
            "mobile" -> 3_500_000 to 30 // cellular uplink
            "balanced" -> 6_000_000 to 30
            // high (Wi-Fi/direct): 60fps for maximum smoothness, with extra bitrate
            // headroom so per-frame quality holds at double the frame rate.
            else -> 14_000_000 to 60
        }
        bitrateCeiling = ceiling
        maxFpsCurrent = maxFps
        // Start modest and let the loop CLIMB on a healthy link — instead of
        // blasting the ceiling and overshooting (which caused the lag + the
        // resolution-drop blur). ~5 Mbps is a good sharp-but-safe starting point.
        currentBitrate = minOf(ceiling, ADAPT_START)
        setSenderBitrate(currentBitrate, maxFps)
        startAdaptive()
    }

    private fun setSenderBitrate(bitrate: Int, maxFps: Int) {
        val sender = videoSender ?: return
        val params = sender.parameters
        if (params.encodings.isNotEmpty()) {
            params.encodings[0].maxBitrateBps = bitrate
            params.encodings[0].maxFramerate = maxFps
            // SCREEN content: keep RESOLUTION sharp (readable text) and shed frame
            // rate under pressure instead. maintain-framerate was throwing away
            // resolution → the soft/blurry look. This is the key quality fix.
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            sender.parameters = params
        }
    }

    // Runs every ~2s: read loss + RTT from getStats and nudge the live bitrate —
    // back off hard when the link hurts, recover gently when it's healthy.
    private fun startAdaptive() {
        stopAdaptive()
        lastStatsLost = -1; lastStatsSent = -1
        val r = object : Runnable {
            override fun run() { adaptTick(); adaptHandler.postDelayed(this, ADAPT_INTERVAL) }
        }
        adaptRunnable = r
        adaptHandler.postDelayed(r, ADAPT_INTERVAL)
    }

    private fun stopAdaptive() {
        adaptRunnable?.let { adaptHandler.removeCallbacks(it) }
        adaptRunnable = null
    }

    private fun adaptTick() {
        val p = pc ?: return
        if (videoSender == null) return
        p.getStats { report ->
            var sent = -1L; var lost = -1L; var rttMs = -1.0
            for (s in report.statsMap.values) {
                when (s.type) {
                    "outbound-rtp" -> if (s.members["kind"] == "video")
                        sent = (s.members["packetsSent"] as? Number)?.toLong() ?: sent
                    "remote-inbound-rtp" -> if (s.members["kind"] == "video") {
                        lost = (s.members["packetsLost"] as? Number)?.toLong() ?: lost
                        rttMs = ((s.members["roundTripTime"] as? Number)?.toDouble() ?: -0.001) * 1000.0
                    }
                }
            }
            adaptHandler.post { applyAdapt(lost, sent, rttMs) }
        }
    }

    private fun applyAdapt(lost: Long, sent: Long, rttMs: Double) {
        if (videoSender == null) return
        var loss = 0.0
        if (lastStatsLost >= 0 && lastStatsSent >= 0 && sent > lastStatsSent) {
            val dLost = (lost - lastStatsLost).coerceAtLeast(0)
            val dSent = sent - lastStatsSent
            if (dSent > 0) loss = dLost.toDouble() / dSent
        }
        lastStatsLost = lost; lastStatsSent = sent
        var next = currentBitrate
        if (loss > 0.03 || (rttMs in 0.0..1e9 && rttMs > 300)) {
            next = maxOf(ADAPT_FLOOR, (currentBitrate * 0.6).toInt())          // hurting → back off hard
        } else if (loss < 0.01 && (rttMs < 0 || rttMs < 200)) {
            next = minOf(bitrateCeiling, (currentBitrate * 1.25).toInt())      // healthy → climb
        }
        if (next != currentBitrate) {
            currentBitrate = next
            setSenderBitrate(next, maxFpsCurrent)
        }
    }

    // Is the phone's active network cellular (weak/jittery upload)? Drives the
    // mobile capture profile so we don't choke the uplink.
    private fun isCellular(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    } catch (_: Throwable) { false }

    // Even dimensions scaled so the long edge is at most [maxEdge], aspect kept.
    private fun scaledCapture(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val longEdge = maxOf(w, h)
        if (longEdge <= maxEdge) return (w and 1.inv()) to (h and 1.inv())
        val s = maxEdge.toFloat() / longEdge
        return ((w * s).toInt() and 1.inv()) to ((h * s).toInt() and 1.inv())
    }

    // When the phone's screen powers back on, the mirrored capture can come back
    // frozen/black. Restart the capturer to get fresh frames + a keyframe. Input
    // keeps working throughout (it's the independent accessibility service).
    private fun registerScreenRecovery() {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT ->
                        recoveryHandler.postDelayed({ resumeCapture() }, 350)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try { context.registerReceiver(r, filter); screenReceiver = r } catch (_: Throwable) {}
    }

    private fun resumeCapture() {
        val cap = capturer ?: return
        if (capW == 0 || capH == 0) return
        try { cap.stopCapture() } catch (_: Throwable) {}
        try { cap.startCapture(capW, capH, capFps) } catch (_: Throwable) {}
    }

    fun stop() {
        stopAdaptive()
        screenReceiver?.let { runCatching { context.unregisterReceiver(it) } }; screenReceiver = null
        inputChannel?.dispose(); inputChannel = null
        fsChannel?.dispose(); fsChannel = null; fileTransfer = null
        controlChannel?.dispose(); controlChannel = null
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); capturer = null
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        helper?.dispose(); helper = null
        pc?.close(); pc?.dispose(); pc = null
    }

    private class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    // Unused import guard (keeps Camera1Enumerator/MediaStreamTrack linked for
    // later input/audio layers without a warning churn).
    @Suppress("unused") private val reserved = arrayOf<Any>(Camera1Enumerator::class, MediaStreamTrack::class)

    private companion object {
        // Wi-Fi/direct capture cap — the smooth↔sharp sweet spot. 1600 (a 1080x2400
        // phone → 720x1600) is light enough for the encoder to hold a rock-solid
        // 30fps at low latency, while H.264 + maintain-resolution + a high bitrate
        // ceiling (huge bits-per-pixel at this size) keep it crisp. Native/1920 were
        // sharper on paper but too heavy → frame-rate drops read as lag.
        const val MAX_CAPTURE_EDGE = 1600
        // Cellular uplink can't carry full-res smoothly — scale down, but 1280 (not
        // 1024) keeps text legible while still fitting a mobile relay.
        const val MOBILE_CAPTURE_EDGE = 1280

        // Adaptive bitrate bounds/pacing (bps / ms).
        const val ADAPT_FLOOR = 800_000
        const val ADAPT_START = 5_000_000   // sharp-but-safe initial live bitrate
        const val ADAPT_INTERVAL = 2000L
    }
}
