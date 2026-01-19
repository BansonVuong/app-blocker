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
    private var lastSnapchatDetectionMs: Long = 0
    private var lastSnapchatTab: SnapchatTab = SnapchatTab.UNKNOWN

    // Local session tracking to enable immediate blocking when timer runs out
    private var sessionStartTimeMs: Long = 0
    private var initialRemainingSeconds: Int = 0
    private var currentWindowEndMs: Long = 0

    companion object {
        var isRunning = false
            private set
        private const val OVERLAY_UPDATE_INTERVAL_MS = 1000L // Update overlay every second
        private const val PENDING_STOP_DELAY_MS = 1000L
        private const val RECENT_BLOCKED_EVENT_THRESHOLD_MS = 1500L
        private const val OVERLAY_MARGIN_DP = 12
        private const val SNAPCHAT_DETECTION_INTERVAL_MS = 400L
        private const val SNAPCHAT_HEADER_MAX_Y_DP = 260
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
        if (event == null) return
        if (!shouldHandleAccessibilityEvent(event.eventType)) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString()
        val nowMs = System.currentTimeMillis()

        val effectivePackageName = resolveEffectivePackageName(
            packageName = packageName,
            className = className,
            eventType = event.eventType,
            nowMs = nowMs
        )
        logDebug("event", buildEventLogMessage(packageName, className))

        // Update or remove debug overlay based on current setting.
        val blockSet = storage.getBlockSetForApp(effectivePackageName)
        val isBlocked = blockSet != null
        logDebug("event", "isBlocked=$isBlocked debug=${storage.isDebugOverlayEnabled()}")
        handleDebugOverlay(effectivePackageName, isBlocked)

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
            if (handleInAppBrowser(packageName, className, stopWhenBrowserNotBlocked = true, nowMs)) return
            handleBlockedApp(effectivePackageName, blockSet, nowMs)
            return
        }

        if (packageName == this.packageName) {
            handleAppBlockerPackageEvent(className, nowMs)
            return
        }

        if (handleInAppBrowser(packageName, className, stopWhenBrowserNotBlocked = false, nowMs)) return
        handleUnblockedAppSwitch(packageName)
    }

    private fun handleBlockedApp(packageName: String, blockSet: BlockSet, nowMs: Long) {
        lastBlockedEventTimeMs = nowMs
        cancelPendingStop()
        if (storage.isQuotaExceeded(blockSet)) {
            logDebug("blocked", "quota exceeded for ${blockSet.name}")
            launchBlockedScreen(blockSet.name, blockSet.id)
            stopTracking()
            return
        }

        if (currentTrackedPackage != packageName) {
            logDebug("track", "switch to blocked $packageName")
            stopTracking()
            startTracking(packageName, blockSet)
        }
        // Always update overlay when in a blocked app (like debug overlay does)
        // This ensures overlay stays visible even if system events occur
        updateOverlayWithLocalTracking(blockSet)
    }

    private fun handleAppBlockerPackageEvent(className: String?, nowMs: Long) {
        val recentlyInBlockedApp = currentTrackedPackage != null &&
            (nowMs - lastBlockedEventTimeMs) < RECENT_BLOCKED_EVENT_THRESHOLD_MS
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
    }

    private fun handleUnblockedAppSwitch(packageName: String) {
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

    private fun handleInAppBrowser(
        packageName: String,
        className: String?,
        stopWhenBrowserNotBlocked: Boolean,
        nowMs: Long
    ): Boolean {
        if (isBrowserPackage(packageName) || !isInAppBrowserClass(className)) return false

        // In-app browser detected from a non-browser app (e.g., Instagram opening Chrome Custom Tab)
        // Check if the associated browser is blocked - if so, track against it
        val browserPackage = getBrowserPackageForInAppBrowser(className ?: "")
        val browserBlockSet = browserPackage?.let { storage.getBlockSetForApp(it) }

        if (browserBlockSet == null) {
            if (stopWhenBrowserNotBlocked) {
                // Browser is not blocked, so don't show overlay for in-app browser
                logDebug("track", "in-app browser detected but browser not blocked, stopping")
                stopTracking()
                return true
            }
            return false
        }

        logDebug("track", "in-app browser from $packageName tracking against browser $browserPackage")
        lastBlockedEventTimeMs = nowMs
        cancelPendingStop()

        if (storage.isQuotaExceeded(browserBlockSet)) {
            logDebug("blocked", "quota exceeded for browser ${browserBlockSet.name}")
            launchBlockedScreen(browserBlockSet.name, browserBlockSet.id)
            stopTracking()
            return true
        }

        if (currentTrackedPackage != browserPackage) {
            logDebug("track", "switch to browser $browserPackage for in-app browser")
            stopTracking()
            startTracking(browserPackage, browserBlockSet)
        }
        updateOverlayWithLocalTracking(browserBlockSet)
        return true
    }

    private fun startTracking(packageName: String, blockSet: BlockSet) {
        cancelPendingStop()
        currentTrackedPackage = packageName
        currentBlockSet = blockSet
        logDebug("track", "start $packageName blockSet=${blockSet.name}")

        // Capture initial state for local time tracking
        // This allows immediate blocking when timer runs out, without waiting for Android's delayed usage stats
        sessionStartTimeMs = System.currentTimeMillis()
        if (AppTargets.isVirtualPackage(packageName)) {
            storage.startVirtualSession(packageName, sessionStartTimeMs)
        }
        initialRemainingSeconds = storage.getRemainingSeconds(blockSet)
        currentWindowEndMs = storage.getWindowEndMillis(blockSet, sessionStartTimeMs)

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
                        // Reset local tracking at window boundary so the timer refreshes.
                        val nowMs = System.currentTimeMillis()
                        if (AppTargets.isVirtualPackage(currentTrackedPackage ?: "")) {
                            storage.updateVirtualSessionHeartbeat(currentTrackedPackage!!, nowMs)
                        }
                        if (currentWindowEndMs > 0 && nowMs >= currentWindowEndMs) {
                            sessionStartTimeMs = nowMs
                            initialRemainingSeconds = storage.getRemainingSeconds(updatedBlockSet)
                            currentWindowEndMs = storage.getWindowEndMillis(updatedBlockSet, nowMs)
                        }

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
        currentTrackedPackage?.let { tracked ->
            if (AppTargets.isVirtualPackage(tracked)) {
                storage.endVirtualSession(tracked)
            }
        }
        currentTrackedPackage = null
        currentBlockSet = null
        sessionStartTimeMs = 0
        initialRemainingSeconds = 0
        currentWindowEndMs = 0
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
        handler.postDelayed(pendingStopRunnable!!, PENDING_STOP_DELAY_MS)
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
                removeViewSafely(overlayView)
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
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
        removeViewSafely(overlayView)
        overlayView = null
        overlayLayoutParams = null
        removeDebugOverlay()
    }

    private fun removeDebugOverlay() {
        logDebug("debug", "remove")
        removeViewSafely(debugOverlayView)
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
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
        return getString(R.string.time_left, minutes, seconds)
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

    private fun removeViewSafely(view: TextView?) {
        if (view == null) return
        try {
            if (view.isAttachedToWindow) {
                windowManager?.removeView(view)
            }
        } catch (_: IllegalArgumentException) {
            // View already removed or not attached.
        }
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

    private fun shouldHandleAccessibilityEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
    }

    private fun resolveEffectivePackageName(
        packageName: String,
        className: String?,
        eventType: Int,
        nowMs: Long
    ): String {
        if (packageName != AppTargets.SNAPCHAT_PACKAGE) return packageName
        if (storage.getBlockSetForApp(AppTargets.SNAPCHAT_PACKAGE) != null) {
            return AppTargets.SNAPCHAT_PACKAGE
        }
        val hasStoriesBlock = storage.getBlockSetForApp(AppTargets.SNAPCHAT_STORIES) != null
        val hasSpotlightBlock = storage.getBlockSetForApp(AppTargets.SNAPCHAT_SPOTLIGHT) != null
        if (!hasStoriesBlock && !hasSpotlightBlock) {
            return AppTargets.SNAPCHAT_PACKAGE
        }

        val tab = detectSnapchatTab(eventType, nowMs)
        return when (tab) {
            SnapchatTab.STORIES ->
                if (hasStoriesBlock) AppTargets.SNAPCHAT_STORIES else AppTargets.SNAPCHAT_PACKAGE
            SnapchatTab.SPOTLIGHT ->
                if (hasSpotlightBlock) AppTargets.SNAPCHAT_SPOTLIGHT else AppTargets.SNAPCHAT_PACKAGE
            else -> AppTargets.SNAPCHAT_PACKAGE
        }
    }

    private fun detectSnapchatTab(eventType: Int, nowMs: Long): SnapchatTab {
        val shouldUpdate = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        if (!shouldUpdate) return lastSnapchatTab
        if (nowMs - lastSnapchatDetectionMs < SNAPCHAT_DETECTION_INTERVAL_MS) {
            return lastSnapchatTab
        }

        lastSnapchatDetectionMs = nowMs
        val root = rootInActiveWindow ?: return lastSnapchatTab
        val headerMaxY = dpToPx(SNAPCHAT_HEADER_MAX_Y_DP)
        var foundStoriesHeader = false
        var foundChatHeader = false

        val queue: ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName ?: ""
            if (viewId == "com.snapchat.android:id/spotlight_container") {
                lastSnapchatTab = SnapchatTab.SPOTLIGHT
                return lastSnapchatTab
            }

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                if (text.equals("Spotlight", ignoreCase = false) && node.isSelected) {
                    lastSnapchatTab = SnapchatTab.SPOTLIGHT
                    return lastSnapchatTab
                }
                if (text.equals("Following", ignoreCase = false) && node.isSelected) {
                    lastSnapchatTab = SnapchatTab.SPOTLIGHT
                    return lastSnapchatTab
                }
                if (text == "Stories" || text == "Chat") {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.top <= headerMaxY) {
                        if (text == "Stories") {
                            foundStoriesHeader = true
                        } else if (text == "Chat") {
                            foundChatHeader = true
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        lastSnapchatTab = when {
            foundStoriesHeader -> SnapchatTab.STORIES
            foundChatHeader -> SnapchatTab.CHAT
            else -> SnapchatTab.UNKNOWN
        }
        return lastSnapchatTab
    }

    private enum class SnapchatTab {
        STORIES,
        SPOTLIGHT,
        CHAT,
        UNKNOWN
    }

    private val browserPackages = setOf(
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

    private fun isLikelyOverlayPackage(packageName: String): Boolean {
        if (packageName in browserPackages) return false

        return packageName == "com.android.systemui" ||
            packageName == "com.android.permissioncontroller" ||
            packageName == "com.google.android.permissioncontroller" ||
            packageName.startsWith("com.google.android.inputmethod") ||
            packageName.contains("keyboard") ||
            packageName.contains("overlay")
    }

    private fun isBrowserPackage(packageName: String): Boolean {
        return packageName in browserPackages
    }

    /**
     * Returns the browser package associated with an in-app browser class name.
     * Chrome Custom Tabs are most common, so we default to Chrome.
     */
    private fun getBrowserPackageForInAppBrowser(className: String): String? {
        val normalized = className.lowercase()
        return when {
            normalized.contains("firefox") -> "org.mozilla.firefox"
            normalized.contains("brave") -> "com.brave.browser"
            normalized.contains("edge") || normalized.contains("emmx") -> "com.microsoft.emmx"
            normalized.contains("opera") -> "com.opera.browser"
            normalized.contains("samsung") || normalized.contains("sbrowser") -> "com.sec.android.app.sbrowser"
            // Chrome Custom Tabs are the most common, default to Chrome for generic indicators
            normalized.contains("customtab") || normalized.contains("chrome") -> "com.android.chrome"
            // Generic webview/browser - assume Chrome as it's most common
            normalized.contains("webview") || normalized.contains("browser") || normalized.contains("webactivity") -> "com.android.chrome"
            else -> null
        }
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
