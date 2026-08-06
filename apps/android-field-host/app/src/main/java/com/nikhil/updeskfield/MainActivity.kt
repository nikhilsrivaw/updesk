package com.nikhil.updeskfield

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Setup / control panel for the field host. The actual streaming lives in
 * [FieldService] so it keeps running when this screen is closed or the app is
 * swiped from recents. This Activity just: shows the fixed ID + password, lets
 * the operator go online (start the 24/7 service) or stop it, and helps grant
 * the permissions that keep it alive (battery-optimization exemption, background
 * location).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var idView: TextView
    private lateinit var statusView: TextView
    private lateinit var passwordEdit: EditText
    private lateinit var goBtn: Button
    private lateinit var stopBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        idView = findViewById(R.id.myId)
        statusView = findViewById(R.id.status)
        passwordEdit = findViewById(R.id.password)
        goBtn = findViewById(R.id.goOnlineBtn)
        stopBtn = findViewById(R.id.stopBtn)

        // Ensure an identity + password exist and show them.
        Identity.load(this)
        passwordEdit.setText(Identity.getPassword(this))

        goBtn.setOnClickListener { goOnline() }
        stopBtn.setOnClickListener { stopService() }
        findViewById<Button>(R.id.savePwBtn).setOnClickListener {
            Identity.setPassword(this, passwordEdit.text.toString())
            setStatusText("password saved")
        }
        findViewById<Button>(R.id.newPwBtn).setOnClickListener {
            passwordEdit.setText(Identity.regeneratePassword(this))
            setStatusText("new password generated")
        }
        findViewById<Button>(R.id.batteryBtn).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.overlayBtn).setOnClickListener { requestOverlay() }

        requestForegroundPermissions()
    }

    private fun canOverlay() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlay() {
        if (canOverlay()) { setStatusText("'Display over other apps' already on"); return }
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun goOnline() {
        // Persist the operator's intent, grant what we can, then start the service.
        Identity.setPassword(this, passwordEdit.text.toString()) // lock in any edit
        requestForegroundPermissions()
        requestBackgroundLocation()
        // 24/7 needs two one-time grants: battery-optimization exemption (so Doze
        // can't suspend it) and "Display over other apps" (so it can start the
        // camera from a cold background). Prompt for whichever is missing — one at
        // a time so we don't stack two settings screens.
        val needBattery = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val needOverlay = !canOverlay()
        FieldService.setAutostart(this, true)
        FieldService.start(this)
        FieldService.scheduleWatchdog(this)
        when {
            needBattery -> { requestBatteryExemption(); setStatusText("grant battery exemption, then tap Go online again") }
            needOverlay -> { requestOverlay(); setStatusText("allow 'Display over other apps', then tap Go online again") }
            else -> setStatusText("going online…")
        }
    }

    private fun stopService() {
        FieldService.stop(this)
        setStatusText("stopped")
    }

    // ---- permissions ----

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestForegroundPermissions() {
        val wanted = mutableListOf<String>()
        fun need(p: String) { if (!granted(p)) wanted.add(p) }
        need(android.Manifest.permission.CAMERA)
        need(android.Manifest.permission.RECORD_AUDIO)
        need(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    // Background location must be requested separately (Android 10+), after the
    // foreground grant, so location keeps flowing while the app isn't open.
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!granted(android.Manifest.permission.ACCESS_FINE_LOCATION)) return
        if (granted(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return
        requestPermissions(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
    }

    // Ask the OS to exempt us from Doze/battery optimization — the single most
    // important toggle for a service that must stay alive for hours/days.
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            setStatusText("battery optimization already off"); return
        }
        runCatching {
            startActivity(
                android.content.Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    // ---- status wiring (the service pushes updates here while we're visible) ----

    override fun onResume() {
        super.onResume()
        FieldService.onStatus = { runOnUiThread { refreshUi() } }
        refreshUi()
        findViewById<Button>(R.id.overlayBtn).text =
            if (canOverlay()) "Display over apps: ON" else "Allow display over other apps"
    }

    override fun onPause() {
        super.onPause()
        FieldService.onStatus = null
    }

    private fun refreshUi() {
        val id = FieldService.connectId
        idView.text = if (id.isNotEmpty())
            id.replace(Regex("(\\d{3})(\\d{3})(\\d{3})"), "$1 $2 $3") else "—"
        statusView.text = if (FieldService.running) FieldService.statusText else "offline — tap Go online"
        goBtn.isEnabled = !FieldService.running
        stopBtn.isEnabled = FieldService.running
    }

    private fun setStatusText(s: String) { statusView.text = s }
}
