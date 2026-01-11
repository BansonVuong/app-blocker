package com.appblocker

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Storage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_blocker", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    companion object {
        private const val KEY_BLOCK_SETS = "block_sets"
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

        val events = usageStatsManager.queryEvents(windowStart, now)
        val event = UsageEvents.Event()

        // Track foreground start times for each app
        val foregroundStartTimes = mutableMapOf<String, Long>()
        var totalMs = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName

            // Only process apps in this block set
            if (!blockSet.apps.contains(packageName)) continue

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // App came to foreground
                    foregroundStartTimes[packageName] = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    // App went to background - calculate time spent
                    val startTime = foregroundStartTimes[packageName]
                    if (startTime != null) {
                        totalMs += event.timeStamp - startTime
                        foregroundStartTimes.remove(packageName)
                    }
                }
            }
        }

        // If any app is still in foreground, count time up to now
        for ((_, startTime) in foregroundStartTimes) {
            totalMs += now - startTime
        }

        return (totalMs / 1000).toInt()
    }

    fun getUsageMinutesInWindow(blockSet: BlockSet): Int {
        return getUsageSecondsInWindow(blockSet) / 60
    }

    fun getRemainingMinutes(blockSet: BlockSet): Int {
        return getRemainingSeconds(blockSet) / 60
    }

    fun getRemainingSeconds(blockSet: BlockSet): Int {
        val usedSeconds = getUsageSecondsInWindow(blockSet)
        val quotaSeconds = blockSet.quotaMinutes * 60
        return maxOf(0, quotaSeconds - usedSeconds)
    }

    fun isQuotaExceeded(blockSet: BlockSet): Boolean {
        return getRemainingSeconds(blockSet) <= 0
    }
}
