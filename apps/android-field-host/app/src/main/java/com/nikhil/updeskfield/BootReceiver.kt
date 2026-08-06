package com.nikhil.updeskfield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the field host back online automatically after a reboot — but only if
 * the operator had it online (autostart flag), so a deliberate Stop survives a
 * restart. This is what makes it "always online" like the native host.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (FieldService.autostartEnabled(context)) {
                FieldService.start(context)
                FieldService.scheduleWatchdog(context)
            }
        }
    }
}
