package com.appblocker

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.roundToInt

internal data class SimpleUsageEvent(
    val packageName: String,
    val eventType: Int,
    val timeStamp: Long
)

internal data class VirtualUsageSession(
    val startMs: Long,
    var endMs: Long? = null,
    var lastSeenMs: Long? = null
)

internal fun computeUsageSeconds(
    blockSet: BlockSet,
    events: List<SimpleUsageEvent>,
    now: Long,
    windowStartMs: Long
): Int {
    val foregroundStartTimes = mutableMapOf<String, Long>()
    var totalMs = 0L

    for (event in events) {
        val packageName = event.packageName
        if (!blockSet.apps.contains(packageName)) continue

        // MOVE_TO_FOREGROUND == ACTIVITY_RESUMED (1) and MOVE_TO_BACKGROUND == ACTIVITY_PAUSED (2)
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                foregroundStartTimes[packageName] = maxOf(event.timeStamp, windowStartMs)
            }
            UsageEvents.Event.ACTIVITY_PAUSED -> {
                val startTime = foregroundStartTimes[packageName]
                if (startTime != null) {
                    val endTime = maxOf(event.timeStamp, windowStartMs)
                    if (endTime > startTime) {
                        totalMs += endTime - startTime
                    }
                    foregroundStartTimes.remove(packageName)
                }
            }
        }
    }

    for ((_, startTime) in foregroundStartTimes) {
        totalMs += now - startTime
    }

    return (totalMs / 1000).toInt()
}

internal fun computeRemainingSeconds(quotaMinutes: Double, usedSeconds: Int): Int {
    val quotaSeconds = (quotaMinutes * 60).roundToInt()
    return maxOf(0, quotaSeconds - usedSeconds)
}

