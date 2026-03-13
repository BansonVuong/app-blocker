package com.appblocker

object AppTargets {
    val browserPackages = setOf(
        "com.android.chrome",
        "com.google.android.apps.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.sec.android.app.sbrowser"
    )

    val removedTrackingPackages = setOf(
        "com.snapchat.android:stories",
        "com.snapchat.android:spotlight",
        "com.instagram.android:reels",
        "com.google.android.youtube:shorts",
        "com.android.chrome:incognito",
        "com.google.android.apps.chrome:incognito",
        "com.chrome.beta:incognito",
        "com.chrome.dev:incognito",
        "com.chrome.canary:incognito",
        "org.mozilla.firefox:private",
        "org.mozilla.firefox_beta:private",
        "org.mozilla.fenix:private",
        "com.brave.browser:private",
        "com.microsoft.emmx:inprivate",
        "com.opera.browser:private",
        "com.opera.mini.native:private",
        "com.sec.android.app.sbrowser:secret"
    )

    private val virtualToParent = emptyMap<String, String>()

    fun isVirtualPackage(packageName: String): Boolean {
        return packageName in virtualToParent
    }

    fun getParentPackage(packageName: String): String? {
        return virtualToParent[packageName]
    }
}
