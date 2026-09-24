package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * The bundled voice (v0.3.5): every sentence Sentry can say must turn into clips the bank has, with nothing but
 * free-typed names (a drone name with a space) spelled out. The bank is the real one in the app's assets.
 */
class VoiceGrammarTest {
    private val assets = File(System.getProperty("sentry.assets") ?: "../app/src/main/assets")
    private val manifest = VoiceGrammar.parseManifest(File(assets, "voice/manifest.tsv").readText())
    private val g = VoiceGrammar(manifest)

    private fun strict(s: String): VoiceGrammar.Result {
        val r = g.tokenize(s)
        assertTrue("missing clips for \"$s\": ${r.missing}", r.missing.isEmpty())
        assertTrue("words spelled out in \"$s\": ${r.spelled}", r.spelled.isEmpty())
        assertTrue("nothing to say for \"$s\"", r.clips.isNotEmpty())
        return r
    }

    @Test fun bankHasEveryRuleClipAndEveryManifestIdHasAFile() {
        VoiceGrammar.RULE_IDS.forEach { assertTrue("bank lacks $it", it in manifest) }
        val files = File(assets, "voice").listFiles()!!.filter { it.name.endsWith(".ogg") }.map { it.nameWithoutExtension }.toSet()
        assertEquals(manifest.keys, files)
        val bytes = File(assets, "voice").listFiles()!!.sumOf { it.length() }
        assertTrue("bank is $bytes bytes", bytes < 3 * 1024 * 1024)
    }

    @Test fun theOwnersExamples() {
        assertEquals(listOf("traffic", ",", "l_n", "n3", "n8", "n8", "l_k", "l_m", ",", "west", ",", "t1", "h5",
            "feet", ",", "h2", "below", ",", "closing"),
            strict("Traffic, N 3 8 8 K M, west, 1,500 feet, 200 below, closing.").tokens)
        assertEquals(listOf("drone_position_lost"), strict("Drone position lost").tokens)
        assertEquals(listOf("sentry_armed"), strict("Sentry armed").tokens)
        assertEquals(listOf("l_n", "n3", "n8", "n8", "l_k", "l_m", "passing_diverging"), strict("N 3 8 8 K M passing, diverging.").tokens)
        assertEquals(listOf("n3", "point", "n0", "miles"), strict("3.0 miles").clips)
        assertEquals(listOf("t1", "h2", "feet"), strict("1,200 feet").clips)
        assertEquals(listOf("t2", "h7", "above"), strict("2,700 above").clips)
        assertEquals(listOf("t12", "h3", "below"), strict("12,300 below").clips)
        assertEquals(listOf("n45", "thousand", "h1", "n5"), g.number(45_105))
        // a TFR's number is read digit by digit
        assertEquals(listOf("traffic", "entering", "tfr", "n6", "n5", "n0", "n2", "n1"), strict("Traffic entering TFR 0 0000, N 1, north.").clips.take(8))
    }

    @Test fun freeTypedNamesAreSpelledOrReplacedByTheirKind() {
        val w = g.tokenize("Watching DEMO-1 Pilot, this controller's aircraft.")
        assertEquals(listOf("DEMO-1", "Pilot"), w.spelled)
        assertTrue(w.missing.isEmpty())
        assertEquals(listOf("watching", "l_u", "l_r", "n3", "n1", "l_r", "l_o", "l_b", "l_i", "l_n", "l_s", "l_o", "l_n", "this_controllers_aircraft"), w.clips)
        assertEquals(listOf("traffic", "entering", "protected_area"), strict("Traffic entering Bravo LZ, N 1, north, 1.0 miles, 300 above, converging.").clips.take(3))
        assertEquals(listOf("traffic", "entering", "ops_area"), strict("Traffic entering ops area, N 1, north, 1.0 miles, 300 above.").clips.take(3))
        assertEquals(listOf("traffic", "inside", "geofence", "l_n"), strict("Traffic inside geofence my-upload.geojson, N 1, north.").clips.take(4))
    }

