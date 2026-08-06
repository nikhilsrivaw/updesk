package com.nikhil.updeskhost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
        capturer!!.startCapture(widthPx, heightPx, 30)
        videoTrack = factory.createVideoTrack("screen", videoSource).apply { setEnabled(true) }
        videoSender = pc!!.addTrack(videoTrack, listOf("updesk-stream"))
        applyQuality("high") // set bitrate/framerate + latency prefs from the start

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
                p.setLocalDescription(EmptySdpObserver(), desc)
                onOfferReady(desc.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
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
        val sender = videoSender ?: return
        val (maxBitrate, maxFps) = when (profile) {
            "saver" -> 800_000 to 12
            "balanced" -> 2_500_000 to 20
            else -> 8_000_000 to 30 // high
        }
        val params = sender.parameters
        if (params.encodings.isNotEmpty()) {
            params.encodings[0].maxBitrateBps = maxBitrate
            params.encodings[0].maxFramerate = maxFps
            // Match the desktop: keep motion smooth under pressure by shedding
            // resolution rather than frame rate.
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            sender.parameters = params
        }
    }

    fun stop() {
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
}
