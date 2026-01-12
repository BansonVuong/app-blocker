package com.appblocker

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageCalculatorTest {
    @Test
    fun computesUsageAcrossAppsAndIgnoresUnblocked() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.example.a", "com.example.b"),
            quotaMinutes = 10,
            windowMinutes = 60
        )
        val now = 8_000L
        val events = listOf(
            SimpleUsageEvent("com.example.a", UsageEvents.Event.ACTIVITY_RESUMED, 1_000L),
            SimpleUsageEvent("com.example.a", UsageEvents.Event.ACTIVITY_PAUSED, 4_000L),
            SimpleUsageEvent("com.other.app", UsageEvents.Event.ACTIVITY_RESUMED, 2_000L),
            SimpleUsageEvent("com.example.b", UsageEvents.Event.ACTIVITY_RESUMED, 5_000L)
        )

        val usedSeconds = computeUsageSeconds(blockSet, events, now)

        assertEquals(6, usedSeconds)
    }

    @Test
    fun handlesMultipleForegroundSessions() {
        val blockSet = BlockSet(
            name = "Games",
            apps = mutableListOf("com.example.game"),
            quotaMinutes = 10,
            windowMinutes = 60
        )
        val now = 5_000L
        val events = listOf(
            SimpleUsageEvent("com.example.game", UsageEvents.Event.ACTIVITY_RESUMED, 0L),
            SimpleUsageEvent("com.example.game", UsageEvents.Event.ACTIVITY_PAUSED, 1_000L),
            SimpleUsageEvent("com.example.game", UsageEvents.Event.ACTIVITY_RESUMED, 2_000L),
            SimpleUsageEvent("com.example.game", UsageEvents.Event.ACTIVITY_PAUSED, 5_000L)
        )

        val usedSeconds = computeUsageSeconds(blockSet, events, now)

        assertEquals(4, usedSeconds)
    }

    @Test
    fun countsForegroundTimeUntilNowWhenNoBackgroundEvent() {
        val blockSet = BlockSet(
            name = "Work",
            apps = mutableListOf("com.example.work"),
            quotaMinutes = 10,
            windowMinutes = 60
        )
        val now = 10_000L
        val events = listOf(
            SimpleUsageEvent("com.example.work", UsageEvents.Event.ACTIVITY_RESUMED, 7_000L)
        )

        val usedSeconds = computeUsageSeconds(blockSet, events, now)

        assertEquals(3, usedSeconds)
    }

    @Test
    fun ignoresBackgroundWithoutMatchingForeground() {
        val blockSet = BlockSet(
            name = "Media",
            apps = mutableListOf("com.example.media"),
            quotaMinutes = 10,
            windowMinutes = 60
        )
        val now = 5_000L
        val events = listOf(
            SimpleUsageEvent("com.example.media", UsageEvents.Event.ACTIVITY_PAUSED, 4_000L)
        )

        val usedSeconds = computeUsageSeconds(blockSet, events, now)

        assertEquals(0, usedSeconds)
    }

    @Test
    fun tracksParallelAppsSeparately() {
        val blockSet = BlockSet(
            name = "Mixed",
            apps = mutableListOf("com.example.a", "com.example.b"),
            quotaMinutes = 10,
            windowMinutes = 60
        )
        val now = 10_000L
        val events = listOf(
            SimpleUsageEvent("com.example.a", UsageEvents.Event.ACTIVITY_RESUMED, 1_000L),
            SimpleUsageEvent("com.example.b", UsageEvents.Event.ACTIVITY_RESUMED, 2_000L),
            SimpleUsageEvent("com.example.a", UsageEvents.Event.ACTIVITY_PAUSED, 4_000L),
            SimpleUsageEvent("com.example.b", UsageEvents.Event.ACTIVITY_PAUSED, 8_000L)
        )

        val usedSeconds = computeUsageSeconds(blockSet, events, now)

        assertEquals(9, usedSeconds)
    }

    @Test
    fun computeRemainingSecondsClampsAtZero() {
        assertEquals(0, computeRemainingSeconds(quotaMinutes = 1, usedSeconds = 61))
    }

    @Test
    fun computeRemainingSecondsReturnsPositiveValue() {
        assertEquals(90, computeRemainingSeconds(quotaMinutes = 2, usedSeconds = 30))
    }
}
