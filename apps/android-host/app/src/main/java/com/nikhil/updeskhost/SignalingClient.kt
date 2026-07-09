package com.nikhil.updeskhost

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * WebSocket signaling client — identical JSON protocol to the desktop host.
 *
 *   auth_init -> auth_challenge -> auth_response -> auth_ok
 *   register  -> registered (carries the 9-digit connectId)
 *   incoming_request (carries the controller's pin)
 *   session_response ; offer / answer / ice_candidate ; end_session
 *
 * The server runs open enrollment (OPEN_ENROLLMENT=1) so no enroll code is sent.
 */
class SignalingClient(
    private val url: String,          // wss://updesk.duckdns.org
    private val identity: Identity,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()                                   // authenticated
        fun onRegistered(connectId: String)            // server assigned our ID
        fun onIncomingRequest(sessionId: String, controllerId: String, pin: String)
        fun onAnswer(sessionId: String, sdp: String)
        fun onIceCandidate(sessionId: String, candidate: JSONObject)
        fun onSessionEnded(sessionId: String)
        fun onError(message: String)
    }

    private val http = OkHttpClient()
    private var ws: WebSocket? = null

    fun connect() {
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Kick off the handshake.
                send(
                    JSONObject()
                        .put("type", "auth_init")
                        .put("identityId", identity.id)
                        .put("kind", "device")
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
            "auth_ok" -> listener.onReady()
            "auth_error" -> listener.onError("auth: " + msg.optString("message"))
            "registered" -> listener.onRegistered(msg.optString("connectId"))
            "incoming_request" -> listener.onIncomingRequest(
                msg.optString("sessionId"),
                msg.optString("controllerId"),
                msg.optString("pin"),
            )
            "answer" -> listener.onAnswer(msg.optString("sessionId"), msg.optString("sdp"))
            "ice_candidate" -> {
                val cand = msg.optJSONObject("candidate")
                if (cand != null) listener.onIceCandidate(msg.optString("sessionId"), cand)
            }
            "session_ended", "peer_disconnected" -> listener.onSessionEnded(msg.optString("sessionId"))
            "error" -> listener.onError(msg.optString("message"))
        }
    }

    // ---- outbound ----
    fun register() = send(
        JSONObject().put("type", "register").put("deviceId", identity.id)
            .put("metadata", JSONObject().put("os", "android").put("app", "updesk-host"))
    )

    fun respond(sessionId: String, accepted: Boolean) = send(
        JSONObject().put("type", "session_response").put("sessionId", sessionId).put("accepted", accepted)
    )

    fun sendOffer(sessionId: String, sdp: String) = send(
        JSONObject().put("type", "offer").put("sessionId", sessionId).put("sdp", sdp)
    )

    fun sendIce(sessionId: String, candidate: JSONObject) = send(
        JSONObject().put("type", "ice_candidate").put("sessionId", sessionId).put("candidate", candidate)
    )

    fun end(sessionId: String) = send(
        JSONObject().put("type", "end_session").put("sessionId", sessionId)
    )

    fun close() { ws?.close(1000, "bye"); ws = null }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }
}
