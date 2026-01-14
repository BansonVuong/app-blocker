package com.appblocker

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal data class SimpleUsageEvent(
    val packageName: String,
    val eventType: Int,
    val timeStamp: Long
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

internal fun computeRemainingSeconds(quotaMinutes: Int, usedSeconds: Int): Int {
    val quotaSeconds = quotaMinutes * 60
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
        return computeUsageSeconds(blockSet, simplifiedEvents, now, windowStart)
    }

    fun getUsageMinutesInWindow(blockSet: BlockSet): Int {
        return getUsageSecondsInWindow(blockSet) / 60
    }

    fun getRemainingMinutes(blockSet: BlockSet): Int {
        return getRemainingSeconds(blockSet) / 60
    }

    fun getRemainingSeconds(blockSet: BlockSet): Int {
        val usedSeconds = getUsageSecondsInWindow(blockSet)
        return computeRemainingSeconds(blockSet.quotaMinutes, usedSeconds)
    }

    fun isQuotaExceeded(blockSet: BlockSet): Boolean {
        return getRemainingSeconds(blockSet) <= 0
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
}
