package com.appblocker

import android.view.accessibility.AccessibilityNodeInfo

class AppBlockerPackageResolver(
    private val storage: Storage,
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val snapchatDetector: SnapchatDetector,
    private val instagramDetector: InstagramDetector,
    private val youtubeDetector: YouTubeDetector,
    private val browserIncognitoDetector: BrowserIncognitoDetector
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

        val browserPackage = resolveBrowserIncognitoPackage(packageName, eventType, nowMs)
        if (browserPackage != packageName) return browserPackage

        if (packageName == AppTargets.SNAPCHAT_PACKAGE) {
            if (storage.getBlockSetForApp(AppTargets.SNAPCHAT_PACKAGE) != null) {
                return AppTargets.SNAPCHAT_PACKAGE
            }
            val hasStoriesBlock = storage.getBlockSetForApp(AppTargets.SNAPCHAT_STORIES) != null
            val hasSpotlightBlock = storage.getBlockSetForApp(AppTargets.SNAPCHAT_SPOTLIGHT) != null
            if (!hasStoriesBlock && !hasSpotlightBlock) {
                return AppTargets.SNAPCHAT_PACKAGE
            }

            val tab = snapchatDetector.detect(eventType, nowMs, rootProvider())
            return when (tab) {
                SnapchatTab.STORIES ->
                    if (hasStoriesBlock) AppTargets.SNAPCHAT_STORIES else AppTargets.SNAPCHAT_PACKAGE
                SnapchatTab.SPOTLIGHT ->
                    if (hasSpotlightBlock) AppTargets.SNAPCHAT_SPOTLIGHT else AppTargets.SNAPCHAT_PACKAGE
                else -> AppTargets.SNAPCHAT_PACKAGE
            }
        }

        if (packageName == AppTargets.INSTAGRAM_PACKAGE) {
            return resolveInstagramPackage(packageName, eventType, nowMs)
        }

        if (packageName == AppTargets.YOUTUBE_PACKAGE) {
            return resolveYouTubePackage(packageName, eventType, nowMs)
        }

        return packageName
    }

    fun resolveInAppBrowserPackage(packageName: String, className: String?): String? {
        if (packageName in inAppBrowserIgnorePackages) return null
        if (isBrowserPackage(packageName)) return null
        if (!isInAppBrowserClass(className)) return null
        return getBrowserPackageForInAppBrowser(className ?: "")
    }

    private fun resolveInstagramPackage(
        packageName: String,
        eventType: Int,
        nowMs: Long
    ): String {
        if (packageName != AppTargets.INSTAGRAM_PACKAGE) return packageName
        if (storage.getBlockSetForApp(AppTargets.INSTAGRAM_PACKAGE) != null) {
            return AppTargets.INSTAGRAM_PACKAGE
        }

        val hasReelsBlock = storage.getBlockSetForApp(AppTargets.INSTAGRAM_REELS) != null
        if (!hasReelsBlock) {
            return AppTargets.INSTAGRAM_PACKAGE
        }

        val tab = instagramDetector.detect(eventType, nowMs, rootProvider())
        return if (tab == InstagramTab.REELS) {
            AppTargets.INSTAGRAM_REELS
        } else {
            AppTargets.INSTAGRAM_PACKAGE
        }
    }

    private fun resolveYouTubePackage(
        packageName: String,
        eventType: Int,
        nowMs: Long
    ): String {
        if (packageName != AppTargets.YOUTUBE_PACKAGE) return packageName
        if (storage.getBlockSetForApp(AppTargets.YOUTUBE_PACKAGE) != null) {
            return AppTargets.YOUTUBE_PACKAGE
        }

        val hasShortsBlock = storage.getBlockSetForApp(AppTargets.YOUTUBE_SHORTS) != null
        if (!hasShortsBlock) {
            return AppTargets.YOUTUBE_PACKAGE
        }

        val tab = youtubeDetector.detect(eventType, nowMs, rootProvider())
        return if (tab == YouTubeTab.SHORTS) {
            AppTargets.YOUTUBE_SHORTS
        } else {
            AppTargets.YOUTUBE_PACKAGE
        }
    }

    private fun resolveBrowserIncognitoPackage(
        packageName: String,
        eventType: Int,
        nowMs: Long
    ): String {
        if (!isBrowserPackage(packageName)) return packageName
        if (storage.getBlockSetForApp(packageName) != null) return packageName

        val incognitoTarget = AppTargets.getBrowserIncognitoTarget(packageName) ?: return packageName
        if (storage.getBlockSetForApp(incognitoTarget.virtualPackage) == null) return packageName

        val isIncognito = browserIncognitoDetector.detect(eventType, nowMs, rootProvider())
        return if (isIncognito) incognitoTarget.virtualPackage else packageName
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
