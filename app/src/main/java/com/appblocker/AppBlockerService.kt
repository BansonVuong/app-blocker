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

/**
 * Accessibility service that monitors foreground app changes and enforces block sets.
 * Renders overlays for timers/debugging and tracks usage windows in near real time.
 */
class AppBlockerService : AccessibilityService() {

    private lateinit var storage: Storage
    private val handler = Handler(Looper.getMainLooper())
    private var currentTrackedPackage: String? = null
    private var currentBlockSet: BlockSet? = null
    private var overlayUpdateRunnable: Runnable? = null
    private var pendingStopRunnable: Runnable? = null
    private lateinit var overlayController: OverlayController
    private lateinit var screenStateTracker: ScreenStateTracker
    private var debugOverlayPrefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var lastBlockedEventTimeMs: Long = 0
    private val snapchatDetector = SnapchatDetector()
    private val instagramDetector = InstagramDetector()
    private val youtubeDetector = YouTubeDetector()
    private var lastForegroundPackage: String? = null

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
        private const val INSTAGRAM_DETECTION_INTERVAL_MS = 400L
        private const val YOUTUBE_DETECTION_INTERVAL_MS = 400L
        private const val LOG_TAG = "AppBlockerOverlay"
    }

    override fun onCreate() {
        super.onCreate()
        storage = App.instance.storage
        overlayController = OverlayController(getSystemService(WINDOW_SERVICE) as WindowManager)
        screenStateTracker = ScreenStateTracker(
            onScreenOff = {
                stopTracking()
            },
            onScreenOn = {}
        )
        screenStateTracker.register()
        debugOverlayPrefListener = storage.registerDebugOverlayEnabledListener { enabled ->
            if (!enabled) {
                overlayController.removeDebugOverlay()
                // Update timer overlay based on current tracking state
                // If tracking a blocked app, show timer; otherwise remove overlay
                updateOverlayWithLocalTracking(currentBlockSet)
                return@registerDebugOverlayEnabledListener
            }
            val trackedPackage = currentTrackedPackage ?: return@registerDebugOverlayEnabledListener
            val blockSet = storage.getBlockSetForApp(trackedPackage)
            overlayController.updateDebugOverlay(trackedPackage, blockSet != null, currentTrackedPackage)
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
        overlayController.removeOverlay()
        debugOverlayPrefListener?.let { storage.unregisterDebugOverlayEnabledListener(it) }
        debugOverlayPrefListener = null
        screenStateTracker.unregister()
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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !isLikelyOverlayPackage(packageName)
        ) {
            lastForegroundPackage = packageName
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            lastForegroundPackage != null &&
            packageName != lastForegroundPackage
        ) {
            return
        }
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
            handleBlockedApp(effectivePackageName, blockSet, nowMs)
            return
        }

        if (packageName == this.packageName) {
            handleAppBlockerPackageEvent(className, nowMs)
            return
        }

        handleUnblockedAppSwitch(effectivePackageName)
    }

    /**
     * Handle a foreground app that is in a block set; updates tracking and launches the blocked
     * screen if the quota is exceeded.
     */
    private fun handleBlockedApp(packageName: String, blockSet: BlockSet, nowMs: Long) {
        lastBlockedEventTimeMs = nowMs
        cancelPendingStop()
        if (storage.isLockdownActive(nowMs)) {
            logDebug("blocked", "lockdown active for ${blockSet.name}")
            launchBlockedScreen(blockSet.name, blockSet.id, packageName)
            stopTracking()
            return
        }
        val overrideActive = storage.isOverrideActive(blockSet, nowMs)
        if (storage.isQuotaExceeded(blockSet) && !overrideActive) {
            logDebug("blocked", "quota exceeded for ${blockSet.name}")
            launchBlockedScreen(blockSet.name, blockSet.id, packageName)
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

    /**
     * Handle events originating from this app's own package to avoid false stop/start cycles.
     */
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

    /**
     * Handle switches to apps that are not blocked, with overlay/system UI filtering.
     */
    private fun handleUnblockedAppSwitch(packageName: String) {
        // Only stop tracking if this looks like a real app switch (not a system overlay/dialog)
        // System overlays, keyboards, etc. often have short package names or system prefixes
        val isLikelyOverlay = isLikelyOverlayPackage(packageName)

        if (!isLikelyOverlay) {
            // This is a real app that's not blocked - stop tracking immediately
            logDebug("track", "schedule stop due to $packageName (likelyOverlay=$isLikelyOverlay)")
            scheduleStopTracking()
        }
        // If it's likely an overlay, just ignore it and keep tracking
    }

    /**
     * Begin tracking a blocked app and start the local countdown/overlay updates.
     */
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
        overlayController.applyStoredOverlayPosition(packageName)

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
                        val overrideActive = storage.isOverrideActive(updatedBlockSet, nowMs)

                        // Block immediately when local timer hits zero
                        if (!overrideActive && localRemainingSeconds <= 0) {
                            val trackedPackage = currentTrackedPackage ?: ""
                            launchBlockedScreen(updatedBlockSet.name, updatedBlockSet.id, trackedPackage)
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

    /**
     * Stop tracking the current app and clear local session state.
     */
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

    /**
     * Schedule a short delayed stop to avoid flicker from transient overlays.
     */
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

    /**
     * Cancel any pending delayed stop.
     */
    private fun cancelPendingStop() {
        if (pendingStopRunnable != null) {
            logDebug("track", "cancel pending stop")
        }
        pendingStopRunnable?.let { handler.removeCallbacks(it) }
        pendingStopRunnable = null
    }

    /**
     * Launch the blocking activity showing quota exceeded UI.
     */
    private fun launchBlockedScreen(
        blockSetName: String,
        blockSetId: String? = null,
        blockedPackageName: String? = null
    ) {
        val intent = Intent(this, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockedActivity.EXTRA_BLOCK_SET_NAME, blockSetName)
            blockSetId?.let { putExtra(BlockedActivity.EXTRA_BLOCK_SET_ID, it) }
            blockedPackageName?.let { putExtra(BlockedActivity.EXTRA_RETURN_PACKAGE, it) }
        }
        startActivity(intent)
    }

    /**
     * Update the main overlay using persisted usage stats.
     */
    private fun updateOverlay(blockSet: BlockSet?) {
        overlayController.updateOverlay(blockSet)
    }

    /**
     * Update the overlay using local countdown tracking for immediate response.
     */
    private fun updateOverlayWithLocalTracking(blockSet: BlockSet?) {
        if (blockSet == null) {
            logDebug("overlay", "local update with null blockSet")
            updateOverlay(null)
            return
        }
        val localRemainingSeconds = getLocalRemainingSeconds()
        val overrideSeconds = storage.getOverrideRemainingSeconds(blockSet)
        val displaySeconds = if (overrideSeconds > 0) overrideSeconds else localRemainingSeconds
        overlayController.updateOverlayWithLocalTracking(blockSet, displaySeconds)
    }

    /**
     * Format remaining seconds as a localized MM:SS string.
     */
    private fun formatRemainingTime(remainingSeconds: Int): String {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        return getString(R.string.time_left, minutes, seconds)
    }

    /**
     * Convert dp to pixels based on current display density.
     */
    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    /**
     * Log to Logcat and optional on-device debug log file when enabled.
     */
    private fun logDebug(tag: String, message: String) {
        if (!BuildConfig.DEBUG_TOOLS_ENABLED) return
        if (!storage.isDebugOverlayEnabled() && !storage.isDebugLogCaptureEnabled()) return
        Log.d(LOG_TAG, "[$tag] $message")
        DebugLogStore.append(applicationContext, tag, message)
    }

    // ===== Debug-only helpers (easy to remove) =====

    /**
     * Show or hide the debug overlay based on debug prefs.
     */
    private fun handleDebugOverlay(packageName: String, isBlocked: Boolean) {
        if (!BuildConfig.DEBUG_TOOLS_ENABLED) return
        if (storage.isDebugOverlayEnabled()) {
            overlayController.updateDebugOverlay(packageName, isBlocked, currentTrackedPackage)
        } else {
            overlayController.removeDebugOverlay()
        }
    }

    /**
     * Build a compact message for debug logging.
     */
    private fun buildEventLogMessage(packageName: String, className: String?): String {
        val safeClassName = className ?: "null"
        return "pkg=$packageName class=$safeClassName tracked=$currentTrackedPackage " +
            "pendingStop=${pendingStopRunnable != null}"
    }

    /**
     * Filter for event types we care about.
     */
    private fun shouldHandleAccessibilityEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
    }

    /**
     * Resolve the effective package name to match block rules (e.g., Snapchat tabs).
     */
    private fun resolveEffectivePackageName(
        packageName: String,
        className: String?,
        eventType: Int,
        nowMs: Long
    ): String {
        val inAppBrowserPackage = resolveInAppBrowserPackage(packageName, className)
        if (inAppBrowserPackage != null) return inAppBrowserPackage
        if (packageName == AppTargets.SNAPCHAT_PACKAGE) {
            if (storage.getBlockSetForApp(AppTargets.SNAPCHAT_PACKAGE) != null) {
                return AppTargets.SNAPCHAT_PACKAGE
            }
            val hasStoriesBlock = storage.getBlockSetForApp(AppTargets.SNAPCHAT_STORIES) != null
            val hasSpotlightBlock = storage.getBlockSetForApp(AppTargets.SNAPCHAT_SPOTLIGHT) != null
            if (!hasStoriesBlock && !hasSpotlightBlock) {
                return AppTargets.SNAPCHAT_PACKAGE
            }

            val tab = snapchatDetector.detect(eventType, nowMs, rootInActiveWindow)
            return when (tab) {
                SnapchatTab.STORIES ->
                    if (hasStoriesBlock) AppTargets.SNAPCHAT_STORIES else AppTargets.SNAPCHAT_PACKAGE
                SnapchatTab.SPOTLIGHT ->
                    if (hasSpotlightBlock) AppTargets.SNAPCHAT_SPOTLIGHT else AppTargets.SNAPCHAT_PACKAGE
                else -> AppTargets.SNAPCHAT_PACKAGE
            }
        }
        if (packageName == AppTargets.INSTAGRAM_PACKAGE) {
            return resolveInstagramPackage(packageName, eventType, nowMs)
        }
        if (packageName == AppTargets.YOUTUBE_PACKAGE) {
            return resolveYouTubePackage(packageName, eventType, nowMs)
        }
        return packageName
    }

    /**
     * Resolve Instagram Reels tab to a virtual package if a block set exists.
     */
    private fun resolveInstagramPackage(
        packageName: String,
        eventType: Int,
        nowMs: Long
    ): String {
        if (packageName != AppTargets.INSTAGRAM_PACKAGE) return packageName
        if (storage.getBlockSetForApp(AppTargets.INSTAGRAM_PACKAGE) != null) {
            return AppTargets.INSTAGRAM_PACKAGE
        }
        val hasReelsBlock = storage.getBlockSetForApp(AppTargets.INSTAGRAM_REELS) != null
        if (!hasReelsBlock) {
            return AppTargets.INSTAGRAM_PACKAGE
        }
        val tab = instagramDetector.detect(eventType, nowMs, rootInActiveWindow)
        return if (tab == InstagramTab.REELS) {
            AppTargets.INSTAGRAM_REELS
        } else {
            AppTargets.INSTAGRAM_PACKAGE
        }
    }

    /**
     * Resolve YouTube Shorts tab to a virtual package if a block set exists.
     */
    private fun resolveYouTubePackage(
        packageName: String,
        eventType: Int,
        nowMs: Long
    ): String {
        if (packageName != AppTargets.YOUTUBE_PACKAGE) return packageName
        if (storage.getBlockSetForApp(AppTargets.YOUTUBE_PACKAGE) != null) {
            return AppTargets.YOUTUBE_PACKAGE
        }
        val hasShortsBlock = storage.getBlockSetForApp(AppTargets.YOUTUBE_SHORTS) != null
        if (!hasShortsBlock) {
            return AppTargets.YOUTUBE_PACKAGE
        }
        val tab = youtubeDetector.detect(eventType, nowMs, rootInActiveWindow)
        return if (tab == YouTubeTab.SHORTS) {
            AppTargets.YOUTUBE_SHORTS
        } else {
            AppTargets.YOUTUBE_PACKAGE
        }
    }

    /**
     * Detect in-app browser contexts and map them to a real browser package.
     */
    private fun resolveInAppBrowserPackage(packageName: String, className: String?): String? {
        if (packageName in inAppBrowserIgnorePackages) return null
        if (isBrowserPackage(packageName)) return packageName
        if (!isInAppBrowserClass(className)) return null
        return getBrowserPackageForInAppBrowser(className ?: "")
    }

    private inner class SnapchatDetector {
        private var lastDetectionMs: Long = 0
        private var lastTab: SnapchatTab = SnapchatTab.UNKNOWN

        fun detect(
            eventType: Int,
            nowMs: Long,
            root: android.view.accessibility.AccessibilityNodeInfo?
        ): SnapchatTab {
            val shouldUpdate = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            if (!shouldUpdate) return lastTab
            if (nowMs - lastDetectionMs < SNAPCHAT_DETECTION_INTERVAL_MS) {
                return lastTab
            }

            lastDetectionMs = nowMs
            val rootNode = root ?: return lastTab
            val headerMaxY = dpToPx(SNAPCHAT_HEADER_MAX_Y_DP)
            var foundStoriesHeader = false
            var foundChatHeader = false
            var selectedStories = false
            var selectedChat = false
            var foundChatUi = false
            var foundStoriesContent = false
            var foundMemories = false

            val queue: ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> = ArrayDeque()
            queue.add(rootNode)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val viewId = node.viewIdResourceName ?: ""
                if (viewId == "com.snapchat.android:id/spotlight_container") {
                    lastTab = SnapchatTab.SPOTLIGHT
                    return lastTab
                }

                if (!foundChatUi && isChatUiIndicator(viewId)) {
                    foundChatUi = true
                }
                if (!foundStoriesContent && isStoriesContentIndicator(viewId, node)) {
                    foundStoriesContent = true
                }
                if (!foundMemories && isMemoriesIndicator(viewId, node)) {
                    foundMemories = true
                }

                val text = node.text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    if (text.equals("Spotlight", ignoreCase = false) && node.isSelected) {
                        lastTab = SnapchatTab.SPOTLIGHT
                        return lastTab
                    }
                    if (text.equals("Following", ignoreCase = false) && node.isSelected) {
                        lastTab = SnapchatTab.SPOTLIGHT
                        return lastTab
                    }
                    if (text == "Stories") {
                        if (node.isSelected) {
                            selectedStories = true
                        } else {
                            val bounds = android.graphics.Rect()
                            node.getBoundsInScreen(bounds)
                            if (bounds.top <= headerMaxY) {
                                foundStoriesHeader = true
                            }
                        }
                    }
                    if (text == "Chat") {
                        if (node.isSelected) {
                            selectedChat = true
                        } else {
                            val bounds = android.graphics.Rect()
                            node.getBoundsInScreen(bounds)
                            if (bounds.top <= headerMaxY) {
                                foundChatHeader = true
                            }
                        }
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }

            lastTab = when {
                foundMemories -> SnapchatTab.UNKNOWN
                foundChatUi -> SnapchatTab.CHAT
                selectedStories -> SnapchatTab.STORIES
                selectedChat -> SnapchatTab.CHAT
                foundStoriesHeader && !foundChatHeader && foundStoriesContent -> SnapchatTab.STORIES
                foundChatHeader && !foundStoriesHeader -> SnapchatTab.CHAT
                else -> SnapchatTab.UNKNOWN
            }
            return lastTab
        }

        private fun isChatUiIndicator(viewId: String): Boolean {
            if (viewId.isEmpty()) return false
            return viewId.contains("ff_item") ||
                viewId.contains("list-picker-pill") ||
                viewId.contains("feed_chat_button") ||
                viewId.contains("feed_pinned_convo_button")
        }

        private fun isStoriesContentIndicator(
            viewId: String,
            node: android.view.accessibility.AccessibilityNodeInfo
        ): Boolean {
            if (viewId.contains("friend_card_frame")) return true
            val text = node.text?.toString()?.trim() ?: return false
            return text == "friend_story_circle_thumbnail"
        }

        private fun isMemoriesIndicator(
            viewId: String,
            node: android.view.accessibility.AccessibilityNodeInfo
        ): Boolean {
            if (viewId.contains("memories_grid")) return true
            val text = node.text?.toString()?.trim() ?: return false
            return text == "Memories"
        }
    }

    private inner class InstagramDetector {
        private var lastDetectionMs: Long = 0
        private var lastTab: InstagramTab = InstagramTab.UNKNOWN

        fun detect(
            eventType: Int,
            nowMs: Long,
            root: android.view.accessibility.AccessibilityNodeInfo?
        ): InstagramTab {
            val shouldUpdate = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            if (!shouldUpdate) return lastTab
            if (nowMs - lastDetectionMs < INSTAGRAM_DETECTION_INTERVAL_MS) {
                return lastTab
            }

            lastDetectionMs = nowMs
            val rootNode = root ?: return lastTab
            var selectedReels = false
            var foundClipsTab = false

            val queue: ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> = ArrayDeque()
            queue.add(rootNode)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val viewId = node.viewIdResourceName.orEmpty()
                val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()
                val text = node.text?.toString()?.trim().orEmpty()

                if (viewId == "com.instagram.android:id/clips_tab") {
                    foundClipsTab = true
                    selectedReels = node.isSelected || node.isChecked
                    if (selectedReels) break
                }

                if (!foundClipsTab &&
                    (node.isSelected || node.isChecked) &&
                    (contentDesc.equals("Reels", ignoreCase = true) ||
                        text.equals("Reels", ignoreCase = true))
                ) {
                    selectedReels = true
                    break
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }

            lastTab = if (selectedReels) InstagramTab.REELS else InstagramTab.UNKNOWN
            return lastTab
        }
    }

    private inner class YouTubeDetector {
        private var lastDetectionMs: Long = 0
        private var lastTab: YouTubeTab = YouTubeTab.UNKNOWN

        fun detect(
            eventType: Int,
            nowMs: Long,
            root: android.view.accessibility.AccessibilityNodeInfo?
        ): YouTubeTab {
            val shouldUpdate = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            if (!shouldUpdate) return lastTab
            if (nowMs - lastDetectionMs < YOUTUBE_DETECTION_INTERVAL_MS) {
                return lastTab
            }

            lastDetectionMs = nowMs
            val rootNode = root ?: return lastTab
            var selectedShorts = false

            val queue: ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> = ArrayDeque()
            queue.add(rootNode)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val viewId = node.viewIdResourceName.orEmpty()
                val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()
                val text = node.text?.toString()?.trim().orEmpty()

                val isShortsLabel = contentDesc.equals("Shorts", ignoreCase = true) ||
                    text.equals("Shorts", ignoreCase = true)
                val isShortsViewId = viewId.contains("shorts", ignoreCase = true)

                if ((isShortsLabel || isShortsViewId) && (node.isSelected || node.isChecked)) {
                    selectedShorts = true
                    break
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }

            lastTab = if (selectedShorts) YouTubeTab.SHORTS else YouTubeTab.UNKNOWN
            return lastTab
        }
    }

    private enum class SnapchatTab {
        STORIES,
        SPOTLIGHT,
        CHAT,
        UNKNOWN
    }

    private enum class InstagramTab {
        REELS,
        UNKNOWN
    }

    private enum class YouTubeTab {
        SHORTS,
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
    private val inAppBrowserIgnorePackages = setOf(
        "com.google.chromeremotedesktop"
    )

    /**
     * Heuristic for transient system overlays that should not trigger stop tracking.
     */
    private fun isLikelyOverlayPackage(packageName: String): Boolean {
        if (packageName in browserPackages) return false

        return packageName == "com.android.systemui" ||
            packageName == "com.android.permissioncontroller" ||
            packageName == "com.google.android.permissioncontroller" ||
            packageName.startsWith("com.google.android.inputmethod") ||
            packageName.contains("keyboard") ||
            packageName.contains("overlay")
    }

    /**
     * Identify well-known browser packages.
     */
    private fun isBrowserPackage(packageName: String): Boolean {
        return packageName in browserPackages
    }

    /**
     * Returns the browser package associated with an in-app browser class name.
     * Chrome Custom Tabs are most common, so we default to Chrome.
     */
    /**
     * Map a class name to a likely browser package, used for custom tabs/webviews.
     */
    private fun getBrowserPackageForInAppBrowser(className: String): String? {
        val normalized = className.lowercase()
        return when {
            normalized.contains("chrome") -> "com.android.chrome"
            normalized.contains("firefox") -> "org.mozilla.firefox"
            normalized.contains("brave") -> "com.brave.browser"
            normalized.contains("edge") || normalized.contains("emmx") -> "com.microsoft.emmx"
            normalized.contains("opera") -> "com.opera.browser"
            normalized.contains("samsung") || normalized.contains("sbrowser") -> "com.sec.android.app.sbrowser"
            else -> null
        }
    }

    /**
     * Identify class names that commonly represent in-app browser surfaces.
     */
    private fun isInAppBrowserClass(className: String?): Boolean {
        if (className == null) return false
        val normalized = className.lowercase()
        val indicators = listOf(
            "customtab",
            "customtabs",
            "browser",
            "webview",
            "webactivity",
            "inappbrowser"
        )
        return indicators.any { normalized.contains(it) }
    }

    /**
     * Tracks screen on/off state to pause overlays and tracking when screen is off.
     */
    private inner class ScreenStateTracker(
        private val onScreenOff: () -> Unit,
        private val onScreenOn: () -> Unit
    ) {
        var isScreenOff: Boolean = false
            private set

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
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
            registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
            )
        }

        fun unregister() {
            unregisterReceiver(receiver)
        }
    }

    /**
     * Manages overlay and debug overlay views along with position persistence.
     */
    private inner class OverlayController(
        private val windowManager: WindowManager
    ) {
        private var overlayView: TextView? = null
        private var overlayLayoutParams: WindowManager.LayoutParams? = null
        private var debugOverlayView: TextView? = null

        fun updateOverlay(blockSet: BlockSet?) {
            if (!Settings.canDrawOverlays(this@AppBlockerService)) {
                removeOverlay()
                return
            }
            if (screenStateTracker.isScreenOff) {
                removeOverlay()
                return
            }
            logDebug("overlay", "update blockSet=${blockSet?.name} view=${overlayView != null}")

            if (blockSet == null) {
                logDebug("overlay", "remove (no blockSet)")
                removeViewSafely(overlayView)
                overlayView = null
                overlayLayoutParams = null
                return
            }

            val overrideSeconds = storage.getOverrideRemainingSeconds(blockSet)
            val remainingSeconds = if (overrideSeconds > 0) {
                overrideSeconds
            } else {
                storage.getRemainingSeconds(blockSet)
            }
            val view = ensureOverlayView()
            view.text = formatRemainingTime(remainingSeconds)
            view.setTextColor(ContextCompat.getColor(this@AppBlockerService, R.color.white))
        }

        fun updateOverlayWithLocalTracking(blockSet: BlockSet, remainingSeconds: Int) {
            if (!Settings.canDrawOverlays(this@AppBlockerService)) {
                removeOverlay()
                return
            }
            if (screenStateTracker.isScreenOff) {
                removeOverlay()
                return
            }

            val view = ensureOverlayView()
            logDebug("overlay", "local update blockSet=${blockSet.name} remaining=$remainingSeconds")
            view.text = formatRemainingTime(remainingSeconds)
            view.setTextColor(ContextCompat.getColor(this@AppBlockerService, R.color.white))
        }

        fun updateDebugOverlay(packageName: String, isBlocked: Boolean, tracking: String?) {
            if (!Settings.canDrawOverlays(this@AppBlockerService)) {
                removeDebugOverlay()
                return
            }
            if (screenStateTracker.isScreenOff) {
                removeDebugOverlay()
                return
            }

            val shortName = packageName.substringAfterLast(".")
            val status = if (isBlocked) "BLOCKED" else "not blocked"
            val trackingInfo = tracking?.substringAfterLast(".") ?: "none"
            val text = "$shortName ($status)\ntracking: $trackingInfo"

            val view = ensureDebugOverlayView()
            logDebug("debug", "update $text")
            view.text = text
        }

        fun removeOverlay() {
            logDebug("overlay", "remove")
            removeViewSafely(overlayView)
            overlayView = null
            overlayLayoutParams = null
            removeDebugOverlay()
        }

        fun removeDebugOverlay() {
            logDebug("debug", "remove")
            removeViewSafely(debugOverlayView)
            debugOverlayView = null
        }

        fun applyStoredOverlayPosition(packageName: String) {
            val position = storage.getOverlayPosition(packageName) ?: return
            val params = overlayLayoutParams ?: return
            params.gravity = Gravity.TOP or Gravity.START
            params.x = position.first
            params.y = position.second
            overlayView?.let { windowManager.updateViewLayout(it, params) }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun ensureOverlayView(): TextView {
            if (overlayView != null) return overlayView!!

            val view = TextView(this@AppBlockerService)
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
                        windowManager.updateViewLayout(view, params)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        persistOverlayPosition(params.x, params.y)
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(view, params)
            overlayView = view
            overlayLayoutParams = params
            return view
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun ensureDebugOverlayView(): TextView {
            if (debugOverlayView != null) return debugOverlayView!!

            val view = TextView(this@AppBlockerService)
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
                        windowManager.updateViewLayout(view, params)
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(view, params)
            debugOverlayView = view
            return view
        }

        private fun persistOverlayPosition(x: Int, y: Int) {
            val packageName = currentTrackedPackage ?: return
            storage.setOverlayPosition(packageName, x, y)
        }

        private fun removeViewSafely(view: TextView?) {
            if (view == null) return
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (_: IllegalArgumentException) {
                // View already removed or not attached.
            }
        }
    }

    override fun onInterrupt() {
        stopTracking()
    }
}
