package com.appblocker

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar
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

data class InterventionChallenge(
    val mode: Int,
    val blockSetId: String,
    val blockSetName: String,
    val randomCodeLength: Int = 32,
    val expectedPassword: String = ""
)

data class EffectiveAppPolicy(
    val packageName: String,
    val activeBlockSets: List<BlockSet>,
    val primaryBlockSet: BlockSet,
    val quotaBlocked: Boolean,
    val interventionChallenges: List<InterventionChallenge>,
    val interventionSignature: String
)

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
        const val OVERRIDE_AUTH_RANDOM = 2

        const val LOCKDOWN_CANCEL_DISABLED = 0
        const val LOCKDOWN_CANCEL_PASSWORD = 1
        const val LOCKDOWN_CANCEL_RANDOM = 2

        private const val KEY_OVERRIDE_RANDOM_CODE_LENGTH = "override_random_code_length"
        private const val KEY_LOCKDOWN_RANDOM_CODE_LENGTH = "lockdown_random_code_length"
        private const val KEY_SETTINGS_RANDOM_CODE_LENGTH = "settings_random_code_length"
        private const val DEFAULT_RANDOM_CODE_LENGTH = 32
    }

    private data class AuthPrefsConfig(
        val modeKey: String,
        val passwordKey: String,
        val randomCodeLengthKey: String,
        val defaultMode: Int
    )

    private val overrideAuthConfig = AuthPrefsConfig(
        modeKey = KEY_OVERRIDE_AUTH_MODE,
        passwordKey = KEY_OVERRIDE_PASSWORD,
        randomCodeLengthKey = KEY_OVERRIDE_RANDOM_CODE_LENGTH,
        defaultMode = OVERRIDE_AUTH_NONE
    )

    private val lockdownAuthConfig = AuthPrefsConfig(
        modeKey = KEY_LOCKDOWN_AUTH_MODE,
        passwordKey = KEY_LOCKDOWN_PASSWORD,
        randomCodeLengthKey = KEY_LOCKDOWN_RANDOM_CODE_LENGTH,
        defaultMode = LOCKDOWN_CANCEL_DISABLED
    )

    private val settingsAuthConfig = AuthPrefsConfig(
        modeKey = KEY_SETTINGS_AUTH_MODE,
        passwordKey = KEY_SETTINGS_PASSWORD,
        randomCodeLengthKey = KEY_SETTINGS_RANDOM_CODE_LENGTH,
        defaultMode = OVERRIDE_AUTH_NONE
    )

    // ===== Debug-only prefs (easy to remove) =====
    fun isDebugOverlayEnabled(): Boolean {
        return prefs.getBoolean(KEY_DEBUG_OVERLAY_ENABLED, false)
    }

    fun setDebugOverlayEnabled(enabled: Boolean) {
        putBoolean(KEY_DEBUG_OVERLAY_ENABLED, enabled)
    }

    fun isDebugLogCaptureEnabled(): Boolean {
        return prefs.getBoolean(KEY_DEBUG_LOG_CAPTURE_ENABLED, false)
    }

    fun setDebugLogCaptureEnabled(enabled: Boolean) {
        putBoolean(KEY_DEBUG_LOG_CAPTURE_ENABLED, enabled)
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
        putString(KEY_BLOCK_SETS, json)
    }

    fun getBlockSets(): MutableList<BlockSet> {
        val json = prefs.getString(KEY_BLOCK_SETS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<BlockSet>>() {}.type
        val blockSets: MutableList<BlockSet> = gson.fromJson(json, type) ?: return mutableListOf()
        var needsSave = false
        for (bs in blockSets) {
            when (bs.intervention) {
                2 -> { bs.intervention = BlockSet.INTERVENTION_RANDOM; bs.interventionCodeLength = 64; needsSave = true }
                3 -> { bs.intervention = BlockSet.INTERVENTION_RANDOM; bs.interventionCodeLength = 128; needsSave = true }
                BlockSet.INTERVENTION_RANDOM -> {
                    if (bs.interventionCodeLength <= 0) { bs.interventionCodeLength = 32; needsSave = true }
                }
            }
        }
        if (needsSave) saveBlockSets(blockSets)
        return blockSets
    }

    fun getBlockSetForApp(packageName: String): BlockSet? {
        return getBlockSets().find { it.apps.contains(packageName) }
    }

    fun resolveEffectiveAppPolicy(
        packageName: String,
        nowMs: Long = System.currentTimeMillis(),
        calendar: Calendar = Calendar.getInstance()
    ): EffectiveAppPolicy? {
        val activeBlockSets = getBlockSets()
            .filter { it.apps.contains(packageName) && it.isScheduleActive(calendar) }
        if (activeBlockSets.isEmpty()) return null

        val remainingByBlockSetId = activeBlockSets.associate { it.id to getRemainingSeconds(it) }
        val primaryBlockSet = activeBlockSets.minWithOrNull(
            effectiveStrictnessComparator(remainingByBlockSetId)
        ) ?: return null

        val quotaBlocked = activeBlockSets.any { blockSet ->
            val remaining = remainingByBlockSetId[blockSet.id] ?: Int.MAX_VALUE
            remaining <= 0 && !isOverrideActive(blockSet, nowMs)
        }

        val interventionChallenges = buildInterventionChallenges(activeBlockSets)
        val interventionSignature = interventionChallenges.joinToString("|") { challenge ->
            listOf(
                challenge.mode.toString(),
                challenge.blockSetId,
                challenge.randomCodeLength.toString(),
                challenge.expectedPassword
            ).joinToString(":")
        }

        return EffectiveAppPolicy(
            packageName = packageName,
            activeBlockSets = activeBlockSets,
            primaryBlockSet = primaryBlockSet,
            quotaBlocked = quotaBlocked,
            interventionChallenges = interventionChallenges,
            interventionSignature = interventionSignature
        )
    }

    private fun buildInterventionChallenges(activeBlockSets: List<BlockSet>): List<InterventionChallenge> {
        val passwordChallenges = activeBlockSets
            .filter { it.intervention == BlockSet.INTERVENTION_PASSWORD }
            .sortedWith(compareBy(BlockSet::name, BlockSet::id))
            .map { blockSet ->
                InterventionChallenge(
                    mode = BlockSet.INTERVENTION_PASSWORD,
                    blockSetId = blockSet.id,
                    blockSetName = blockSet.name,
                    expectedPassword = blockSet.interventionPassword
                )
            }
        if (passwordChallenges.isNotEmpty()) {
            return passwordChallenges
        }

        val randomInterventions = activeBlockSets.filter { it.intervention == BlockSet.INTERVENTION_RANDOM }
        if (randomInterventions.isEmpty()) {
            return emptyList()
        }
        val strictestCodeLength = randomInterventions.maxOf { it.interventionCodeLength.coerceAtLeast(1) }
        val remainingByBlockSetId = randomInterventions.associate { it.id to getRemainingSeconds(it) }
        val strictestBlockSet = randomInterventions.minWithOrNull(
            effectiveStrictnessComparator(remainingByBlockSetId)
        )
            ?: randomInterventions.first()
        return listOf(
            InterventionChallenge(
                mode = BlockSet.INTERVENTION_RANDOM,
                blockSetId = strictestBlockSet.id,
                blockSetName = strictestBlockSet.name,
                randomCodeLength = strictestCodeLength
            )
        )
    }

    private fun effectiveStrictnessComparator(
        remainingByBlockSetId: Map<String, Int>
    ): Comparator<BlockSet> {
        return compareBy<BlockSet>(
            { if (it.scheduleEnabled) 0 else 1 },
            { -interventionStrictness(it.intervention) },
            { remainingByBlockSetId[it.id] ?: Int.MAX_VALUE },
            { it.quotaMinutes },
            { it.windowMinutes },
            { it.id }
        )
    }

    private fun interventionStrictness(intervention: Int): Int {
        return when (intervention) {
            BlockSet.INTERVENTION_PASSWORD -> 2
            BlockSet.INTERVENTION_RANDOM -> 1
            else -> 0
        }
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
        putLong(KEY_OVERRIDE_END_PREFIX + blockSetId, endMs)
    }

    fun setLockdownHours(
        hours: Int,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val durationMs = hours * 60L * 60L * 1000L
        val endMs = nowMs + durationMs
        putLong(KEY_LOCKDOWN_END, endMs)
    }

    fun getLockdownCancelAuthMode(): Int {
        return getAuthMode(lockdownAuthConfig)
    }

    fun setLockdownCancelAuthMode(mode: Int) {
        setAuthMode(lockdownAuthConfig, mode)
    }

    fun getLockdownPassword(): String {
        return getAuthPassword(lockdownAuthConfig)
    }

    fun setLockdownPassword(password: String) {
        setAuthPassword(lockdownAuthConfig, password)
    }

    fun getLockdownRandomCodeLength(): Int = getAuthRandomCodeLength(lockdownAuthConfig)
    fun setLockdownRandomCodeLength(length: Int) { setAuthRandomCodeLength(lockdownAuthConfig, length) }

    fun clearLockdown() {
        removeKey(KEY_LOCKDOWN_END)
    }

    fun isLockdownActive(
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return getActiveEndMillis(KEY_LOCKDOWN_END, nowMs, ::clearLockdown) != null
    }

    fun getLockdownRemainingSeconds(
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val endMs = getActiveEndMillis(KEY_LOCKDOWN_END, nowMs, ::clearLockdown) ?: return 0
        return ((endMs - nowMs) / 1000).toInt()
    }

    fun getLockdownEndMillis(
        nowMs: Long = System.currentTimeMillis()
    ): Long? {
        return getActiveEndMillis(KEY_LOCKDOWN_END, nowMs, ::clearLockdown)
    }

    fun getOverrideAuthMode(): Int {
        return getAuthMode(overrideAuthConfig)
    }

    fun setOverrideAuthMode(mode: Int) {
        setAuthMode(overrideAuthConfig, mode)
    }

    fun getOverridePassword(): String {
        return getAuthPassword(overrideAuthConfig)
    }

    fun setOverridePassword(password: String) {
        setAuthPassword(overrideAuthConfig, password)
    }

    fun getOverrideRandomCodeLength(): Int = getAuthRandomCodeLength(overrideAuthConfig)
    fun setOverrideRandomCodeLength(length: Int) { setAuthRandomCodeLength(overrideAuthConfig, length) }

    fun getSettingsAuthMode(): Int {
        return getAuthMode(settingsAuthConfig)
    }

    fun setSettingsAuthMode(mode: Int) {
        setAuthMode(settingsAuthConfig, mode)
    }

    fun getSettingsPassword(): String {
        return getAuthPassword(settingsAuthConfig)
    }

    fun setSettingsPassword(password: String) {
        setAuthPassword(settingsAuthConfig, password)
    }

    fun getSettingsRandomCodeLength(): Int = getAuthRandomCodeLength(settingsAuthConfig)
    fun setSettingsRandomCodeLength(length: Int) { setAuthRandomCodeLength(settingsAuthConfig, length) }

    fun grantInterventionBypass(packageName: String) {
        putBoolean(KEY_INTERVENTION_BYPASS_PREFIX + packageName, true)
    }

    fun consumeInterventionBypass(packageName: String): Boolean {
        val key = KEY_INTERVENTION_BYPASS_PREFIX + packageName
        val allowed = prefs.getBoolean(key, false)
        if (allowed) {
            removeKey(key)
        }
        return allowed
    }

    fun clearOverride(blockSetId: String) {
        removeKey(KEY_OVERRIDE_END_PREFIX + blockSetId)
    }

    fun isOverrideActive(
        blockSet: BlockSet,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!blockSet.allowOverride) return false
        val key = KEY_OVERRIDE_END_PREFIX + blockSet.id
        return getActiveEndMillis(key, nowMs) { clearOverride(blockSet.id) } != null
    }

    fun getOverrideRemainingSeconds(
        blockSet: BlockSet,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        if (!blockSet.allowOverride) return 0
        val key = KEY_OVERRIDE_END_PREFIX + blockSet.id
        val endMs = getActiveEndMillis(key, nowMs) { clearOverride(blockSet.id) } ?: return 0
        return ((endMs - nowMs) / 1000).toInt()
    }

    fun getDisplayRemainingSeconds(
        blockSet: BlockSet,
        quotaRemainingSeconds: Int = getRemainingSeconds(blockSet),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val overrideRemainingSeconds = getOverrideRemainingSeconds(blockSet, nowMs)
        return maxOf(quotaRemainingSeconds, overrideRemainingSeconds)
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
        putString(KEY_VIRTUAL_SESSIONS_PREFIX + packageName, json)
    }

    private fun getAuthMode(config: AuthPrefsConfig): Int {
        val mode = prefs.getInt(config.modeKey, config.defaultMode)
        return when (mode) {
            3 -> {
                setAuthRandomCodeLength(config, 64)
                setAuthMode(config, OVERRIDE_AUTH_RANDOM)
                OVERRIDE_AUTH_RANDOM
            }
            4 -> {
                setAuthRandomCodeLength(config, 128)
                setAuthMode(config, OVERRIDE_AUTH_RANDOM)
                OVERRIDE_AUTH_RANDOM
            }
            else -> mode
        }
    }

    private fun setAuthMode(config: AuthPrefsConfig, mode: Int) {
        putInt(config.modeKey, mode)
    }

    private fun getAuthPassword(config: AuthPrefsConfig): String {
        return prefs.getString(config.passwordKey, "") ?: ""
    }

    private fun setAuthPassword(config: AuthPrefsConfig, password: String) {
        putString(config.passwordKey, password)
    }

    private fun getAuthRandomCodeLength(config: AuthPrefsConfig): Int {
        return prefs.getInt(config.randomCodeLengthKey, DEFAULT_RANDOM_CODE_LENGTH)
    }

    private fun setAuthRandomCodeLength(config: AuthPrefsConfig, length: Int) {
        putInt(config.randomCodeLengthKey, length)
    }

    private fun getActiveEndMillis(
        key: String,
        nowMs: Long,
        clearExpired: () -> Unit
    ): Long? {
        val endMs = prefs.getLong(key, 0L)
        if (endMs <= nowMs) {
            if (endMs > 0L) {
                clearExpired()
            }
            return null
        }
        return endMs
    }

    private fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    private fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    private fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    private fun removeKey(key: String) {
        prefs.edit().remove(key).apply()
    }
}
