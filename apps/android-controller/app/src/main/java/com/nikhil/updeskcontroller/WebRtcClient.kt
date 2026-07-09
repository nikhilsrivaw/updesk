package com.nikhil.updeskcontroller

import android.content.Context
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Controller-side WebRTC: answers the host's offer, renders the incoming screen
 * to [renderer], and exposes the host's `input` data channel so touches/keys can
 * be sent back. (control/file channels are ignored in this baseline.)
 */
class WebRtcClient(
    private val context: Context,
    private val renderer: SurfaceViewRenderer,
    private val onLocalIce: (JSONObject) -> Unit,
    private val onAnswerReady: (String) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    val eglBase: EglBase = EglBase.create()
    private lateinit var factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var inputChannel: DataChannel? = null

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
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
    }

    /** Handle the host's offer: set remote, create + send answer. */
    fun onRemoteOffer(sdp: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                onLocalIce(
                    JSONObject().put("sdpMid", c.sdpMid)
                        .put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp)
                )
            }
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) { track.addSink(renderer); onStatus("video track received") }
            }
            override fun onDataChannel(dc: DataChannel) {
                if (dc.label() == "input") inputChannel = dc
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                (receiver.track() as? VideoTrack)?.let { it.addSink(renderer); onStatus("video track received") }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) { onStatus("ice: $s") }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
        }) ?: return

        // Sequence properly: setRemoteDescription is async — only createAnswer
        // once it has actually applied, or the video m-line isn't negotiated.
        pc!!.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                pc!!.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc!!.setLocalDescription(EmptySdp(), desc)
                        onAnswerReady(desc.description)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) { onStatus("answer failed: $error") }
                    override fun onSetFailure(error: String?) {}
                }, MediaConstraints())
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) { onStatus("remote sdp failed: $error") }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun onRemoteIce(candidate: JSONObject) {
        pc?.addIceCandidate(
            IceCandidate(
                candidate.optString("sdpMid"),
                candidate.optInt("sdpMLineIndex"),
                candidate.optString("candidate"),
            )
        )
    }

    /** Send a controller input event (mouse move/click, key) to the host. */
    fun sendInput(event: JSONObject) {
        val ch = inputChannel ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        val bytes = event.toString().toByteArray(StandardCharsets.UTF_8)
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    fun stop() {
        inputChannel?.dispose(); inputChannel = null
        pc?.close(); pc?.dispose(); pc = null
        runCatching { renderer.release() }
    }

    private class EmptySdp : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
