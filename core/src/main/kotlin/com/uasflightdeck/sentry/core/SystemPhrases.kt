package com.uasflightdeck.sentry.core

/**
 * Every fixed sentence the app itself speaks (outside the engine, selector and health monitor), in one place so
 * VoiceGrammarTest can prove the bundled voice bank covers each of them.
 */
object SystemPhrases {
    const val ARMED = "Sentry armed"
    const val DISARMED = "Sentry disarmed"
    const val REPLAY_STARTING = "Replay starting"
    const val UPDATE_AVAILABLE = "Update available"
    const val TEST_TEXT = "Test callout. Traffic, N388KM, southwest, 1,500 feet, 300 below, converging."
    const val TEST_SPEECH = "Test callout. Traffic, N 3 8 8 K M, southwest, 1,500 feet, 300 below, converging."
    /** Settings → Voice → Test: a full warning sentence through whichever voice is active. */
    const val VOICE_TEST_TEXT = "Warning. Traffic, N388KM, southwest, 1,500 feet, 200 below, converging, closest 1,200 feet in 18 seconds."
    const val VOICE_TEST_SPEECH = "Warning. Traffic, N 3 8 8 K M, southwest, 1,500 feet, 200 below, converging, closest 1,200 feet in 18 seconds."

    /** Link-health sources: key to spoken name (HealthMonitor says "<name> lost / not reachable / regained"). */
    val HEALTH_SOURCES = listOf("fleet" to "Drone feed", "station" to "Station link", "cloud" to "Cloud traffic", "tfr" to "T F R data")

    val ALL = listOf(ARMED, DISARMED, REPLAY_STARTING, UPDATE_AVAILABLE, TEST_SPEECH, VOICE_TEST_SPEECH)
}
