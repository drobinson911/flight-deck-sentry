package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.3, bug from the 0.4.2 emulator pass: when the bound aircraft came back after the 30 s fallback, "Bound aircraft
 * acquired" (selector) and "Bound aircraft back" (engine) posted in the same tick and only the second was visible.
 * Now: "acquired" only for the first bind of a session; every regain is ONE message ("back").
 */
class RegainMessageTest {
    private val T0 = 1_790_000_000_000L
    private fun sec(n: Int) = T0 + n * 1000L
    private val PIN = "1581DEMO000000000007"
    private fun drone(n: Int) = Ownship("d7", "DEMO-7", 39.4, -120.0, 8000.0, 300.0, sec(n), OwnshipSource.FLEET_DRONESENSE,
        callsign = "DEMO-7", serial = PIN, speedMs = 0.0)

    /** The service's live path: selector, engine, merged events, then the planner's housekeeping pop-ups. */
    private fun run(present: (Int) -> Boolean, controller: Boolean, range: IntRange): Map<Int, List<OutputPlanner.Output>> {
        val sel = DroneSelector(DroneSelector.SelectorConfig(pinnedSerial = PIN))
        val eng = AlertEngine(externalSelection = true)
        val mutes = MuteBook()
        val out = LinkedHashMap<Int, List<OutputPlanner.Output>>()
        for (n in range) {
            val fix = if (controller) ControllerFix(39.43, -120.03, 5100.0, 4.0, sec(n)) else null
            val s = sel.step(sec(n), if (present(n)) listOf(drone(n)) else emptyList(), fix)
            val r = eng.step(sec(n), s.ownship, emptyList(), emptyList(), 0.0)
            out[n] = OutputPlanner.plan(DroneSelector.merge(s.events, r.events), mutes, AlertStyle.STANDARD, sec(n))
        }
        return out
    }

    private fun popups(o: List<OutputPlanner.Output>) =
        o.filter { it.banner == OutputPlanner.BannerAction.POPUP && it.event.kind in setOf(EventKind.SELECTION, EventKind.OWNSHIP_REGAINED, EventKind.OWNSHIP_ACQUIRED) }

    private fun check(controller: Boolean) {
        // present 0-19, absent 20-69 (past the 15 s lost + 30 s fallback), back at 70
        val out = run({ it < 20 || it >= 70 }, controller, 0..80)
        val first = popups(out.getValue(0))
        assertEquals("first bind: ${first.map { it.event }}", listOf(EventKind.SELECTION), first.map { it.event.kind })
        assertTrue(first.single().event.text.startsWith("Watching DEMO-7"))
        val back = popups(out.getValue(70))
        assertEquals("regain (controller=$controller): ${back.map { it.event }}", 1, back.size)
        assertEquals(EventKind.OWNSHIP_REGAINED, back.single().event.kind)
        assertTrue(back.single().sounds)
        // nothing else about the bound aircraft between 71 and 80
        assertTrue((71..80).all { popups(out.getValue(it)).isEmpty() })
    }

    @Test fun regainAfterFallback_oneMessage_noControllerGps() = check(controller = false)
    @Test fun regainAfterFallback_oneMessage_controllerGps() = check(controller = true)

    @Test fun shortDropOut_engineSaysBack_once() {
        // absent 20-25 only (lost at 15 s of age, selector still holding PINNED): the engine's "regained" is the message
        val out = run({ it < 20 || it >= 36 }, controller = false, range = 0..45)
        val back = (36..45).flatMap { popups(out.getValue(it)) }
        assertEquals(back.map { it.event }.toString(), 1, back.size)
        assertEquals(EventKind.OWNSHIP_REGAINED, back.single().event.kind)
    }
}
