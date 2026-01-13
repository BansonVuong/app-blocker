package com.appblocker

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class AppBlockerServiceTest {
    private lateinit var app: Application
    private lateinit var storage: Storage

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val prefs = app.getSharedPreferences("app_blocker", Application.MODE_PRIVATE)
        prefs.edit().clear().commit()
        storage = (app as App).storage
        val shadowApp = Shadows.shadowOf(app)
        while (shadowApp.nextStartedActivity != null) {
            // Drain any queued intents from previous runs.
        }
    }

    private fun createWindowStateChangedEvent(packageName: String): AccessibilityEvent {
        return AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            this.packageName = packageName
        }
    }

    private fun getPrivateField(service: AppBlockerService, fieldName: String): Any? {
        val field = AppBlockerService::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(service)
    }

    @Test
    fun launchesBlockedScreenWhenQuotaExceeded() {
        val storage = (app as App).storage
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.example.blocked"
        }

        service.onAccessibilityEvent(event)

        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(BlockedActivity::class.java.name, intent.component?.className)
        assertEquals(blockSet.name, intent.getStringExtra(BlockedActivity.EXTRA_BLOCK_SET_NAME))
        assertEquals(blockSet.id, intent.getStringExtra(BlockedActivity.EXTRA_BLOCK_SET_ID))
    }

    @Test
    fun doesNotLaunchBlockedScreenForUnblockedApp() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.example.unblocked"
        }

        service.onAccessibilityEvent(event)

        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertNull(intent)
    }

    // ==================== Overlay Tracking Tests ====================

    @Test
    fun startsTrackingWhenEnteringBlockedApp() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?
        val trackedBlockSet = getPrivateField(service, "currentBlockSet") as BlockSet?

        assertEquals("com.instagram.android", trackedPackage)
        assertNotNull(trackedBlockSet)
        assertEquals("Social", trackedBlockSet?.name)
    }

    @Test
    fun maintainsTrackingOnMultipleEventsForSameBlockedApp() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Send multiple events for the same blocked app (simulates user scrolling, etc.)
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?
        val trackedBlockSet = getPrivateField(service, "currentBlockSet") as BlockSet?

        // Should still be tracking
        assertEquals("com.instagram.android", trackedPackage)
        assertNotNull(trackedBlockSet)
    }

    @Test
    fun maintainsTrackingWhenKeyboardAppears() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Enter blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        // Keyboard appears (system overlay)
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.google.android.inputmethod.latin"))

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?

        // Should still be tracking the blocked app
        assertEquals("com.instagram.android", trackedPackage)
    }

    @Test
    fun maintainsTrackingWhenSystemUIAppears() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Enter blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        // System UI event (notification shade, etc.)
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.android.systemui"))

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?

        // Should still be tracking the blocked app
        assertEquals("com.instagram.android", trackedPackage)
    }

    @Test
    fun maintainsTrackingWhenSamsungOverlayAppears() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Enter blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        // Samsung system overlay
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.samsung.android.app.cocktailbarservice"))

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?

        // Should still be tracking the blocked app
        assertEquals("com.instagram.android", trackedPackage)
    }

    @Test
    fun stopsTrackingImmediatelyWhenLauncherAppears() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Enter blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        // Go to launcher
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.android.launcher"))

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?

        // Should have stopped tracking immediately
        assertNull(trackedPackage)
    }

    @Test
    fun stopsTrackingImmediatelyWhenThirdPartyLauncherAppears() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Enter blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        // Go to third party launcher
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.teslacoilsw.launcher"))

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?

        // Should have stopped tracking immediately
        assertNull(trackedPackage)
    }

    @Test
    fun schedulesStopTrackingWhenSwitchingToNonBlockedApp() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Enter blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        // Switch to non-blocked app (not a system overlay)
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.spotify.music"))

        // Should have scheduled stop (pendingStopRunnable should be set)
        val pendingStopRunnable = getPrivateField(service, "pendingStopRunnable")
        assertNotNull(pendingStopRunnable)
    }

    @Test
    fun cancelsScheduledStopWhenReturningToBlockedApp() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Enter blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        // Switch to non-blocked app (schedules stop)
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.spotify.music"))

        // Return to blocked app before stop executes
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        // Pending stop should be cancelled
        val pendingStopRunnable = getPrivateField(service, "pendingStopRunnable")
        assertNull(pendingStopRunnable)

        // Should still be tracking
        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?
        assertEquals("com.instagram.android", trackedPackage)
    }

    @Test
    fun switchesBetweenBlockedApps() {
        val blockSets = listOf(
            BlockSet(
                name = "Social",
                apps = mutableListOf("com.instagram.android"),
                quotaMinutes = 30,
                windowMinutes = 60
            ),
            BlockSet(
                name = "Video",
                apps = mutableListOf("com.tiktok.android"),
                quotaMinutes = 15,
                windowMinutes = 60
            )
        )
        storage.saveBlockSets(blockSets)

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Enter first blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        var trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?
        var trackedBlockSet = getPrivateField(service, "currentBlockSet") as BlockSet?
        assertEquals("com.instagram.android", trackedPackage)
        assertEquals("Social", trackedBlockSet?.name)

        // Switch to second blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.tiktok.android"))

        trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?
        trackedBlockSet = getPrivateField(service, "currentBlockSet") as BlockSet?
        assertEquals("com.tiktok.android", trackedPackage)
        assertEquals("Video", trackedBlockSet?.name)
    }

    @Test
    fun initializesLocalSessionTrackingOnStart() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        val sessionStartTimeMs = getPrivateField(service, "sessionStartTimeMs") as Long
        val initialRemainingSeconds = getPrivateField(service, "initialRemainingSeconds") as Int

        // Session start time should be set to a recent time
        assertTrue(sessionStartTimeMs > 0)
        assertTrue(System.currentTimeMillis() - sessionStartTimeMs < 1000)

        // Initial remaining seconds should be set
        assertTrue(initialRemainingSeconds > 0)
    }

    @Test
    fun resetsLocalSessionTrackingWhenSwitchingBlockedApps() {
        val blockSets = listOf(
            BlockSet(
                name = "Social",
                apps = mutableListOf("com.instagram.android"),
                quotaMinutes = 30,
                windowMinutes = 60
            ),
            BlockSet(
                name = "Video",
                apps = mutableListOf("com.tiktok.android"),
                quotaMinutes = 15,
                windowMinutes = 60
            )
        )
        storage.saveBlockSets(blockSets)

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Enter first blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))
        val firstSessionStart = getPrivateField(service, "sessionStartTimeMs") as Long

        // Small delay to ensure different timestamps
        Thread.sleep(10)

        // Switch to second blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.tiktok.android"))
        val secondSessionStart = getPrivateField(service, "sessionStartTimeMs") as Long

        // Session should be reset with new timestamp
        assertTrue(secondSessionStart > firstSessionStart)
    }

    @Test
    fun clearsSessionTrackingWhenStoppingTracking() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Enter blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        // Go to launcher (immediate stop)
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.android.launcher"))

        val sessionStartTimeMs = getPrivateField(service, "sessionStartTimeMs") as Long
        val initialRemainingSeconds = getPrivateField(service, "initialRemainingSeconds") as Int

        // Session tracking should be cleared
        assertEquals(0L, sessionStartTimeMs)
        assertEquals(0, initialRemainingSeconds)
    }

    @Test
    fun ignoresNonWindowStateChangedEvents() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Send a different event type
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "com.instagram.android"
        }
        service.onAccessibilityEvent(event)

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?

        // Should not have started tracking
        assertNull(trackedPackage)
    }

    @Test
    fun ignoresEventsWithNullPackageName() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Send event with null package name
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = null
        }
        service.onAccessibilityEvent(event)

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?

        // Should not have started tracking
        assertNull(trackedPackage)
    }

    @Test
    fun multipleBlockedAppEventsKeepOverlayUpdateRunnableActive() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Send multiple events for the same blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))
        val firstRunnable = getPrivateField(service, "overlayUpdateRunnable")

        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))
        val secondRunnable = getPrivateField(service, "overlayUpdateRunnable")

        // Runnable should still be active (same instance since we didn't restart tracking)
        assertNotNull(firstRunnable)
        assertNotNull(secondRunnable)
        assertEquals(firstRunnable, secondRunnable)
    }
}
