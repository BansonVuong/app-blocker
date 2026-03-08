package com.appblocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BlockSetTest {

    @Test
    fun daytimePeriodMatchesOnlyWithinRangeOnSelectedDay() {
        val period = TimePeriod(
            days = mutableListOf(Calendar.MONDAY),
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0
        )

        assertTrue(period.isActiveNow(calendarFor(Calendar.MONDAY, 10, 0)))
        assertFalse(period.isActiveNow(calendarFor(Calendar.MONDAY, 8, 59)))
        assertFalse(period.isActiveNow(calendarFor(Calendar.TUESDAY, 10, 0)))
    }

    @Test
    fun overnightPeriodMatchesLateOnSelectedDayAndEarlyNextDay() {
        val period = TimePeriod(
            days = mutableListOf(Calendar.MONDAY),
            startHour = 23,
            startMinute = 0,
            endHour = 6,
            endMinute = 0
        )

        assertTrue(period.isActiveNow(calendarFor(Calendar.MONDAY, 23, 30)))
        assertTrue(period.isActiveNow(calendarFor(Calendar.TUESDAY, 1, 0)))
        assertFalse(period.isActiveNow(calendarFor(Calendar.MONDAY, 22, 59)))
        assertFalse(period.isActiveNow(calendarFor(Calendar.TUESDAY, 6, 1)))
    }

    @Test
    fun overnightPeriodSupportsSundayToMondayBoundary() {
        val period = TimePeriod(
            days = mutableListOf(Calendar.SUNDAY),
            startHour = 23,
            startMinute = 0,
            endHour = 6,
            endMinute = 0
        )

        assertTrue(period.isActiveNow(calendarFor(Calendar.MONDAY, 2, 0)))
        assertFalse(period.isActiveNow(calendarFor(Calendar.TUESDAY, 2, 0)))
    }

    private fun calendarFor(dayOfWeek: Int, hour: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
