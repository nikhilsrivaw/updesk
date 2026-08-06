package com.nikhil.updeskfield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Doze-proof heartbeat. Each firing (a) revives the service if the OS killed it,
 * and (b) schedules the NEXT one-shot alarm — so it self-chains around the clock
 * even while the phone is idle. This is what keeps the device online through long
 * inactivity, alongside the battery-optimization exemption.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (FieldService.autostartEnabled(context)) {
            FieldService.start(context)            // no-op if already up; revives if killed
            FieldService.scheduleWatchdog(context) // chain the next Doze-proof tick
        }
    }
}
