package org.fossify.calendar.extensions

import org.fossify.calendar.helpers.MONTH
import org.fossify.calendar.helpers.WEEK
import org.fossify.calendar.helpers.YEAR
import org.fossify.commons.helpers.EVERY_DAY_BIT
import org.joda.time.DateTimeConstants

fun Int.rotateWeekdayBits(days: Int): Int {
    return ((this shl days) or (this ushr (DateTimeConstants.DAYS_PER_WEEK - days))) and EVERY_DAY_BIT
}

fun Int.isXWeeklyRepetition() = this != 0 && this % WEEK == 0

fun Int.isXMonthlyRepetition() = this != 0 && this % MONTH == 0

fun Int.isXYearlyRepetition() = this != 0 && this % YEAR == 0
