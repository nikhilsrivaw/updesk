package com.nikhil.updeskhost

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.widget.Button
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

        // Android 13+ needs runtime notification permission for the foreground
        // service's visible "screen is being shared" notification.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        findViewById<Button>(R.id.goOnlineBtn).setOnClickListener { goOnline() }
        findViewById<Button>(R.id.newPinBtn).setOnClickListener {
            currentPin = genPin(); pinView.text = currentPin
        }
        // Open Accessibility settings so the user can enable remote-control input.
        findViewById<Button>(R.id.enableControlBtn).setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
    }

    private fun goOnline() {
        currentPin = genPin()
        pinView.text = currentPin
        setStatus("connecting…")
        signaling = SignalingClient("wss://updesk.duckdns.org", identity, this)
        signaling.connect()
    }

    // ---- SignalingClient.Listener (all fired off the socket thread) ----
    // Block bodies (not `= ui.post {}`) so each returns Unit, not Handler.post's Boolean.

    override fun onReady() { ui.post {
        setStatus("online — waiting for a connection")
        signaling.register()
    } }

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

    private fun beginShare(sessionId: String, projectionData: Intent) {
        ScreenCaptureService.start(this)
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
                    ui.post { setStatus("sharing screen") }
                },
            ).also { it.init() }
            rtc!!.startSession(projectionData, metrics.widthPixels, metrics.heightPixels)
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
        setStatus("session ended — online")
    } }

    override fun onError(message: String) { ui.post { setStatus("error: $message") } }

    private fun setStatus(s: String) { statusView.text = s }
    private fun genPin() = (1000 + Random.nextInt(9000)).toString()

    override fun onDestroy() {
        super.onDestroy()
        rtc?.stop()
        if (::signaling.isInitialized) signaling.close()
    }
}
