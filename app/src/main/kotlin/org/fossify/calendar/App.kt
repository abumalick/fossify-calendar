package org.fossify.calendar

import org.fossify.calendar.extensions.hasDummyAlarm
import org.fossify.calendar.helpers.DisplayTimeZone
import org.fossify.calendar.jobs.AppStartupWorker
import org.fossify.commons.FossifyApp

class App : FossifyApp() {
    override fun onCreate() {
        super.onCreate()
        DisplayTimeZone.apply(this)
        if (!hasDummyAlarm()) {
            AppStartupWorker.start(this)
        }
    }
}
