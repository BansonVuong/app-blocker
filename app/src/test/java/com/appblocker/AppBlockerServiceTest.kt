package com.appblocker

import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.Calendar

@Suppress("DEPRECATION")
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
        return AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            this.packageName = packageName
        }
    }

    private fun createWindowContentChangedEvent(packageName: String): AccessibilityEvent {
        return AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            this.packageName = packageName
        }
    }

    private fun createEvent(
        packageName: String,
        eventType: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        className: String? = null
    ): AccessibilityEvent {
        return AccessibilityEvent.obtain().apply {
            this.eventType = eventType
            this.packageName = packageName
            this.className = className
        }
    }

    private fun getPrivateField(service: AppBlockerService, fieldName: String): Any? {
        val field = AppBlockerService::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(service)
    }

    private fun setPrivateField(service: AppBlockerService, fieldName: String, value: Any?) {
        val field = AppBlockerService::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(service, value)
    }

    private fun getNestedPrivateField(instance: Any, fieldName: String): Any? {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(instance)
    }

    private fun buildNode(
        viewId: String? = null,
        text: String? = null,
        contentDesc: String? = null,
        packageName: String? = null,
        bounds: Rect? = null
    ): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain()
        if (viewId != null) {
            node.viewIdResourceName = viewId
        }
        if (text != null) {
            node.text = text
        }
        if (contentDesc != null) {
            node.contentDescription = contentDesc
        }
        if (packageName != null) {
            node.packageName = packageName
        }
        if (bounds != null) {
            node.setBoundsInScreen(bounds)
        }
        return node
    }

    private fun resolveInAppBrowserPackage(
        service: AppBlockerService,
        packageName: String,
        className: String?
    ): String? {
        val method = AppBlockerService::class.java.getDeclaredMethod(
            "resolveInAppBrowserPackage",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(service, packageName, className) as String?
    }

    private fun addChild(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) {
        Shadows.shadowOf(parent).addChild(child)
    }

    private fun installPackageResolverWithRoot(service: AppBlockerService, rootPackageName: String) {
        setPrivateField(
            service,
            "packageResolver",
            AppBlockerPackageResolver(
                storage = storage,
                rootProvider = {
                    AccessibilityNodeInfo.obtain().apply {
                        packageName = rootPackageName
                    }
                }
            )
        )
    }

    private fun installPackageResolverWithDynamicRoot(
        service: AppBlockerService,
        rootProvider: () -> String
    ) {
        setPrivateField(
            service,
            "packageResolver",
            AppBlockerPackageResolver(
                storage = storage,
                rootProvider = {
                    AccessibilityNodeInfo.obtain().apply {
                        packageName = rootProvider()
                    }
                }
            )
        )
    }

    private fun drainStartedActivitiesCount(): Int {
        val shadowApp = Shadows.shadowOf(app)
        var count = 0
        while (shadowApp.nextStartedActivity != null) {
            count++
        }
        return count
    }

    @Test
    fun launchesBlockedScreenWhenQuotaExceeded() {
        val storage = (app as App).storage
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 0.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
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
            quotaMinutes = 0.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageName = "com.example.unblocked"
        }

        service.onAccessibilityEvent(event)

        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertNull(intent)
    }

    @Test
    fun launchesInterventionScreenWhenBlockSetRequiresIntervention() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 60,
            intervention = BlockSet.INTERVENTION_RANDOM
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))

        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(BlockedActivity::class.java.name, intent.component?.className)
        assertEquals(BlockedActivity.MODE_INTERVENTION, intent.getIntExtra(BlockedActivity.EXTRA_MODE, -1))
        assertEquals(
            BlockSet.INTERVENTION_RANDOM,
            intent.getIntExtra(BlockedActivity.EXTRA_INTERVENTION_MODE, -1)
        )
    }

    @Test
    fun launchesInterventionScreenWhenXiaomiLauncherWrapsBlockedApp() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
            windowMinutes = 60,
            intervention = BlockSet.INTERVENTION_RANDOM
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        installPackageResolverWithRoot(service, "com.instagram.android")

        service.onAccessibilityEvent(createEvent(packageName = "com.miui.home"))

        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(BlockedActivity::class.java.name, intent.component?.className)
        assertEquals("com.instagram.android", intent.getStringExtra(BlockedActivity.EXTRA_RETURN_PACKAGE))
        assertEquals(BlockedActivity.MODE_INTERVENTION, intent.getIntExtra(BlockedActivity.EXTRA_MODE, -1))
    }

    @Test
    fun doesNotRelaunchInterventionScreenForRapidDuplicateEvents() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 60,
            intervention = BlockSet.INTERVENTION_RANDOM
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))

        assertEquals(1, drainStartedActivitiesCount())
    }

    @Test
    fun suppressesInterventionRetriggerWhenForegroundReturnsViaContentChangedEventWithinGrace() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("org.mozilla.fenix"),
            quotaMinutes = 30.0,
            windowMinutes = 60,
            intervention = BlockSet.INTERVENTION_RANDOM
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        service.onAccessibilityEvent(createWindowStateChangedEvent("org.mozilla.fenix"))
        service.onAccessibilityEvent(
            createEvent(
                packageName = "com.appblocker",
                className = "com.appblocker.MainActivity"
            )
        )
        drainStartedActivitiesCount()
        setPrivateField(service, "lastBlockedScreenLaunchMs", 0L)

        service.onAccessibilityEvent(createWindowContentChangedEvent("org.mozilla.fenix"))

        assertEquals(0, drainStartedActivitiesCount())
        assertNull(getPrivateField(service, "currentTrackedPackage"))
    }

    @Test
    fun doesNotStartTrackingBlockedAppFromContentChangedEventAlone() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("org.mozilla.fenix"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowContentChangedEvent("org.mozilla.fenix"))

        assertNull(getPrivateField(service, "currentTrackedPackage"))
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }

    @Test
    fun doesNotSwitchBlockedAppsFromContentChangedEvent() {
        val blockSets = listOf(
            BlockSet(
                name = "Social",
                apps = mutableListOf("com.instagram.android"),
                quotaMinutes = 30.0,
                windowMinutes = 60
            ),
            BlockSet(
                name = "Video",
                apps = mutableListOf("com.zhiliaoapp.musically"),
                quotaMinutes = 30.0,
                windowMinutes = 60
            )
        )
        storage.saveBlockSets(blockSets)

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))
        service.onAccessibilityEvent(createWindowContentChangedEvent("com.zhiliaoapp.musically"))

        assertEquals("com.instagram.android", getPrivateField(service, "currentTrackedPackage"))
    }

    @Test
    fun nonWindowStateChangedEventDoesNotStopActiveBlockedTracking() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))
        service.onAccessibilityEvent(createWindowContentChangedEvent("com.example.unblocked"))

        assertEquals("com.instagram.android", getPrivateField(service, "currentTrackedPackage"))
        assertNull(getPrivateField(service, "pendingStopRunnable"))
    }

    @Test
    fun launcherContentChangedEventDoesNotStopTracking() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))
        service.onAccessibilityEvent(createWindowContentChangedEvent("com.google.android.apps.nexuslauncher"))

        assertEquals("com.instagram.android", getPrivateField(service, "currentTrackedPackage"))
    }

    @Test
    fun appBlockerOverlayEventDoesNotStopTrackingWhenRootIsTrackedApp() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        installPackageResolverWithDynamicRoot(service) { "com.instagram.android" }

        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))
        service.onAccessibilityEvent(
            createEvent(
                packageName = "com.appblocker",
                className = "android.widget.TextView"
            )
        )

        assertEquals("com.instagram.android", getPrivateField(service, "currentTrackedPackage"))
    }

    @Test
    fun stopsTrackingImmediatelyWhenAppBlockerAppIsForeground() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        assertEquals("com.instagram.android", getPrivateField(service, "currentTrackedPackage"))

        service.onAccessibilityEvent(
            createEvent(
                packageName = "com.appblocker",
                className = "com.appblocker.MainActivity"
            )
        )

        assertNull(getPrivateField(service, "currentTrackedPackage"))
        assertNull(getPrivateField(service, "pendingStopRunnable"))
    }

    @Test
    fun samsungWrapperResolvedToGoogleAppDoesNotLaunchBlockScreen() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.google.android.googlequicksearchbox"),
            quotaMinutes = 30.0,
            windowMinutes = 60,
            intervention = BlockSet.INTERVENTION_RANDOM
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        installPackageResolverWithRoot(service, "com.google.android.googlequicksearchbox")

        service.onAccessibilityEvent(
            createEvent(packageName = "com.samsung.android.app.cocktailbarservice")
        )

        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertNull(getPrivateField(service, "currentTrackedPackage"))
    }

    @Test
    fun overlappingBlockSetsTrackStrictestQuotaSet() {
        val strict = BlockSet(
            name = "Strict",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 1.0,
            windowMinutes = 5
        )
        val lenient = BlockSet(
            name = "Lenient",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 3.0,
            windowMinutes = 5
        )
        storage.saveBlockSets(listOf(lenient, strict))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))

        val trackedBlockSet = getPrivateField(service, "currentBlockSet") as BlockSet?
        assertEquals("Strict", trackedBlockSet?.name)
    }

    @Test
    fun overlappingRandomInterventionsUseStrictestCodeLength() {
        val first = BlockSet(
            name = "Set A",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 5,
            intervention = BlockSet.INTERVENTION_RANDOM,
            interventionCodeLength = 30
        )
        val second = BlockSet(
            name = "Set B",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 5,
            intervention = BlockSet.INTERVENTION_RANDOM,
            interventionCodeLength = 10
        )
        storage.saveBlockSets(listOf(first, second))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))

        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(BlockedActivity.MODE_INTERVENTION, intent.getIntExtra(BlockedActivity.EXTRA_MODE, -1))

        val json = intent.getStringExtra(BlockedActivity.EXTRA_INTERVENTION_STEPS_JSON)
        val type = object : TypeToken<List<InterventionChallenge>>() {}.type
        val steps = Gson().fromJson<List<InterventionChallenge>>(json, type).orEmpty()
        assertEquals(1, steps.size)
        assertEquals(BlockSet.INTERVENTION_RANDOM, steps.first().mode)
        assertEquals(30, steps.first().randomCodeLength)
    }

    @Test
    fun overlappingScheduledInterventionUsesScheduledBlockSetAsPrimary() {
        val alwaysOn = BlockSet(
            name = "Always On",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 1.0,
            windowMinutes = 60
        )
        val scheduled = BlockSet(
            name = "Scheduled",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 10.0,
            windowMinutes = 60,
            scheduleEnabled = true,
            intervention = BlockSet.INTERVENTION_RANDOM,
            interventionCodeLength = 20,
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
            packageName = "com.example.blocked",
            nowMs = 1_000L,
            calendar = calendar
        )

        assertEquals("Scheduled", policy?.primaryBlockSet?.name)
        assertEquals(BlockSet.INTERVENTION_RANDOM, policy?.primaryBlockSet?.intervention)
    }

    @Test
    fun overlappingExpiredQuotaLaunchesBlockedScreenForBlockingBlockSet() {
        val alwaysOn = BlockSet(
            name = "Always On",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 0.0,
            windowMinutes = 60
        )
        val scheduled = BlockSet(
            name = "Scheduled",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 10.0,
            windowMinutes = 60,
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
        storage.saveBlockSets(listOf(alwaysOn, scheduled))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))

        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(BlockedActivity::class.java.name, intent.component?.className)
        assertEquals("Always On", intent.getStringExtra(BlockedActivity.EXTRA_BLOCK_SET_NAME))
        assertEquals(alwaysOn.id, intent.getStringExtra(BlockedActivity.EXTRA_BLOCK_SET_ID))
    }

    @Test
    fun passwordInterventionsOverrideRandomAndRemainSequential() {
        val randomSet = BlockSet(
            name = "Random",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 5,
            intervention = BlockSet.INTERVENTION_RANDOM,
            interventionCodeLength = 12
        )
        val passwordA = BlockSet(
            name = "Alpha",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 5,
            intervention = BlockSet.INTERVENTION_PASSWORD,
            interventionPassword = "first-pass"
        )
        val passwordB = BlockSet(
            name = "Beta",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 5,
            intervention = BlockSet.INTERVENTION_PASSWORD,
            interventionPassword = "second-pass"
        )
        storage.saveBlockSets(listOf(randomSet, passwordB, passwordA))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))

        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(BlockedActivity.MODE_INTERVENTION, intent.getIntExtra(BlockedActivity.EXTRA_MODE, -1))

        val json = intent.getStringExtra(BlockedActivity.EXTRA_INTERVENTION_STEPS_JSON)
        val type = object : TypeToken<List<InterventionChallenge>>() {}.type
        val steps = Gson().fromJson<List<InterventionChallenge>>(json, type).orEmpty()

        assertEquals(2, steps.size)
        assertTrue(steps.all { it.mode == BlockSet.INTERVENTION_PASSWORD })
        assertEquals(listOf("Alpha", "Beta"), steps.map { it.blockSetName })
        assertFalse(steps.any { it.mode == BlockSet.INTERVENTION_RANDOM })
    }

    @Test
    fun consumesInterventionBypassAndTracksApp() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 60,
            intervention = BlockSet.INTERVENTION_RANDOM
        )
        storage.saveBlockSets(listOf(blockSet))
        storage.grantInterventionBypass("com.example.blocked")

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))

        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertNull(intent)
        assertEquals("com.example.blocked", getPrivateField(service, "currentTrackedPackage"))
    }

    @Test
    fun interventionAuthorizationSurvivesTransientStopTracking() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 60,
            intervention = BlockSet.INTERVENTION_RANDOM
        )
        storage.saveBlockSets(listOf(blockSet))
        storage.grantInterventionBypass("com.example.blocked")

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))
        assertNull(Shadows.shadowOf(app).nextStartedActivity)

        val stopTracking = AppBlockerService::class.java.getDeclaredMethod("stopTracking")
        stopTracking.isAccessible = true
        stopTracking.invoke(service)

        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }

    @Test
    fun interventionGraceSuppressesRetriggerWithinFiveSeconds() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 60,
            intervention = BlockSet.INTERVENTION_RANDOM
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))
        assertEquals(1, drainStartedActivitiesCount())

        setPrivateField(service, "interventionGraceUntilMs", System.currentTimeMillis() + 4_000L)
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))

        assertEquals(0, drainStartedActivitiesCount())
        assertEquals("com.example.blocked", getPrivateField(service, "currentTrackedPackage"))
    }

    @Test
    fun interventionGraceExpiresAfterFiveSeconds() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 60,
            intervention = BlockSet.INTERVENTION_RANDOM
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))
        assertEquals(1, drainStartedActivitiesCount())

        setPrivateField(service, "interventionGraceUntilMs", System.currentTimeMillis() - 1L)
        setPrivateField(service, "lastBlockedScreenLaunchMs", 0L)
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))

        assertEquals(1, drainStartedActivitiesCount())
        assertNull(getPrivateField(service, "currentTrackedPackage"))
    }

    // ==================== Overlay Tracking Tests ====================

    @Test
    fun startsTrackingWhenEnteringBlockedApp() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
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
            quotaMinutes = 30.0,
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
            quotaMinutes = 30.0,
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
            quotaMinutes = 30.0,
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

    // ==================== Snapchat Detection Tests ====================

    @Test
    fun resolveInAppBrowserPackageReturnsNullForRealBrowserPackage() {
        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        val resolved = resolveInAppBrowserPackage(
            service,
            "org.mozilla.firefox",
            "org.mozilla.fenix.HomeActivity"
        )

        assertNull(resolved)
    }

    @Test
    fun maintainsTrackingWhenSamsungOverlayAppears() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Enter blocked app
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        // Samsung system overlay
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.samsung.android.app.cocktailbarservice"))

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?
        val pendingStopRunnable = getPrivateField(service, "pendingStopRunnable")

        // Should still be tracking the blocked app
        assertEquals("com.instagram.android", trackedPackage)
        assertNull(pendingStopRunnable)
    }

    @Test
    fun maintainsTrackingWhenXiaomiSecurityCenterAppears() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.miui.securitycenter"))

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?
        val pendingStopRunnable = getPrivateField(service, "pendingStopRunnable")

        assertEquals("com.instagram.android", trackedPackage)
        assertNull(pendingStopRunnable)
    }

    @Test
    fun maintainsTrackingWhenSidebarOverlayAppears() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.coloros.smartsidebar"))

        val trackedPackage = getPrivateField(service, "currentTrackedPackage") as String?
        val pendingStopRunnable = getPrivateField(service, "pendingStopRunnable")

        assertEquals("com.instagram.android", trackedPackage)
        assertNull(pendingStopRunnable)
    }

    @Test
    fun stopsTrackingImmediatelyWhenLauncherAppears() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
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
            quotaMinutes = 30.0,
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
            quotaMinutes = 30.0,
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
            quotaMinutes = 30.0,
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
                quotaMinutes = 30.0,
                windowMinutes = 60
            ),
            BlockSet(
                name = "Video",
                apps = mutableListOf("com.tiktok.android"),
                quotaMinutes = 15.0,
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
            quotaMinutes = 30.0,
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
                quotaMinutes = 30.0,
                windowMinutes = 60
            ),
            BlockSet(
                name = "Video",
                apps = mutableListOf("com.tiktok.android"),
                quotaMinutes = 15.0,
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
            quotaMinutes = 30.0,
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
    fun localQuotaExpiryDoesNotRelaunchBlockScreenWhileOverrideIsActive() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 1.0,
            windowMinutes = 60,
            allowOverride = true
        )
        storage.saveBlockSets(listOf(blockSet))
        storage.setOverrideMinutes(blockSet.id, 10)

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))
        drainStartedActivitiesCount()

        setPrivateField(service, "sessionStartTimeMs", System.currentTimeMillis() - 61_000L)
        setPrivateField(service, "initialRemainingSeconds", 60)

        val runnable = getPrivateField(service, "overlayUpdateRunnable") as Runnable
        runnable.run()

        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertEquals(
            "com.example.blocked",
            getPrivateField(service, "currentTrackedPackage") as String?
        )
    }

    @Test
    fun ignoresNonWindowStateChangedEvents() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Send a different event type
        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
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
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()

        // Send event with null package name
        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
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
            quotaMinutes = 30.0,
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

    @Test
    fun screenOffStopsTrackingAndRemovesOverlay() {
        val blockSet = BlockSet(
            name = "Social",
            apps = mutableListOf("com.instagram.android"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.instagram.android"))

        val trackedBefore = getPrivateField(service, "currentTrackedPackage") as String?
        assertEquals("com.instagram.android", trackedBefore)

        val screenStateTracker = getPrivateField(service, "screenStateTracker")
        val receiver = screenStateTracker?.let { getNestedPrivateField(it, "receiver") }
            as android.content.BroadcastReceiver
        receiver.onReceive(app, Intent(Intent.ACTION_SCREEN_OFF))

        val trackedAfter = getPrivateField(service, "currentTrackedPackage") as String?
        val overlayController = getPrivateField(service, "overlayController")
        val overlayView = overlayController?.let { getNestedPrivateField(it, "overlayView") }
        val overlayRunnable = getPrivateField(service, "overlayUpdateRunnable")

        assertNull(trackedAfter)
        assertNull(overlayView)
        assertNull(overlayRunnable)
    }

    @Test
    fun resetsLocalTimerAtWindowBoundary() {
        val blockSet = BlockSet(
            name = "Focus",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val service = Robolectric.buildService(AppBlockerService::class.java).create().get()
        service.onAccessibilityEvent(createWindowStateChangedEvent("com.example.blocked"))

        val runnable = getPrivateField(service, "overlayUpdateRunnable") as Runnable?
        assertNotNull(runnable)

        setPrivateField(service, "initialRemainingSeconds", 1)
        setPrivateField(service, "currentWindowEndMs", System.currentTimeMillis() - 1)

        runnable?.run()

        val refreshedRemaining = getPrivateField(service, "initialRemainingSeconds") as Int
        val refreshedWindowEnd = getPrivateField(service, "currentWindowEndMs") as Long

        assertEquals(storage.getRemainingSeconds(blockSet), refreshedRemaining)
        assertTrue(refreshedWindowEnd >= System.currentTimeMillis())
    }

}
