package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Internet lost / regained: stacked evidence, 10 s hysteresis. */
class ConnectivityTest {
    private fun s(n: Int) = n * 1000L

    @Test fun onlineAtStartIsSilent() {
        val m = InternetMonitor()
        for (i in 0..20) assertNull(m.step(s(i), true, s(i), null))
        assertEquals(InternetMonitor.State.ONLINE, m.state)
    }

    @Test fun lostAfter10sAndRegainedAfter10s() {
        val m = InternetMonitor()
        for (i in 0..12) m.step(s(i), true, s(i), null)
        // network drops at 13 s
        val ev = (13..40).mapNotNull { m.step(s(it), false, s(12), null) }
        assertEquals(listOf(EventKind.INTERNET_LOST to 23_000L), ev.map { it.kind to it.timeMs })
        assertEquals("Internet offline", ev.single().text)
        val back = (41..60).mapNotNull { m.step(s(it), true, s(it), null) }
        assertEquals(listOf(EventKind.INTERNET_REGAINED to 51_000L), back.map { it.kind to it.timeMs })
    }

    @Test fun aBlipShorterThanTheHysteresisIsNotAlerted() {
        val m = InternetMonitor()
        for (i in 0..12) m.step(s(i), true, s(i), null)
        for (i in 13..18) assertNull(m.step(s(i), false, s(12), null))
        for (i in 19..40) assertNull(m.step(s(i), true, s(i), null))
    }

    @Test fun networkUpButTheWorkerUnreachableIsOffline() {
        val m = InternetMonitor()
        for (i in 0..12) m.step(s(i), true, s(i), null)
        // Wi-Fi without internet: requests fail at the network level
        val ev = (13..30).mapNotNull { m.step(s(it), true, s(12), s(it)) }
        assertEquals(EventKind.INTERNET_LOST, ev.single().kind)
    }

    @Test fun silenceLongerThanTheProbeWindowIsOffline() {
        val m = InternetMonitor()
        for (i in 0..12) m.step(s(i), true, s(i), null)
        assertEquals(InternetMonitor.State.OFFLINE, m.observed(s(40), true, s(12), null))
    }

    @Test fun offlineAtStartIsAlerted() {
        val m = InternetMonitor()
        val ev = (0..15).mapNotNull { m.step(s(it), false, null, null) }
        assertEquals(EventKind.INTERNET_LOST, ev.single().kind)
    }
}
