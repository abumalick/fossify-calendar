package org.fossify.calendar.helpers

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import org.fossify.calendar.R
import org.fossify.calendar.databinding.ForkSettingsDisplayTimeZonesBinding
import org.fossify.calendar.helpers.DisplayTimeZone.cityName
import org.fossify.commons.extensions.getAlertDialogBuilder

/** The settings entry listing the favorite zones that the toolbar button switches between. */
object DisplayTimeZoneSettings {
    /** Must be called while [activity] is being created. The entry is added right after [anchor]. */
    fun addSettingsItem(activity: ComponentActivity, anchor: View) {
        val parent = anchor.parent as ViewGroup
        val row = ForkSettingsDisplayTimeZonesBinding.inflate(LayoutInflater.from(activity), parent, false)
        parent.addView(row.root, parent.indexOfChild(anchor) + 1)

        fun refresh() {
            val favorites = DisplayTimeZone.getFavorites(activity)
            row.forkSettingsDisplayTimeZones.text = if (favorites.isEmpty()) {
                activity.getString(R.string.fork_no_display_time_zones)
            } else {
                favorites.joinToString(", ") { it.cityName }
            }
        }

        val addFavorite = DisplayTimeZone.registerZonePicker(activity) { zoneId ->
            DisplayTimeZone.setFavorites(activity, DisplayTimeZone.getFavorites(activity) + zoneId)
            refresh()
        }

        row.root.setOnClickListener {
            val favorites = DisplayTimeZone.getFavorites(activity)
            if (favorites.isEmpty()) {
                addFavorite.launch(DisplayTimeZone.pickerIntent(activity))
                return@setOnClickListener
            }

            val kept = BooleanArray(favorites.size) { true }
            activity.getAlertDialogBuilder()
                .setTitle(R.string.fork_display_time_zones)
                .setMultiChoiceItems(favorites.map { it.cityName }.toTypedArray(), kept) { _, which, isChecked ->
                    kept[which] = isChecked
                }
                .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                    DisplayTimeZone.setFavorites(activity, favorites.filterIndexed { index, _ -> kept[index] })
                    refresh()
                }
                .setNeutralButton(R.string.fork_add_time_zone) { _, _ ->
                    addFavorite.launch(DisplayTimeZone.pickerIntent(activity))
                }
                .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                .show()
        }
        refresh()
    }
}
