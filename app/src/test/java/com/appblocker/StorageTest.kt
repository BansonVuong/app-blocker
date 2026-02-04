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
}
