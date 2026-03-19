package com.appblocker

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class AppBlockerPackageResolverTest {
    private lateinit var storage: Storage

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("app_blocker", Application.MODE_PRIVATE)
        prefs.edit().clear().commit()
        storage = (app as App).storage
    }

    @Test
    fun resolvesXiaomiLauncherEventToRootPackage() {
        val resolver = AppBlockerPackageResolver(
            storage = storage,
            rootProvider = {
                AccessibilityNodeInfo.obtain().apply {
                    packageName = "com.instagram.android"
                }
            }
        )

        val resolved = resolver.resolveEffectivePackageName(
            packageName = "com.miui.home",
            className = "android.widget.FrameLayout",
            eventType = 0,
            nowMs = 0L
        )

        assertEquals("com.instagram.android", resolved)
    }

    @Test
    fun resolvesXiaomiSecurityCenterEventToRootPackage() {
        val resolver = AppBlockerPackageResolver(
            storage = storage,
            rootProvider = {
                AccessibilityNodeInfo.obtain().apply {
                    packageName = "com.zhiliaoapp.musically"
                }
            }
        )

        val resolved = resolver.resolveEffectivePackageName(
            packageName = "com.miui.securitycenter",
            className = "android.widget.FrameLayout",
            eventType = 0,
            nowMs = 0L
        )

        assertEquals("com.zhiliaoapp.musically", resolved)
    }

    @Test
    fun doesNotUseFallbackWhenRootIsAnotherLauncher() {
        val resolver = AppBlockerPackageResolver(
            storage = storage,
            rootProvider = {
                AccessibilityNodeInfo.obtain().apply {
                    packageName = "com.miui.home"
                }
            }
        )

        val resolved = resolver.resolveEffectivePackageName(
            packageName = "com.teslacoilsw.launcher",
            className = "android.widget.FrameLayout",
            eventType = 0,
            nowMs = 0L
        )

        assertEquals("com.teslacoilsw.launcher", resolved)
    }

    @Test
    fun returnsNullForUnknownInAppBrowserClass() {
        val resolver = AppBlockerPackageResolver(
            storage = storage,
            rootProvider = { null }
        )

        val resolved = resolver.resolveInAppBrowserPackage(
            packageName = "com.example.app",
            className = "com.example.app.MainActivity"
        )

        assertNull(resolved)
    }
}
