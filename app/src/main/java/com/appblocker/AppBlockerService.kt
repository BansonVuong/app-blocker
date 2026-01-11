package com.appblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.content.ContextCompat

class AppBlockerService : AccessibilityService() {

    private lateinit var storage: Storage
    private val handler = Handler(Looper.getMainLooper())
    private var currentTrackedPackage: String? = null
    private var currentBlockSetId: String? = null
    private var trackingRunnable: Runnable? = null
    private var pendingStopRunnable: Runnable? = null
    private var overlayView: TextView? = null
    private var windowManager: WindowManager? = null

    companion object {
        var isRunning = false
            private set
        private const val TRACKING_INTERVAL_MS = 1000L // Track every second
        private const val OVERLAY_MARGIN_DP = 12
    }

    override fun onCreate() {
        super.onCreate()
        storage = App.instance.storage
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        updateOverlay(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopTracking()
        removeOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Ignore our own app and system UI
        if (packageName == "com.android.systemui") {
            // Ignore transient system UI overlays (e.g., notification shade)
            return
        }

        if (packageName == this.packageName ||
            packageName == "com.android.launcher" ||
            packageName.contains("launcher")) {
            stopTracking()
            return
        }

        // Check if this app is in any block set
        val blockSet = storage.getBlockSetForApp(packageName)

        if (blockSet != null) {
            cancelPendingStop()
            // Check if quota is exceeded
            if (storage.isQuotaExceeded(blockSet)) {
                launchBlockedScreen(blockSet.name, blockSet.id)
                stopTracking()
                return
            }

            // Start or continue tracking
            if (currentTrackedPackage != packageName) {
                stopTracking()
                startTracking(packageName, blockSet.id)
                updateOverlay(blockSet)
            }
        } else {
            // App not in any block set, stop tracking
            scheduleStopTracking()
            updateOverlay(null)
        }
    }

    private fun startTracking(packageName: String, blockSetId: String) {
        cancelPendingStop()
        currentTrackedPackage = packageName
        currentBlockSetId = blockSetId

        trackingRunnable = object : Runnable {
            override fun run() {
                currentBlockSetId?.let { id ->
                    storage.addUsageSeconds(id, 1)

                    // Check if quota exceeded after adding usage
                    val blockSet = storage.getBlockSets().find { it.id == id }
                    if (blockSet != null && storage.isQuotaExceeded(blockSet)) {
                        launchBlockedScreen(blockSet.name, blockSet.id)
                        stopTracking()
                        return
                    }
                    if (blockSet != null) {
                        updateOverlay(blockSet)
                    }
                }
                handler.postDelayed(this, TRACKING_INTERVAL_MS)
            }
        }
        handler.postDelayed(trackingRunnable!!, TRACKING_INTERVAL_MS)
    }

    private fun stopTracking() {
        cancelPendingStop()
        trackingRunnable?.let { handler.removeCallbacks(it) }
        trackingRunnable = null
        currentTrackedPackage = null
        currentBlockSetId = null
    }

    private fun scheduleStopTracking() {
        if (currentTrackedPackage == null) {
            stopTracking()
            return
        }
        if (pendingStopRunnable != null) return
        pendingStopRunnable = Runnable {
            stopTracking()
        }
        handler.postDelayed(pendingStopRunnable!!, 1000L)
    }

    private fun cancelPendingStop() {
        pendingStopRunnable?.let { handler.removeCallbacks(it) }
        pendingStopRunnable = null
    }

    private fun launchBlockedScreen(blockSetName: String, blockSetId: String? = null) {
        val intent = Intent(this, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockedActivity.EXTRA_BLOCK_SET_NAME, blockSetName)
            blockSetId?.let { putExtra(BlockedActivity.EXTRA_BLOCK_SET_ID, it) }
        }
        startActivity(intent)
    }

    private fun updateOverlay(blockSet: BlockSet?) {
        if (!Settings.canDrawOverlays(this)) {
            return
        }

        val text = if (blockSet == null) {
            "No blocked app"
        } else {
            val remainingSeconds = storage.getRemainingSeconds(blockSet)
            formatRemainingTime(remainingSeconds)
        }
        val view = ensureOverlayView()
        view.text = text
        view.setTextColor(ContextCompat.getColor(this, R.color.white))
    }

    private fun ensureOverlayView(): TextView {
        if (overlayView != null) return overlayView!!

        val view = TextView(this)
        view.setTextColor(Color.WHITE)
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        view.typeface = Typeface.DEFAULT_BOLD
        view.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
        view.background = GradientDrawable().apply {
            cornerRadius = dpToPx(12).toFloat()
            setColor(Color.argb(200, 0, 0, 0))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = dpToPx(OVERLAY_MARGIN_DP)
        params.y = dpToPx(OVERLAY_MARGIN_DP)

        windowManager?.addView(view, params)
        overlayView = view
        return view
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        windowManager?.removeView(view)
        overlayView = null
    }

    private fun formatRemainingTime(remainingSeconds: Int): String {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        return String.format("%d:%02d left", minutes, seconds)
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onInterrupt() {
        stopTracking()
    }
}
