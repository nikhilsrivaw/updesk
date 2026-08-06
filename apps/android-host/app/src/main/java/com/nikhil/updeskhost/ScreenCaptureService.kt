package com.nikhil.updeskhost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * A foreground service whose only job is to legitimately hold the
 * MediaProjection while the screen is being shared (Android 10+ requires a
 * foregroundServiceType="mediaProjection" service for this). It also gives the
 * user a persistent, visible "screen is being shared" notification — the
 * transparency the forensics use case is built on.
 */
class ScreenCaptureService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val withMic = intent?.getBooleanExtra(EXTRA_MIC, false) == true && micGranted()
        val notif = buildNotification(withMic)
        // Android 10+ must declare the mediaProjection foreground type at
        // startForeground time. Add microphone only when we actually have the
        // RECORD_AUDIO grant, and fall back to projection-only if the combined
        // start is ever rejected — audio must never take down the screen share.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (withMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try {
                startForeground(NOTIF_ID, notif, type)
            } catch (t: Throwable) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            }
        } else {
            startForeground(NOTIF_ID, notif)
        }
        acquireWakeLock()
        return START_STICKY
    }

    private fun micGranted(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // Keep the CPU running so the WebRTC session + screen capture survive the
    // phone's screen timing out — otherwise Doze suspends them and the session
    // freezes mid-use. Partial lock only (no screen), with a safety timeout so a
    // crash can never leave it draining the battery indefinitely.
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "updesk:capture").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L) // 3h cap; released on stop
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun buildNotification(withMic: Boolean): Notification {
        val channelId = "updesk_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        // Be transparent about what's being captured.
        val text = if (withMic) "Your screen and audio are being shared" else "Your screen is being shared"
        return builder
            .setContentTitle("UpDesk")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val EXTRA_MIC = "with_mic"
        fun start(ctx: Context, withMic: Boolean = false) {
            val i = Intent(ctx, ScreenCaptureService::class.java).putExtra(EXTRA_MIC, withMic)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ScreenCaptureService::class.java)) }
    }
}
