package com.appblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class AppBlockerService : AccessibilityService() {

    private lateinit var storage: Storage
    private val handler = Handler(Looper.getMainLooper())
    private var currentTrackedPackage: String? = null
    private var currentBlockSetId: String? = null
    private var trackingRunnable: Runnable? = null

    companion object {
        var isRunning = false
            private set
        private const val TRACKING_INTERVAL_MS = 1000L // Track every second
    }

    override fun onCreate() {
        super.onCreate()
        storage = App.instance.storage
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopTracking()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Ignore our own app and system UI
        if (packageName == this.packageName ||
            packageName == "com.android.systemui" ||
            packageName == "com.android.launcher" ||
            packageName.contains("launcher")) {
            return
        }

        // Check if this app is in any block set
        val blockSet = storage.getBlockSetForApp(packageName)

        if (blockSet != null) {
            // Check if quota is exceeded
            if (storage.isQuotaExceeded(blockSet)) {
                launchBlockedScreen(blockSet.name)
                stopTracking()
                return
            }

            // Start or continue tracking
            if (currentTrackedPackage != packageName) {
                stopTracking()
                startTracking(packageName, blockSet.id)
            }
        } else {
            // App not in any block set, stop tracking
            stopTracking()
        }
    }

    private fun startTracking(packageName: String, blockSetId: String) {
        currentTrackedPackage = packageName
        currentBlockSetId = blockSetId

        trackingRunnable = object : Runnable {
            override fun run() {
                currentBlockSetId?.let { id ->
                    storage.addUsageSeconds(id, 1)

                    // Check if quota exceeded after adding usage
                    val blockSet = storage.getBlockSets().find { it.id == id }
                    if (blockSet != null && storage.isQuotaExceeded(blockSet)) {
                        launchBlockedScreen(blockSet.name)
                        stopTracking()
                        return
                    }
                }
                handler.postDelayed(this, TRACKING_INTERVAL_MS)
            }
        }
        handler.postDelayed(trackingRunnable!!, TRACKING_INTERVAL_MS)
    }

    private fun stopTracking() {
        trackingRunnable?.let { handler.removeCallbacks(it) }
        trackingRunnable = null
        currentTrackedPackage = null
        currentBlockSetId = null
    }

    private fun launchBlockedScreen(blockSetName: String) {
        val intent = Intent(this, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockedActivity.EXTRA_BLOCK_SET_NAME, blockSetName)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        stopTracking()
    }
}
