package com.uasflightdeck.sentry

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * "Pick a drone": every callsign seen live this run or in the persisted
 * history (newest first, "airborne now" when it is in the latest fleet poll),
 * plus "Protect this controller". Picking a callsign sets the pattern to that
 * exact callsign; the pilot can generalise digits to # in Settings.
 */
object DronePicker {
    fun show(a: Activity, settings: Settings, onChanged: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        val entries = DroneHistory.entries(a)
        val live = DroneHistory.liveNow
        val labels = ArrayList<String>()
        labels += "Protect this controller (ignore drones)" + if (settings.protectController) "  ✓" else ""
        val pat = settings.callsignPattern
        labels += "Auto: callsign pattern" + (if (pat.isBlank()) " (none set)" else " \"$pat\"") +
            (if (!settings.protectController) "  ✓" else "")
        for (e in entries) {
            val seen = if (e.callsign.lowercase() in live) "airborne now" else "seen ${ago(now - e.lastSeenMs)}"
            labels += "${e.callsign}   ·  $seen" + (e.serial?.let { "  ·  s/n $it" } ?: "")
        }
        if (entries.isEmpty()) labels += "(no drones seen yet: they appear here once the fleet feed shows them)"
        AlertDialog.Builder(a, R.style.Sentry_Dialog)
            .setTitle("Pick a drone")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i == 0 -> { settings.protectController = true; toast(a, "Protecting this controller") }
                    i == 1 -> { settings.protectController = false; toast(a, "Using the callsign pattern / serials") }
                    i - 2 < entries.size -> {
                        val e = entries[i - 2]
                        settings.callsignPattern = e.callsign; settings.protectController = false
                        toast(a, "Pattern set to \"${e.callsign}\" (Settings: use # for any digit)")
                    }
                }
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun ago(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 90 -> "${s}s ago"
            s < 5400 -> "${s / 60} min ago"
            s < 48 * 3600 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    private fun toast(a: Activity, t: String) = Toast.makeText(a, t, Toast.LENGTH_LONG).show()
}
