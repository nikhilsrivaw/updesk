package com.nikhil.updeskhost

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject

/**
 * The always-on brain of the UNATTENDED (native) Android host. Like the native
 * Windows service, it runs headless 24/7: connects to signaling, registers its
 * fixed ID, and auto-accepts any controller that presents the fixed password —
 * then shares the screen with NO tap on the phone.
 *
 * The one thing Android can't do silently is grant screen capture, so the flow
 * is: auto-accept -> launch a transparent [ProjectionRequestActivity] (allowed
 * from the background because we hold "Display over other apps") -> the system
 * capture dialog appears -> [InputAccessibilityService] auto-taps "Start now" ->
 * we get the token and start the WebRTC screen stream. Input, files, clipboard
 * and nav all keep working through the existing data channels + services.
 */
class HostService : Service(), SignalingClient.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var identity: Identity
    private var signaling: SignalingClient? = null
    private var rtc: WebRtcClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var deliberateStop = false
    private var pendingSessionId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        identity = Identity.load(this)
        acquireWakeLock()
        startForegroundNotice("Online — waiting for a connection")
        connect()
        scheduleWatchdog(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                deliberateStop = true
                setAutostart(this, false)
                teardownAll()
                cancelWatchdog(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROJECTION_RESULT -> {
                val ok = intent.getBooleanExtra(EXTRA_OK, false)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                onProjectionResult(ok, data)
            }
            else -> if (signaling == null) connect()
        }
        return START_STICKY
    }

    private fun connect() {
        if (signaling != null) return
        signaling = SignalingClient("wss://up-desk.online", identity, this).also { it.connect() }
    }

    // ---- SignalingClient.Listener (fired off the socket thread) ----

    override fun onReady() = main.post { signaling?.register(); setStatus("online — waiting for a connection") }.let {}
    override fun onRegistered(id: String) = main.post { connectId = id; notifyStatus() }.let {}

    override fun onIncomingRequest(sessionId: String, controllerId: String, pin: String) = main.post {
        if (pin != Identity.getPassword(this)) {
            signaling?.respond(sessionId, false)
            setStatus("rejected a connection (wrong password)")
            return@post
        }
        beginSession(sessionId)
    }.let {}

    override fun onAnswer(sessionId: String, sdp: String) = main.post { rtc?.onRemoteAnswer(sdp) }.let {}
    override fun onIceCandidate(sessionId: String, candidate: JSONObject) = main.post { rtc?.onRemoteIce(candidate) }.let {}
    override fun onSessionEnded(sessionId: String) = main.post { endSession(); setStatus("online — waiting for a connection") }.let {}
    override fun onError(message: String) = main.post { setStatus("error: $message") }.let {}
    override fun onReconnecting(attempt: Int) = main.post { setStatus("reconnecting (try $attempt)…") }.let {}
    override fun onReconnected() = main.post { setStatus("reconnected — online") }.let {}

    // ---- session lifecycle ----

    private fun beginSession(sessionId: String) {
        endSession()
        pendingSessionId = sessionId
        setStatus("connection accepted — starting screen…")
        // Arm the accessibility auto-confirm, then launch the transparent activity
        // that pops the system capture dialog (background launch needs the overlay
        // permission). The accessibility service taps "Start now" for us.
        InputAccessibilityService.armProjectionAutoConfirm()
        try {
            startActivity(
                Intent(this, ProjectionRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (t: Throwable) {
            rejectPending("couldn't open capture request (grant 'Display over other apps'): ${t.message}")
        }
    }

    private fun onProjectionResult(ok: Boolean, data: Intent?) {
        val sessionId = pendingSessionId ?: return
        if (!ok || data == null) { rejectPending("screen capture was not granted"); return }
        setStatus("streaming screen")
        // The mediaProjection foreground service must be up before capture (A14).
        ScreenCaptureService.start(this, micGranted())
        main.postDelayed({ startCapture(sessionId, data) }, 700)
    }

    private fun startCapture(sessionId: String, projectionData: Intent) {
        try {
            val (w, h) = screenSize()
            rtc = WebRtcClient(
                context = applicationContext,
                onLocalIce = { cand -> signaling?.sendIce(sessionId, cand) },
                onOfferReady = { sdp ->
                    signaling?.respond(sessionId, true)
                    signaling?.sendOffer(sessionId, sdp)
                    val fp = extractDtlsFp(sdp)
                    if (fp.isNotEmpty()) {
                        signaling?.sendE2E(sessionId, fp, identity.sign(fp.toByteArray(Charsets.UTF_8)), identity.publicKeyB64)
                    }
                },
                onChat = { /* no phone-side UI in unattended mode */ },
            ).also { it.init() }
            rtc!!.startSession(projectionData, w, h, micGranted())
        } catch (t: Throwable) {
            rejectPending("screen share failed: ${t.message}")
        }
    }

    private fun rejectPending(reason: String) {
        pendingSessionId?.let { signaling?.respond(it, false) }
        endSession()
        setStatus(reason)
    }

    private fun endSession() {
        pendingSessionId = null
        rtc?.stop(); rtc = null
        ScreenCaptureService.stop(this)
    }

    private fun teardownAll() {
        endSession()
        runCatching { signaling?.close() }
        signaling = null
        running = false
        connectId = ""
        releaseWakeLock()
        onStatus = null
    }

    // ---- survival ----

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!deliberateStop) scheduleRestart(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (!deliberateStop) scheduleRestart(this)
        super.onDestroy()
    }

    // ---- foreground notification (hidden: IMPORTANCE_MIN, no status-bar icon) ----

    private fun startForegroundNotice(text: String) {
        val channelId = "updesk-host-bg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                val ch = NotificationChannel(channelId, "Background service", NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false)
                ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
                nm.createNotificationChannel(ch)
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("UpDesk Host")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
        // The brain runs as a long-lived "special use" FGS (its own type); the
        // per-session mediaProjection type is held by ScreenCaptureService.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun micGranted() =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "updesk-host:online").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun releaseWakeLock() { runCatching { wakeLock?.release() }; wakeLock = null }

    private fun setStatus(text: String) {
        statusText = text
        runCatching { startForegroundNotice(text) }
        notifyStatus()
    }

    private fun notifyStatus() { main.post { onStatus?.invoke() } }

    private fun extractDtlsFp(sdp: String): String =
        Regex("a=fingerprint:sha-256\\s+([0-9A-Fa-f:]+)", RegexOption.IGNORE_CASE)
            .find(sdp)?.groupValues?.get(1)?.uppercase() ?: ""

    companion object {
        private const val NOTIF_ID = 2001
        const val ACTION_STOP = "com.nikhil.updeskhost.STOP"
        const val ACTION_PROJECTION_RESULT = "com.nikhil.updeskhost.PROJECTION_RESULT"
        const val EXTRA_OK = "ok"
        const val EXTRA_DATA = "data"

        @Volatile var running = false
        @Volatile var connectId: String = ""
        @Volatile var statusText: String = "offline"
        var onStatus: (() -> Unit)? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, HostService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Throwable) {}
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, HostService::class.java).setAction(ACTION_STOP))
        }

        /** Called by ProjectionRequestActivity to hand the capture token to the service. */
        fun deliverProjection(ctx: Context, ok: Boolean, data: Intent?) {
            val i = Intent(ctx, HostService::class.java)
                .setAction(ACTION_PROJECTION_RESULT)
                .putExtra(EXTRA_OK, ok)
            if (data != null) i.putExtra(EXTRA_DATA, data)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Throwable) {}
        }

        private fun scheduleRestart(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getForegroundService(
                ctx, 1, Intent(ctx, HostService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1500, pi) }
        }

        fun scheduleWatchdog(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val interval = 15L * 60 * 1000
            runCatching { am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + interval, interval, pi) }
        }

        fun cancelWatchdog(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, WatchdogReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) runCatching { am.cancel(pi) }
        }

        fun setAutostart(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("updesk", Context.MODE_PRIVATE).edit().putBoolean("autostart", on).apply()
        }

        fun autostartEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences("updesk", Context.MODE_PRIVATE).getBoolean("autostart", false)
    }
}
