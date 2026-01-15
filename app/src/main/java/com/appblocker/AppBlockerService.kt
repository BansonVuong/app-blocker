package com.appblocker

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
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
import android.util.Log

class AppBlockerService : AccessibilityService() {

    private lateinit var storage: Storage
    private val handler = Handler(Looper.getMainLooper())
    private var currentTrackedPackage: String? = null
    private var currentBlockSet: BlockSet? = null
    private var overlayUpdateRunnable: Runnable? = null
    private var pendingStopRunnable: Runnable? = null
    private var overlayView: TextView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var debugOverlayView: TextView? = null
    private var windowManager: WindowManager? = null
    private var debugOverlayPrefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var lastBlockedEventTimeMs: Long = 0
    private var screenStateReceiver: BroadcastReceiver? = null
    private var isScreenOff: Boolean = false

    // Local session tracking to enable immediate blocking when timer runs out
    private var sessionStartTimeMs: Long = 0
    private var initialRemainingSeconds: Int = 0

    companion object {
        var isRunning = false
            private set
        private const val OVERLAY_UPDATE_INTERVAL_MS = 1000L // Update overlay every second
        private const val OVERLAY_MARGIN_DP = 12
        private const val LOG_TAG = "AppBlockerOverlay"
    }

    override fun onCreate() {
        super.onCreate()
        storage = App.instance.storage
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOff = true
                        stopTracking()
                        removeOverlay()
                        removeDebugOverlay()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOff = false
                    }
                }
            }
        }
        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        )
        debugOverlayPrefListener = storage.registerDebugOverlayEnabledListener { enabled ->
            if (!enabled) {
                removeDebugOverlay()
                // Update timer overlay based on current tracking state
                // If tracking a blocked app, show timer; otherwise remove overlay
                updateOverlayWithLocalTracking(currentBlockSet)
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
        screenStateReceiver?.let { unregisterReceiver(it) }
        screenStateReceiver = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString()
        logDebug("event", buildEventLogMessage(packageName, className))

        // Update or remove debug overlay based on current setting.
        val blockSet = storage.getBlockSetForApp(packageName)
        val isBlocked = blockSet != null
        logDebug("event", "isBlocked=$isBlocked debug=${storage.isDebugOverlayEnabled()}")
        handleDebugOverlay(packageName, isBlocked)

        // Ignore system UI
        if (packageName == "com.android.systemui") {
            // Ignore transient system UI overlays (e.g., notification shade)
            return
        }

        if (packageName == "com.android.launcher" ||
            packageName.contains("launcher")) {
            stopTracking()
            return
        }

        // blockSet already looked up above for debug overlay
        if (blockSet != null) {
            if (isInAppBrowserClass(className)) {
                logDebug("track", "in-app browser detected for $packageName class=$className")
                stopTracking()
                return
            }
            lastBlockedEventTimeMs = System.currentTimeMillis()
            cancelPendingStop()
            // Check if quota is exceeded
            if (storage.isQuotaExceeded(blockSet)) {
                logDebug("blocked", "quota exceeded for ${blockSet.name}")
                launchBlockedScreen(blockSet.name, blockSet.id)
                stopTracking()
                return
            }

            // Start or continue tracking for overlay updates
            if (currentTrackedPackage != packageName) {
                logDebug("track", "switch to blocked $packageName")
                stopTracking()
                startTracking(packageName, blockSet)
            }
            // Always update overlay when in a blocked app (like debug overlay does)
            // This ensures overlay stays visible even if system events occur
            updateOverlayWithLocalTracking(blockSet)
        } else if (packageName == this.packageName) {
            val now = System.currentTimeMillis()
            val recentlyInBlockedApp = currentTrackedPackage != null &&
                (now - lastBlockedEventTimeMs) < 1500
            val isAppBlockerActivity = className?.startsWith(this.packageName) == true
            if (currentTrackedPackage != null && !isAppBlockerActivity) {
                logDebug("track", "ignore appblocker non-activity event during blocked app")
                return
            }
            if (recentlyInBlockedApp && !isAppBlockerActivity) {
                logDebug("track", "ignore appblocker event during blocked app")
                return
            }
            if (currentTrackedPackage != null) {
                logDebug("track", "schedule stop due to appblocker event while tracking")
                scheduleStopTracking()
            } else {
                logDebug("track", "stop due to appblocker event")
                stopTracking()
            }
        } else {
            // App not in any block set
            // Only stop tracking if this looks like a real app switch (not a system overlay/dialog)
            // System overlays, keyboards, etc. often have short package names or system prefixes
            val isLikelyOverlay = isLikelyOverlayPackage(packageName)
            
            if (!isLikelyOverlay) {
                // This is a real app that's not blocked - stop tracking
                logDebug("track", "schedule stop due to $packageName (likelyOverlay=$isLikelyOverlay)")
                scheduleStopTracking()
            }
            // If it's likely an overlay, just ignore it and keep tracking
        }
    }

    private fun startTracking(packageName: String, blockSet: BlockSet) {
        cancelPendingStop()
        currentTrackedPackage = packageName
        currentBlockSet = blockSet
        logDebug("track", "start $packageName blockSet=${blockSet.name}")

        // Capture initial state for local time tracking
        // This allows immediate blocking when timer runs out, without waiting for Android's delayed usage stats
        sessionStartTimeMs = System.currentTimeMillis()
        initialRemainingSeconds = storage.getRemainingSeconds(blockSet)

        // Update overlay immediately
        updateOverlayWithLocalTracking(blockSet)
        applyStoredOverlayPosition(packageName)

        // Then update overlay periodically with local tracking for immediate response
        overlayUpdateRunnable = object : Runnable {
            override fun run() {
                currentBlockSet?.let { bs ->
                    // Re-fetch blockSet to get current state
                    val updatedBlockSet = storage.getBlockSets().find { it.id == bs.id }
                    if (updatedBlockSet != null) {
                        // Calculate remaining time using local tracking for immediate response
                        val localRemainingSeconds = getLocalRemainingSeconds()

                        // Block immediately when local timer hits zero
                        if (localRemainingSeconds <= 0) {
                            launchBlockedScreen(updatedBlockSet.name, updatedBlockSet.id)
                            stopTracking()
                            return
                        }
                        updateOverlayWithLocalTracking(updatedBlockSet)
                    }
                }
                handler.postDelayed(this, OVERLAY_UPDATE_INTERVAL_MS)
            }
        }
        handler.postDelayed(overlayUpdateRunnable!!, OVERLAY_UPDATE_INTERVAL_MS)
    }

    private fun getLocalRemainingSeconds(): Int {
        val elapsedSeconds = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt()
        return maxOf(0, initialRemainingSeconds - elapsedSeconds)
    }

    private fun stopTracking() {
        cancelPendingStop()
        overlayUpdateRunnable?.let { handler.removeCallbacks(it) }
        overlayUpdateRunnable = null
        currentTrackedPackage = null
        currentBlockSet = null
        sessionStartTimeMs = 0
        initialRemainingSeconds = 0
        logDebug("track", "stop")
        updateOverlay(null)
    }

    private fun scheduleStopTracking() {
        if (currentTrackedPackage == null) {
            stopTracking()
            return
        }
        if (pendingStopRunnable != null) return
        logDebug("track", "schedule stop in 1s")
        pendingStopRunnable = Runnable {
            logDebug("track", "stop runnable fired")
            stopTracking()
        }
        handler.postDelayed(pendingStopRunnable!!, 1000L)
    }

    private fun cancelPendingStop() {
        if (pendingStopRunnable != null) {
            logDebug("track", "cancel pending stop")
        }
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
        if (isScreenOff) {
            removeOverlay()
            return
        }
        logDebug("overlay", "update blockSet=${blockSet?.name} view=${overlayView != null}")

        if (blockSet == null) {
            if (!storage.isDebugOverlayEnabled()) {
                logDebug("overlay", "remove (no blockSet)")
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

    private fun updateOverlayWithLocalTracking(blockSet: BlockSet?) {
        if (!Settings.canDrawOverlays(this)) {
            return
        }
        if (isScreenOff) {
            removeOverlay()
            return
        }

        if (blockSet == null) {
            logDebug("overlay", "local update with null blockSet")
            updateOverlay(null)
            return
        }

        // Use local tracking for more accurate real-time countdown
        val remainingSeconds = getLocalRemainingSeconds()
        val view = ensureOverlayView()
        logDebug("overlay", "local update blockSet=${blockSet.name} remaining=$remainingSeconds")
        view.text = formatRemainingTime(remainingSeconds)
        view.setTextColor(ContextCompat.getColor(this, R.color.white))
    }

    @SuppressLint("ClickableViewAccessibility")
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        val storedPosition = currentTrackedPackage?.let { storage.getOverlayPosition(it) }
        params.x = storedPosition?.first ?: dpToPx(OVERLAY_MARGIN_DP)
        params.y = storedPosition?.second ?: dpToPx(OVERLAY_MARGIN_DP)

        // Make the timer overlay draggable
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
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persistOverlayPosition(params.x, params.y)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(view, params)
        overlayView = view
        overlayLayoutParams = params
        return view
    }

    private fun removeOverlay() {
        logDebug("overlay", "remove")
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        overlayLayoutParams = null
        removeDebugOverlay()
    }

    private fun removeDebugOverlay() {
        logDebug("debug", "remove")
        debugOverlayView?.let { windowManager?.removeView(it) }
        debugOverlayView = null
    }

    private fun updateDebugOverlay(packageName: String, isBlocked: Boolean, tracking: String?) {
        if (!Settings.canDrawOverlays(this)) return
        if (isScreenOff) return

        val shortName = packageName.substringAfterLast(".")
        val status = if (isBlocked) "BLOCKED" else "not blocked"
        val trackingInfo = tracking?.substringAfterLast(".") ?: "none"
        val text = "$shortName ($status)\ntracking: $trackingInfo"

        val view = ensureDebugOverlayView()
        logDebug("debug", "update $text")
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
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

    private fun persistOverlayPosition(x: Int, y: Int) {
        val packageName = currentTrackedPackage ?: return
        storage.setOverlayPosition(packageName, x, y)
    }

    private fun applyStoredOverlayPosition(packageName: String) {
        val position = storage.getOverlayPosition(packageName) ?: return
        val params = overlayLayoutParams ?: return
        params.gravity = Gravity.TOP or Gravity.START
        params.x = position.first
        params.y = position.second
        overlayView?.let { windowManager?.updateViewLayout(it, params) }
    }

    private fun logDebug(tag: String, message: String) {
        if (!BuildConfig.DEBUG_TOOLS_ENABLED) return
        if (!storage.isDebugOverlayEnabled() && !storage.isDebugLogCaptureEnabled()) return
        Log.d(LOG_TAG, "[$tag] $message")
        DebugLogStore.append(applicationContext, tag, message)
    }

    // ===== Debug-only helpers (easy to remove) =====

    private fun handleDebugOverlay(packageName: String, isBlocked: Boolean) {
        if (!BuildConfig.DEBUG_TOOLS_ENABLED) return
        if (storage.isDebugOverlayEnabled()) {
            updateDebugOverlay(packageName, isBlocked, currentTrackedPackage)
        } else {
            removeDebugOverlay()
        }
    }

    private fun buildEventLogMessage(packageName: String, className: String?): String {
        val safeClassName = className ?: "null"
        return "pkg=$packageName class=$safeClassName tracked=$currentTrackedPackage " +
            "pendingStop=${pendingStopRunnable != null}"
    }

    private fun isLikelyOverlayPackage(packageName: String): Boolean {
        val realAppOverrides = setOf(
            "com.android.chrome",
            "com.google.android.apps.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.sec.android.app.sbrowser"
        )
        if (packageName in realAppOverrides) return false

        return packageName == "com.android.systemui" ||
            packageName == "com.android.permissioncontroller" ||
            packageName == "com.google.android.permissioncontroller" ||
            packageName.startsWith("com.google.android.inputmethod") ||
            packageName.contains("keyboard") ||
            packageName.contains("overlay")
    }

    private fun isInAppBrowserClass(className: String?): Boolean {
        if (className == null) return false
        val normalized = className.lowercase()
        val indicators = listOf(
            "customtab",
            "customtabs",
            "chrome",
            "browser",
            "webview",
            "webactivity",
            "inappbrowser"
        )
        return indicators.any { normalized.contains(it) }
    }

    override fun onInterrupt() {
        stopTracking()
    }
}
