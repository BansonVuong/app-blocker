package com.appblocker

import android.view.accessibility.AccessibilityNodeInfo

class AppBlockerPackageResolver(
    private val storage: Storage,
    private val rootProvider: () -> AccessibilityNodeInfo?
) {
    private val inAppBrowserIgnorePackages = setOf(
        "com.google.chromeremotedesktop"
    )
    private val rootFallbackPackages = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.miui.securitycenter",
        "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.app.taskedge",
        "com.coloros.smartsidebar",
        "com.oplus.sidebar",
        "com.vivo.upslide"
    )

    fun resolveEffectivePackageName(
        packageName: String,
        className: String?,
        eventType: Int,
        nowMs: Long
    ): String {
        val inAppBrowserPackage = resolveInAppBrowserPackage(packageName, className)
        if (inAppBrowserPackage != null) return inAppBrowserPackage

        resolveRootPackage(packageName)?.let { return it }

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

    private fun resolveRootPackage(eventPackageName: String): String? {
        if (!shouldUseRootPackageFallback(eventPackageName)) return null
        val rootPackageName = rootProvider()?.packageName?.toString()?.trim().orEmpty()
        if (rootPackageName.isEmpty()) return null
        if (rootPackageName == eventPackageName) return null
        if (rootPackageName == "com.appblocker") return null
        if (shouldUseRootPackageFallback(rootPackageName)) return null
        return rootPackageName
    }

    private fun shouldUseRootPackageFallback(packageName: String): Boolean {
        if (packageName in AppTargets.browserPackages) return false
        return packageName in rootFallbackPackages ||
            packageName.startsWith("com.google.android.inputmethod") ||
            packageName.contains("securitycenter") ||
            packageName.contains("keyboard") ||
            packageName.contains("overlay") ||
            packageName.contains("launcher") ||
            packageName.contains("sidebar") ||
            packageName.contains("cocktailbar") ||
            packageName.contains("taskedge") ||
            packageName.contains("edgepanel") ||
            packageName.contains("floating") ||
            packageName.contains("popup") ||
            packageName.contains("freeform")
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