class Storage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_blocker", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    companion object {
        private const val KEY_BLOCK_SETS = "block_sets"
        private const val KEY_DEBUG_OVERLAY_ENABLED = "debug_overlay_enabled"
        private const val KEY_DEBUG_LOG_CAPTURE_ENABLED = "debug_log_capture_enabled"
        private const val KEY_OVERLAY_X_PREFIX = "overlay_x_"
        private const val KEY_OVERLAY_Y_PREFIX = "overlay_y_"
        private const val KEY_VIRTUAL_SESSIONS_PREFIX = "virtual_sessions_"
        private const val KEY_OVERRIDE_END_PREFIX = "override_end_"
        private const val KEY_LOCKDOWN_END = "lockdown_end"
        private const val KEY_LOCKDOWN_AUTH_MODE = "lockdown_auth_mode"
        private const val KEY_LOCKDOWN_PASSWORD = "lockdown_password"
        private const val KEY_OVERRIDE_AUTH_MODE = "override_auth_mode"
        private const val KEY_OVERRIDE_PASSWORD = "override_password"
        private const val KEY_SETTINGS_AUTH_MODE = "settings_auth_mode"
        private const val KEY_SETTINGS_PASSWORD = "settings_password"
        private const val KEY_INTERVENTION_BYPASS_PREFIX = "intervention_bypass_"
        private const val OVERLAY_POS_UNSET = Int.MIN_VALUE
        private const val VIRTUAL_SESSION_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        const val OVERRIDE_AUTH_NONE = 0
        const val OVERRIDE_AUTH_PASSWORD = 1
        const val OVERRIDE_AUTH_RANDOM_32 = 2
        const val OVERRIDE_AUTH_RANDOM_64 = 3
        const val OVERRIDE_AUTH_RANDOM_128 = 4

        const val LOCKDOWN_CANCEL_DISABLED = 0
        const val LOCKDOWN_CANCEL_PASSWORD = 1
        const val LOCKDOWN_CANCEL_RANDOM_32 = 2
        const val LOCKDOWN_CANCEL_RANDOM_64 = 3
        const val LOCKDOWN_CANCEL_RANDOM_128 = 4
    }

    // ===== Debug-only prefs (easy to remove) =====
    fun isDebugOverlayEnabled(): Boolean {
        return prefs.getBoolean(KEY_DEBUG_OVERLAY_ENABLED, false)
    }

    fun setDebugOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_OVERLAY_ENABLED, enabled).apply()
    }

    fun isDebugLogCaptureEnabled(): Boolean {
        return prefs.getBoolean(KEY_DEBUG_LOG_CAPTURE_ENABLED, false)
    }

    fun setDebugLogCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_LOG_CAPTURE_ENABLED, enabled).apply()
    }
    // ===== End debug-only prefs =====

    fun registerDebugOverlayEnabledListener(
        listener: (Boolean) -> Unit
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_DEBUG_OVERLAY_ENABLED) {
                listener(isDebugOverlayEnabled())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        return prefListener
    }

    fun unregisterDebugOverlayEnabledListener(
        prefListener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    fun saveBlockSets(blockSets: List<BlockSet>) {
        val json = gson.toJson(blockSets)
        prefs.edit().putString(KEY_BLOCK_SETS, json).apply()
    }

    fun getBlockSets(): MutableList<BlockSet> {
        val json = prefs.getString(KEY_BLOCK_SETS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<BlockSet>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun getBlockSetForApp(packageName: String): BlockSet? {
        return getBlockSets().find { it.apps.contains(packageName) }
    }

    fun getOverlayPosition(packageName: String): Pair<Int, Int>? {
        val x = prefs.getInt(KEY_OVERLAY_X_PREFIX + packageName, OVERLAY_POS_UNSET)
        val y = prefs.getInt(KEY_OVERLAY_Y_PREFIX + packageName, OVERLAY_POS_UNSET)
        if (x == OVERLAY_POS_UNSET || y == OVERLAY_POS_UNSET) return null
        return x to y
    }

    fun setOverlayPosition(packageName: String, x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_OVERLAY_X_PREFIX + packageName, x)
            .putInt(KEY_OVERLAY_Y_PREFIX + packageName, y)
            .apply()
    }

    fun startVirtualSession(packageName: String, nowMs: Long = System.currentTimeMillis()) {
        if (!AppTargets.isVirtualPackage(packageName)) return
        val sessions = loadVirtualSessions(packageName)
        val openSession = sessions.lastOrNull { it.endMs == null }
        if (openSession == null) {
            sessions.add(
                VirtualUsageSession(
                    startMs = nowMs,
                    endMs = null,
                    lastSeenMs = nowMs
                )
            )
        } else {
            openSession.lastSeenMs = nowMs
        }
        pruneAndSaveVirtualSessions(packageName, sessions, nowMs)
    }

    fun updateVirtualSessionHeartbeat(packageName: String, nowMs: Long = System.currentTimeMillis()) {
        if (!AppTargets.isVirtualPackage(packageName)) return
        val sessions = loadVirtualSessions(packageName)
        val openSession = sessions.lastOrNull { it.endMs == null }
        if (openSession != null) {
            openSession.lastSeenMs = nowMs
            pruneAndSaveVirtualSessions(packageName, sessions, nowMs)
        }
    }

    fun endVirtualSession(packageName: String, nowMs: Long = System.currentTimeMillis()) {
        if (!AppTargets.isVirtualPackage(packageName)) return
        val sessions = loadVirtualSessions(packageName)
        val openSession = sessions.lastOrNull { it.endMs == null }
        if (openSession != null) {
            openSession.endMs = nowMs
            openSession.lastSeenMs = nowMs
            pruneAndSaveVirtualSessions(packageName, sessions, nowMs)
        }
    }

    fun getVirtualUsageSecondsInWindow(
        packageName: String,
        windowStartMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        if (!AppTargets.isVirtualPackage(packageName)) return 0
        val sessions = loadVirtualSessions(packageName)
        var totalMs = 0L
        for (session in sessions) {
            val sessionEnd = session.endMs ?: session.lastSeenMs ?: nowMs
            val start = maxOf(session.startMs, windowStartMs)
            val end = minOf(sessionEnd, nowMs)
            if (end > start) {
                totalMs += end - start
            }
        }
        return (totalMs / 1000).toInt()
    }

    // Get usage time from system UsageStats events for apps in a block set
    fun getUsageSecondsInWindow(blockSet: BlockSet): Int {
        val now = System.currentTimeMillis()
        val windowMs = blockSet.windowMinutes * 60 * 1000L
        val windowStart = (now / windowMs) * windowMs // Align to window boundary

        val lookbackStart = maxOf(0L, windowStart - windowMs)
        val events = usageStatsManager.queryEvents(lookbackStart, now)
        val event = UsageEvents.Event()
        val simplifiedEvents = mutableListOf<SimpleUsageEvent>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            simplifiedEvents.add(
                SimpleUsageEvent(
                    packageName = event.packageName,
                    eventType = event.eventType,
                    timeStamp = event.timeStamp
                )
            )
        }
        val usedSeconds = computeUsageSeconds(blockSet, simplifiedEvents, now, windowStart)
        val virtualSeconds = blockSet.apps
            .filter { AppTargets.isVirtualPackage(it) }
            .sumOf { getVirtualUsageSecondsInWindow(it, windowStart, now) }
        return usedSeconds + virtualSeconds
    }

    fun getRemainingSeconds(blockSet: BlockSet): Int {
        val usedSeconds = getUsageSecondsInWindow(blockSet)
        return computeRemainingSeconds(blockSet.quotaMinutes, usedSeconds)
    }

    fun getWindowEndMillis(blockSet: BlockSet, nowMillis: Long = System.currentTimeMillis()): Long {
        val windowMs = blockSet.windowMinutes * 60 * 1000L
        val windowStart = (nowMillis / windowMs) * windowMs
        return windowStart + windowMs
    }

    fun isQuotaExceeded(blockSet: BlockSet): Boolean {
        return getRemainingSeconds(blockSet) <= 0
    }

    fun setOverrideMinutes(
        blockSetId: String,
        minutes: Int,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val durationMs = minutes * 60 * 1000L
        val endMs = nowMs + durationMs
        prefs.edit().putLong(KEY_OVERRIDE_END_PREFIX + blockSetId, endMs).apply()
    }

    fun setLockdownHours(
        hours: Int,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val durationMs = hours * 60L * 60L * 1000L
        val endMs = nowMs + durationMs
        prefs.edit().putLong(KEY_LOCKDOWN_END, endMs).apply()
    }

    fun getLockdownCancelAuthMode(): Int {
        return prefs.getInt(KEY_LOCKDOWN_AUTH_MODE, LOCKDOWN_CANCEL_DISABLED)
    }

    fun setLockdownCancelAuthMode(mode: Int) {
        prefs.edit().putInt(KEY_LOCKDOWN_AUTH_MODE, mode).apply()
    }

    fun getLockdownPassword(): String {
        return prefs.getString(KEY_LOCKDOWN_PASSWORD, "") ?: ""
    }

    fun setLockdownPassword(password: String) {
        prefs.edit().putString(KEY_LOCKDOWN_PASSWORD, password).apply()
    }

    fun clearLockdown() {
        prefs.edit().remove(KEY_LOCKDOWN_END).apply()
    }

    fun isLockdownActive(
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val endMs = prefs.getLong(KEY_LOCKDOWN_END, 0L)
        if (endMs <= nowMs) {
            if (endMs > 0L) {
                clearLockdown()
            }
            return false
        }
        return true
    }

    fun getLockdownRemainingSeconds(
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val endMs = prefs.getLong(KEY_LOCKDOWN_END, 0L)
        if (endMs <= nowMs) {
            if (endMs > 0L) {
                clearLockdown()
            }
            return 0
        }
        return ((endMs - nowMs) / 1000).toInt()
    }

    fun getLockdownEndMillis(
        nowMs: Long = System.currentTimeMillis()
    ): Long? {
        val endMs = prefs.getLong(KEY_LOCKDOWN_END, 0L)
        if (endMs <= nowMs) {
            if (endMs > 0L) {
                clearLockdown()
            }
            return null
        }
        return endMs
    }

    fun getOverrideAuthMode(): Int {
        return prefs.getInt(KEY_OVERRIDE_AUTH_MODE, OVERRIDE_AUTH_NONE)
    }

    fun setOverrideAuthMode(mode: Int) {
        prefs.edit().putInt(KEY_OVERRIDE_AUTH_MODE, mode).apply()
    }

    fun getOverridePassword(): String {
        return prefs.getString(KEY_OVERRIDE_PASSWORD, "") ?: ""
    }

    fun setOverridePassword(password: String) {
        prefs.edit().putString(KEY_OVERRIDE_PASSWORD, password).apply()
    }

    fun getSettingsAuthMode(): Int {
        return prefs.getInt(KEY_SETTINGS_AUTH_MODE, OVERRIDE_AUTH_NONE)
    }

    fun setSettingsAuthMode(mode: Int) {
        prefs.edit().putInt(KEY_SETTINGS_AUTH_MODE, mode).apply()
    }

    fun getSettingsPassword(): String {
        return prefs.getString(KEY_SETTINGS_PASSWORD, "") ?: ""
    }

    fun setSettingsPassword(password: String) {
        prefs.edit().putString(KEY_SETTINGS_PASSWORD, password).apply()
    }

    fun grantInterventionBypass(packageName: String) {
        prefs.edit().putBoolean(KEY_INTERVENTION_BYPASS_PREFIX + packageName, true).apply()
    }

    fun consumeInterventionBypass(packageName: String): Boolean {
        val key = KEY_INTERVENTION_BYPASS_PREFIX + packageName
        val allowed = prefs.getBoolean(key, false)
        if (allowed) {
            prefs.edit().remove(key).apply()
        }
        return allowed
    }

    fun clearOverride(blockSetId: String) {
        prefs.edit().remove(KEY_OVERRIDE_END_PREFIX + blockSetId).apply()
    }

    fun isOverrideActive(
        blockSet: BlockSet,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!blockSet.allowOverride) return false
        val endMs = prefs.getLong(KEY_OVERRIDE_END_PREFIX + blockSet.id, 0L)
        if (endMs <= nowMs) {
            if (endMs > 0L) {
                clearOverride(blockSet.id)
            }
            return false
        }
        return true
    }

    fun getOverrideRemainingSeconds(
        blockSet: BlockSet,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        if (!blockSet.allowOverride) return 0
        val endMs = prefs.getLong(KEY_OVERRIDE_END_PREFIX + blockSet.id, 0L)
        if (endMs <= nowMs) {
            if (endMs > 0L) {
                clearOverride(blockSet.id)
            }
            return 0
        }
        return ((endMs - nowMs) / 1000).toInt()
    }

    fun getUsageSecondsLastWeek(packageNames: Set<String>): Map<String, Int> {
        if (packageNames.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val windowStart = now - 7L * 24 * 60 * 60 * 1000
        val events = usageStatsManager.queryEvents(windowStart, now)
        val event = UsageEvents.Event()
        val foregroundStartTimes = mutableMapOf<String, Long>()
        val usageMs = mutableMapOf<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName
            if (!packageNames.contains(packageName)) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    foregroundStartTimes[packageName] = maxOf(event.timeStamp, windowStart)
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val startTime = foregroundStartTimes[packageName]
                    if (startTime != null) {
                        val endTime = maxOf(event.timeStamp, windowStart)
                        if (endTime > startTime) {
                            usageMs[packageName] = (usageMs[packageName] ?: 0L) + (endTime - startTime)
                        }
                        foregroundStartTimes.remove(packageName)
                    }
                }
            }
        }

        for ((packageName, startTime) in foregroundStartTimes) {
            usageMs[packageName] = (usageMs[packageName] ?: 0L) + (now - startTime)
        }

        return usageMs.mapValues { (_, ms) -> (ms / 1000).toInt() }
    }

    private fun loadVirtualSessions(packageName: String): MutableList<VirtualUsageSession> {
        val json = prefs.getString(KEY_VIRTUAL_SESSIONS_PREFIX + packageName, null)
            ?: return mutableListOf()
        val type = object : TypeToken<MutableList<VirtualUsageSession>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    private fun pruneAndSaveVirtualSessions(
        packageName: String,
        sessions: MutableList<VirtualUsageSession>,
        nowMs: Long
    ) {
        val cutoff = nowMs - VIRTUAL_SESSION_MAX_AGE_MS
        sessions.removeIf { session ->
            val lastPoint = session.endMs ?: session.lastSeenMs ?: session.startMs
            lastPoint < cutoff
        }
        val json = gson.toJson(sessions)
        prefs.edit().putString(KEY_VIRTUAL_SESSIONS_PREFIX + packageName, json).apply()
    }
}
