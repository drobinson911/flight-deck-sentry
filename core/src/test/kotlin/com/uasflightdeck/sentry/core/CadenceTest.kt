package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.3.5 range + closure driven callout cadence (owner-approved numbers). [Cadence] is tested row by row, then the
 * engine is driven through each band, the opening/passing sequence and the demo replay (timeline printed).
 */
class CadenceTest {
    private val cfg = SentryConfig()
    private val O = LatLon(39.4, -120.0)
    private val T0 = 1_790_000_000_000L
    private fun sec(n: Int) = T0 + n * 1000L
    private fun own(t: Long, src: OwnshipSource = OwnshipSource.FLEET_FDA) = Ownship("D1", "DEMO-1", O.lat, O.lon, 8000.0, 400.0, t, src)

    /** A point [eastNm] east and [northNm] north of the drone. */
    private fun en(eastNm: Double, northNm: Double) = Geo.fromEN(O, EN(Units.nmToM(eastNm), Units.nmToM(northNm)))
    private fun polar(bearing: Double, nm: Double): LatLon { val b = Math.toRadians(bearing); return en(nm * sin(b), nm * cos(b)) }
    private fun tgt(t: Long, p: LatLon, gs: Double = 0.0, trk: Double = 0.0, hex: String = "abc123", cs: String = "N1234", alt: Double = 8000.0) =
        Target(hex = hex, callsign = cs, lat = p.lat, lon = p.lon, altGeomFt = alt, gsKt = gs, trackDeg = trk, posTimeMs = t, sources = setOf("test"))

    // ── the table, row by row ───────────────────────────────────────────────
    private data class Row(val sev: Severity, val nm: Double, val trend: Trend?, val cpaT: Double?, val cpaNm: Double?,
                           val passingSaid: Boolean, val band: Cadence.Band, val every: Double?)

    @Test fun tableRows() {
        val C = Trend.CONVERGING; val D = Trend.DIVERGING; val P = Trend.PASSING
        val rows = listOf(
            // beyond 3 nm (inside the exit hysteresis): no repeats
            Row(Severity.ADVISORY, 3.1, C, null, null, false, Cadence.Band.NONE, null),
            // diverging without a close pass: no repeats
            Row(Severity.CAUTION, 0.8, D, null, null, false, Cadence.Band.NONE, null),
            // 1-3 nm converging, caution/warning level: 20 s
            Row(Severity.CAUTION, 2.0, C, 80.0, 1.2, false, Cadence.Band.MID, 20.0),
            Row(Severity.WARNING, 1.9, C, 40.0, 0.4, false, Cadence.Band.MID, 20.0),
            // 1-3 nm, advisory level: stays 30 s
            Row(Severity.ADVISORY, 2.0, C, null, null, false, Cadence.Band.ADVISORY, 30.0),
            Row(Severity.ADVISORY, 1.2, P, null, null, false, Cadence.Band.ADVISORY, 30.0),
            // 0.5-1 nm converging (or passing / no velocity): 12 s
            Row(Severity.CAUTION, 0.9, C, 100.0, 0.6, false, Cadence.Band.NEAR, 12.0),
            Row(Severity.CAUTION, 0.6, null, null, null, false, Cadence.Band.NEAR, 12.0),
            // inside 0.5 nm: 6 s, short sentence
            Row(Severity.WARNING, 0.4, C, 10.0, 0.1, false, Cadence.Band.CLOSE, 6.0),
            Row(Severity.WARNING, 0.2, P, null, null, false, Cadence.Band.CLOSE, 6.0),
            // predicted pass inside 0.5 nm within 30 s, from further out: 6 s
            Row(Severity.WARNING, 1.6, C, 29.0, 0.3, false, Cadence.Band.CLOSE, 6.0),
            // ... but not a CPA 31 s out, nor a 30 s CPA that misses by 0.6 nm
            Row(Severity.WARNING, 1.6, C, 31.0, 0.3, false, Cadence.Band.MID, 20.0),
            Row(Severity.CAUTION, 0.9, C, 20.0, 0.6, false, Cadence.Band.NEAR, 12.0),
            // opening after a close pass ("passing, diverging" said): 45 s while inside 3 nm
            Row(Severity.CAUTION, 0.7, D, null, null, true, Cadence.Band.OPENING, 45.0),
            Row(Severity.ADVISORY, 2.9, D, null, null, true, Cadence.Band.OPENING, 45.0),
            // nothing at NONE
            Row(Severity.NONE, 0.2, C, 5.0, 0.0, false, Cadence.Band.NONE, null),
        )
        for (r in rows) {
            val b = Cadence.band(r.sev, r.nm, r.trend, r.cpaT, r.cpaNm, r.passingSaid, cfg)
            assertEquals("$r", r.band, b)
            assertEquals("$r", r.every, Cadence.intervalSec(b, cfg))
        }
        assertTrue(Cadence.Band.CLOSE.short)
        assertTrue(Cadence.Band.values().filter { it != Cadence.Band.CLOSE }.none { it.short })
    }

