package com.nikhil.updeskhost

import android.os.Handler
import android.os.Looper
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
        fun onReconnecting(attempt: Int) {}   // optional
        fun onReconnected() {}                 // optional
    }

    private val ui = Handler(Looper.getMainLooper())
    private var deliberate = false
    private var attempt = 0
    private var everConnected = false

    // pingInterval keeps the WebSocket alive so idle/hiccup drops ("Software
    // caused connection abort") don't kill the session silently.
    private val http = OkHttpClient.Builder()
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null

    fun connect() {
        deliberate = false
        openSocket()
    }

    private fun openSocket() {
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (everConnected) ui.post { listener.onReconnected() }
                everConnected = true
                attempt = 0
                send(
                    JSONObject()
                        .put("type", "auth_init")
                        .put("identityId", identity.id)
                        .put("kind", "device")
                        .put("publicKey", identity.publicKeyB64)
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "websocket failure")
                scheduleReconnect()
            }
        })
    }

    // Reconnect with capped exponential backoff so a network hiccup doesn't drop
    // the host off the grid — it re-auths + re-registers automatically.
    private fun scheduleReconnect() {
        if (deliberate) return
        attempt++
        val delay = minOf(15000L, 1000L * attempt)
        ui.post { listener.onReconnecting(attempt) }
        ui.postDelayed({ if (!deliberate) openSocket() }, delay)
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

    fun close() { deliberate = true; ws?.close(1000, "bye"); ws = null }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }
}
