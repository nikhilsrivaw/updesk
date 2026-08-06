package com.nikhil.updeskfield

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
import org.json.JSONObject

/**
 * The always-on brain of the field host. Everything that must survive the app
 * being swiped from recents lives here, NOT in the Activity: the signaling
 * connection, the WebRTC session, and the location stream.
 *
 * Unattended, like the native host: it auto-connects, registers its fixed ID,
 * and auto-accepts any controller that presents the correct fixed password —
 * no one has to tap "accept" on the phone. It runs as a foreground service so
 * Android keeps it alive with the screen off, restarts itself if the task is
 * removed or the process is killed, and comes back on boot.
 */
class FieldService : Service(), SignalingClient.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var identity: Identity
    private var signaling: SignalingClient? = null
    private var rtc: WebRtcClient? = null
    private var location: LocationStreamer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var deliberateStop = false
    // Idle = non-restricted FGS (boot-startable); true only while actually
    // streaming (camera/mic/location types, which can't start from the background).
    private var capturing = false
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
        if (intent?.action == ACTION_STOP) {
            deliberateStop = true
            setAutostart(this, false) // stay stopped across reboots until re-enabled
            teardownAll()
            cancelWatchdog(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START_CAPTURE) {
            // Fired by CaptureLauncherActivity now that we have a foreground context.
            pendingSessionId?.let { startCapture(it) }
            return START_STICKY
        }
        // Restarted by the system / watchdog / boot: make sure we're connected.
        if (signaling == null) connect()
        return START_STICKY // ask the OS to recreate us if it kills us for memory
    }

    private fun connect() {
        if (signaling != null) return
        signaling = SignalingClient("wss://up-desk.online", identity, this).also { it.connect() }
    }

    // ---- SignalingClient.Listener (fired off the socket thread) ----

    override fun onReady() = main.post {
        signaling?.register()
        setStatus("online — waiting for a connection")
    }.let {}

    override fun onRegistered(id: String) = main.post {
        connectId = id
        notifyStatus()
    }.let {}

    override fun onIncomingRequest(sessionId: String, controllerId: String, pin: String) = main.post {
        // Unattended auth: accept iff the presented value equals our fixed password.
        if (pin != Identity.getPassword(this)) {
            signaling?.respond(sessionId, false)
            setStatus("rejected a connection (wrong password)")
            return@post
        }
        beginSession(sessionId)
    }.let {}

    override fun onAnswer(sessionId: String, sdp: String) = main.post { rtc?.onRemoteAnswer(sdp) }.let {}
    override fun onIceCandidate(sessionId: String, candidate: JSONObject) = main.post { rtc?.onRemoteIce(candidate) }.let {}
    override fun onSessionEnded(sessionId: String) = main.post {
        endSession()
        setStatus("online — waiting for a connection")
    }.let {}

    override fun onError(message: String) = main.post { setStatus("error: $message") }.let {}
    override fun onReconnecting(attempt: Int) = main.post { setStatus("reconnecting (try $attempt)…") }.let {}
    override fun onReconnected() = main.post { setStatus("reconnected — online") }.let {}

    // ---- session lifecycle ----

    private fun beginSession(sessionId: String) {
        endSession() // drop any prior session first
        pendingSessionId = sessionId
        setStatus("connection accepted — starting…")
        // Camera/mic FGS can't be started from a cold background (Android 12+). We
        // briefly bring a transparent activity forward (allowed by "Display over
        // other apps") to get a foreground context, then it pings us to capture.
        // If the app is already foreground the activity is just a no-op flash.
        try {
            startActivity(
                Intent(this, CaptureLauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (t: Throwable) {
            startCapture(sessionId) // no overlay perm — try directly (works if foreground)
        }
    }

    // The actual capture start (elevate FGS to camera/mic/location + WebRTC). Called
    // once we have a foreground context (from CaptureLauncherActivity or directly).
    private fun startCapture(sessionId: String) {
        if (rtc != null) return // already streaming
        capturing = true
        try {
            startForegroundNotice(statusText)
        } catch (t: Throwable) {
            capturing = false
            runCatching { startForegroundNotice(statusText) }
            signaling?.respond(sessionId, false)
            setStatus("couldn't start capture — enable 'Display over other apps'")
            return
        }
        try {
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
            ).also { it.init() }
            // No preview sink — this device runs headless in the background.
            rtc!!.startSession(preferFront = false, previewSink = null)
            if (hasLocationPermission()) {
                location = LocationStreamer(applicationContext) { fix -> rtc?.sendLocation(fix) }.also { it.start() }
            }
            setStatus("streaming camera + mic + location")
        } catch (t: Throwable) {
            endSession()
            signaling?.respond(sessionId, false)
            setStatus("stream failed: ${t.message}")
        }
    }

    private fun endSession() {
        pendingSessionId = null
        location?.stop(); location = null
        val client = rtc; rtc = null
        client?.stop()
        client?.disposeEgl()
        // Drop the foreground service back to the non-restricted idle type so the
        // service keeps running (and stays boot-startable) between sessions.
        if (capturing) {
            capturing = false
            runCatching { startForegroundNotice(statusText) }
        }
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

    // ---- survival: keep running through task-swipe / process death ----

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The forensic device must keep streaming after the app is swiped from
        // recents — schedule a near-immediate self-restart in case the OS kills us.
        if (!deliberateStop) scheduleRestart(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (!deliberateStop) scheduleRestart(this)
        super.onDestroy()
    }

    // ---- foreground notification ----

    private fun startForegroundNotice(text: String) {
        // IMPORTANCE_MIN → no status-bar icon; the (mandatory) foreground-service
        // notification only sits collapsed at the bottom of the shade. A new
        // channel id is used because a channel's importance can't be lowered after
        // it's first created. (Android still requires *some* FGS notification, and
        // the camera/mic in-use dots can't be hidden without Device Owner / root.)
        val channelId = "updesk-field-bg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel("updesk-field") // drop the old visible channel
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
            .setContentTitle("UpDesk Field")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()

        // Idle uses a non-restricted type so the service can start from boot/Doze;
        // only while capturing do we advertise camera/mic/location.
        val type = if (capturing) foregroundType() else idleType()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // Non-restricted FGS type for the idle/waiting state — the key to boot-start.
    private fun idleType(): Int = when {
        Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    private fun foregroundType(): Int {
        var t = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && granted(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (granted(android.Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(android.Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return t
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun hasLocationPermission() =
        granted(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "updesk-field:stream").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    // Update the notification + the Activity (if it's showing).
    private fun setStatus(text: String) {
        statusText = text
        runCatching { startForegroundNotice(text) }
        notifyStatus()
    }

    private fun notifyStatus() { main.post { onStatus?.invoke() } }

    // DTLS cert fingerprint from an SDP — signing it proves the encrypted channel
    // terminates at this device (E2E). Matches the desktop/host regex.
    private fun extractDtlsFp(sdp: String): String =
        Regex("a=fingerprint:sha-256\\s+([0-9A-Fa-f:]+)", RegexOption.IGNORE_CASE)
            .find(sdp)?.groupValues?.get(1)?.uppercase() ?: ""

    companion object {
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.nikhil.updeskfield.STOP"
        const val ACTION_START_CAPTURE = "com.nikhil.updeskfield.START_CAPTURE"

        // Lightweight status shared with the Activity (same process).
        @Volatile var running = false
        @Volatile var connectId: String = ""
        @Volatile var statusText: String = "offline"
        var onStatus: (() -> Unit)? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, FieldService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Throwable) {
                // Background-start blocked (no battery-opt exemption) — the boot/
                // watchdog paths retry; the Activity start always succeeds.
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FieldService::class.java).setAction(ACTION_STOP))
        }

        // One-shot near-immediate restart (used from onTaskRemoved / onDestroy).
        private fun scheduleRestart(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getForegroundService(
                ctx, 1, Intent(ctx, FieldService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val at = System.currentTimeMillis() + 1500
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }

        // Periodic insurance: every ~15 min, make sure the service is up.
        fun scheduleWatchdog(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // One-shot alarm that fires EVEN IN DOZE. setInexactRepeating is
            // deferred indefinitely during Doze — exactly the "offline after long
            // inactivity" bug. setAndAllowWhileIdle punches through Doze; the
            // receiver reschedules the next one, so it self-chains 24/7.
            val at = System.currentTimeMillis() + 10L * 60 * 1000
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else
                    am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }

        fun cancelWatchdog(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, WatchdogReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) runCatching { am.cancel(pi) }
        }

        // Whether the device should be online 24/7 (set when the operator goes
        // online; cleared on a deliberate Stop). Gates boot + watchdog restarts.
        fun setAutostart(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("updesk-field", Context.MODE_PRIVATE)
                .edit().putBoolean("autostart", on).apply()
        }

        fun autostartEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences("updesk-field", Context.MODE_PRIVATE)
                .getBoolean("autostart", false)
    }
}
