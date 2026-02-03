package com.appblocker

object AppTargets {
    const val SNAPCHAT_PACKAGE = "com.snapchat.android"
    const val SNAPCHAT_STORIES = "$SNAPCHAT_PACKAGE:stories"
    const val SNAPCHAT_SPOTLIGHT = "$SNAPCHAT_PACKAGE:spotlight"
    const val INSTAGRAM_PACKAGE = "com.instagram.android"
    const val INSTAGRAM_REELS = "$INSTAGRAM_PACKAGE:reels"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val YOUTUBE_SHORTS = "$YOUTUBE_PACKAGE:shorts"

    data class BrowserIncognitoTarget(
        val parentPackage: String,
        val virtualPackage: String,
        val label: String
    )

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

    private val browserIncognitoTargets = mapOf(
        "com.android.chrome" to BrowserIncognitoTarget(
            parentPackage = "com.android.chrome",
            virtualPackage = "com.android.chrome:incognito",
            label = "Incognito tabs"
        ),
        "com.google.android.apps.chrome" to BrowserIncognitoTarget(
            parentPackage = "com.google.android.apps.chrome",
            virtualPackage = "com.google.android.apps.chrome:incognito",
            label = "Incognito tabs"
        ),
        "com.chrome.beta" to BrowserIncognitoTarget(
            parentPackage = "com.chrome.beta",
            virtualPackage = "com.chrome.beta:incognito",
            label = "Incognito tabs"
        ),
        "com.chrome.dev" to BrowserIncognitoTarget(
            parentPackage = "com.chrome.dev",
            virtualPackage = "com.chrome.dev:incognito",
            label = "Incognito tabs"
        ),
        "com.chrome.canary" to BrowserIncognitoTarget(
            parentPackage = "com.chrome.canary",
            virtualPackage = "com.chrome.canary:incognito",
            label = "Incognito tabs"
        ),
        "org.mozilla.firefox" to BrowserIncognitoTarget(
            parentPackage = "org.mozilla.firefox",
            virtualPackage = "org.mozilla.firefox:private",
            label = "Private tabs"
        ),
        "org.mozilla.firefox_beta" to BrowserIncognitoTarget(
            parentPackage = "org.mozilla.firefox_beta",
            virtualPackage = "org.mozilla.firefox_beta:private",
            label = "Private tabs"
        ),
        "org.mozilla.fenix" to BrowserIncognitoTarget(
            parentPackage = "org.mozilla.fenix",
            virtualPackage = "org.mozilla.fenix:private",
            label = "Private tabs"
        ),
        "com.brave.browser" to BrowserIncognitoTarget(
            parentPackage = "com.brave.browser",
            virtualPackage = "com.brave.browser:private",
            label = "Private tabs"
        ),
        "com.microsoft.emmx" to BrowserIncognitoTarget(
            parentPackage = "com.microsoft.emmx",
            virtualPackage = "com.microsoft.emmx:inprivate",
            label = "InPrivate tabs"
        ),
        "com.opera.browser" to BrowserIncognitoTarget(
            parentPackage = "com.opera.browser",
            virtualPackage = "com.opera.browser:private",
            label = "Private tabs"
        ),
        "com.opera.mini.native" to BrowserIncognitoTarget(
            parentPackage = "com.opera.mini.native",
            virtualPackage = "com.opera.mini.native:private",
            label = "Private tabs"
        ),
        "com.sec.android.app.sbrowser" to BrowserIncognitoTarget(
            parentPackage = "com.sec.android.app.sbrowser",
            virtualPackage = "com.sec.android.app.sbrowser:secret",
            label = "Secret mode"
        )
    )

    val snapchatVirtualPackages = setOf(SNAPCHAT_STORIES, SNAPCHAT_SPOTLIGHT)
    val instagramVirtualPackages = setOf(INSTAGRAM_REELS)
    val youtubeVirtualPackages = setOf(YOUTUBE_SHORTS)
    val browserIncognitoVirtualPackages = browserIncognitoTargets.values
        .map { it.virtualPackage }
        .toSet()
    private val virtualToParent = mapOf(
        SNAPCHAT_STORIES to SNAPCHAT_PACKAGE,
        SNAPCHAT_SPOTLIGHT to SNAPCHAT_PACKAGE,
        INSTAGRAM_REELS to INSTAGRAM_PACKAGE,
        YOUTUBE_SHORTS to YOUTUBE_PACKAGE
    ) + browserIncognitoTargets.values.associate { it.virtualPackage to it.parentPackage }

    fun getBrowserIncognitoTarget(packageName: String): BrowserIncognitoTarget? {
        return browserIncognitoTargets[packageName]
    }

    fun isVirtualPackage(packageName: String): Boolean {
        return packageName in virtualToParent
    }

    fun getParentPackage(packageName: String): String? {
        return virtualToParent[packageName]
    }
}
