package com.appblocker

object AppTargets {
    const val SNAPCHAT_PACKAGE = "com.snapchat.android"
    const val SNAPCHAT_STORIES = "$SNAPCHAT_PACKAGE:stories"
    const val SNAPCHAT_SPOTLIGHT = "$SNAPCHAT_PACKAGE:spotlight"

    val snapchatVirtualPackages = setOf(SNAPCHAT_STORIES, SNAPCHAT_SPOTLIGHT)

    fun isVirtualPackage(packageName: String): Boolean {
        return packageName in snapchatVirtualPackages
    }
}
