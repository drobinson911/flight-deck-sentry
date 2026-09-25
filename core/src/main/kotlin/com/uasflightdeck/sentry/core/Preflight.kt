package com.uasflightdeck.sentry.core

/**
 * The pre-flight check (v0.4.0): every item passes or fails with the fix for a failure. The app gathers the facts;
 * the verdict and wording live here so they are unit-tested.
 */
object Preflight {
    data class Item(val name: String, val ok: Boolean, val detail: String, val fix: String = "")

    data class Facts(
        val internet: Boolean,
        /** null = no token entered; true = accepted; false = rejected (HTTP 401/403). */
        val tokenAccepted: Boolean?,
        val tokenDetail: String,
        val droneFeedOk: Boolean,
        val droneFeedDetail: String,
        val pinnedSerial: String?,
        /** null = not in the feed; true = airborne; false = on the pad. */
        val boundAirborne: Boolean?,
        val boundCallsign: String?,
        val controllerGps: Boolean,
        val controllerGpsDetail: String,
        val trafficOk: Boolean,
        val trafficDetail: String,
        val tfrOk: Boolean,
        val tfrDetail: String,
        val notificationsEnabled: Boolean,
        val headsUpAllowed: Boolean,
        val batteryExempt: Boolean,
        /** Alarm and notification streams not at zero (the warning sound was played). */
        val soundAudible: Boolean,
        val soundDetail: String,
        val vibrator: Boolean,
    )

    fun items(f: Facts): List<Item> = listOf(
        Item("Internet", f.internet, if (f.internet) "online" else "offline", "Connect the controller to Wi-Fi or a hotspot"),
        when (f.tokenAccepted) {
            true -> Item("Fleet token", true, "accepted")
            false -> Item("Fleet token", false, "rejected (${f.tokenDetail})", "Paste the current fleet token in Settings")
            null -> Item("Fleet token", false, "none entered", "Paste the fleet token in Settings")
        },
        Item("Drone feed", f.droneFeedOk, f.droneFeedDetail, "Check the token and the internet; the feed must answer"),
        when {
            f.pinnedSerial == null -> Item("Bound aircraft", false, "none pinned: protecting this controller only",
                "Pin this controller's aircraft in Settings (tap it in the feed list)")
            f.boundAirborne == null -> Item("Bound aircraft", false, "${f.pinnedSerial} not in the feed",
                "Power the aircraft and let DroneSense connect; it appears within seconds")
            else -> Item("Bound aircraft", true, "${f.pinnedSerial}${f.boundCallsign?.let { " · $it" } ?: ""} · " +
                if (f.boundAirborne) "airborne" else "on the pad")
        },
        Item("Controller GPS", f.controllerGps, f.controllerGpsDetail, "Allow location for Sentry and wait for a fix outdoors"),
        Item("Traffic feed", f.trafficOk, f.trafficDetail, "Cloud ADS-B needs the internet; or enable the truck station"),
        Item("TFR data", f.tfrOk, f.tfrDetail, "Needs the internet once; the last download is kept on the controller"),
        Item("Notifications", f.notificationsEnabled && f.headsUpAllowed,
            when { !f.notificationsEnabled -> "off"; !f.headsUpAllowed -> "on, but traffic banners can't pop up"; else -> "on, heads-up allowed" },
            "Android Settings > Apps > Sentry > Notifications: allow, and set Traffic alerts to Pop on screen"),
        Item("Battery optimisation", f.batteryExempt, if (f.batteryExempt) "exempt" else "not exempt",
            "Settings > Background > Battery optimisation exemption"),
        Item("Sound", f.soundAudible, f.soundDetail, "Turn up the alarm and notification volume"),
        Item("Vibration", f.vibrator, if (f.vibrator) "available" else "not available on this controller (skipped)"),
    )

    /** Vibration missing is not a failure (the RC Plus may have no motor): it is reported and skipped. */
    fun passed(items: List<Item>) = items.filter { it.name != "Vibration" }.all { it.ok }

    fun summary(items: List<Item>): String {
        val bad = items.filter { !it.ok && it.name != "Vibration" }
        return if (bad.isEmpty()) "Pre-flight: all ${items.size} checks passed"
        else "Pre-flight: ${bad.size} to fix: " + bad.joinToString(", ") { it.name }
    }
}
