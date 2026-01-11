package com.appblocker

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Storage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_blocker", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_BLOCK_SETS = "block_sets"
        private const val KEY_USAGE_TIMESTAMPS_PREFIX = "usage_ts_"
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

    // Usage tracking - stores list of timestamps (in seconds) when usage occurred
    private fun getUsageTimestampsKey(blockSetId: String): String {
        return "${KEY_USAGE_TIMESTAMPS_PREFIX}$blockSetId"
    }

    private fun getUsageTimestamps(blockSetId: String): MutableList<Long> {
        val json = prefs.getString(getUsageTimestampsKey(blockSetId), null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Long>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    private fun saveUsageTimestamps(blockSetId: String, timestamps: List<Long>) {
        val json = gson.toJson(timestamps)
        prefs.edit().putString(getUsageTimestampsKey(blockSetId), json).apply()
    }

    fun addUsageSeconds(blockSetId: String, seconds: Int) {
        val now = System.currentTimeMillis() / 1000
        val timestamps = getUsageTimestamps(blockSetId)

        // Add a timestamp for each second of usage
        repeat(seconds) {
            timestamps.add(now)
        }

        // Clean up old timestamps (older than 1 hour to save space)
        val cutoff = now - 3600
        val cleaned = timestamps.filter { it >= cutoff }

        saveUsageTimestamps(blockSetId, cleaned)
    }

    fun getUsageMinutesInWindow(blockSetId: String, windowMinutes: Int): Int {
        return getUsageSecondsInWindow(blockSetId, windowMinutes) / 60
    }

    fun getUsageSecondsInWindow(blockSetId: String, windowMinutes: Int): Int {
        val now = System.currentTimeMillis() / 1000
        val windowSeconds = windowMinutes * 60
        val windowStart = (now / windowSeconds) * windowSeconds
        val timestamps = getUsageTimestamps(blockSetId)

        return timestamps.count { it >= windowStart }
    }

    fun getRemainingMinutes(blockSet: BlockSet): Int {
        return getRemainingSeconds(blockSet) / 60
    }

    fun getRemainingSeconds(blockSet: BlockSet): Int {
        val usedSeconds = getUsageSecondsInWindow(blockSet.id, blockSet.windowMinutes)
        val quotaSeconds = blockSet.quotaMinutes * 60
        return maxOf(0, quotaSeconds - usedSeconds)
    }

    fun isQuotaExceeded(blockSet: BlockSet): Boolean {
        return getRemainingMinutes(blockSet) <= 0
    }

    fun clearUsage(blockSetId: String) {
        prefs.edit().remove(getUsageTimestampsKey(blockSetId)).apply()
    }
}
