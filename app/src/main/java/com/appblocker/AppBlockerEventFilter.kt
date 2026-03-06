package com.appblocker

import android.view.accessibility.AccessibilityEvent

class AppBlockerEventFilter {
    fun shouldHandleAccessibilityEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
    }

    fun isLikelyOverlayPackage(packageName: String): Boolean {
        if (packageName in AppTargets.browserPackages) return false

        return packageName == "com.android.systemui" ||
            packageName == "com.android.permissioncontroller" ||
            packageName == "com.google.android.permissioncontroller" ||
            packageName.startsWith("com.google.android.inputmethod") ||
            packageName.contains("keyboard") ||
            packageName.contains("overlay")
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
