package com.nikhil.updeskfield

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * A transparent, no-UI activity whose only purpose is to give the app a brief
 * FOREGROUND context. Android 12+ won't let a service start camera/mic capture
 * from a cold background, but it will once the app is (momentarily) in the
 * foreground. [FieldService] launches this from the background — allowed because
 * we hold "Display over other apps" — and on resume we ping the service to start
 * the actual capture, then finish immediately. The user sees at most a flash.
 */
class CaptureLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // We're foreground now — tell the service it's safe to open the camera.
        val i = Intent(this, FieldService::class.java).setAction(FieldService.ACTION_START_CAPTURE)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } catch (_: Throwable) {}
        finish()
    }
}
