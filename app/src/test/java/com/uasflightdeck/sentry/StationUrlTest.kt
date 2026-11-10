package com.uasflightdeck.sentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationUrlTest {
    @Test fun normalizesWhatAPilotTypes() {
        assertEquals("http://192.168.1.20:8080", Settings.normalizeStationBase("192.168.1.20"))
        assertEquals("http://192.168.1.20:9000", Settings.normalizeStationBase("192.168.1.20:9000/"))
        assertEquals("http://t91.local:8080", Settings.normalizeStationBase("http://t91.local/data/aircraft.json"))
        assertEquals("https://x.example:8443", Settings.normalizeStationBase(" https://x.example:8443 "))
        assertNull(Settings.normalizeStationBase("  "))
    }
}

class StationCandidatesTest {
    private val pi = "http://192.168.0.133:8080"
    private val mb = "http://192.168.0.133:80"

    @Test fun bareIpTriesPiThenMicroBar() {
        assertEquals(listOf(pi, mb), StationCandidates.order("192.168.0.133", null, null))
    }
    @Test fun rememberedPortGoesFirst() {
        assertEquals(listOf(mb, pi), StationCandidates.order("192.168.0.133", null, mb))
        assertEquals(listOf(pi, mb), StationCandidates.order("192.168.0.133", null, pi))
    }
    @Test fun rememberedFromOtherHostIgnored() {
        assertEquals(listOf(pi, mb), StationCandidates.order("192.168.0.133", null, "http://10.0.0.1:80"))
    }
    @Test fun beaconFromSameHostWins() {
        assertEquals(listOf(mb, pi), StationCandidates.order("192.168.0.133", mb, pi))
        assertEquals(listOf("http://192.168.0.133:8081", pi, mb), StationCandidates.order("192.168.0.133", "http://192.168.0.133:8081", null))
    }
    @Test fun beaconFromOtherHostIsLastFallback() {
        assertEquals(listOf(pi, mb, "http://10.0.0.9:8080"), StationCandidates.order("192.168.0.133", "http://10.0.0.9:8080", null))
    }
    @Test fun explicitPortUntouched() {
        assertEquals(listOf("http://192.168.0.133:80"), StationCandidates.order("192.168.0.133:80", null, pi))
        assertEquals(listOf("http://h:9000", "http://10.0.0.9:8080"), StationCandidates.order("http://h:9000/", "http://10.0.0.9:8080", null))
        assertEquals(listOf("https://x.example:8443"), StationCandidates.order("https://x.example:8443", null, null))
    }
    @Test fun noTypedUrlUsesBeaconOnly() {
        assertEquals(listOf(mb), StationCandidates.order("", mb, null))
        assertEquals(emptyList<String>(), StationCandidates.order("", null, null))
    }
    @Test fun fullUrlWithoutPortStillTriesBoth() {
        assertEquals(listOf("http://t91.local:8080", "http://t91.local:80"), StationCandidates.order("http://t91.local/data/aircraft.json", null, null))
    }
    @Test fun shapeCheck() {
        assertEquals(true, StationCandidates.isReadsbShaped("""{"now":1,"aircraft":[]}"""))
        assertEquals(true, StationCandidates.isReadsbShaped("""{"ac":[{"hex":"a"}]}"""))
        assertEquals(false, StationCandidates.isReadsbShaped("<html>hi</html>"))
        assertEquals(false, StationCandidates.isReadsbShaped("""{"ok":true}"""))
    }
    @Test fun label() { assertEquals("192.168.0.133:80", StationCandidates.label(mb)) }
}

/** Read-only live probe; runs only with SENTRY_LIVE_HOST set (GETs only). */
class StationLiveProbeTest {
    @Test fun bareHostReachesAStation() {
        val host = System.getenv("SENTRY_LIVE_HOST") ?: return
        var ok: String? = null; var n = -1
        for (b in StationCandidates.order(host, null, null)) {
            try {
                val c = java.net.URL("$b/data/aircraft.json").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 4000
                val text = c.inputStream.bufferedReader().readText()
                if (!StationCandidates.isReadsbShaped(text)) continue
                n = com.uasflightdeck.sentry.core.Parsers.parseReadsb(text, System.currentTimeMillis(), "station").size
                ok = b; break
            } catch (e: Exception) { println("LIVE miss $b: $e") }
        }
        println("LIVE answered=$ok label=${ok?.let { StationCandidates.label(it) }} ac=$n")
        org.junit.Assert.assertNotNull(ok)
    }
}
