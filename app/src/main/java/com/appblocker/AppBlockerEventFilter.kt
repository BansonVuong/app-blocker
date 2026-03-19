package com.appblocker

import android.view.accessibility.AccessibilityEvent

class AppBlockerEventFilter {
    private val overlayPackages = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.miui.securitycenter",
        "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.app.taskedge",
        "com.coloros.smartsidebar",
        "com.oplus.sidebar",
        "com.vivo.upslide"
    )

    fun shouldHandleAccessibilityEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
    }

    fun isLikelyOverlayPackage(packageName: String): Boolean {
        if (packageName in AppTargets.browserPackages) return false

        return packageName in overlayPackages ||
            packageName.startsWith("com.google.android.inputmethod") ||
            packageName.contains("securitycenter") ||
            packageName.contains("keyboard") ||
            packageName.contains("overlay") ||
            packageName.contains("sidebar") ||
            packageName.contains("cocktailbar") ||
            packageName.contains("taskedge") ||
            packageName.contains("edgepanel") ||
            packageName.contains("floating") ||
            packageName.contains("popup") ||
            packageName.contains("freeform")
    }

    fun isSameAppFamily(first: String?, second: String): Boolean {
        val firstPackage = first ?: return false
        return canonicalAppPackage(firstPackage) == canonicalAppPackage(second)
    }

    private fun canonicalAppPackage(packageName: String): String {
        AppTargets.getParentPackage(packageName)?.let { return it }
        return packageName.substringBefore(":")
    }
}
