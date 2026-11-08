package org.fossify.calendar.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.activity.ComponentActivity
import androidx.appcompat.content.res.AppCompatResources
import org.fossify.calendar.R
import org.fossify.calendar.helpers.DisplayTimeZone.cityName

/** The toolbar button that switches between the device's zone and the favorite zones picked in the settings. */
object DisplayTimeZoneMenu {
    private const val ACTIVE_DOT_SIZE_DP = 8

    /** Must be called while [activity] is being created, as it registers for the zone picker's result. */
    // the button is the point of the feature, so it must not end up in the overflow menu
    @SuppressLint("AlwaysShowAction")
    fun addMenuItems(activity: ComponentActivity, menu: Menu) {
        DisplayTimeZone.apply(activity)
        val pickFirstFavorite = DisplayTimeZone.registerZonePicker(activity) { zoneId ->
            DisplayTimeZone.setFavorites(activity, DisplayTimeZone.getFavorites(activity) + zoneId)
            DisplayTimeZone.show(activity, zoneId)
        }

        val isActive = DisplayTimeZone.isActive(activity)
        val shownCity = DisplayTimeZone.getShownZoneId(activity).cityName
        val favorites = DisplayTimeZone.getFavorites(activity)
        val title = when {
            isActive -> {
                activity.getString(R.string.fork_back_to_device_time, DisplayTimeZone.getDeviceZoneId().cityName)
            }

            favorites.size == 1 -> activity.getString(R.string.fork_show_time_in, favorites.first().cityName)
            else -> activity.getString(R.string.fork_display_time_zone)
        }

        val itemId = View.generateViewId()
        menu.add(Menu.NONE, itemId, Menu.NONE, title).apply {
            icon = getIcon(activity, isActive)
            contentDescription = "$title, ${activity.getString(R.string.fork_showing_time_in, shownCity)}"
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                val currentFavorites = DisplayTimeZone.getFavorites(activity)
                val currentlyActive = DisplayTimeZone.isActive(activity)
                when {
                    currentFavorites.isEmpty() -> pickFirstFavorite.launch(DisplayTimeZone.pickerIntent(activity))
                    currentFavorites.size == 1 && (!currentlyActive || isShown(activity, currentFavorites.first())) -> {
                        DisplayTimeZone.show(activity, if (currentlyActive) null else currentFavorites.first())
                    }

                    else -> {
                        val anchor = activity.findViewById<View>(itemId) ?: return@setOnMenuItemClickListener false
                        showZonesPopup(activity, anchor)
                    }
                }
                true
            }
        }
    }

    private fun showZonesPopup(activity: ComponentActivity, anchor: View) {
        val deviceZoneId = DisplayTimeZone.getDeviceZoneId()
        val zoneIds = listOf(deviceZoneId) + DisplayTimeZone.getFavorites(activity).filter { it != deviceZoneId }
        val shownZoneId = DisplayTimeZone.getShownZoneId(activity)

        PopupMenu(activity, anchor).apply {
            zoneIds.forEachIndexed { index, zoneId ->
                val label = if (index == 0) {
                    activity.getString(R.string.fork_device_time, zoneId.cityName)
                } else {
                    zoneId.cityName
                }
                menu.add(Menu.NONE, index, index, label).isChecked = zoneId == shownZoneId
            }
            menu.setGroupCheckable(Menu.NONE, true, true)
            setOnMenuItemClickListener { item ->
                DisplayTimeZone.show(activity, zoneIds[item.itemId].takeIf { item.itemId != 0 })
                true
            }
            show()
        }
    }

    private fun isShown(context: Context, zoneId: String) = DisplayTimeZone.getShownZoneId(context) == zoneId

    private fun getIcon(context: Context, isActive: Boolean) = if (isActive) {
        val dotSize = (ACTIVE_DOT_SIZE_DP * context.resources.displayMetrics.density).toInt()
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(dotSize, dotSize)
        }
        LayerDrawable(arrayOf(AppCompatResources.getDrawable(context, R.drawable.ic_globe_vector)!!, dot)).apply {
            setLayerGravity(1, Gravity.BOTTOM or Gravity.END)
            setLayerSize(1, dotSize, dotSize)
        }
    } else {
        AppCompatResources.getDrawable(context, R.drawable.ic_globe_vector)
    }
}
