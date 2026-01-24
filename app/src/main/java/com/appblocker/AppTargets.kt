package com.appblocker

object AppTargets {
    const val SNAPCHAT_PACKAGE = "com.snapchat.android"
    const val SNAPCHAT_STORIES = "$SNAPCHAT_PACKAGE:stories"
    const val SNAPCHAT_SPOTLIGHT = "$SNAPCHAT_PACKAGE:spotlight"
    const val INSTAGRAM_PACKAGE = "com.instagram.android"
    const val INSTAGRAM_REELS = "$INSTAGRAM_PACKAGE:reels"

    val snapchatVirtualPackages = setOf(SNAPCHAT_STORIES, SNAPCHAT_SPOTLIGHT)
    val instagramVirtualPackages = setOf(INSTAGRAM_REELS)
    private val virtualToParent = mapOf(
        SNAPCHAT_STORIES to SNAPCHAT_PACKAGE,
        SNAPCHAT_SPOTLIGHT to SNAPCHAT_PACKAGE,
        INSTAGRAM_REELS to INSTAGRAM_PACKAGE
    )

    fun isVirtualPackage(packageName: String): Boolean {
        return packageName in virtualToParent
    }

    fun getParentPackage(packageName: String): String? {
        return virtualToParent[packageName]
    }
}
