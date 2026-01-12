package com.appblocker

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val prefs = app.getSharedPreferences("app_blocker", Application.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val shadowApp = Shadows.shadowOf(app)
        while (shadowApp.nextStartedActivity != null) {
            // Drain any queued intents from previous runs.
        }
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
            packageName = "com.example.unblocked"
        }

        service.onAccessibilityEvent(event)

        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertNull(intent)
    }
}
