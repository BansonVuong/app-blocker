package com.appblocker

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val usageSecondsLastWeek: Int
)