    @Test fun sampleFileMatchesTheGrammar() {
        val lines = File("../tools/voicebank/sample.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(SystemPhrases.VOICE_TEST_SPEECH, lines[0])
        assertEquals(strict(lines[0]).tokens.joinToString(" "), lines.getOrNull(1))
    }

    /** Samples in an Ogg Vorbis file: the granule position of its last page. */
    private fun oggSamples(f: File): Long {
        val b = f.readBytes()
        var i = b.size - 4
        while (i >= 0 && !(b[i] == 'O'.code.toByte() && b[i + 1] == 'g'.code.toByte() && b[i + 2] == 'g'.code.toByte() && b[i + 3] == 'S'.code.toByte())) i--
        var g = 0L
        for (k in 7 downTo 0) g = (g shl 8) or (b[i + 6 + k].toLong() and 0xff)
        return g
    }

    /**
     * What ClipVoice plays for the sample warning: the real clips back to back (their true lengths from the bank)
     * with the punctuation pauses, and every clip exists. ClipVoice logs this same timeline on the device.
     */
    @Test fun sampleWarningTimelineFromTheRealClips() {
        val r = strict(SystemPhrases.VOICE_TEST_SPEECH)
        val ms = { id: String -> (oggSamples(File(assets, "voice/$id.ogg")) * 1000 / 22_050).toInt() }
        val tl = ClipTimeline.build(r.tokens, ms)
        println("sample warning, ${ClipTimeline.totalMs(tl)} ms: " + tl.joinToString(" ") { "${it.id}@${it.startMs}" })
        tl.zipWithNext().forEach { (a, b) -> assertEquals(a.startMs + a.lengthMs, b.startMs) }
        tl.filter { it.id != "," && it.id != "." }.forEach { assertTrue("${it.id} is ${it.lengthMs} ms", it.lengthMs in 150..2500) }
        assertTrue("${ClipTimeline.totalMs(tl)} ms", ClipTimeline.totalMs(tl) in 5_000..14_000)
    }

    /** Every number the phrasing can produce: distance 0-50 nm, vertical to ±30,000 ft, CPA seconds. */
    @Test fun everyNumberThePhrasingCanProduce() {
        var d = 0.0
        while (d <= 50.0) { strict(Phrasing.spokenDistance(d)); d += 0.01 }
        var v = -30_000.0
        while (v <= 30_000.0) { strict(Phrasing.spokenVertical(v)); v += 50.0 }
        strict(Phrasing.spokenVertical(null))
        for (s in 0..200) strict("closest 1,200 feet in ${Phrasing.seconds(s.toDouble())}")
    }

    // ── enumerate what the engine, selector, health monitor and app really say ─────────────────────────────
    private val O = LatLon(39.4, -120.0)
    private val T0 = 1_790_000_000_000L
    private fun sec(n: Int) = T0 + n * 1000L
    private fun at(bearing: Double, nm: Double): LatLon {
        val b = Math.toRadians(bearing); val m = Units.nmToM(nm)
        return Geo.fromEN(O, EN(m * sin(b), m * cos(b)))
    }

    private fun engineSentences(): Set<String> {
        val out = LinkedHashSet<String>()
        val tfr = Zone("t", "0/0000", ZoneKind.TFR, listOf(Polygon(listOf(at(45.0, 9.0), at(135.0, 9.0), at(225.0, 9.0), at(315.0, 9.0)))),
            AltLimit.SURFACE, AltLimit(18_000.0, AltRef.MSL))
        val geo = Parsers.circleZone("g", "imported", O, 5.0).copy(kind = ZoneKind.GEOFENCE)
        val zoneSets = listOf(emptyList(), listOf(tfr), listOf(geo))
        for (zones in zoneSets) for (b in 0 until 360 step 45) for (nm in listOf(0.1, 0.3, 0.45, 0.7, 0.95, 1.5, 2.4, 2.95))
            for (dv in listOf(null, -9_000.0, -1_500.0, -200.0, 0.0, 300.0, 1_900.0)) for (motion in 0..3) {
                val e = AlertEngine()
                val own = { t: Long -> Ownship("D1", "DEMO-1", O.lat, O.lon, 8000.0, 400.0, t, OwnshipSource.FLEET_FDA) }
                e.step(sec(0), own(sec(0)), emptyList(), zones, 0.0).events.forEach { out += it.speech }
                // motion: 0 parked, 1 inbound, 2 outbound, 3 crossing
                val trk = when (motion) { 1 -> b + 180.0; 2 -> b.toDouble(); 3 -> b + 90.0; else -> 0.0 }
                val gs = if (motion == 0) 0.0 else 140.0
                for (i in 1..14) {
                    val dist = when (motion) { 1 -> nm - i * gs / 3600.0; 2 -> nm + i * gs / 3600.0; else -> nm }.coerceAtLeast(0.02)
                    val p = at(b.toDouble(), dist)
                    val t = Target(hex = "abc123", callsign = "N388KM", lat = p.lat, lon = p.lon,
                        altGeomFt = dv?.let { 8000.0 + it }, reportsGround = dv == null, gsKt = if (dv == null) 160.0 else gs,
                        trackDeg = trk, posTimeMs = sec(i))
                    e.step(sec(i), own(sec(i)), listOf(t), zones, 0.0).events.forEach { out += it.speech }
                }
                // it lands, or vanishes, or the drone drops out
                e.step(sec(15), own(sec(15)), listOf(Target(hex = "abc123", callsign = "N388KM", lat = O.lat, lon = O.lon,
                    reportsGround = true, gsKt = 10.0, posTimeMs = sec(15))), zones, 0.0).events.forEach { out += it.speech }
            }
        // track lost, drone position lost / regained / still lost, traffic stale / restored, the controller cylinders
        val e = AlertEngine()
        val own = { t: Long -> Ownship("D1", "DEMO-1", O.lat, O.lon, 8000.0, 400.0, t, OwnshipSource.FLEET_FDA) }
        val near = { t: Long -> Target(hex = "abc123", callsign = "N388KM", lat = at(0.0, 0.8).lat, lon = at(0.0, 0.8).lon, altGeomFt = 8000.0, posTimeMs = t) }
        e.step(sec(0), own(sec(0)), listOf(near(sec(0))), emptyList(), 0.0).events.forEach { out += it.speech }
        e.step(sec(1), own(sec(1)), emptyList(), emptyList(), 40.0).events.forEach { out += it.speech }
        e.step(sec(2), own(sec(2)), emptyList(), emptyList(), 0.0).events.forEach { out += it.speech }
        for (i in 3..200) e.step(sec(i), own(sec(2)), emptyList(), emptyList(), 0.0).events.forEach { out += it.speech }
        e.step(sec(201), own(sec(201)), emptyList(), emptyList(), 0.0).events.forEach { out += it.speech }
        // called, then it climbs out of the protected volume: "clear." with no trend
        val up = AlertEngine()
        val v = { t: Long, alt: Double -> Target(hex = "abc123", callsign = "N388KM", lat = at(0.0, 0.8).lat, lon = at(0.0, 0.8).lon, altGeomFt = alt, posTimeMs = t) }
        up.step(sec(0), own(sec(0)), emptyList(), emptyList(), 0.0)
        up.step(sec(1), own(sec(1)), listOf(v(sec(1), 8000.0)), emptyList(), 0.0).events.forEach { out += it.speech }
        up.step(sec(2), own(sec(2)), listOf(v(sec(2), 11_000.0)), emptyList(), 0.0).events.forEach { out += it.speech }
        val ex = AlertEngine(externalSelection = true)
        for (i in 0..200) ex.step(sec(i), if (i < 3 || i > 150) own(sec(i)) else own(sec(2)), emptyList(), emptyList(), 0.0).events.forEach { out += it.speech }
        val ctl = AlertEngine(externalSelection = true)
        val cyls = listOf(Cylinder("a", "ops area", 1.0, 0.0, 3000.0), Cylinder("b", "advisory area", 3.0, 0.0, 3000.0), Cylinder("c", "Bravo LZ", 2.0, 0.0, 3000.0))
        for (c in cyls) for (i in 0..40) {
            val o = Ownship("ctl", "this controller", O.lat, O.lon, 8000.0, 0.0, sec(i), OwnshipSource.CONTROLLER)
            val p = at(200.0, 3.5 - i * 0.1)
            ctl.step(sec(i), o, listOf(Target(hex = "c${c.id}", callsign = "N388KM", lat = p.lat, lon = p.lon, altGeomFt = 8400.0,
                gsKt = 360.0, trackDeg = 20.0, posTimeMs = sec(i))), listOf(c.toZone(O)), 0.0).events.forEach { out += it.speech }
        }
        return out
    }

    private fun selectorAndHealthSentences(): Set<String> {
        val out = LinkedHashSet<String>()
        val serial = "1581F7K3C251F00C9B34"
        val d = { t: Long -> Ownship("ds:1", "DEMO-1", O.lat, O.lon, 8000.0, 400.0, t, OwnshipSource.FLEET_DRONESENSE, callsign = "DEMO-1", serial = serial) }
        for (pinned in listOf("", serial)) {
            val s = DroneSelector(DroneSelector.SelectorConfig(pinnedSerial = pinned))
            for (i in 0..120) {
                val drones = if (i in 10..40 || i > 100) listOf(d(sec(i))) else emptyList()
                val fix = if (i in 50..80) null else ControllerFix(O.lat, O.lon, 5100.0, 5.0, sec(i), "test")
                s.step(sec(i), drones, fix).events.forEach { out += it.speech }
            }
        }
        val h = HealthMonitor()
        SystemPhrases.HEALTH_SOURCES.forEach { (k, n) -> h.register(k, n, 10.0); h.setEnabled(k, true, T0) }
        h.step(T0)
        h.step(sec(20)).forEach { out += it.speech }
        SystemPhrases.HEALTH_SOURCES.forEach { (k, _) -> h.ok(k, sec(21)) }
        h.step(sec(21)).forEach { out += it.speech }
        h.step(sec(40)).forEach { out += it.speech }
        return out
    }

    @Test fun everySentenceTheAppCanSayTokenisesFully() {
        val engine = engineSentences()
        val sel = selectorAndHealthSentences()
        val all = engine + sel + SystemPhrases.ALL
        println("${all.size} distinct sentences (${engine.size} from the engine sweep, ${sel.size} selector/health, ${SystemPhrases.ALL.size} app)")
        // every template the engine has must show up in the sweep
        for (needle in listOf("Warning. Traffic, ", "Caution. Traffic, ", "Traffic, N 3 8 8 K M", "closest ", "Traffic inside TFR 0 0000",
                "Traffic inside geofence", "Traffic entering ops area", "Traffic entering Bravo LZ", "passing, diverging.", " clear.",
                "clear, diverging.", "track lost.", "on the ground.", "closing.", "altitude unknown", "same altitude",
                "Drone position lost", "Drone position still lost", "Drone position regained", "Traffic data stale", "Traffic data restored"))
            assertTrue("sweep never produced \"$needle\"", engine.any { it.contains(needle) })
        for (needle in listOf("Watching U R 3 1, this controller's aircraft.", "Waiting for this controller's aircraft.",
                "No aircraft pinned. Protecting this controller.", "Controller GPS unavailable. Nothing protected.", "Controller GPS regained.",
                "Drone feed not reachable", "Station link regained", "T F R data lost"))
            assertTrue("never produced \"$needle\" (got $sel)", sel.any { it.contains(needle) })
        var spelledOnly = 0
        for (s in all) {
            val r = g.tokenize(s)
            assertTrue("missing clips for \"$s\": ${r.missing}", r.missing.isEmpty())
            // the only words allowed to fall back to spelling are drone names (DEMO-1) and free-typed zone names
            assertTrue("\"$s\" spelled ${r.spelled}", r.spelled.all { it == "DEMO-1" })
            if (r.spelled.isNotEmpty()) spelledOnly++
        }
        // a sample of real sentences + their clip sequences, for tools/voicebank/gen.py --check (ASR of stitched audio)
        val pick = all.filter { it.contains("N 3 8 8 K M") }.shuffled(java.util.Random(7)).take(18) + sel.take(6) + SystemPhrases.ALL
        File("build").mkdirs()
        File("build/voice-sentences.tsv").writeText(pick.joinToString("") { "$it\t${g.tokenize(it).tokens.joinToString(" ")}\n" })
    }
}
