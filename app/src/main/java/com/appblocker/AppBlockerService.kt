package com.appblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson

/**
 * Accessibility service that monitors foreground app changes and enforces block sets.
 * Renders overlays for timers and tracks usage windows in near real time.
 */
class AppBlockerService : AccessibilityService() {

    private lateinit var storage: Storage
    private val handler = Handler(Looper.getMainLooper())
    private var currentTrackedPackage: String? = null
    private var currentBlockSet: BlockSet? = null
    private var overlayUpdateRunnable: Runnable? = null
    private var pendingStopRunnable: Runnable? = null
    private var interventionAuthorizedPackage: String? = null
    private var interventionAuthorizedSignature: String? = null
    private lateinit var overlayController: AppBlockerOverlayController
    private lateinit var screenStateTracker: AppBlockerScreenStateTracker
    private lateinit var eventFilter: AppBlockerEventFilter
    private lateinit var packageResolver: AppBlockerPackageResolver
    private lateinit var sessionTracker: LocalSessionTracker
    private var lastBlockedEventTimeMs: Long = 0
    private var lastBlockedScreenKey: String? = null
    private var lastBlockedScreenLaunchMs: Long = 0
    private var lastForegroundPackage: String? = null
    private val gson = Gson()

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
        private const val LOG_TAG = "AppBlockerOverlay"
        private const val BLOCKED_SCREEN_DEDUP_WINDOW_MS = 1_500L
    }

    override fun onCreate() {
        super.onCreate()
        storage = App.instance.storage
        eventFilter = AppBlockerEventFilter()
        sessionTracker = LocalSessionTracker(storage)

        packageResolver = AppBlockerPackageResolver(
            storage = storage,
            rootProvider = { rootInActiveWindow }
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
            overlayMarginDp = OVERLAY_MARGIN_DP
        )
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

        val policy = storage.resolveEffectiveAppPolicy(effectivePackageName, nowMs)

        if (packageName == "com.android.systemui") {
            return
        }

        if (packageName == "com.android.launcher" || packageName.contains("launcher")) {
            interventionAuthorizedPackage = null
            interventionAuthorizedSignature = null
            stopTracking()
            return
        }

        if (policy != null) {
            handleBlockedApp(effectivePackageName, policy, nowMs)
            return
        }

        if (packageName == this.packageName) {
            handleAppBlockerPackageEvent(className, nowMs)
            return
        }

        handleUnblockedAppSwitch(effectivePackageName)
    }

    private fun handleBlockedApp(packageName: String, policy: EffectiveAppPolicy, nowMs: Long) {
        val blockSet = policy.primaryBlockSet
        lastBlockedEventTimeMs = nowMs
        cancelPendingStop()

        if (storage.isLockdownActive(nowMs)) {
            launchBlockedScreen(blockSet.name, blockSet.id, packageName)
            stopTracking()
            return
        }

        if (policy.quotaBlocked) {
            val quotaBlockingBlockSet = policy.quotaBlockingBlockSet ?: blockSet
            launchBlockedScreen(quotaBlockingBlockSet.name, quotaBlockingBlockSet.id, packageName)
            stopTracking()
            return
        }

        if (!isInterventionAuthorized(packageName, policy)) {
            launchBlockedScreen(
                blockSetName = blockSet.name,
                blockSetId = blockSet.id,
                blockedPackageName = packageName,
                mode = BlockedActivity.MODE_INTERVENTION,
                interventionMode = blockSet.intervention,
                interventionCodeLength = blockSet.interventionCodeLength,
                interventionStepsJson = gson.toJson(policy.interventionChallenges)
            )
            stopTracking()
            return
        }

        if (currentTrackedPackage != packageName) {
            stopTracking()
            startTracking(packageName, blockSet, policy.effectiveDisplayRemainingSeconds)
        }
        updateOverlayWithLocalTracking(blockSet, policy.effectiveDisplayRemainingSeconds)
    }

    private fun handleAppBlockerPackageEvent(className: String?, nowMs: Long) {
        val recentlyInBlockedApp = currentTrackedPackage != null &&
            (nowMs - lastBlockedEventTimeMs) < RECENT_BLOCKED_EVENT_THRESHOLD_MS
        val isAppBlockerActivity = className?.startsWith(this.packageName) == true

        if (currentTrackedPackage != null && !isAppBlockerActivity) {
            return
        }
        if (recentlyInBlockedApp && !isAppBlockerActivity) {
            return
        }
        if (currentTrackedPackage != null) {
            scheduleStopTracking()
        } else {
            stopTracking()
        }
    }

    private fun handleUnblockedAppSwitch(packageName: String) {
        val trackedPackage = currentTrackedPackage
        if (isSameAppFamily(trackedPackage, packageName)) {
            cancelPendingStop()
            return
        }

        val isLikelyOverlay = isLikelyOverlayPackage(packageName)
        if (!isLikelyOverlay) {
            scheduleStopTracking()
        }
    }

    private fun startTracking(packageName: String, blockSet: BlockSet, initialDisplayRemainingSeconds: Int) {
        cancelPendingStop()
        currentTrackedPackage = packageName
        currentBlockSet = blockSet

        applySessionState(
            sessionTracker.start(
                packageName = packageName,
                blockSet = blockSet,
                nowMs = System.currentTimeMillis()
            )
        )

        updateOverlayWithLocalTracking(blockSet, initialDisplayRemainingSeconds)
        overlayController.applyStoredOverlayPosition(packageName)

        overlayUpdateRunnable = object : Runnable {
            override fun run() {
                currentBlockSet?.let {
                    val nowMs = System.currentTimeMillis()
                    val trackedPackage = currentTrackedPackage ?: return
                    val policy = storage.resolveEffectiveAppPolicy(trackedPackage, nowMs)
                    val updatedBlockSet = policy?.primaryBlockSet
                    if (updatedBlockSet != null) {

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
                        val effectiveDisplayRemainingSeconds = storage.getEffectiveDisplayRemainingSeconds(
                            activeBlockSets = policy.activeBlockSets,
                            quotaRemainingByBlockSetId = mapOf(updatedBlockSet.id to localRemainingSeconds),
                            nowMs = nowMs
                        ) ?: localRemainingSeconds
                        if (policy.quotaBlocked || effectiveDisplayRemainingSeconds <= 0) {
                            val quotaBlockingBlockSet = policy.quotaBlockingBlockSet ?: updatedBlockSet
                            launchBlockedScreen(
                                quotaBlockingBlockSet.name,
                                quotaBlockingBlockSet.id,
                                trackedPackage
                            )
                            stopTracking()
                            return
                        }
                        if (!isInterventionAuthorized(trackedPackage, policy)) {
                            launchBlockedScreen(
                                blockSetName = updatedBlockSet.name,
                                blockSetId = updatedBlockSet.id,
                                blockedPackageName = trackedPackage,
                                mode = BlockedActivity.MODE_INTERVENTION,
                                interventionMode = updatedBlockSet.intervention,
                                interventionCodeLength = updatedBlockSet.interventionCodeLength,
                                interventionStepsJson = gson.toJson(policy.interventionChallenges)
                            )
                            stopTracking()
                            return
                        }
                        updateOverlayWithLocalTracking(updatedBlockSet, effectiveDisplayRemainingSeconds)
                    } else {
                        stopTracking()
                        return
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
        handler.postDelayed(pendingStopRunnable!!, PENDING_STOP_DELAY_MS)
    }

    private fun cancelPendingStop() {
        pendingStopRunnable?.let { handler.removeCallbacks(it) }
        pendingStopRunnable = null
    }

    private fun launchBlockedScreen(
        blockSetName: String,
        blockSetId: String? = null,
        blockedPackageName: String? = null,
        mode: Int = BlockedActivity.MODE_QUOTA,
        interventionMode: Int = BlockSet.INTERVENTION_NONE,
        interventionCodeLength: Int = 32,
        interventionStepsJson: String? = null
    ) {
        val launchKey = listOf(
            blockSetId ?: "",
            blockedPackageName ?: "",
            mode.toString(),
            interventionMode.toString(),
            interventionStepsJson ?: ""
        ).joinToString("|")

        val nowMs = System.currentTimeMillis()
        if (lastBlockedScreenKey == launchKey &&
            (nowMs - lastBlockedScreenLaunchMs) < BLOCKED_SCREEN_DEDUP_WINDOW_MS
        ) {
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
            interventionStepsJson?.let { putExtra(BlockedActivity.EXTRA_INTERVENTION_STEPS_JSON, it) }
        }
        startActivity(intent)
    }

    private fun isInterventionAuthorized(packageName: String, policy: EffectiveAppPolicy): Boolean {
        if (policy.interventionChallenges.isEmpty()) {
            return true
        }
        if (interventionAuthorizedPackage == packageName &&
            interventionAuthorizedSignature == policy.interventionSignature
        ) {
            return true
        }
        if (storage.consumeInterventionBypass(packageName)) {
            interventionAuthorizedPackage = packageName
            interventionAuthorizedSignature = policy.interventionSignature
            return true
        }
        return false
    }

    private fun updateOverlay(blockSet: BlockSet?) {
        overlayController.updateOverlay(blockSet)
    }

    private fun updateOverlayWithLocalTracking(
        blockSet: BlockSet?,
        effectiveDisplayRemainingSeconds: Int? = null
    ) {
        if (blockSet == null) {
            updateOverlay(null)
            return
        }

        val displaySeconds = effectiveDisplayRemainingSeconds ?: run {
            val localRemainingSeconds = getLocalRemainingSeconds()
            storage.getDisplayRemainingSeconds(
                blockSet = blockSet,
                quotaRemainingSeconds = localRemainingSeconds
            )
        }
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
