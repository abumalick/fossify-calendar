package org.fossify.calendar.helpers

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class AllDayEventZoneTest {
    private val riyadh = DateTimeZone.forID("Asia/Riyadh")
    private val brussels = DateTimeZone.forID("Europe/Brussels")
    private val newYork = DateTimeZone.forID("America/New_York")

    private fun seconds(y: Int, mo: Int, d: Int, h: Int, zone: DateTimeZone) =
        DateTime(y, mo, d, h, 0, zone).millis / 1000L

    @Test
    fun startOfDayStaysOnItsDate() {
        val stored = seconds(2026, 11, 15, 0, riyadh)
        assertEquals(seconds(2026, 11, 15, 0, brussels), AllDayEventZone.moveWallClock(stored, riyadh, brussels))
        assertEquals(seconds(2026, 11, 15, 0, newYork), AllDayEventZone.moveWallClock(stored, riyadh, newYork))
    }

    @Test
    fun endAtNoonStaysOnItsDate() {
        val stored = seconds(2026, 11, 16, 12, brussels)
        assertEquals(seconds(2026, 11, 16, 12, riyadh), AllDayEventZone.moveWallClock(stored, brussels, riyadh))
    }

    @Test
    fun moveAndBackIsIdentity() {
        val stored = seconds(2027, 3, 28, 0, riyadh)
        val moved = AllDayEventZone.moveWallClock(stored, riyadh, brussels)
        assertEquals(stored, AllDayEventZone.moveWallClock(moved, brussels, riyadh))
    }

    @Test
    fun midnightSkippedByDstStartsWhenTheDayStarts() {
        // Beirut moves its clocks from 00:00 to 01:00 on the last Sunday of March
        val beirut = DateTimeZone.forID("Asia/Beirut")
        val stored = seconds(2027, 3, 28, 0, riyadh)
        assertEquals(seconds(2027, 3, 28, 1, beirut), AllDayEventZone.moveWallClock(stored, riyadh, beirut))
    }

    @Test
    fun unknownOrMissingStoredZoneFallsBack() {
        assertEquals(riyadh, AllDayEventZone.storedZone("", riyadh))
        assertEquals(riyadh, AllDayEventZone.storedZone("Not/AZone", riyadh))
        assertEquals(brussels, AllDayEventZone.storedZone("Europe/Brussels", riyadh))
    }
}
