package org.fossify.calendar.models

import androidx.collection.LongSparseArray
import org.fossify.calendar.extensions.isTsOnProperDay
import org.fossify.calendar.extensions.isXWeeklyRepetition
import org.fossify.calendar.extensions.rotateWeekdayBits
import org.fossify.calendar.helpers.DAY
import org.fossify.calendar.helpers.FLAG_ALL_DAY
import org.fossify.calendar.helpers.MONTH
import org.fossify.calendar.helpers.Parser
import org.fossify.calendar.helpers.REPEAT_ORDER_WEEKDAY
import org.fossify.calendar.helpers.REPEAT_SAME_DAY
import org.fossify.calendar.helpers.WEEK
import org.fossify.commons.helpers.EVERY_DAY_BIT
import org.fossify.commons.helpers.MONDAY_BIT
import org.fossify.commons.helpers.SUNDAY_BIT
import org.fossify.commons.helpers.TUESDAY_BIT
import org.joda.time.DateTime
import org.joda.time.DateTimeConstants
import org.joda.time.DateTimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class EventRecurrenceTimeZoneTest {
    private val brussels = DateTimeZone.forID("Europe/Brussels")
    private val riyadh = DateTimeZone.forID("Asia/Riyadh")
    private lateinit var originalDefault: DateTimeZone

    @Before
    fun setDeviceZone() {
        originalDefault = DateTimeZone.getDefault()
        DateTimeZone.setDefault(riyadh)
    }

    @After
    fun restoreDeviceZone() = DateTimeZone.setDefault(originalDefault)

    private fun seconds(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: DateTimeZone = brussels) =
        DateTime(y, mo, d, h, mi, zone).millis / 1000L

    private fun series(startTS: Long, interval: Int, rule: Int = 0, zone: String = brussels.id) = Event(
        id = 1L,
        startTS = startTS,
        endTS = startTS + 3600,
        repeatInterval = interval,
        repeatRule = rule,
        timeZone = zone,
    )

    /** Mirrors the stepping and gating done by EventsHelper when it expands a series. */
    private fun occurrences(event: Event, count: Int): List<DateTime> {
        val startTimes = LongSparseArray<Long>().apply { put(event.id!!, event.startTS) }
        val original = event.copy()
        val result = ArrayList<DateTime>()
        var guard = 0
        while (result.size < count && guard++ < 2000) {
            val weekly = event.repeatInterval.isXWeeklyRepetition()
            if (!weekly || (event.startTS.isTsOnProperDay(event) && event.isOnProperWeek(startTimes))) {
                result.add(DateTime(event.startTS * 1000L, brussels))
            }
            event.addIntervalTime(original)
        }
        return result
    }

    @Test
    fun `weekly series keeps its wall clock across the autumn DST change`() {
        val event = series(seconds(2026, 10, 13, 9, 0), WEEK, TUESDAY_BIT)
        val result = occurrences(event, 4)
        assertEquals(listOf(13, 20, 27, 3), result.map { it.dayOfMonth })
        assertEquals(listOf(9, 9, 9, 9), result.map { it.hourOfDay })
    }

    @Test
    fun `weekly series keeps its wall clock across the spring DST change`() {
        val event = series(seconds(2027, 3, 23, 9, 0), WEEK, TUESDAY_BIT)
        assertEquals(listOf(9, 9, 9), occurrences(event, 3).map { it.hourOfDay })
    }

    @Test
    fun `late evening weekly series stays on its own weekday`() {
        val event = series(seconds(2026, 10, 18, 23, 30), WEEK, SUNDAY_BIT)
        val result = occurrences(event, 4)
        assertEquals(listOf(18, 25, 1, 8), result.map { it.dayOfMonth })
        result.forEach {
            assertEquals(DateTimeConstants.SUNDAY, it.dayOfWeek)
            assertEquals(23, it.hourOfDay)
            assertEquals(30, it.minuteOfHour)
        }
    }

    @Test
    fun `biweekly late evening series stays on the proper week`() {
        val event = series(seconds(2026, 10, 18, 23, 30), 2 * WEEK, SUNDAY_BIT)
        assertEquals(listOf(18, 1, 15), occurrences(event, 3).map { it.dayOfMonth })
    }

    @Test
    fun `series repeating every two days keeps its wall clock`() {
        val event = series(seconds(2026, 10, 23, 9, 0), 2 * DAY)
        val result = occurrences(event, 4)
        assertEquals(listOf(23, 25, 27, 29), result.map { it.dayOfMonth })
        assertEquals(listOf(9, 9, 9, 9), result.map { it.hourOfDay })
    }

    @Test
    fun `occurrence falling in the DST gap does not shift later occurrences`() {
        val event = series(seconds(2027, 3, 21, 2, 30), WEEK, SUNDAY_BIT)
        val result = occurrences(event, 3)
        assertEquals(listOf(21, 28, 4), result.map { it.dayOfMonth })
        assertEquals(listOf(2, 3, 2), result.map { it.hourOfDay })
        assertEquals(listOf(30, 30, 30), result.map { it.minuteOfHour })
    }

    @Test
    fun `series starting before autumn keeps its wall clock through repeated and skipped hours`() {
        val event = series(seconds(2026, 10, 18, 2, 30), WEEK, SUNDAY_BIT)
        val result = occurrences(event, 25)

        val repeatedHour = result[1]
        assertEquals(25, repeatedHour.dayOfMonth)
        assertEquals(2, repeatedHour.hourOfDay)
        assertEquals(2 * 3600 * 1000, brussels.getOffset(repeatedHour))

        val missingHour = result[23]
        assertEquals(3, missingHour.monthOfYear)
        assertEquals(28, missingHour.dayOfMonth)
        assertEquals(3, missingHour.hourOfDay)
        assertEquals(30, missingHour.minuteOfHour)

        assertEquals(2, result[24].hourOfDay)
    }

    @Test
    fun `monthly same day series uses the date of the event zone`() {
        val event = series(seconds(2026, 8, 31, 23, 30), MONTH, REPEAT_SAME_DAY)
        val result = occurrences(event, 3)
        assertEquals(listOf(8, 10, 12), result.map { it.monthOfYear })
        assertEquals(listOf(31, 31, 31), result.map { it.dayOfMonth })
        assertEquals(listOf(23, 23, 23), result.map { it.hourOfDay })
    }

    @Test
    fun `monthly order weekday series uses the weekday of the event zone`() {
        val event = series(seconds(2026, 9, 20, 23, 30), MONTH, REPEAT_ORDER_WEEKDAY)
        val result = occurrences(event, 3)
        assertEquals(listOf(20, 18, 15), result.map { it.dayOfMonth })
        result.forEach { assertEquals(DateTimeConstants.SUNDAY, it.dayOfWeek) }
    }

    @Test
    fun `event without a known zone expands in the device zone`() {
        val start = seconds(2026, 10, 13, 9, 0, riyadh)
        listOf("", "Not/AZone").forEach { zone ->
            val event = series(start, WEEK, TUESDAY_BIT, zone)
            event.addIntervalTime(event.copy())
            assertEquals(start + DAY, event.startTS)
        }
    }

    @Test
    fun `all day series stays on its date after the device changes zone`() {
        val start = seconds(2026, 10, 18, 0, 0, riyadh)
        DateTimeZone.setDefault(brussels)
        val event = series(start, WEEK, SUNDAY_BIT, riyadh.id).apply { flags = FLAG_ALL_DAY }
        val result = occurrences(event, 3)
        result.forEach { assertEquals(DateTimeConstants.SUNDAY, it.dayOfWeek) }
    }

    @Test
    fun `weekday bits rotate in both directions`() {
        assertEquals(SUNDAY_BIT, MONDAY_BIT.rotateWeekdayBits(6))
        assertEquals(MONDAY_BIT, SUNDAY_BIT.rotateWeekdayBits(1))
        assertEquals(TUESDAY_BIT, TUESDAY_BIT.rotateWeekdayBits(0))
        assertEquals(EVERY_DAY_BIT, EVERY_DAY_BIT.rotateWeekdayBits(3))
    }

    @Test
    fun `weekly rule without BYDAY takes its weekday from the event zone`() {
        val startTS = seconds(2026, 10, 18, 23, 30)
        val rule = Parser().parseRepeatInterval("FREQ=WEEKLY", startTS, brussels)
        assertEquals(SUNDAY_BIT, rule.repeatRule)
    }

    @Test
    fun `exported rule uses the weekday of the event zone`() {
        val event = series(seconds(2026, 9, 20, 23, 30), MONTH, REPEAT_ORDER_WEEKDAY)
        assertEquals("FREQ=MONTHLY;INTERVAL=1;BYDAY=3SU", Parser().getRepeatCode(event))
    }
}
