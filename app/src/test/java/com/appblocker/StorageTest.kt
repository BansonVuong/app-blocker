package com.appblocker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class StorageTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_blocker", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun savesAndLoadsBlockSets() {
        val storage = Storage(context)
        val blockSets = listOf(
            BlockSet(
                name = "Social",
                apps = mutableListOf("com.example.a"),
                quotaMinutes = 10.0,
                windowMinutes = 60
            ),
            BlockSet(
                name = "Games",
                apps = mutableListOf("com.example.b"),
                quotaMinutes = 5.0,
                windowMinutes = 30
            )
        )

        storage.saveBlockSets(blockSets)
        val loaded = storage.getBlockSets()

        assertEquals(2, loaded.size)
        assertEquals("Social", loaded[0].name)
        assertEquals("Games", loaded[1].name)
    }

    @Test
    fun stripsRemovedInAppTrackingTargetsWhenSavingAndLoadingBlockSets() {
        val storage = Storage(context)
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf(
                "com.instagram.android",
                "com.instagram.android:reels",
                "com.google.android.youtube:shorts",
                "com.android.chrome:incognito"
            )
        )

        storage.saveBlockSets(listOf(blockSet))
        val loaded = storage.getBlockSets()

        assertEquals(listOf("com.instagram.android"), loaded.single().apps)
    }

    @Test
    fun findsBlockSetForApp() {
        val storage = Storage(context)
        val blockSets = listOf(
            BlockSet(
                name = "Social",
                apps = mutableListOf("com.example.a"),
                quotaMinutes = 10.0,
                windowMinutes = 60
            )
        )
        storage.saveBlockSets(blockSets)

        val found = storage.getBlockSetForApp("com.example.a")
        val missing = storage.getBlockSetForApp("com.example.missing")

        assertEquals("Social", found?.name)
        assertNull(missing)
    }

    @Test
    fun debugOverlayFlagPersists() {
        val storage = Storage(context)

        assertFalse(storage.isDebugOverlayEnabled())
        storage.setDebugOverlayEnabled(true)
        assertTrue(storage.isDebugOverlayEnabled())
    }

    @Test
    fun debugOverlayListenerNotifiesAndStopsAfterUnregister() {
        val storage = Storage(context)
        val observed = mutableListOf<Boolean>()

        val listener = storage.registerDebugOverlayEnabledListener { enabled ->
            observed.add(enabled)
        }

        storage.setDebugOverlayEnabled(true)
        storage.setDebugOverlayEnabled(false)

        assertEquals(listOf(true, false), observed)

        storage.unregisterDebugOverlayEnabledListener(listener)
        storage.setDebugOverlayEnabled(true)

        assertEquals(listOf(true, false), observed)
    }

    @Test
    fun overlayPositionPersistsPerPackage() {
        val storage = Storage(context)
        assertNull(storage.getOverlayPosition("com.example.app"))

        storage.setOverlayPosition("com.example.app", 120, 240)
        storage.setOverlayPosition("com.example.other", 5, 10)

        assertEquals(120 to 240, storage.getOverlayPosition("com.example.app"))
        assertEquals(5 to 10, storage.getOverlayPosition("com.example.other"))
    }

    @Test
    fun overrideStateTracksRemainingTime() {
        val storage = Storage(context)
        val blockSet = BlockSet(name = "Social", allowOverride = true)
        val nowMs = 1_000L

        storage.setOverrideMinutes(blockSet.id, 5, nowMs)

        assertTrue(storage.isOverrideActive(blockSet, nowMs))
        assertEquals(300, storage.getOverrideRemainingSeconds(blockSet, nowMs))
        assertFalse(storage.isOverrideActive(blockSet, nowMs + 301_000L))
        assertEquals(0, storage.getOverrideRemainingSeconds(blockSet, nowMs + 301_000L))
    }

    @Test
    fun overrideAuthSettingsPersist() {
        val storage = Storage(context)

        assertEquals(Storage.OVERRIDE_AUTH_NONE, storage.getOverrideAuthMode())
        storage.setOverrideAuthMode(Storage.OVERRIDE_AUTH_PASSWORD)
        storage.setOverridePassword("secret")

        assertEquals(Storage.OVERRIDE_AUTH_PASSWORD, storage.getOverrideAuthMode())
        assertEquals("secret", storage.getOverridePassword())
    }

    @Test
    fun settingsAuthSettingsPersist() {
        val storage = Storage(context)

        assertEquals(Storage.OVERRIDE_AUTH_NONE, storage.getSettingsAuthMode())
        storage.setSettingsAuthMode(Storage.OVERRIDE_AUTH_PASSWORD)
        storage.setSettingsPassword("admin")

        assertEquals(Storage.OVERRIDE_AUTH_PASSWORD, storage.getSettingsAuthMode())
        assertEquals("admin", storage.getSettingsPassword())
    }

    @Test
    fun lockdownTracksRemainingTime() {
        val storage = Storage(context)
        val nowMs = 1_000L

        storage.setLockdownHours(2, nowMs)

        assertTrue(storage.isLockdownActive(nowMs))
        assertEquals(2 * 60 * 60, storage.getLockdownRemainingSeconds(nowMs))
        assertFalse(storage.isLockdownActive(nowMs + 2 * 60 * 60 * 1000L + 1))
        assertEquals(0, storage.getLockdownRemainingSeconds(nowMs + 2 * 60 * 60 * 1000L + 1))
    }

    @Test
    fun lockdownAuthSettingsPersist() {
        val storage = Storage(context)

        assertEquals(Storage.LOCKDOWN_CANCEL_DISABLED, storage.getLockdownCancelAuthMode())
        storage.setLockdownCancelAuthMode(Storage.LOCKDOWN_CANCEL_PASSWORD)
        storage.setLockdownPassword("lock-secret")

        assertEquals(Storage.LOCKDOWN_CANCEL_PASSWORD, storage.getLockdownCancelAuthMode())
        assertEquals("lock-secret", storage.getLockdownPassword())
    }

    @Test
    fun interventionBypassIsSingleUse() {
        val storage = Storage(context)

        assertFalse(storage.consumeInterventionBypass("com.example.app"))
        storage.grantInterventionBypass("com.example.app")

        assertTrue(storage.consumeInterventionBypass("com.example.app"))
        assertFalse(storage.consumeInterventionBypass("com.example.app"))
    }

    @Test
    fun scheduledBlockSetBecomesPrimaryWhenOverlappingAlwaysOnSet() {
        val storage = Storage(context)
        val alwaysOn = BlockSet(
            name = "Always On",
            apps = mutableListOf("com.example.app"),
            quotaMinutes = 1.0,
            windowMinutes = 60
        )
        val scheduled = BlockSet(
            name = "Scheduled",
            apps = mutableListOf("com.example.app"),
            quotaMinutes = 10.0,
            windowMinutes = 60,
            scheduleEnabled = true,
            intervention = BlockSet.INTERVENTION_RANDOM,
            timePeriods = mutableListOf(
                TimePeriod(
                    days = mutableListOf(Calendar.MONDAY),
                    startHour = 9,
                    startMinute = 0,
                    endHour = 17,
                    endMinute = 0
                )
            )
        )
        storage.saveBlockSets(listOf(alwaysOn, scheduled))

        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
        }

        val policy = storage.resolveEffectiveAppPolicy(
            packageName = "com.example.app",
            nowMs = 1_000L,
            calendar = calendar
        )

        assertEquals("Scheduled", policy?.primaryBlockSet?.name)
        assertEquals(BlockSet.INTERVENTION_RANDOM, policy?.primaryBlockSet?.intervention)
    }

    @Test
    fun exhaustedOverlapTracksQuotaBlockingBlockSetSeparatelyFromPrimary() {
        val storage = Storage(context)
        val alwaysOn = BlockSet(
            name = "Always On",
            apps = mutableListOf("com.example.app"),
            quotaMinutes = 0.0,
            windowMinutes = 60
        )
        val scheduled = BlockSet(
            name = "Scheduled",
            apps = mutableListOf("com.example.app"),
            quotaMinutes = 10.0,
            windowMinutes = 60,
            scheduleEnabled = true,
            intervention = BlockSet.INTERVENTION_RANDOM,
            timePeriods = mutableListOf(
                TimePeriod(
                    days = mutableListOf(Calendar.MONDAY),
                    startHour = 9,
                    startMinute = 0,
                    endHour = 17,
                    endMinute = 0
                )
            )
        )
        storage.saveBlockSets(listOf(alwaysOn, scheduled))

        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
        }

        val policy = storage.resolveEffectiveAppPolicy(
            packageName = "com.example.app",
            nowMs = 1_000L,
            calendar = calendar
        )

        assertEquals("Scheduled", policy?.primaryBlockSet?.name)
        assertTrue(policy?.quotaBlocked == true)
        assertEquals("Always On", policy?.quotaBlockingBlockSet?.name)
    }

    @Test
    fun displayRemainingSecondsUsesMostGenerousAllowancePerBlockSet() {
        val storage = Storage(context)
        val blockSet = BlockSet(
            name = "Social",
            allowOverride = true
        )
        val nowMs = 1_000L

        storage.setOverrideMinutes(blockSet.id, 1, nowMs)

        assertEquals(
            120,
            storage.getDisplayRemainingSeconds(
                blockSet = blockSet,
                quotaRemainingSeconds = 120,
                nowMs = nowMs + 30_000L
            )
        )
        assertEquals(
            60,
            storage.getDisplayRemainingSeconds(
                blockSet = blockSet,
                quotaRemainingSeconds = 30,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun effectiveDisplayRemainingSecondsUsesStrictestActiveBlockSet() {
        val storage = Storage(context)
        val stricter = BlockSet(
            name = "Stricter",
            apps = mutableListOf("com.example.app"),
            quotaMinutes = 1.0,
            windowMinutes = 60,
            allowOverride = true
        )
        val looser = BlockSet(
            name = "Looser",
            apps = mutableListOf("com.example.app"),
            quotaMinutes = 2.0,
            windowMinutes = 60,
            allowOverride = true
        )
        val nowMs = 31_000L

        storage.setOverrideMinutes(stricter.id, 1, 1_000L)
        storage.setOverrideMinutes(looser.id, 1, 1_000L)

        val effectiveDisplayRemainingSeconds = storage.getEffectiveDisplayRemainingSeconds(
            activeBlockSets = listOf(stricter, looser),
            quotaRemainingByBlockSetId = mapOf(
                stricter.id to 60,
                looser.id to 120
            ),
            nowMs = nowMs
        )

        assertEquals(60, effectiveDisplayRemainingSeconds)
    }

    @Test
    fun policyUsesStrictestDisplayRemainingAcrossOverlappingActiveBlockSets() {
        val storage = Storage(context)
        val stricter = BlockSet(
            name = "Stricter",
            apps = mutableListOf("com.example.app"),
            quotaMinutes = 1.0,
            windowMinutes = 60,
            scheduleEnabled = true,
            allowOverride = true,
            timePeriods = mutableListOf(
                TimePeriod(
                    days = mutableListOf(Calendar.MONDAY),
                    startHour = 9,
                    startMinute = 0,
                    endHour = 17,
                    endMinute = 0
                )
            )
        )
        val looser = BlockSet(
            name = "Looser",
            apps = mutableListOf("com.example.app"),
            quotaMinutes = 2.0,
            windowMinutes = 60,
            scheduleEnabled = true,
            allowOverride = true,
            timePeriods = mutableListOf(
                TimePeriod(
                    days = mutableListOf(Calendar.MONDAY),
                    startHour = 9,
                    startMinute = 0,
                    endHour = 17,
                    endMinute = 0
                )
            )
        )
        storage.saveBlockSets(listOf(stricter, looser))

        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
        }
        val nowMs = 31_000L
        storage.setOverrideMinutes(stricter.id, 1, 1_000L)
        storage.setOverrideMinutes(looser.id, 1, 1_000L)

        val policy = storage.resolveEffectiveAppPolicy(
            packageName = "com.example.app",
            nowMs = nowMs,
            calendar = calendar
        )

        assertEquals(60, policy?.effectiveDisplayRemainingSeconds)
    }

    @Test
    fun positiveQuotaUnblocksAtWindowEnd() {
        val storage = Storage(context)
        val blockSet = BlockSet(
            name = "Social",
            quotaMinutes = 1.0,
            windowMinutes = 60
        )

        assertEquals(3_600_000L, storage.getQuotaUnblockedAtMillis(blockSet, nowMs = 1_000L))
    }

    @Test
    fun zeroQuotaWithoutScheduleNeverUnblocks() {
        val storage = Storage(context)
        val blockSet = BlockSet(
            name = "Always On",
            quotaMinutes = 0.0,
            scheduleEnabled = false
        )

        assertNull(storage.getQuotaUnblockedAtMillis(blockSet, nowMs = 1_000L))
    }

    @Test
    fun zeroQuotaScheduledBlockUnblocksAtNextInactiveMinute() {
        val storage = Storage(context)
        val blockSet = BlockSet(
            name = "Work Hours",
            quotaMinutes = 0.0,
            scheduleEnabled = true,
            timePeriods = mutableListOf(
                TimePeriod(
                    days = mutableListOf(Calendar.MONDAY),
                    startHour = 9,
                    startMinute = 0,
                    endHour = 17,
                    endMinute = 0
                )
            )
        )
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 9, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val unblockAtMillis = storage.getQuotaUnblockedAtMillis(
            blockSet = blockSet,
            nowMs = calendar.timeInMillis,
            calendar = calendar
        )

        val expected = (calendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 17)
            set(Calendar.MINUTE, 1)
        }.timeInMillis
        assertEquals(expected, unblockAtMillis)
    }

    @Test
    fun zeroQuotaScheduledBlockCanStillNeverUnblock() {
        val storage = Storage(context)
        val blockSet = BlockSet(
            name = "All Day",
            quotaMinutes = 0.0,
            scheduleEnabled = true,
            timePeriods = mutableListOf(
                TimePeriod(
                    days = mutableListOf(
                        Calendar.SUNDAY,
                        Calendar.MONDAY,
                        Calendar.TUESDAY,
                        Calendar.WEDNESDAY,
                        Calendar.THURSDAY,
                        Calendar.FRIDAY,
                        Calendar.SATURDAY
                    ),
                    startHour = 0,
                    startMinute = 0,
                    endHour = 23,
                    endMinute = 59
                )
            )
        )
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 9, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

        assertNull(
            storage.getQuotaUnblockedAtMillis(
                blockSet = blockSet,
                nowMs = calendar.timeInMillis,
                calendar = calendar
            )
        )
    }
}
