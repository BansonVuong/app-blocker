package com.appblocker

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.content.SharedPreferences

class AppBlockerService : AccessibilityService() {

    private lateinit var storage: Storage
    private val handler = Handler(Looper.getMainLooper())
    private var currentTrackedPackage: String? = null
    private var currentBlockSet: BlockSet? = null
    private var overlayUpdateRunnable: Runnable? = null
    private var pendingStopRunnable: Runnable? = null
    private var overlayView: TextView? = null
    private var debugOverlayView: TextView? = null
    private var windowManager: WindowManager? = null
    private var debugOverlayPrefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    companion object {
        var isRunning = false
            private set
        private const val OVERLAY_UPDATE_INTERVAL_MS = 1000L // Update overlay every second
        private const val OVERLAY_MARGIN_DP = 12
    }

    override fun onCreate() {
        super.onCreate()
        storage = App.instance.storage
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        debugOverlayPrefListener = storage.registerDebugOverlayEnabledListener { enabled ->
            if (!enabled) {
                removeDebugOverlay()
                updateOverlay(null)
                return@registerDebugOverlayEnabledListener
            }
            val trackedPackage = currentTrackedPackage ?: return@registerDebugOverlayEnabledListener
            val blockSet = storage.getBlockSetForApp(trackedPackage)
            updateDebugOverlay(trackedPackage, blockSet != null, currentTrackedPackage)
            updateOverlay(currentBlockSet)
        }
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
        debugOverlayPrefListener?.let { storage.unregisterDebugOverlayEnabledListener(it) }
        debugOverlayPrefListener = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Update or remove debug overlay based on current setting.
        val blockSet = storage.getBlockSetForApp(packageName)
        val isBlocked = blockSet != null
        if (storage.isDebugOverlayEnabled()) {
            updateDebugOverlay(packageName, isBlocked, currentTrackedPackage)
        } else {
            removeDebugOverlay()
        }

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

        // blockSet already looked up above for debug overlay
        if (blockSet != null) {
            cancelPendingStop()
            // Check if quota is exceeded
            if (storage.isQuotaExceeded(blockSet)) {
                launchBlockedScreen(blockSet.name, blockSet.id)
                stopTracking()
                return
            }

            // Start or continue tracking for overlay updates
            if (currentTrackedPackage != packageName) {
                stopTracking()
                startTracking(packageName, blockSet)
                updateOverlay(blockSet)
            }
        } else {
            // App not in any block set
            // Only stop tracking if this looks like a real app switch (not a system overlay/dialog)
            // System overlays, keyboards, etc. often have short package names or system prefixes
            val isLikelyOverlay = packageName.startsWith("com.android.") ||
                packageName.startsWith("com.google.android.inputmethod") ||
                packageName.startsWith("com.samsung.") ||
                packageName.contains("keyboard") ||
                packageName.contains("overlay")
            
            if (!isLikelyOverlay) {
                // This is a real app that's not blocked - stop tracking
                scheduleStopTracking()
            }
            // If it's likely an overlay, just ignore it and keep tracking
        }
    }

    private fun startTracking(packageName: String, blockSet: BlockSet) {
        cancelPendingStop()
        currentTrackedPackage = packageName
        currentBlockSet = blockSet

        // Update overlay immediately
        updateOverlay(blockSet)

        // Then update overlay periodically - system tracks actual usage time
        overlayUpdateRunnable = object : Runnable {
            override fun run() {
                currentBlockSet?.let { bs ->
                    // Re-fetch blockSet to get current state
                    val updatedBlockSet = storage.getBlockSets().find { it.id == bs.id }
                    if (updatedBlockSet != null) {
                        // Check if quota exceeded
                        if (storage.isQuotaExceeded(updatedBlockSet)) {
                            launchBlockedScreen(updatedBlockSet.name, updatedBlockSet.id)
                            stopTracking()
                            return
                        }
                        updateOverlay(updatedBlockSet)
                    }
                }
                handler.postDelayed(this, OVERLAY_UPDATE_INTERVAL_MS)
            }
        }
        handler.postDelayed(overlayUpdateRunnable!!, OVERLAY_UPDATE_INTERVAL_MS)
    }

    private fun stopTracking() {
        cancelPendingStop()
        overlayUpdateRunnable?.let { handler.removeCallbacks(it) }
        overlayUpdateRunnable = null
        currentTrackedPackage = null
        currentBlockSet = null
        updateOverlay(null)
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

        if (blockSet == null) {
            if (!storage.isDebugOverlayEnabled()) {
                overlayView?.let { windowManager?.removeView(it) }
                overlayView = null
                return
            }
            val view = ensureOverlayView()
            view.text = "No blocked app"
            view.setTextColor(ContextCompat.getColor(this, R.color.white))
            return
        }

        val remainingSeconds = storage.getRemainingSeconds(blockSet)
        val view = ensureOverlayView()
        view.text = formatRemainingTime(remainingSeconds)
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
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        removeDebugOverlay()
    }

    private fun removeDebugOverlay() {
        debugOverlayView?.let { windowManager?.removeView(it) }
        debugOverlayView = null
    }

    private fun updateDebugOverlay(packageName: String, isBlocked: Boolean, tracking: String?) {
        if (!Settings.canDrawOverlays(this)) return

        val shortName = packageName.substringAfterLast(".")
        val status = if (isBlocked) "BLOCKED" else "not blocked"
        val trackingInfo = tracking?.substringAfterLast(".") ?: "none"
        val text = "$shortName ($status)\ntracking: $trackingInfo"

        val view = ensureDebugOverlayView()
        view.text = text
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureDebugOverlayView(): TextView {
        if (debugOverlayView != null) return debugOverlayView!!

        val view = TextView(this)
        view.setTextColor(Color.YELLOW)
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        view.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
        view.background = GradientDrawable().apply {
            cornerRadius = dpToPx(8).toFloat()
            setColor(Color.argb(200, 50, 50, 50))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dpToPx(OVERLAY_MARGIN_DP)
        params.y = dpToPx(OVERLAY_MARGIN_DP + 40)

        // Make the overlay draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(view, params)
        debugOverlayView = view
        return view
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
