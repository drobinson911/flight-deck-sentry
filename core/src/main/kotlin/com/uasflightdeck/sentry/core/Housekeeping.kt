package com.uasflightdeck.sentry.core

/**
 * The housekeeping pop-ups (internet, bound aircraft, restart, sounds on): title + ONE line.
 *
 * 0.4.3: on the RC Plus (1920x1200 @ 400 dpi, Android 10) a heads-up shows the title and a single body line of about
 * 78 characters; 0.4.2's sentences were cut with ".." ("... The Overwatch station lin.."). Every body is now at most
 * [MAX_BODY] characters so the key content is on screen; the pulled-down card (0.4.5: our own dark card, no
 * BigTextStyle) shows the full text.
 */
object Housekeeping {
    /** Body length that fits one heads-up line at 400 dpi with margin (the emulated RC Plus fits ~78). */
    const val MAX_BODY = 70

    /** [stationLive]: the Overwatch station link is healthy right now (it works without the internet): only then is it named. */
    fun text(kind: EventKind, eventText: String, stationLive: Boolean): Pair<String, String>? = when (kind) {
        EventKind.INTERNET_LOST -> SystemText.INTERNET_LOST to
            if (stationLive) "Cloud traffic, drone feed, TFRs paused; Overwatch station still live"
            else "Cloud traffic, drone feed and TFR updates paused until it is back"
        EventKind.INTERNET_REGAINED -> SystemText.INTERNET_REGAINED to "Cloud traffic and the drone feed resume"
        EventKind.SELECTION -> "Bound aircraft acquired" to eventText
        EventKind.OWNSHIP_LOST -> "Bound aircraft lost" to "$eventText; controller cylinders are the fallback"
        EventKind.OWNSHIP_REGAINED, EventKind.OWNSHIP_ACQUIRED -> "Bound aircraft back" to eventText
        EventKind.SOUNDS_ON -> "Sentry sounds on" to "Quiet is over: traffic sounds are back"
        EventKind.RESTARTED -> eventText to if (eventText == RestartPolicy.ARMED_AFTER_BOOT)
            "Re-armed after a controller restart or app update; watching again"
            else "Stopped unexpectedly and restarted itself; armed and watching again"
        else -> null
    }

    /**
     * 0.4.5: the card tone of a housekeeping pop-up. Amber = something the pilot relies on is gone (drone position,
     * internet, a traffic source, controller GPS, a failed pre-flight); green = it is back (or the pre-flight passed);
     * grey-blue = information (selection, restart, sounds on, anything else).
     */
    fun tone(kind: EventKind, preflightOk: Boolean = true): BannerLook.Tone = when (kind) {
        EventKind.OWNSHIP_LOST, EventKind.INTERNET_LOST, EventKind.TRAFFIC_STALE, EventKind.SOURCE_LOST,
        EventKind.CONTROLLER_GPS_LOST -> BannerLook.Tone.LOST
        EventKind.OWNSHIP_REGAINED, EventKind.OWNSHIP_ACQUIRED, EventKind.INTERNET_REGAINED, EventKind.TRAFFIC_RESTORED,
        EventKind.SOURCE_REGAINED, EventKind.CONTROLLER_GPS_REGAINED -> BannerLook.Tone.REGAINED
        EventKind.PREFLIGHT -> if (preflightOk) BannerLook.Tone.REGAINED else BannerLook.Tone.LOST
        else -> BannerLook.Tone.INFO
    }
}
