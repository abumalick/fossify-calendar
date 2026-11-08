package org.fossify.calendar.helpers

import android.content.Context
import org.fossify.calendar.databases.EventsDatabase
import org.joda.time.DateTimeConstants.MILLIS_PER_SECOND
import org.joda.time.DateTimeZone

/**
 * All-day events are stored as times of day (start of the first day, noon of the last day) in the zone that was the
 * default when they were saved, and that zone is recorded in their time zone. This moves them to the same times of day
 * in another zone, so that they stay on their dates when the default zone changes. The task editor does not record a
 * zone, so tasks are taken to be in the default zone, as the app reads them.
 */
object AllDayEventZone {
    fun moveWallClock(ts: Long, from: DateTimeZone, to: DateTimeZone): Long {
        val localMillis = from.convertUTCToLocal(ts * MILLIS_PER_SECOND)
        return to.convertLocalToUTC(localMillis, false) / MILLIS_PER_SECOND
    }

    fun storedZone(id: String, fallback: DateTimeZone): DateTimeZone =
        if (id in DateTimeZone.getAvailableIDs()) DateTimeZone.forID(id) else fallback

    /** Events without a known zone, and tasks, are taken to be in [fallback]. Events already in [to] are skipped. */
    fun moveStoredEvents(context: Context, fallback: DateTimeZone, to: DateTimeZone) {
        val db = EventsDatabase.getInstance(context).openHelper.writableDatabase
        db.beginTransaction()
        try {
            val rows = ArrayList<StoredAllDayEvent>()
            db.query(
                "SELECT id, type, start_ts, end_ts, repeat_limit, time_zone FROM events " +
                    "WHERE (flags & $FLAG_ALL_DAY) != 0 AND (type = $TYPE_TASK OR time_zone != ?)",
                arrayOf(to.id)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val column = { name: String -> cursor.getColumnIndexOrThrow(name) }
                    val isTask = cursor.getInt(column("type")) == TYPE_TASK
                    val storedZoneId = cursor.getString(column("time_zone")).orEmpty()
                    rows += StoredAllDayEvent(
                        id = cursor.getLong(column("id")),
                        isTask = isTask,
                        startTS = cursor.getLong(column("start_ts")),
                        endTS = cursor.getLong(column("end_ts")),
                        repeatLimit = cursor.getLong(column("repeat_limit")),
                        storedZoneId = storedZoneId,
                        zone = if (isTask) fallback else storedZone(storedZoneId, fallback),
                    )
                }
            }

            rows.filter { it.zone != to }.forEach { row ->
                val move = { ts: Long -> moveWallClock(ts, row.zone, to) }
                // a positive repeat limit is the timestamp of the last day, a negative one is a count of occurrences
                val repeatLimit = if (row.repeatLimit > 0) move(row.repeatLimit) else row.repeatLimit
                val zoneId = if (row.isTask) row.storedZoneId else to.id
                db.execSQL(
                    "UPDATE events SET start_ts = ?, end_ts = ?, repeat_limit = ?, time_zone = ? WHERE id = ?",
                    arrayOf(move(row.startTS), move(row.endTS), repeatLimit, zoneId, row.id)
                )
                // completed occurrences of a task are found by their start time
                moveCompletedTasks(db, row.id, move)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun moveCompletedTasks(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        taskId: Long,
        move: (Long) -> Long,
    ) {
        val completed = ArrayList<Pair<Long, Long>>()
        db.query("SELECT id, start_ts FROM tasks WHERE task_id = ?", arrayOf(taskId)).use { cursor ->
            while (cursor.moveToNext()) {
                completed += cursor.getLong(0) to cursor.getLong(1)
            }
        }
        completed.forEach { (id, startTS) ->
            db.execSQL("UPDATE tasks SET start_ts = ? WHERE id = ?", arrayOf(move(startTS), id))
        }
    }

    private class StoredAllDayEvent(
        val id: Long,
        val isTask: Boolean,
        val startTS: Long,
        val endTS: Long,
        val repeatLimit: Long,
        val storedZoneId: String,
        val zone: DateTimeZone,
    )
}