    // ── the engine through each band ────────────────────────────────────────
    private fun runEngine(n: IntRange, e: AlertEngine = AlertEngine(), zones: List<Zone> = emptyList(),
                          o: (Long) -> Ownship = { own(it) }, targets: (Int) -> List<Target>): List<AlertEvent> {
        e.step(sec(0), o(sec(0)), emptyList(), zones, 0.0)
        return n.flatMap { i -> e.step(sec(i), o(sec(i)), targets(i), zones, 0.0).events }
    }
    private fun secs(ev: List<AlertEvent>) = ev.map { ((it.timeMs - T0) / 1000).toInt() }

    @Test fun farBandNoRepeats() {
        // called at 2.9 nm (advisory), then parked at 3.1 nm (still advisory by the 0.2 nm exit hysteresis): silent
        val ev = runEngine(1..90) { i -> listOf(tgt(sec(i), polar(0.0, if (i == 1) 2.9 else 3.1))) }
        assertEquals(listOf(1), secs(ev))
    }

    @Test fun midBandCautionEvery20s() {
        // controller mode, a 3 nm cylinder: caution at 2 nm, parked (not diverging)
        val cyl = Cylinder("adv", "advisory area", 3.0, 0.0, 3000.0).toZone(O)
        val ev = runEngine(1..65, zones = listOf(cyl), o = { own(it, OwnshipSource.CONTROLLER) }) { i ->
            listOf(tgt(sec(i), polar(90.0, 2.0), alt = 8300.0)) }
        assertEquals(listOf(1, 21, 41, 61), secs(ev))
        assertEquals(EventKind.CYLINDER_ENTRY, ev[0].kind)
        assertTrue(ev.drop(1).all { it.kind == EventKind.PROXIMITY && it.severity == Severity.CAUTION })
    }

    @Test fun advisoryStays30s() {
        val ev = runEngine(1..65) { i -> listOf(tgt(sec(i), polar(0.0, 2.0))) }
        assertEquals(listOf(1, 31, 61), secs(ev))
    }

    @Test fun nearBandEvery12s() {
        val ev = runEngine(1..40) { i -> listOf(tgt(sec(i), polar(0.0, 0.8))) }
        assertEquals(listOf(1, 13, 25, 37), secs(ev))
    }

    @Test fun closeBandEvery6sWithTheShortSentence() {
        // 0.4 nm west, 1,500 ft... drifting in at 10 kt (converging): warning, then the short sentence every 6 s
        val ev = runEngine(1..20) { i -> listOf(tgt(sec(i), polar(270.0, 0.40 - i * 10.0 / 3600.0), gs = 10.0, trk = 90.0, alt = 7800.0)) }
        assertEquals(listOf(1, 7, 13, 19), secs(ev))
        assertTrue(ev[0].text, ev[0].text.startsWith("Warning. Traffic, N1234, west, 2,400 feet, 200 below, converging"))
        assertEquals("Traffic, N1234, west, 2,300 feet, 200 below, closing.", ev[1].text)
        assertEquals("Traffic, N 1 2 3 4, west, 2,300 feet, 200 below, closing.", ev[1].speech)
        assertTrue(ev.drop(1).all { it.severity == Severity.WARNING && it.text.startsWith("Traffic, ") })
    }

    @Test fun predictedPassWithin30sIsTheCloseBandFromFurtherOut() {
        // 0.95 nm east, inbound at 120 kt straight at the drone: CPA 0 nm in 28.5 s -> predictive warning, then 6 s
        val ev = runEngine(1..14) { i -> listOf(tgt(sec(i), polar(90.0, 0.95 - (i - 1) * 120.0 / 3600.0), gs = 120.0, trk = 270.0)) }
        // tick 1 has no velocity history needed (track given): caution + predictive = warning at once
        assertEquals(EventKind.PREDICTIVE, ev[0].kind)
        assertEquals(1, secs(ev)[0])
        // then every 6 s until the 0.5 nm ring (which is not an escalation: it is already warning)
        assertEquals(listOf(1, 7, 13), secs(ev))
        assertTrue(ev.drop(1).all { it.text.endsWith("closing.") })
    }

    @Test fun escalationInterruptsTheTimer() {
        // advisory at 2 nm (next repeat would be 30 s later), caution 2 s later, warning 2 s after that
        val ev = runEngine(1..5) { i -> listOf(tgt(sec(i), polar(0.0, when { i < 3 -> 2.0; i < 5 -> 0.9; else -> 0.4 }))) }
        assertEquals(listOf(1 to Severity.ADVISORY, 3 to Severity.CAUTION, 5 to Severity.WARNING), ev.map { ((it.timeMs - T0) / 1000).toInt() to it.severity })
    }

