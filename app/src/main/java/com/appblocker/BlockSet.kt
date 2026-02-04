package com.appblocker

import java.util.UUID

data class BlockSet(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var apps: MutableList<String> = mutableListOf(), // package names
    var quotaMinutes: Double = 30.0,
    var windowMinutes: Int = 60, // clock-aligned window: 5, 10, 15, 20, 30, or 60 minutes
    var combinedQuota: Boolean = true, // if true, all apps share the quota
    var allowOverride: Boolean = false,
    var intervention: Int = INTERVENTION_NONE
) {
    companion object {
        const val INTERVENTION_NONE = 0
        const val INTERVENTION_RANDOM_32 = 1
        const val INTERVENTION_RANDOM_64 = 2
        const val INTERVENTION_RANDOM_128 = 3
    }
}
