package org.fossify.calendar.helpers

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.fossify.calendar.extensions.calDAVHelper
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.models.CalendarEntity
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Syncs recurring events through the calendar provider and compares the occurrences Fossify expands
 * with the provider's own Instances table, which evaluates RRULEs in the event's time zone.
 */
@RunWith(AndroidJUnit4::class)
class RecurrenceMatchesInstancesTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val deviceZone = DateTimeZone.forID("Asia/Riyadh")
    private val brussels = DateTimeZone.forID("Europe/Brussels")
    private val windowStart = DateTime(2026, 10, 1, 0, 0, DateTimeZone.UTC).millis
    private val windowEnd = DateTime(2028, 4, 30, 0, 0, DateTimeZone.UTC).millis

    private lateinit var originalDeviceZone: DateTimeZone
    private lateinit var originalSyncedIds: String
    private lateinit var originalDisplayCalendars: Set<String>
    private lateinit var originalQuickFilterCalendars: Set<String>
    private var providerCalendarId = 0L
    private var localCalendarId = 0L

    private val syncAdapterCalendars: Uri = Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    @Before
    fun setUp() {
        originalDeviceZone = DateTimeZone.getDefault()
        DateTimeZone.setDefault(deviceZone)
        originalSyncedIds = context.config.caldavSyncedCalendarIds
        originalDisplayCalendars = context.config.displayCalendars
        originalQuickFilterCalendars = context.config.quickFilterCalendars
        deleteProviderCalendars()
        waitForDefaultCalendar()

        val calendarValues = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, ACCOUNT)
            put(Calendars.CALENDAR_DISPLAY_NAME, ACCOUNT)
            put(Calendars.CALENDAR_COLOR, Color.BLUE)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(Calendars.CALENDAR_TIME_ZONE, deviceZone.id)
            put(Calendars.SYNC_EVENTS, 1)
        }
        providerCalendarId = ContentUris.parseId(resolver.insert(syncAdapterCalendars, calendarValues)!!)
        localCalendarId = context.eventsHelper.insertOrUpdateCalendarSync(
            CalendarEntity(
                id = null,
                title = ACCOUNT,
                color = Color.BLUE,
                caldavCalendarId = providerCalendarId.toInt(),
                caldavDisplayName = ACCOUNT,
                caldavEmail = ACCOUNT,
            )
        )
        context.config.caldavSyncedCalendarIds = providerCalendarId.toString()
    }

    @After
    fun tearDown() {
        context.eventsHelper.deleteEvents(
            ids = context.eventsDB.getEventsFromCalDAVCalendar("$CALDAV-$providerCalendarId")
                .mapNotNull { it.id }.toMutableList(),
            deleteFromCalDAV = false,
            updateWidgets = false,
        )
        context.calendarsDB.getCalendarWithId(localCalendarId)?.let { context.calendarsDB.deleteCalendars(listOf(it)) }
        deleteProviderCalendars()
        context.config.caldavSyncedCalendarIds = originalSyncedIds
        context.config.displayCalendars = originalDisplayCalendars
        context.config.quickFilterCalendars = originalQuickFilterCalendars
        DateTimeZone.setDefault(originalDeviceZone)
    }

    // a new database inserts its default calendar with a fixed id from a background thread, replacing whatever has it
    private fun waitForDefaultCalendar() {
        val deadline = System.currentTimeMillis() + DEFAULT_CALENDAR_TIMEOUT_MS
        while (context.calendarsDB.getCalendarWithId(LOCAL_CALENDAR_ID) == null) {
            check(System.currentTimeMillis() < deadline) { "default calendar was not created" }
            Thread.sleep(DEFAULT_CALENDAR_RETRY_MS)
        }
    }

    private fun deleteProviderCalendars() {
        resolver.delete(syncAdapterCalendars, "${Calendars.ACCOUNT_NAME} = ?", arrayOf(ACCOUNT))
    }

    private fun insertEvent(
        title: String,
        start: DateTime,
        rrule: String,
        exdate: String? = null,
        allDay: Boolean = false,
    ) {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, providerCalendarId)
            put(Events.TITLE, title)
            put(Events.DTSTART, start.millis)
            put(Events.DURATION, if (allDay) "P1D" else "PT1H")
            put(Events.EVENT_TIMEZONE, start.zone.id)
            put(Events.RRULE, rrule)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            exdate?.let { put(Events.EXDATE, it) }
        }
        resolver.insert(Events.CONTENT_URI, values)
    }

    private fun instancesByTitle(): Map<String, List<Long>> {
        val result = HashMap<String, MutableList<Long>>()
        val projection = arrayOf(Instances.TITLE, Instances.BEGIN, Instances.CALENDAR_ID)
        Instances.query(resolver, projection, windowStart, windowEnd).use { cursor ->
            while (cursor.moveToNext()) {
                val begin = cursor.getLong(1)
                if (begin in windowStart..windowEnd && cursor.getLong(2) == providerCalendarId) {
                    result.getOrPut(cursor.getString(0)) { ArrayList() }.add(begin / 1000L)
                }
            }
        }
        return result.mapValues { it.value.sorted() }
    }

    private fun fossifyByTitle(): Map<String, List<Long>> {
        var refreshed = false
        context.calDAVHelper.refreshCalendars(showToasts = false, scheduleNextSync = false) { refreshed = true }
        assertTrue("CalDAV refresh did not run", refreshed)
        var result = emptyMap<String, List<Long>>()
        context.eventsHelper.getEventsSync(windowStart / 1000L, windowEnd / 1000L, applyTypeFilter = false) { events ->
            result = events
                .filter { it.source == "$CALDAV-$providerCalendarId" && it.startTS * 1000L >= windowStart }
                .groupBy({ it.title }, { it.startTS })
                .mapValues { it.value.sorted() }
        }
        return result
    }

    private fun assertMatches(expected: Map<String, List<Long>>, actual: Map<String, List<Long>>) {
        val mismatches = (expected.keys + actual.keys).filter { expected[it] != actual[it] }.map { title ->
            val instances = expected[title].orEmpty()
            val fossify = actual[title].orEmpty()
            "$title: only in Instances ${instances - fossify.toSet()}, only in Fossify ${fossify - instances.toSet()}"
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun timedSeriesMatchInstances() {
        val newYork = DateTimeZone.forID("America/New_York")
        val tokyo = DateTimeZone.forID("Asia/Tokyo")

        fun inBrussels(month: Int, day: Int, hour: Int, minute: Int = 0) =
            DateTime(2026, month, day, hour, minute, brussels)

        insertEvent("weekly morning", inBrussels(10, 13, 9), "FREQ=WEEKLY;BYDAY=TU")
        insertEvent("weekly without BYDAY", inBrussels(10, 18, 23, 30), "FREQ=WEEKLY")
        insertEvent("weekly late evening", inBrussels(10, 18, 23, 30), "FREQ=WEEKLY;BYDAY=SU")
        insertEvent("biweekly late evening", inBrussels(10, 18, 23, 30), "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU")
        insertEvent("weekly several days", inBrussels(10, 12, 22, 30), "FREQ=WEEKLY;BYDAY=MO,TH")
        insertEvent("weekly count", inBrussels(10, 13, 9), "FREQ=WEEKLY;BYDAY=TU;COUNT=6")
        insertEvent("weekly until", inBrussels(10, 13, 9), "FREQ=WEEKLY;BYDAY=TU;UNTIL=20270201T000000Z")
        insertEvent(
            "weekly with exdate",
            inBrussels(10, 13, 9),
            "FREQ=WEEKLY;BYDAY=TU",
            exdate = "20261103T080000Z",
        )
        insertEvent("daily", inBrussels(10, 20, 9), "FREQ=DAILY;COUNT=20")
        insertEvent("every two days", inBrussels(10, 23, 9), "FREQ=DAILY;INTERVAL=2")
        insertEvent("every three days late evening", inBrussels(10, 23, 23, 30), "FREQ=DAILY;INTERVAL=3")
        insertEvent("monthly same day", inBrussels(10, 15, 9), "FREQ=MONTHLY")
        insertEvent("monthly on the 31st", inBrussels(10, 31, 23, 30), "FREQ=MONTHLY")
        insertEvent("monthly third sunday", inBrussels(10, 18, 23, 30), "FREQ=MONTHLY;BYDAY=3SU")
        insertEvent("monthly last sunday", inBrussels(10, 25, 23, 30), "FREQ=MONTHLY;BYDAY=-1SU")
        insertEvent("yearly", inBrussels(10, 3, 9), "FREQ=YEARLY")
        insertEvent("new york evening", DateTime(2026, 10, 13, 20, 30, newYork), "FREQ=WEEKLY;BYDAY=TU")
        insertEvent("tokyo morning", DateTime(2026, 10, 12, 7, 0, tokyo), "FREQ=WEEKLY;BYDAY=MO")
        insertEvent("riyadh", DateTime(2026, 10, 13, 9, 0, deviceZone), "FREQ=WEEKLY;BYDAY=TU")

        val instances = instancesByTitle()
        assertEquals(SERIES_COUNT, instances.size)
        assertMatches(instances, fossifyByTitle())
    }

    /**
     * Instances is not a usable reference for local times that a DST change repeats or skips. It picks the second
     * 02:30 where RFC 5545 asks for the first, and keeps every occurrence after a gap at 03:30. RFC 5545 is not fully
     * consistent about skipped times; they are kept here, using the offset from before the gap.
     */
    @Test
    fun localTimesRepeatedOrSkippedByDstKeepTheirWallClock() {
        insertEvent("weekly 02:30", DateTime(2026, 10, 18, 2, 30, brussels), "FREQ=WEEKLY;BYDAY=SU")

        val occurrences = fossifyByTitle().getValue("weekly 02:30").map { DateTime(it * 1000L, brussels) }

        assertEquals(DateTime(2026, 10, 25, 2, 30, brussels).withEarlierOffsetAtOverlap(), occurrences[1])
        assertEquals(DateTime(2027, 3, 28, 3, 30, brussels), occurrences[23])
        assertEquals(DateTime(2027, 4, 4, 2, 30, brussels), occurrences[24])
    }

    @Test
    fun allDaySeriesStayOnTheirDates() {
        // west of UTC, the UTC midnight of an all-day event falls on the previous local day
        DateTimeZone.setDefault(DateTimeZone.forID("America/New_York"))
        insertEvent(
            "weekly all day",
            DateTime(2026, 10, 18, 0, 0, DateTimeZone.UTC),
            "FREQ=WEEKLY;BYDAY=SU",
            allDay = true,
        )

        val instances = instancesByTitle().getValue("weekly all day").map(Formatter::getUTCDayCodeFromTS)
        fossifyByTitle().getValue("weekly all day").map { Formatter.getDayCodeFromTS(it) }.let {
            assertEquals(instances, it)
        }
    }

    private companion object {
        const val ACCOUNT = "fossify-recurrence-test"
        const val SERIES_COUNT = 19
        const val DEFAULT_CALENDAR_TIMEOUT_MS = 10_000L
        const val DEFAULT_CALENDAR_RETRY_MS = 100L
    }
}
