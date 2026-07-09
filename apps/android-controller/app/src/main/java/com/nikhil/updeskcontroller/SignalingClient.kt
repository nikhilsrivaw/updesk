package com.nikhil.updeskcontroller

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Controller-side signaling. Dials a host by 9-digit partnerId + pin; the host
 * validates the PIN, accepts, and sends the media **offer** (it owns the screen).
 * The controller answers.
 *
 *   auth_init -> auth_challenge -> auth_response -> auth_ok
 *   connect_request(partnerId, pin) -> session_response(accepted)
 *   offer (from host) -> answer (to host) ; ice_candidate both ways
 */
class SignalingClient(
    private val url: String,
    private val identity: Identity,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onSessionResponse(accepted: Boolean, sessionId: String)
        fun onOffer(sessionId: String, sdp: String)
        fun onIceCandidate(sessionId: String, candidate: JSONObject)
        fun onSessionEnded(sessionId: String)
        fun onError(message: String)
    }

    private val http = OkHttpClient()
    private var ws: WebSocket? = null
    private var pendingPartnerId = ""
    private var pendingPin = ""

    fun connect(partnerId: String, pin: String) {
        pendingPartnerId = partnerId
        pendingPin = pin
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(
                    JSONObject()
                        .put("type", "auth_init")
                        .put("identityId", identity.id)
                        .put("kind", "controller")
                        .put("publicKey", identity.publicKeyB64)
                )
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "websocket failure")
            }
        })
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "auth_challenge" -> {
                val nonce = msg.optString("nonce")
                val sig = identity.sign(nonce.toByteArray(Charsets.UTF_8))
                send(JSONObject().put("type", "auth_response").put("signature", sig))
            }
            "auth_ok" -> {
                listener.onReady()
                // Immediately dial the partner.
                send(
                    JSONObject().put("type", "connect_request")
                        .put("partnerId", pendingPartnerId).put("pin", pendingPin)
                )
            }
            "auth_error" -> listener.onError("auth: " + msg.optString("message"))
            "session_response" -> listener.onSessionResponse(
                msg.optBoolean("accepted"), msg.optString("sessionId")
            )
            "offer" -> listener.onOffer(msg.optString("sessionId"), msg.optString("sdp"))
            "ice_candidate" -> {
                val cand = msg.optJSONObject("candidate")
                if (cand != null) listener.onIceCandidate(msg.optString("sessionId"), cand)
            }
            "session_ended", "peer_disconnected" -> listener.onSessionEnded(msg.optString("sessionId"))
            "error" -> listener.onError(msg.optString("message"))
        }
    }

    fun sendAnswer(sessionId: String, sdp: String) = send(
        JSONObject().put("type", "answer").put("sessionId", sessionId).put("sdp", sdp)
    )

    fun sendIce(sessionId: String, candidate: JSONObject) = send(
        JSONObject().put("type", "ice_candidate").put("sessionId", sessionId).put("candidate", candidate)
    )

    fun end(sessionId: String) = send(JSONObject().put("type", "end_session").put("sessionId", sessionId))

    fun close() { ws?.close(1000, "bye"); ws = null }
    private fun send(o: JSONObject) { ws?.send(o.toString()) }
}
