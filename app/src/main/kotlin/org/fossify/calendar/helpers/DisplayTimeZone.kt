package org.fossify.calendar.helpers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.SQLException
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import org.fossify.calendar.R
import org.fossify.calendar.activities.SelectTimeZoneActivity
import org.fossify.calendar.extensions.scheduleAllEvents
import org.fossify.calendar.extensions.updateWidgets
import org.fossify.calendar.models.MyTimeZone
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.joda.time.DateTimeZone
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows the whole app in another time zone, for following a calendar kept in another country, by replacing the
 * process's default zone. Timed events are instants, so they follow on their own. All-day events are stored as times
 * of day in the default zone, so switching moves them to the same times of day in the new zone.
 */
object DisplayTimeZone {
    private const val PREF_ACTIVE = "fork_display_time_zone_active"
    private const val PREF_ZONE = "fork_display_time_zone"
    private const val PREF_FAVORITES = "fork_display_time_zones"

    private var deviceZoneId: String? = null
    private val isSwitching = AtomicBoolean(false)

    fun apply(context: Context) {
        // forget the override first, so that a change of the device's own zone is picked up
        TimeZone.setDefault(null)
        deviceZoneId = TimeZone.getDefault().id
        setDefaultZone(getShownZoneId(context))
    }

    fun getDeviceZoneId() = deviceZoneId ?: TimeZone.getDefault().id

    fun isActive(context: Context) =
        context.getSharedPrefs().getBoolean(PREF_ACTIVE, false) && getChosenZoneId(context) != null

    fun getShownZoneId(context: Context): String =
        if (isActive(context)) getChosenZoneId(context)!! else getDeviceZoneId()

    fun getFavorites(context: Context): List<String> {
        val prefs = context.getSharedPrefs()
        // before favorites, a single chosen zone was kept
        val stored = prefs.getStringSet(PREF_FAVORITES, null) ?: setOfNotNull(getChosenZoneId(context))
        // the device's zone is always offered, and it can change when travelling
        return stored.filter { it.isKnownZone() && it != getDeviceZoneId() }.sortedBy { it.cityName }
    }

    fun setFavorites(context: Context, zoneIds: Collection<String>) {
        context.getSharedPrefs().edit { putStringSet(PREF_FAVORITES, zoneIds.toSet()) }
    }

    /** Must be called while [activity] is being created. [onPicked] receives the id of the zone picked by the user. */
    fun registerZonePicker(activity: ComponentActivity, onPicked: (String) -> Unit): ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            @Suppress("DEPRECATION")
            val zone = result.data?.getSerializableExtra(TIME_ZONE) as? MyTimeZone
            zone?.zoneName?.takeIf { it.isKnownZone() }?.let(onPicked)
        }

    fun pickerIntent(activity: Activity): Intent =
        Intent(activity, SelectTimeZoneActivity::class.java).putExtra(CURRENT_TIME_ZONE, getShownZoneId(activity))

    /** Shows [zoneId], or the device's own zone when it is null, then recreates [activity]. */
    fun show(activity: Activity, zoneId: String?) {
        val target = zoneId?.takeIf { it != getDeviceZoneId() }
        if (target == (if (isActive(activity)) getChosenZoneId(activity) else null)) return
        if (!isSwitching.compareAndSet(false, true)) return

        ensureBackgroundThread {
            try {
                // move the events first: if that fails, the app keeps showing the zone they are stored in
                val nextZoneId = target ?: getDeviceZoneId()
                val nextZone = DateTimeZone.forTimeZone(TimeZone.getTimeZone(nextZoneId))
                AllDayEventZone.moveStoredEvents(activity, DateTimeZone.getDefault(), nextZone)
                activity.getSharedPrefs().edit(commit = true) {
                    putBoolean(PREF_ACTIVE, target != null)
                    target?.let { putString(PREF_ZONE, it) }
                }
                setDefaultZone(nextZoneId)
            } catch (e: SQLException) {
                activity.runOnUiThread { activity.showErrorToast(e) }
                return@ensureBackgroundThread
            } finally {
                isSwitching.set(false)
            }

            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.toast(activity.getString(R.string.fork_showing_time_in, getShownZoneId(activity).cityName))
                    activity.recreate()
                }
            }
            // reminders of all-day events are set from their stored times, which moved
            activity.scheduleAllEvents()
            activity.updateWidgets()
        }
    }

    private fun getChosenZoneId(context: Context) =
        context.getSharedPrefs().getString(PREF_ZONE, null)?.takeIf { it.isKnownZone() }

    // Etc/GMT+3 is UTC-3, so show its offset rather than its inverted name
    val String.cityName: String
        get() = if (startsWith("Etc/")) {
            TimeZone.getTimeZone(this).getDisplayName(false, TimeZone.SHORT)
        } else {
            substringAfterLast('/').replace('_', ' ')
        }
}

private fun String.isKnownZone() = this in DateTimeZone.getAvailableIDs()

private fun setDefaultZone(zoneId: String) {
    val zone = TimeZone.getTimeZone(zoneId)
    TimeZone.setDefault(zone)
    DateTimeZone.setDefault(DateTimeZone.forTimeZone(zone))
}