    /**
     * A fly-through: westbound at 150 kt along a line 0.2 nm north of the drone, from 2.5 nm east to 3.5 nm west.
     * Converging cadence on the way in, ONE "passing, diverging" as it opens, then every 45 s inside 3 nm, then "clear".
     */
    @Test fun openingAfterAClosePass() {
        val ev = runEngine(1..150) { i -> listOf(tgt(sec(i), en(2.5 - i * 150.0 / 3600.0, 0.2), gs = 150.0, trk = 270.0)) }
        println("── synthetic fly-through, 0.2 nm abeam at 150 kt ──")
        ev.forEach { println("t+${(it.timeMs - T0) / 1000}s  ${it.severity.label.padEnd(8)} ${it.kind.name.padEnd(12)} ${it.text}") }
        val passing = ev.filter { it.kind == EventKind.PASSING }
        assertEquals(1, passing.size)
        assertEquals("N1234 passing, diverging.", passing[0].text)
        val tPass = secs(passing)[0]
        // CPA is at t = 60 s
        assertTrue("passing at $tPass", tPass in 59..63)
        val after = ev.filter { it.timeMs > passing[0].timeMs }
        // then 45 s later a diverging repeat (1.9 nm west), then clear once when beyond 3.2 nm (t = 137 s)
        assertEquals(listOf(tPass + 45), secs(after.filter { it.kind == EventKind.PROXIMITY }))
        assertTrue(after.first { it.kind == EventKind.PROXIMITY }.text.endsWith("diverging."))
        val clear = after.single { it.kind == EventKind.CLEAR }
        assertEquals("N1234 clear, diverging.", clear.text)
        assertTrue(secs(listOf(clear))[0] in 136..139)
        // on the way in, the close band (<0.5 nm or CPA < 30 s) repeats every 6 s with the short sentence
        val before = ev.filter { it.timeMs < passing[0].timeMs && it.kind == EventKind.PROXIMITY && it.text.endsWith("closing.") }
        assertTrue(before.size >= 3)
        secs(before).zipWithNext().forEach { (a, b) -> assertEquals(6, b - a) }
    }

    @Test fun twoAircraftTheCloserOneFirst() {
        val e = AlertEngine()
        e.step(sec(0), own(sec(0)), emptyList(), emptyList(), 0.0)
        val ev = e.step(sec(1), own(sec(1)), listOf(
            tgt(sec(1), polar(0.0, 0.9), hex = "aaa111", cs = "N1111"),
            tgt(sec(1), polar(180.0, 0.6), hex = "bbb222", cs = "N2222"),
        ), emptyList(), 0.0).events
        assertEquals(listOf("N2222", "N1111"), ev.map { it.text.substringAfter("Traffic, ").substringBefore(",") })
    }

    // ── the demo replay with the new cadence (timeline printed for the README / report) ─────────────
    private val assets = File(System.getProperty("sentry.assets") ?: "../app/src/main/assets")
    private fun read(name: String) = File(assets, "replay/$name").readText()
    private val fmt = SimpleDateFormat("HH:mm:ss").apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }

    @Test fun demoTimeline() {
        val sc = DemoReplayFixture.load(read("demo_drone.json"), read("n388km_merged.json"), read("tfr_demo.json"))
        val e = AlertEngine()
        val ev = ArrayList<AlertEvent>()
        var t = sc.startMs
        while (t <= sc.endMs) { ev += e.step(t, sc.ownshipAt(t), sc.trafficAt(t), sc.zones, 0.0).events; t += 1000 }
        val n = ev.filter { it.hex == DemoReplayFixture.HEX }
        println("── demo 2026-09-23, DEMO-1 vs N388KM, v0.3.5 cadence ──")
        n.forEach { println("| ${fmt.format(Date(it.timeMs))} | ${it.severity.label} | ${it.kind} | \"${it.text}\" |") }

        // first warning still 43 s before the pass
        val warn = n.first { it.severity == Severity.WARNING }
        assertTrue("first warning at ${fmt.format(Date(warn.timeMs))}", kotlin.math.abs(DemoReplayFixture.CLOSEST_MS - 43_000 - warn.timeMs) <= 1000)
        // inside the close band the repeats are 6 s apart (the TFR entry resets the timer)
        val closeSpaced = n.filter { it.timeMs in (DemoReplayFixture.CLOSEST_MS - 20_000)..DemoReplayFixture.CLOSEST_MS }
        closeSpaced.zipWithNext().forEach { (a, b) -> assertTrue("${a.text} -> ${b.text}", b.timeMs - a.timeMs <= 6000) }
        // exactly one "passing, diverging", within 2 s of the closest approach
        val p = n.single { it.kind == EventKind.PASSING }
        assertTrue(kotlin.math.abs(p.timeMs - DemoReplayFixture.CLOSEST_MS) <= 2000)
        // nothing more often than every 45 s while it opens, then one clear, then silence
        val opening = n.filter { it.timeMs > p.timeMs }
        assertEquals(EventKind.CLEAR, opening.last().kind)
        opening.dropLast(1).forEach { assertTrue(it.text.endsWith("diverging.")) }
        (listOf(p) + opening).zipWithNext().dropLast(1).forEach { (a, b) -> assertTrue(b.timeMs - a.timeMs >= 45_000) }
        assertEquals(1, n.count { it.kind == EventKind.CLEAR })
    }
}
