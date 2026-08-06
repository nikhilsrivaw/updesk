package com.nikhil.updeskhost

import android.content.Intent
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
 * Setup / control panel for the UNATTENDED (native) host. Streaming + auto-accept
 * live in [HostService] so the phone keeps serving with the app closed or swiped
 * away. This screen shows the fixed ID + password, starts/stops the 24/7 service,
 * and helps grant the three things unattended capture needs:
 *   1. Accessibility  — input injection AND auto-confirming the capture dialog
 *   2. Display over other apps — so the service can pop that dialog from the bg
 *   3. Battery-optimization exemption — so it stays alive for days
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

        Identity.load(this)
        passwordEdit.setText(Identity.getPassword(this))

        goBtn.setOnClickListener { goOnline() }
        stopBtn.setOnClickListener { HostService.stop(this); setStatusText("stopped") }
        findViewById<Button>(R.id.savePwBtn).setOnClickListener {
            Identity.setPassword(this, passwordEdit.text.toString()); setStatusText("password saved")
        }
        findViewById<Button>(R.id.newPwBtn).setOnClickListener {
            passwordEdit.setText(Identity.regeneratePassword(this)); setStatusText("new password generated")
        }
        findViewById<Button>(R.id.enableControlBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.overlayBtn).setOnClickListener { requestOverlay() }
        findViewById<Button>(R.id.batteryBtn).setOnClickListener { requestBatteryExemption() }

        requestBasePermissions()
    }

    private fun goOnline() {
        Identity.setPassword(this, passwordEdit.text.toString())
        requestBasePermissions()
        if (!InputAccessibilityService.isEnabled)
            setStatusText("enable 'Remote control (Accessibility)' for input + auto capture")
        else if (!canOverlay())
            setStatusText("enable 'Display over other apps' so capture can start unattended")
        HostService.setAutostart(this, true)
        HostService.start(this)
        HostService.scheduleWatchdog(this)
        setStatusText("going online…")
    }

    private fun requestBasePermissions() {
        val wanted = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            wanted.add(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            wanted.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    private fun canOverlay() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlay() {
        if (canOverlay()) { setStatusText("'Display over other apps' already on"); return }
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { setStatusText("battery optimization already off"); return }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        HostService.onStatus = { runOnUiThread { refreshUi() } }
        refreshUi()
        findViewById<Button>(R.id.enableControlBtn).text =
            if (InputAccessibilityService.isEnabled) "Remote control: ON" else getString(R.string.enable_control)
        findViewById<Button>(R.id.overlayBtn).text =
            if (canOverlay()) "Display over apps: ON" else "Allow display over other apps"
    }

    override fun onPause() { super.onPause(); HostService.onStatus = null }

    private fun refreshUi() {
        val id = HostService.connectId
        idView.text = if (id.isNotEmpty()) id.replace(Regex("(\\d{3})(\\d{3})(\\d{3})"), "$1 $2 $3") else "—"
        statusView.text = if (HostService.running) HostService.statusText else "offline — tap Go online"
        goBtn.isEnabled = !HostService.running
        stopBtn.isEnabled = HostService.running
    }

    private fun setStatusText(s: String) { statusView.text = s }
}
