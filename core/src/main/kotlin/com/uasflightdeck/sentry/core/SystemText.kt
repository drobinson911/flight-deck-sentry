package com.uasflightdeck.sentry.core

/** Fixed app-level lines (log, Last alerts and banners). Sentry has no voice from 0.4.0. */
object SystemText {
    const val ARMED = "Sentry armed"
    const val DISARMED = "Sentry disarmed"
    const val REPLAY_STARTING = "Replay starting"
    const val UPDATE_AVAILABLE = "Update available"
    const val INTERNET_LOST = "Internet offline"
    const val INTERNET_REGAINED = "Internet back"

    /** "1 target", "0 targets", "12 targets" (0.4.3: the status line said "1 targets"). */
    fun count(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"

    /** Link-health sources: key to display name (HealthMonitor says "<name> lost / not reachable / regained", screen only). */
    val HEALTH_SOURCES = listOf("fleet" to "Drone feed", "station" to "Overwatch station link", "cloud" to "Cloud traffic", "tfr" to "TFR data")

    /** The Sources row label when it differs from the alert name: "Overwatch station". */
    private val ROW_LABELS = mapOf("station" to "Overwatch station")
    fun rowLabel(key: String, name: String): String = ROW_LABELS[key] ?: name
}
