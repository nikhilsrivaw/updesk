package com.nikhil.updeskhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Periodic insurance: if the host should be online but the OS killed it, restart. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (HostService.autostartEnabled(context) && !HostService.running) {
            HostService.start(context)
        }
    }
}
