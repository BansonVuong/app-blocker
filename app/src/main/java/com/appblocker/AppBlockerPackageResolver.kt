package com.appblocker

import android.view.accessibility.AccessibilityNodeInfo

class AppBlockerPackageResolver(
    private val storage: Storage,
    private val rootProvider: () -> AccessibilityNodeInfo?
) {
    private val inAppBrowserIgnorePackages = setOf(
        "com.google.chromeremotedesktop"
    )

    fun resolveEffectivePackageName(
        packageName: String,
        className: String?,
        eventType: Int,
        nowMs: Long
    ): String {
        val inAppBrowserPackage = resolveInAppBrowserPackage(packageName, className)
        if (inAppBrowserPackage != null) return inAppBrowserPackage

        return packageName
    }

    fun resolveInAppBrowserPackage(packageName: String, className: String?): String? {
        if (packageName in inAppBrowserIgnorePackages) return null
        if (isBrowserPackage(packageName)) return null
        if (!isInAppBrowserClass(className)) return null
        return getBrowserPackageForInAppBrowser(className ?: "")
    }

    private fun isBrowserPackage(packageName: String): Boolean {
        return packageName in AppTargets.browserPackages
    }

    private fun getBrowserPackageForInAppBrowser(className: String): String? {
        val normalized = className.lowercase()
        return when {
            normalized.contains("chrome") -> "com.android.chrome"
            normalized.contains("firefox") -> "org.mozilla.firefox"
            normalized.contains("brave") -> "com.brave.browser"
            normalized.contains("edge") || normalized.contains("emmx") -> "com.microsoft.emmx"
            normalized.contains("opera") -> "com.opera.browser"
            normalized.contains("samsung") || normalized.contains("sbrowser") -> "com.sec.android.app.sbrowser"
            else -> null
        }
    }

    private fun isInAppBrowserClass(className: String?): Boolean {
        if (className == null) return false
        val normalized = className.lowercase()
        val indicators = listOf(
            "customtab",
            "customtabs",
            "browser",
            "webview",
            "webactivity",
            "inappbrowser"
        )
        return indicators.any { normalized.contains(it) }
    }
}
