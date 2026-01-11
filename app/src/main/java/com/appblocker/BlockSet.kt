package com.appblocker

import java.util.UUID

data class BlockSet(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var apps: MutableList<String> = mutableListOf(), // package names
    var quotaMinutes: Int = 30,
    var windowMinutes: Int = 60, // clock-aligned window: 5, 10, 15, 20, 30, or 60 minutes
    var combinedQuota: Boolean = true // if true, all apps share the quota
)
