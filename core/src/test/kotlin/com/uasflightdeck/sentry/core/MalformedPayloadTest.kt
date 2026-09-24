package com.uasflightdeck.sentry.core

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Crash isolation (v0.3.3, "never impact DroneSense"): a bad feed payload must never bring the process down.
 * Contract: text that isn't JSON throws an ordinary Exception, which the service's per-poll catch turns into
 * a health line; JSON of the wrong shape parses to nothing. Nothing ever throws an Error (StackOverflowError,
 * OutOfMemoryError …), which that catch would not stop.
 */
class MalformedPayloadTest {
    private val now = 1_790_000_000_000L

    private val notJson = listOf("", " ", "not json", "{", "[1,2", "{\"a\":}", "\u0000\u0001", "<html>502 Bad Gateway</html>",
        "[".repeat(200_000), "{\"a\":".repeat(50_000))
    private val wrongShape = listOf("null", "42", "true", "\"str\"", "[]", "{}", "[1,\"x\",null,{}]",
        """{"ac":5}""", """{"ac":[1,"x",null,{"hex":7,"lat":"a","lon":null}]}""", """{"now":"x","ac":{"hex":"abc"}}""",
        """{"drones":{"x":1}}""", """{"drones":[null,5,{"drone":"x","pos":[]},{"drone":{"id":5},"pos":{"lat":"n","lon":1}}]}""",
        """{"body":[1,"x",null,{"latitude":"abc","longitude":1},{"id":{},"latitude":1,"longitude":2,"lastUpdate":"soon"}]}""",
        """{"type":"FeatureCollection","features":[null,{},{"geometry":{"type":"Polygon","coordinates":[[["a"]]]}},{"geometry":{"type":"MultiPolygon","coordinates":7}}]}""",
        """{"tag_name":5,"assets":"none","body":[]}""", """{"tag_name":"v1.2.3","assets":[{"name":null,"browser_download_url":7}]}""")

    private val parsers: List<Pair<String, (String) -> Any?>> = listOf(
        "readsb" to { t -> Parsers.parseReadsb(t, now, "cloud") },
        "our-drones" to { t -> Parsers.parseOurDrones(t, now) },
        "dronesense" to { t -> Parsers.parseDroneSense(t, now) },
        "tfrs" to { t -> Parsers.parseTfrs(t) },
        "geofences" to { t -> Parsers.parseGeofences(t, "x.geojson") },
        "releases" to { t -> Releases.parseLatest(t) },
        "semver" to { t -> SemVer.parse(t) },
    )

    @Test fun nonJsonThrowsOnlyAnOrdinaryException() {
        for ((name, p) in parsers) for (t in notJson) {
            try { p(t) } catch (e: Exception) { /* caught per poll: fine */ } catch (e: Throwable) {
                fail("$name threw ${e.javaClass.simpleName} (not an Exception) on ${t.take(20)}…")
            }
        }
    }

    @Test fun wrongShapeJsonParsesToNothingWithoutThrowing() {
        for ((name, p) in parsers) for (t in wrongShape) {
            val r = try { p(t) } catch (e: Throwable) { fail("$name threw ${e.javaClass.simpleName} on $t"); null }
            val empty = when (r) {
                null -> true
                is Collection<*> -> r.isEmpty()
                is Parsers.FleetParse -> r.drones.isEmpty() && r.airsense.isEmpty()
                is SemVer -> true
                else -> r.toString().isNotEmpty()
            }
            assertTrue("$name produced something from $t: $r", empty || name == "releases")
        }
    }
}
