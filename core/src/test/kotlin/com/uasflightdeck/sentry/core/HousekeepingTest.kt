package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.3: 0.4.2's housekeeping pop-ups were cut with ".." on the RC Plus (one ~78-char body line at 400 dpi). */
class HousekeepingTest {
    private val cases = listOf(
        EventKind.INTERNET_LOST to "Internet offline",
        EventKind.INTERNET_REGAINED to "Internet back",
        EventKind.SELECTION to "Watching DEMO-7 Pilot, this controller's aircraft.",
        EventKind.SELECTION to "Waiting for this controller's aircraft.",
        EventKind.OWNSHIP_LOST to "Drone position lost",
        EventKind.OWNSHIP_LOST to "Drone position still lost",
        EventKind.OWNSHIP_REGAINED to "Drone position regained · watching DEMO-7 Pilot",
        EventKind.SOUNDS_ON to "Sounds on",
        EventKind.RESTARTED to RestartPolicy.RESTARTED,
        EventKind.RESTARTED to RestartPolicy.ARMED_AFTER_BOOT,
    )

    @Test fun everyBodyFitsOneHeadsUpLine() {
        for (station in listOf(true, false)) for ((k, t) in cases) {
            val (title, body) = Housekeeping.text(k, t, station)!!
            assertTrue("$k '$body' is ${body.length} chars", body.length <= Housekeeping.MAX_BODY)
            assertTrue(title.length <= 30)
            assertTrue(!body.contains(".."))
        }
    }

    @Test fun keyContentKept() {
        assertEquals("Internet offline" to "Cloud traffic, drone feed, TFRs paused; Overwatch station still live",
            Housekeeping.text(EventKind.INTERNET_LOST, "", stationLive = true))
        // without a station it doesn't claim one is live
        assertTrue(!Housekeeping.text(EventKind.INTERNET_LOST, "", stationLive = false)!!.second.contains("Overwatch"))
        assertEquals("Bound aircraft lost" to "Drone position lost; controller cylinders are the fallback",
            Housekeeping.text(EventKind.OWNSHIP_LOST, "Drone position lost", true))
        assertEquals("Sentry restarted" to "Stopped unexpectedly and restarted itself; armed and watching again",
            Housekeeping.text(EventKind.RESTARTED, RestartPolicy.RESTARTED, true))
        assertEquals("Bound aircraft back", Housekeeping.text(EventKind.OWNSHIP_REGAINED, "x", true)!!.first)
        assertNotNull(Housekeeping.text(EventKind.SOUNDS_ON, "", true))
    }
}

class PluralTest {
    @Test fun statusLineCountIsPluralised() {
        assertEquals("1 target", SystemText.count(1, "target"))
        assertEquals("0 targets", SystemText.count(0, "target"))
        assertEquals("12 targets", SystemText.count(12, "target"))
    }
}
