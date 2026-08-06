package com.nikhil.updeskfield

import android.content.Context
import org.json.JSONObject
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

/**
 * Owns the WebRTC peer connection for one field session. Like the desktop/screen
 * host, this device is the *offerer* (it owns the camera + mic + GPS). Unlike the
 * screen host it is transmit-only: no input, no file browser. It streams
 *   - the phone camera (front/back, switchable) as the video track,
 *   - the microphone as the audio track,
 *   - live location as JSON over a dedicated `location` data channel.
 *
 * ICE servers mirror the cloud config (STUN + coturn TURN) so media traverses
 * across networks, and the same ICE-restart recovery keeps a moving field device
 * connected through Wi-Fi/cellular handoffs.
 */
class WebRtcClient(
    private val context: Context,
    private val onLocalIce: (JSONObject) -> Unit,
    private val onOfferReady: (String) -> Unit,
) {
    val eglBase: EglBase = EglBase.create()
    private lateinit var factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSender: org.webrtc.RtpSender? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var locationChannel: DataChannel? = null
    private var controlChannel: DataChannel? = null
    private var previewSink: VideoSink? = null
    private var usingFront = false

    // Mid-session recovery: this device is the offerer, so it drives ICE restarts
    // when the P2P path drops (Wi-Fi flap, cellular handoff, NAT rebinding) — the
    // common case for a phone carried around in the field.
    private val recoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var iceRestarting = false

    private val iceServers = listOf(
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
     * Start a session: open the camera + mic, wire the location channel, and emit
     * an offer. [preferFront] chooses the starting camera; [previewSink] (the
     * on-device SurfaceViewRenderer) shows the operator exactly what's being sent.
     */
    fun startSession(preferFront: Boolean, previewSink: VideoSink?) {
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
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                when (newState) {
                    PeerConnection.PeerConnectionState.FAILED -> restartIce()
                    PeerConnection.PeerConnectionState.CONNECTED -> iceRestarting = false
                    else -> {}
                }
            }
        }) ?: return

        // Camera -> VideoSource -> VideoTrack.
        val enumerator: CameraEnumerator =
            if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        capturer = createCapturer(enumerator, preferFront)
        if (capturer == null) {
            // No camera on this device — carry on with mic + location only.
        } else {
            videoSource = factory.createVideoSource(false)
            helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            capturer!!.initialize(helper, context, videoSource!!.capturerObserver)
            capturer!!.startCapture(1280, 720, 30)
            videoTrack = factory.createVideoTrack("cam", videoSource).apply { setEnabled(true) }
            this.previewSink = previewSink
            previewSink?.let { videoTrack!!.addSink(it) }
            videoSender = pc!!.addTrack(videoTrack, listOf("updesk-field"))
            applyQuality("balanced") // sensible default for a mobile uplink
        }

        // Microphone — always on for a field device (the whole point is live A/V).
        try {
            audioSource = factory.createAudioSource(MediaConstraints())
            audioTrack = factory.createAudioTrack("audio", audioSource).apply { setEnabled(true) }
            pc!!.addTrack(audioTrack, listOf("updesk-field"))
        } catch (t: Throwable) { audioTrack = null; audioSource = null }

        // Location channel: this device pushes GPS fixes as JSON to the controller.
        locationChannel = pc!!.createDataChannel("location", DataChannel.Init())

        // Control channel: announce we're a transmit-only field device so the
        // controller shows the location panel and hides input/file UI.
        controlChannel = pc!!.createDataChannel("control", DataChannel.Init())
        controlChannel!!.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                val m = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
                when (m.optString("kind")) {
                    // The controller can dial the uplink quality up/down on a weak link.
                    "quality" -> applyQuality(m.optString("profile"))
                    // Camera flip (front/back) is driven by the controller, not the phone.
                    "switchCamera" -> switchCamera()
                }
            }
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (controlChannel?.state() == DataChannel.State.OPEN) {
                    sendControl(
                        JSONObject().put("kind", "perms").put("os", "android")
                            .put("device", "field")
                            .put("input", false).put("clipboard", false).put("file", false)
                            .put("location", true).put("camera", true).put("audio", true)
                    )
                }
            }
        })

        emitOffer(iceRestart = false)
    }

    private fun createCapturer(enumerator: CameraEnumerator, preferFront: Boolean): CameraVideoCapturer? {
        val names = enumerator.deviceNames
        // Try the preferred facing first, then the other, then anything.
        val ordered = names.sortedByDescending {
            val match = if (preferFront) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
            if (match) 1 else 0
        }
        for (name in ordered) {
            val cap = enumerator.createCapturer(name, null)
            if (cap != null) {
                usingFront = enumerator.isFrontFacing(name)
                return cap
            }
        }
        return null
    }

    /** Flip between front and back cameras mid-session. */
    fun switchCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { usingFront = isFront }
            override fun onCameraSwitchError(error: String?) {}
        })
    }

    /** Push a location fix (JSON) to the controller over the location channel. */
    fun sendLocation(fix: JSONObject) {
        val ch = locationChannel ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(fix.toString().toByteArray(Charsets.UTF_8)), false))
    }

    private fun sendControl(o: JSONObject) {
        val ch = controlChannel ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(o.toString().toByteArray(Charsets.UTF_8)), false))
    }

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

    // Controller-requested encoder profile (bitrate/fps) — helps on a weak uplink.
    fun applyQuality(profile: String) {
        val sender = videoSender ?: return
        val (maxBitrate, maxFps) = when (profile) {
            "saver" -> 500_000 to 12
            "high" -> 4_000_000 to 30
            else -> 1_800_000 to 24 // balanced
        }
        val params = sender.parameters
        if (params.encodings.isNotEmpty()) {
            params.encodings[0].maxBitrateBps = maxBitrate
            params.encodings[0].maxFramerate = maxFps
            // Keep motion smooth under pressure by shedding resolution, not fps.
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            sender.parameters = params
        }
    }

    /**
     * Tear down the session. Order matters: stop the capturer *first* so no more
     * frames flow, detach the preview sink, then dispose tracks/pc. The shared
     * [eglBase] is intentionally NOT released here — the Activity releases its
     * preview renderer first, then calls [disposeEgl], so the GL context outlives
     * everything that draws on it (avoids a teardown-race crash).
     */
    fun stop() {
        locationChannel?.dispose(); locationChannel = null
        controlChannel?.dispose(); controlChannel = null
        runCatching { capturer?.stopCapture() }
        previewSink?.let { runCatching { videoTrack?.removeSink(it) } }
        previewSink = null
        capturer?.dispose(); capturer = null
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        helper?.dispose(); helper = null
        pc?.close(); pc?.dispose(); pc = null
    }

    /** Release the shared GL context. Call *after* the preview renderer is released. */
    fun disposeEgl() {
        runCatching { eglBase.release() }
    }

    private class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
