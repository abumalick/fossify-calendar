package org.fossify.calendar.helpers

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.edit
import org.fossify.calendar.R
import org.fossify.calendar.activities.SelectTimeZoneActivity
import org.fossify.calendar.extensions.calDAVHelper
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.scheduleAllEvents
import org.fossify.calendar.extensions.updateWidgets
import org.fossify.calendar.models.MyTimeZone
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.joda.time.DateTimeZone
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows the whole app in another time zone, for following a calendar kept in another country, by replacing the
 * process's default zone. Switching behaves like the device changing zone: synced all-day events, stored as midnight
 * in the default zone, are re-synced so they stay on their dates. All-day events kept only in this app are not moved.
 */
object DisplayTimeZone {
    private const val PREF_ACTIVE = "fork_display_time_zone_active"
    private const val PREF_ZONE = "fork_display_time_zone"
    private const val ACTIVE_DOT_SIZE_DP = 8
    private const val REFRESH_TIMEOUT_MS = 30_000L
    private const val REFRESH_RETRY_MS = 200L

    private var deviceZoneId: String? = null
    private val isSwitching = AtomicBoolean(false)

    fun apply(context: Context) {
        // forget the override first, so that a change of the device's own zone is picked up
        TimeZone.setDefault(null)
        deviceZoneId = TimeZone.getDefault().id
        setDefaultZone(getShownZoneId(context))
    }

    /** Must be called while [activity] is being created, as it registers for the zone picker's result. */
    // the toggle is the point of the feature, so it must not end up in the overflow menu
    @SuppressLint("AlwaysShowAction")
    fun addMenuItems(activity: ComponentActivity, menu: Menu) {
        apply(activity)
        val pickZone = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            @Suppress("DEPRECATION")
            val zone = result.data?.getSerializableExtra(TIME_ZONE) as? MyTimeZone ?: return@registerForActivityResult
            activity.getSharedPrefs().edit { putString(PREF_ZONE, zone.zoneName) }
            switch(activity, zone.zoneName != deviceZoneId)
        }
        val launchPicker = {
            val currentZone = getChosenZoneId(activity) ?: deviceZoneId
            val intent = Intent(activity, SelectTimeZoneActivity::class.java).putExtra(CURRENT_TIME_ZONE, currentZone)
            pickZone.launch(intent)
        }

        val isActive = isActive(activity)
        val shownCity = getShownZoneId(activity).cityName
        val chosenZoneId = getChosenZoneId(activity)
        val toggleTitle = when {
            isActive -> activity.getString(R.string.fork_back_to_device_time, deviceZoneId!!.cityName)
            chosenZoneId != null -> activity.getString(R.string.fork_show_time_in, chosenZoneId.cityName)
            else -> activity.getString(R.string.fork_display_time_zone)
        }

        menu.add(Menu.NONE, Menu.NONE, Menu.NONE, toggleTitle).apply {
            icon = getToggleIcon(activity, isActive)
            contentDescription = "$toggleTitle, ${activity.getString(R.string.fork_showing_time_in, shownCity)}"
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                if (getChosenZoneId(activity) == null) launchPicker() else switch(activity, !isActive(activity))
                true
            }
        }

        menu.add(Menu.NONE, Menu.NONE, Menu.NONE, R.string.fork_display_time_zone).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                launchPicker()
                true
            }
        }
    }

    private fun switch(activity: Activity, active: Boolean) {
        if (!isSwitching.compareAndSet(false, true)) return
        // until the re-sync is done, synced all-day events can be on the wrong day: they must not be opened and saved
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        activity.toast(R.string.fork_switching_time_zone)
        ensureBackgroundThread {
            try {
                activity.getSharedPrefs().edit { putBoolean(PREF_ACTIVE, active) }
                setDefaultZone(getShownZoneId(activity))
                resyncCalDAVCalendars(activity)
                activity.scheduleAllEvents()
                activity.updateWidgets()
            } finally {
                isSwitching.set(false)
            }

            activity.runOnUiThread {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.toast(activity.getString(R.string.fork_showing_time_in, getShownZoneId(activity).cityName))
                    activity.recreate()
                }
            }
        }
    }

    // a refresh already running, possibly started in the old zone, makes this one return without running: retry
    private fun resyncCalDAVCalendars(context: Context) {
        if (!context.config.caldavSync) return

        var refreshed = false
        val deadline = System.currentTimeMillis() + REFRESH_TIMEOUT_MS
        while (!refreshed && System.currentTimeMillis() < deadline) {
            context.calDAVHelper.refreshCalendars(showToasts = false, scheduleNextSync = false) { refreshed = true }
            if (!refreshed) Thread.sleep(REFRESH_RETRY_MS)
        }
    }

    private fun isActive(context: Context) =
        context.getSharedPrefs().getBoolean(PREF_ACTIVE, false) && getChosenZoneId(context) != null

    private fun getChosenZoneId(context: Context) =
        context.getSharedPrefs().getString(PREF_ZONE, null)?.takeIf { it in DateTimeZone.getAvailableIDs() }

    private fun getShownZoneId(context: Context) = if (isActive(context)) getChosenZoneId(context)!! else deviceZoneId!!

    // Etc/GMT+3 is UTC-3, so show its offset rather than its inverted name
    private val String.cityName: String
        get() = if (startsWith("Etc/")) {
            TimeZone.getTimeZone(this).getDisplayName(false, TimeZone.SHORT)
        } else {
            substringAfterLast('/').replace('_', ' ')
        }

    private fun setDefaultZone(zoneId: String) {
        val zone = TimeZone.getTimeZone(zoneId)
        TimeZone.setDefault(zone)
        DateTimeZone.setDefault(DateTimeZone.forTimeZone(zone))
    }

    private fun getToggleIcon(context: Context, isActive: Boolean) = if (isActive) {
        val dotSize = (ACTIVE_DOT_SIZE_DP * context.resources.displayMetrics.density).toInt()
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.WHITE)
            setSize(dotSize, dotSize)
        }
        LayerDrawable(arrayOf(AppCompatResources.getDrawable(context, R.drawable.ic_globe_vector)!!, dot)).apply {
            setLayerGravity(1, android.view.Gravity.BOTTOM or android.view.Gravity.END)
            setLayerSize(1, dotSize, dotSize)
        }
    } else {
        AppCompatResources.getDrawable(context, R.drawable.ic_globe_vector)
    }
}
