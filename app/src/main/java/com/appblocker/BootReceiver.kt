package com.appblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // The accessibility service will auto-start if enabled
            // This receiver is here if we need additional boot-time initialization
        }
    }
}
