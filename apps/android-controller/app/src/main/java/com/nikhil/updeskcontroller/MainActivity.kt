package com.nikhil.updeskcontroller

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer

/**
 * Baseline UpDesk Android controller: enter a host's ID + PIN, view its screen,
 * and control it with touch (→ mouse) and the soft keyboard (→ keystrokes).
 */
class MainActivity : AppCompatActivity(), SignalingClient.Listener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var identity: Identity
    private var signaling: SignalingClient? = null
    private var rtc: WebRtcClient? = null
    private var sessionId: String? = null

    private lateinit var configView: View
    private lateinit var liveView: View
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var status: TextView
    private lateinit var keyInput: EditText
    private var lastMove = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        identity = Identity.load(this)

        configView = findViewById(R.id.config)
        liveView = findViewById(R.id.live)
        renderer = findViewById(R.id.screen)
        status = findViewById(R.id.status)
        keyInput = findViewById(R.id.keyInput)

        findViewById<Button>(R.id.connectBtn).setOnClickListener { connect() }
        findViewById<Button>(R.id.disconnectBtn).setOnClickListener { teardown() }
        setupTouch()
        setupKeyboard()
    }

    private fun connect() {
        val partnerId = findViewById<EditText>(R.id.partnerId).text.toString().filter { it.isDigit() }
        val pin = findViewById<EditText>(R.id.pin).text.toString().trim()
        if (partnerId.length < 9) { status.text = "enter the 9-digit ID"; return }
        if (pin.isEmpty()) { status.text = "enter the PIN"; return }
        configView.visibility = View.GONE
        liveView.visibility = View.VISIBLE
        status.text = "connecting…"
        signaling = SignalingClient("wss://updesk.duckdns.org", identity, this).also {
            it.connect(partnerId, pin)
        }
    }

    // ---- SignalingClient.Listener ----

    override fun onReady() { ui.post { status.text = "dialing…" } }

    override fun onSessionResponse(accepted: Boolean, sessionId: String) { ui.post {
        if (!accepted) { status.text = "rejected — wrong PIN or host declined"; return@post }
        this.sessionId = sessionId
        status.text = "accepted — connecting media…"
    } }

    override fun onOffer(sessionId: String, sdp: String) { ui.post {
        this.sessionId = sessionId
        rtc = WebRtcClient(
            applicationContext, renderer,
            onLocalIce = { c -> signaling?.sendIce(sessionId, c) },
            onAnswerReady = { answer -> signaling?.sendAnswer(sessionId, answer) },
            onStatus = { s -> ui.post { addStatus(s) } },
        ).also { it.init(); it.onRemoteOffer(sdp) }
        addStatus("negotiating…")
    } }

    override fun onIceCandidate(sessionId: String, candidate: JSONObject) { ui.post { rtc?.onRemoteIce(candidate) } }
    override fun onSessionEnded(sessionId: String) { ui.post { teardown() } }
    override fun onError(message: String) { ui.post { status.text = "error: $message" } }

    // ---- input ----

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        renderer.setOnTouchListener { v, e ->
            val x = (e.x / v.width).coerceIn(0f, 1f)
            val y = (e.y / v.height).coerceIn(0f, 1f)
            when (e.action) {
                MotionEvent.ACTION_DOWN ->
                    send("mousedown", x, y, button = "left")
                MotionEvent.ACTION_MOVE -> {
                    val now = System.currentTimeMillis()
                    if (now - lastMove >= 16) { lastMove = now; send("move", x, y) }
                }
                MotionEvent.ACTION_UP ->
                    send("mouseup", x, y, button = "left")
            }
            true
        }
    }

    private fun send(kind: String, x: Float, y: Float, button: String? = null) {
        val o = JSONObject().put("kind", kind).put("x", x.toDouble()).put("y", y.toDouble())
        if (button != null) o.put("button", button)
        rtc?.sendInput(o)
    }

    // Minimal keyboard: characters typed into the hidden field are sent as
    // keydown/keyup. Good enough for the baseline; a full IME bridge comes later.
    private fun setupKeyboard() {
        findViewById<Button>(R.id.kbBtn).setOnClickListener {
            keyInput.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(keyInput, 0)
        }
        keyInput.addTextChangedListener(object : android.text.TextWatcher {
            private var prev = ""
            override fun afterTextChanged(s: android.text.Editable?) {
                val now = s?.toString() ?: ""
                if (now.length > prev.length) {
                    val ch = now.substring(prev.length)
                    for (c in ch) { keyEvent(c.toString()) }
                } else if (now.length < prev.length) {
                    keyEvent("Backspace")
                }
                prev = now
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun keyEvent(key: String) {
        rtc?.sendInput(JSONObject().put("kind", "keydown").put("key", key))
        rtc?.sendInput(JSONObject().put("kind", "keyup").put("key", key))
    }

    // Keep the most recent few distinct events visible (latest ICE state replaces
    // the previous ICE line so it doesn't spam).
    private val statusLines = LinkedHashSet<String>()
    private fun addStatus(s: String) {
        if (s.startsWith("ice:")) statusLines.removeAll { it.startsWith("ice:") }
        statusLines.add(s)
        while (statusLines.size > 5) statusLines.remove(statusLines.first())
        status.text = statusLines.joinToString("  •  ")
    }

    private fun teardown() {
        sessionId?.let { signaling?.end(it) }
        rtc?.stop(); rtc = null
        signaling?.close(); signaling = null
        sessionId = null
        liveView.visibility = View.GONE
        configView.visibility = View.VISIBLE
        status.text = "disconnected"
    }

    override fun onDestroy() {
        super.onDestroy()
        rtc?.stop()
        signaling?.close()
    }
}
