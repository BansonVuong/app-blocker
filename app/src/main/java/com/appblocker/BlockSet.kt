package com.appblocker

import java.util.Calendar
import java.util.UUID

data class TimePeriod(
    val id: String = UUID.randomUUID().toString(),
    var days: MutableList<Int> = mutableListOf(), // Calendar.SUNDAY(1) through Calendar.SATURDAY(7)
    var startHour: Int = 0,
    var startMinute: Int = 0,
    var endHour: Int = 23,
    var endMinute: Int = 59
) {
    fun isActiveNow(calendar: Calendar = Calendar.getInstance()): Boolean {
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        if (!days.contains(dayOfWeek)) return false

        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute

        return nowMinutes in startMinutes..endMinutes
    }

    fun formatDays(): String {
        val dayNames = mapOf(
            Calendar.SUNDAY to "Sun",
            Calendar.MONDAY to "Mon",
            Calendar.TUESDAY to "Tue",
            Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu",
            Calendar.FRIDAY to "Fri",
            Calendar.SATURDAY to "Sat"
        )
        if (days.size == 7) return "Every day"
        val weekdays = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        val weekends = listOf(Calendar.SATURDAY, Calendar.SUNDAY)
        if (days.sorted() == weekdays.sorted()) return "Weekdays"
        if (days.sorted() == weekends.sorted()) return "Weekends"
        return days.sortedBy { if (it == Calendar.SUNDAY) 8 else it }
            .mapNotNull { dayNames[it] }
            .joinToString(", ")
    }

    fun formatTime(): String {
        return "${formatTimeValue(startHour, startMinute)} – ${formatTimeValue(endHour, endMinute)}"
    }

    private fun formatTimeValue(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format("%d:%02d %s", displayHour, minute, amPm)
    }
}

data class BlockSet(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var apps: MutableList<String> = mutableListOf(), // package names
    var quotaMinutes: Double = 30.0,
    var windowMinutes: Int = 60, // clock-aligned window: 5, 10, 15, 20, 30, or 60 minutes
    var combinedQuota: Boolean = true, // if true, all apps share the quota
    var allowOverride: Boolean = false,
    var intervention: Int = INTERVENTION_NONE,
    var interventionCodeLength: Int = 32,
    var interventionPassword: String = "",
    var scheduleEnabled: Boolean = false,
    var timePeriods: MutableList<TimePeriod> = mutableListOf()
) {
    fun isScheduleActive(calendar: Calendar = Calendar.getInstance()): Boolean {
        if (!scheduleEnabled) return true
        if (timePeriods.isEmpty()) return true
        return timePeriods.any { it.isActiveNow(calendar) }
    }

    companion object {
        const val INTERVENTION_NONE = 0
        const val INTERVENTION_RANDOM = 1
        const val INTERVENTION_PASSWORD = 4
    }
}
