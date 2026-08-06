package com.nikhil.updeskhost

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import kotlin.random.Random

/**
 * Baseline UpDesk Android host: go online, show ID + PIN, and on a PIN-correct
 * request, share the screen to the controller over WebRTC.
 */
class MainActivity : AppCompatActivity(), SignalingClient.Listener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var identity: Identity
    private lateinit var signaling: SignalingClient
    private var rtc: WebRtcClient? = null

    private var currentPin = ""
    private var pendingSessionId: String? = null

    private lateinit var statusView: TextView
    private lateinit var idView: TextView
    private lateinit var pinView: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null && pendingSessionId != null) {
            beginShare(pendingSessionId!!, data)
        } else {
            pendingSessionId?.let { signaling.respond(it, false) }
            setStatus("screen permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.status)
        idView = findViewById(R.id.myId)
        pinView = findViewById(R.id.myPin)

        identity = Identity.load(this)

        // Runtime permissions: notifications (Android 13+) for the foreground
        // service's visible notification, and microphone (optional) so audio can
        // be streamed. Both are best-effort — the app works without either.
        val wanted = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) wanted.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) wanted.add(android.Manifest.permission.RECORD_AUDIO)
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)

        findViewById<Button>(R.id.goOnlineBtn).setOnClickListener { goOnline() }
        findViewById<Button>(R.id.newPinBtn).setOnClickListener {
            currentPin = genPin(); pinView.text = currentPin
        }
        findViewById<Button>(R.id.chatSend).setOnClickListener { sendChat() }
        // Open Accessibility settings so the user can enable remote-control input.
        findViewById<Button>(R.id.enableControlBtn).setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // Root input mode (custody devices) — checks root, toggles routing.
        findViewById<Button>(R.id.rootBtn).setOnClickListener {
            Thread {
                val ok = RootInput.available
                ui.post {
                    if (!ok) { setStatus("root not available on this device") }
                    else {
                        RootInput.enabled = !RootInput.enabled
                        if (RootInput.enabled) {
                            val m = DisplayMetrics().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(it) }
                            RootInput.start(m.widthPixels, m.heightPixels)
                        }
                        findViewById<Button>(R.id.rootBtn).text =
                            if (RootInput.enabled) "Root input: ON" else getString(R.string.use_root)
                    }
                }
            }.start()
        }
        // Grant full-storage access for the remote file browser.
        findViewById<Button>(R.id.enableFilesBtn).setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private fun filesGranted(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
            android.os.Environment.isExternalStorageManager()

    override fun onResume() {
        super.onResume()
        // Reflect whether remote-control (Accessibility) is currently enabled.
        val btn = findViewById<Button>(R.id.enableControlBtn)
        btn.text = if (InputAccessibilityService.isEnabled)
            "Remote control: ON" else getString(R.string.enable_control)
        val fb = findViewById<Button>(R.id.enableFilesBtn)
        fb.text = if (filesGranted()) "File access: ON" else getString(R.string.enable_files)
        // If the operator just enabled Accessibility mid-session, clear the
        // VIEW-ONLY warning on return to the app.
        if (rtc != null) {
            val warn = if (inputReady()) "" else " — VIEW-ONLY (enable 'Remote control')"
            setStatus("sharing screen$warn")
        }
    }

    private fun goOnline() {
        currentPin = genPin()
        pinView.text = currentPin
        setStatus("connecting…")
        signaling = SignalingClient("wss://up-desk.online", identity, this)
        signaling.connect()
    }

    // ---- SignalingClient.Listener (all fired off the socket thread) ----
    // Block bodies (not `= ui.post {}`) so each returns Unit, not Handler.post's Boolean.

    override fun onReady() { ui.post {
        // Warn up-front if remote input won't work, so the operator fixes it
        // before a controller connects and finds taps do nothing.
        val hint = if (inputReady()) "" else "  ⚠ enable 'Remote control' for input"
        setStatus("online — waiting for a connection$hint")
        signaling.register()
    } }

    // Can we actually inject the controller's input? Either the Accessibility
    // service is enabled, or root routing is active. If neither, a session is
    // view-only and the operator should know.
    private fun inputReady() =
        InputAccessibilityService.isEnabled || (RootInput.enabled && RootInput.available)

    override fun onRegistered(connectId: String) { ui.post {
        idView.text = connectId.replace(Regex("(\\d{3})(\\d{3})(\\d{3})"), "$1 $2 $3")
    } }

    override fun onIncomingRequest(sessionId: String, controllerId: String, pin: String) { ui.post {
        if (pin != currentPin) {
            signaling.respond(sessionId, false)
            setStatus("a connection was rejected (wrong PIN)")
            return@post
        }
        // PIN correct -> ask for the one-time screen-capture permission.
        pendingSessionId = sessionId
        setStatus("PIN correct — requesting screen permission")
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    } }

    private fun micGranted(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun beginShare(sessionId: String, projectionData: Intent) {
        ScreenCaptureService.start(this, micGranted())
        // The foreground service becomes active asynchronously; on Android 14 the
        // MediaProjection can't be used until it is. Give it a beat, then capture.
        ui.postDelayed({ startCapture(sessionId, projectionData) }, 700)
    }

    private fun startCapture(sessionId: String, projectionData: Intent) {
        try {
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            rtc = WebRtcClient(
                context = applicationContext,
                onLocalIce = { cand -> signaling.sendIce(sessionId, cand) },
                onOfferReady = { sdp ->
                    signaling.respond(sessionId, true)  // accept
                    signaling.sendOffer(sessionId, sdp) // then the media offer
                    // Sign our DTLS fingerprint so the controller can verify the
                    // encrypted channel really terminates at this device (E2E).
                    val fp = extractDtlsFp(sdp)
                    if (fp.isNotEmpty()) {
                        signaling.sendE2E(sessionId, fp, identity.sign(fp.toByteArray(Charsets.UTF_8)), identity.publicKeyB64)
                    }
                    ui.post {
                        val warn = if (inputReady()) "" else " — VIEW-ONLY (enable 'Remote control')"
                        setStatus("sharing screen$warn"); showChat(true)
                    }
                },
                onChat = { text -> ui.post { addChatMessage(false, text) } },
            ).also { it.init() }
            rtc!!.startSession(projectionData, metrics.widthPixels, metrics.heightPixels, micGranted())
        } catch (t: Throwable) {
            // Surface the failure instead of crashing the app.
            ScreenCaptureService.stop(this)
            signaling.respond(sessionId, false)
            setStatus("share failed: ${t.message}")
        }
    }

    override fun onAnswer(sessionId: String, sdp: String) { ui.post { rtc?.onRemoteAnswer(sdp) } }
    override fun onIceCandidate(sessionId: String, candidate: JSONObject) { ui.post { rtc?.onRemoteIce(candidate) } }

    override fun onSessionEnded(sessionId: String) { ui.post {
        rtc?.stop(); rtc = null
        ScreenCaptureService.stop(this)
        pendingSessionId = null
        showChat(false)
        setStatus("session ended — online")
    } }

    override fun onError(message: String) { ui.post { setStatus("error: $message") } }
    override fun onReconnecting(attempt: Int) { ui.post { setStatus("connection lost — reconnecting (try $attempt)…") } }
    override fun onReconnected() { ui.post { setStatus("reconnected — online") } }

    // ---- chat ----
    private fun showChat(show: Boolean) {
        findViewById<View>(R.id.chatCard).visibility = if (show) View.VISIBLE else View.GONE
        if (!show) findViewById<android.widget.LinearLayout>(R.id.chatMessages).removeAllViews()
    }

    private fun sendChat() {
        val input = findViewById<EditText>(R.id.chatInput)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        rtc?.sendChat(text)
        addChatMessage(true, text)
        input.setText("")
    }

    private fun addChatMessage(mine: Boolean, text: String) {
        val box = findViewById<android.widget.LinearLayout>(R.id.chatMessages)
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(if (mine) 0xFFFFFFFF.toInt() else 0xFFE8EAED.toInt())
            setBackgroundColor(if (mine) 0xFF2563EB.toInt() else 0xFF232C3B.toInt())
            setPadding(20, 12, 20, 12)
            textSize = 14f
        }
        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 6
            gravity = if (mine) android.view.Gravity.END else android.view.Gravity.START
        }
        box.addView(tv, lp)
        // auto-scroll to bottom
        findViewById<ScrollView>(R.id.chatScroll).post {
            findViewById<ScrollView>(R.id.chatScroll).fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun setStatus(s: String) { statusView.text = s }
    private fun genPin() = (100000 + Random.nextInt(900000)).toString() // 6 digits

    // DTLS cert fingerprint from an SDP — WebRTC binds the media keys to it, so
    // signing it proves the channel is ours (matches the desktop's regex).
    private fun extractDtlsFp(sdp: String): String =
        Regex("a=fingerprint:sha-256\\s+([0-9A-Fa-f:]+)", RegexOption.IGNORE_CASE)
            .find(sdp)?.groupValues?.get(1)?.uppercase() ?: ""

    override fun onDestroy() {
        super.onDestroy()
        rtc?.stop()
        if (::signaling.isInitialized) signaling.close()
    }
}
