package com.nikhil.updeskhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the unattended host back online after a reboot (if autostart was on). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (HostService.autostartEnabled(context)) {
                HostService.start(context)
                HostService.scheduleWatchdog(context)
            }
        }
    }
}
