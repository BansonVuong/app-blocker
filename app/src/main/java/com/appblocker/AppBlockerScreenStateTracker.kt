package com.appblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class AppBlockerScreenStateTracker(
    private val context: Context,
    private val onScreenOff: () -> Unit,
    private val onScreenOn: () -> Unit
) {
    var isScreenOff: Boolean = false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOff = true
                    onScreenOff()
                }

                Intent.ACTION_SCREEN_ON -> {
                    isScreenOff = false
                    onScreenOn()
                }
            }
        }
    }

    fun register() {
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        )
    }

    fun unregister() {
        context.unregisterReceiver(receiver)
    }
}
