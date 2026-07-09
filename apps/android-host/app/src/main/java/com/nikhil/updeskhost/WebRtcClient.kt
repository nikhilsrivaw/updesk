package com.nikhil.updeskhost

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.webrtc.Camera1Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
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
) {
    private val eglBase: EglBase = EglBase.create()
    private lateinit var factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var helper: SurfaceTextureHelper? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder(
            listOf(
                "turn:updesk.duckdns.org:3478?transport=udp",
                "turn:updesk.duckdns.org:3478?transport=tcp",
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
    fun startSession(projectionData: Intent, widthPx: Int, heightPx: Int) {
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
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
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
        pc!!.addTrack(videoTrack, listOf("updesk-stream"))

        // Create the offer.
        val constraints = MediaConstraints()
        pc!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc!!.setLocalDescription(EmptySdpObserver(), desc)
                onOfferReady(desc.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
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

    fun stop() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); capturer = null
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
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
