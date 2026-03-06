package com.appblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

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
    private var interventionAuthorizedPackage: String? = null
    private lateinit var overlayController: AppBlockerOverlayController
    private lateinit var screenStateTracker: AppBlockerScreenStateTracker
    private lateinit var eventFilter: AppBlockerEventFilter
    private lateinit var packageResolver: AppBlockerPackageResolver
    private lateinit var sessionTracker: LocalSessionTracker
    private var debugOverlayPrefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var lastBlockedEventTimeMs: Long = 0
    private var lastBlockedScreenKey: String? = null
    private var lastBlockedScreenLaunchMs: Long = 0
    private lateinit var snapchatDetector: SnapchatDetector
    private lateinit var instagramDetector: InstagramDetector
    private lateinit var youtubeDetector: YouTubeDetector
    private lateinit var browserIncognitoDetector: BrowserIncognitoDetector
    private var lastForegroundPackage: String? = null

    // Local session tracking to enable immediate blocking when timer runs out.
    private var sessionStartTimeMs: Long = 0
    private var initialRemainingSeconds: Int = 0
    private var currentWindowEndMs: Long = 0

    companion object {
        var isRunning = false
            private set
        private const val OVERLAY_UPDATE_INTERVAL_MS = 1000L
        private const val PENDING_STOP_DELAY_MS = 1000L
        private const val RECENT_BLOCKED_EVENT_THRESHOLD_MS = 1500L
        private const val OVERLAY_MARGIN_DP = 12
        private const val SNAPCHAT_DETECTION_INTERVAL_MS = 400L
        private const val SNAPCHAT_HEADER_MAX_Y_DP = 260
        private const val INSTAGRAM_DETECTION_INTERVAL_MS = 400L
        private const val YOUTUBE_DETECTION_INTERVAL_MS = 400L
        private const val BROWSER_DETECTION_INTERVAL_MS = 400L
        private const val LOG_TAG = "AppBlockerOverlay"
        private const val BLOCKED_SCREEN_DEDUP_WINDOW_MS = 1_500L
    }

    override fun onCreate() {
        super.onCreate()
        storage = App.instance.storage
        eventFilter = AppBlockerEventFilter()
        sessionTracker = LocalSessionTracker(storage)

        snapchatDetector = SnapchatDetector(
            detectionIntervalMs = SNAPCHAT_DETECTION_INTERVAL_MS,
            headerMaxYProvider = { dpToPx(SNAPCHAT_HEADER_MAX_Y_DP) }
        )
        instagramDetector = InstagramDetector(INSTAGRAM_DETECTION_INTERVAL_MS)
        youtubeDetector = YouTubeDetector(YOUTUBE_DETECTION_INTERVAL_MS)
        browserIncognitoDetector = BrowserIncognitoDetector(BROWSER_DETECTION_INTERVAL_MS)

        packageResolver = AppBlockerPackageResolver(
            storage = storage,
            rootProvider = { rootInActiveWindow },
            snapchatDetector = snapchatDetector,
            instagramDetector = instagramDetector,
            youtubeDetector = youtubeDetector,
            browserIncognitoDetector = browserIncognitoDetector
        )

        screenStateTracker = AppBlockerScreenStateTracker(
            context = this,
            onScreenOff = { stopTracking() },
            onScreenOn = {}
        )
        screenStateTracker.register()

        overlayController = AppBlockerOverlayController(
            context = this,
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager,
            storage = storage,
            isScreenOff = { screenStateTracker.isScreenOff },
            currentTrackedPackage = { currentTrackedPackage },
            dpToPx = { dpToPx(it) },
            formatRemainingTime = { formatRemainingTime(it) },
            logDebug = { tag, message -> logDebug(tag, message) },
            overlayMarginDp = OVERLAY_MARGIN_DP
        )

        debugOverlayPrefListener = storage.registerDebugOverlayEnabledListener { enabled ->
            if (!enabled) {
                overlayController.removeDebugOverlay()
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
            if (isLikelyOverlayPackage(packageName)) {
                return
            }
            lastForegroundPackage = packageName
        }

        logDebug("event", buildEventLogMessage(packageName, className))

        val blockSet = storage.getBlockSetForApp(effectivePackageName)
        val isBlocked = blockSet != null
        logDebug("event", "isBlocked=$isBlocked debug=${storage.isDebugOverlayEnabled()}")
        handleDebugOverlay(effectivePackageName, isBlocked)

        if (packageName == "com.android.systemui") {
            return
        }

        if (packageName == "com.android.launcher" || packageName.contains("launcher")) {
            interventionAuthorizedPackage = null
            stopTracking()
            return
        }

        if (blockSet != null && blockSet.isScheduleActive()) {
            handleBlockedApp(effectivePackageName, blockSet, nowMs)
            return
        }

        if (packageName == this.packageName) {
            handleAppBlockerPackageEvent(className, nowMs)
            return
        }

        handleUnblockedAppSwitch(effectivePackageName)
    }

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

        if (!isInterventionAuthorized(packageName, blockSet)) {
            logDebug("blocked", "intervention required for ${blockSet.name}")
            launchBlockedScreen(
                blockSetName = blockSet.name,
                blockSetId = blockSet.id,
                blockedPackageName = packageName,
                mode = BlockedActivity.MODE_INTERVENTION,
                interventionMode = blockSet.intervention,
                interventionCodeLength = blockSet.interventionCodeLength
            )
            stopTracking()
            return
        }

        if (currentTrackedPackage != packageName) {
            logDebug("track", "switch to blocked $packageName")
            stopTracking()
            startTracking(packageName, blockSet)
        }
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
        val trackedPackage = currentTrackedPackage
        if (isSameAppFamily(trackedPackage, packageName)) {
            logDebug(
                "track",
                "ignore switch within same app family: $packageName (tracked=$trackedPackage)"
            )
            cancelPendingStop()
            return
        }

        val isLikelyOverlay = isLikelyOverlayPackage(packageName)
        if (!isLikelyOverlay) {
            logDebug("track", "schedule stop due to $packageName (likelyOverlay=$isLikelyOverlay)")
            scheduleStopTracking()
        }
    }

    private fun startTracking(packageName: String, blockSet: BlockSet) {
        cancelPendingStop()
        currentTrackedPackage = packageName
        currentBlockSet = blockSet
        logDebug("track", "start $packageName blockSet=${blockSet.name}")

        applySessionState(
            sessionTracker.start(
                packageName = packageName,
                blockSet = blockSet,
                nowMs = System.currentTimeMillis()
            )
        )

        updateOverlayWithLocalTracking(blockSet)
        overlayController.applyStoredOverlayPosition(packageName)

        overlayUpdateRunnable = object : Runnable {
            override fun run() {
                currentBlockSet?.let { bs ->
                    val updatedBlockSet = storage.getBlockSets().find { it.id == bs.id }
                    if (updatedBlockSet != null) {
                        val nowMs = System.currentTimeMillis()
                        val trackedPackage = currentTrackedPackage ?: return

                        applySessionState(
                            sessionTracker.updateForWindowBoundary(
                                packageName = trackedPackage,
                                blockSet = updatedBlockSet,
                                nowMs = nowMs,
                                state = currentSessionState()
                            )
                        )

                        val localRemainingSeconds =
                            sessionTracker.localRemainingSeconds(currentSessionState(), nowMs)
                        val overrideActive = storage.isOverrideActive(updatedBlockSet, nowMs)

                        if (!overrideActive && localRemainingSeconds <= 0) {
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
        return sessionTracker.localRemainingSeconds(
            state = currentSessionState(),
            nowMs = System.currentTimeMillis()
        )
    }

    private fun stopTracking() {
        cancelPendingStop()
        overlayUpdateRunnable?.let { handler.removeCallbacks(it) }
        overlayUpdateRunnable = null

        sessionTracker.stop(currentTrackedPackage)

        currentTrackedPackage = null
        currentBlockSet = null
        applySessionState(LocalSessionTracker.State.EMPTY)
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

    private fun launchBlockedScreen(
        blockSetName: String,
        blockSetId: String? = null,
        blockedPackageName: String? = null,
        mode: Int = BlockedActivity.MODE_QUOTA,
        interventionMode: Int = BlockSet.INTERVENTION_NONE,
        interventionCodeLength: Int = 32
    ) {
        val launchKey = listOf(
            blockSetId ?: "",
            blockedPackageName ?: "",
            mode.toString(),
            interventionMode.toString()
        ).joinToString("|")

        val nowMs = System.currentTimeMillis()
        if (lastBlockedScreenKey == launchKey &&
            (nowMs - lastBlockedScreenLaunchMs) < BLOCKED_SCREEN_DEDUP_WINDOW_MS
        ) {
            logDebug("blocked", "skip duplicate blocked screen launch for $launchKey")
            return
        }
        lastBlockedScreenKey = launchKey
        lastBlockedScreenLaunchMs = nowMs

        val intent = Intent(this, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockedActivity.EXTRA_BLOCK_SET_NAME, blockSetName)
            blockSetId?.let { putExtra(BlockedActivity.EXTRA_BLOCK_SET_ID, it) }
            blockedPackageName?.let { putExtra(BlockedActivity.EXTRA_RETURN_PACKAGE, it) }
            putExtra(BlockedActivity.EXTRA_MODE, mode)
            putExtra(BlockedActivity.EXTRA_INTERVENTION_MODE, interventionMode)
            putExtra(BlockedActivity.EXTRA_INTERVENTION_CODE_LENGTH, interventionCodeLength)
        }
        startActivity(intent)
    }

    private fun isInterventionAuthorized(packageName: String, blockSet: BlockSet): Boolean {
        if (blockSet.intervention == BlockSet.INTERVENTION_NONE) {
            return true
        }
        if (interventionAuthorizedPackage == packageName) {
            return true
        }
        if (storage.consumeInterventionBypass(packageName)) {
            interventionAuthorizedPackage = packageName
            return true
        }
        return false
    }

    private fun updateOverlay(blockSet: BlockSet?) {
        overlayController.updateOverlay(blockSet)
    }

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

    private fun formatRemainingTime(remainingSeconds: Int): String {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        return getString(R.string.time_left, minutes, seconds)
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun logDebug(tag: String, message: String) {
        if (!BuildConfig.DEBUG_TOOLS_ENABLED) return
        if (!storage.isDebugOverlayEnabled() && !storage.isDebugLogCaptureEnabled()) return
        Log.d(LOG_TAG, "[$tag] $message")
        DebugLogStore.append(applicationContext, tag, message)
    }

    private fun handleDebugOverlay(packageName: String, isBlocked: Boolean) {
        if (!BuildConfig.DEBUG_TOOLS_ENABLED) return
        if (storage.isDebugOverlayEnabled()) {
            overlayController.updateDebugOverlay(packageName, isBlocked, currentTrackedPackage)
        } else {
            overlayController.removeDebugOverlay()
        }
    }

    private fun buildEventLogMessage(packageName: String, className: String?): String {
        val safeClassName = className ?: "null"
        return "pkg=$packageName class=$safeClassName tracked=$currentTrackedPackage " +
            "pendingStop=${pendingStopRunnable != null}"
    }

    private fun shouldHandleAccessibilityEvent(eventType: Int): Boolean {
        return eventFilter.shouldHandleAccessibilityEvent(eventType)
    }

    private fun resolveEffectivePackageName(
        packageName: String,
        className: String?,
        eventType: Int,
        nowMs: Long
    ): String {
        return packageResolver.resolveEffectivePackageName(packageName, className, eventType, nowMs)
    }

    private fun resolveInAppBrowserPackage(packageName: String, className: String?): String? {
        return packageResolver.resolveInAppBrowserPackage(packageName, className)
    }

    private fun isLikelyOverlayPackage(packageName: String): Boolean {
        return eventFilter.isLikelyOverlayPackage(packageName)
    }

    private fun isSameAppFamily(first: String?, second: String): Boolean {
        return eventFilter.isSameAppFamily(first, second)
    }

    private fun currentSessionState(): LocalSessionTracker.State {
        return LocalSessionTracker.State(
            sessionStartTimeMs = sessionStartTimeMs,
            initialRemainingSeconds = initialRemainingSeconds,
            currentWindowEndMs = currentWindowEndMs
        )
    }

    private fun applySessionState(state: LocalSessionTracker.State) {
        sessionStartTimeMs = state.sessionStartTimeMs
        initialRemainingSeconds = state.initialRemainingSeconds
        currentWindowEndMs = state.currentWindowEndMs
    }

    override fun onInterrupt() {
        stopTracking()
    }
}
