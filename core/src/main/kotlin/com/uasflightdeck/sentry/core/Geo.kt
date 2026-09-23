package com.uasflightdeck.sentry.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit conversions. Aviation convention: horizontal distance in NAUTICAL miles. */
object Units {
    const val M_PER_NM = 1852.0
    const val M_PER_FT = 0.3048
    const val MS_PER_KT = 1852.0 / 3600.0      // 1 kt in m/s
    const val FT_PER_NM = M_PER_NM / M_PER_FT  // 6076.1 ft

    fun nmToM(nm: Double) = nm * M_PER_NM
    fun mToNm(m: Double) = m / M_PER_NM
    fun ftToM(ft: Double) = ft * M_PER_FT
    fun mToFt(m: Double) = m / M_PER_FT
    fun ktToMs(kt: Double) = kt * MS_PER_KT
    fun msToKt(ms: Double) = ms / MS_PER_KT
    /** feet per minute → m/s */
    fun fpmToMs(fpm: Double) = fpm * M_PER_FT / 60.0
}

data class LatLon(val lat: Double, val lon: Double)

/** Local east/north offset in metres from some origin. */
data class EN(val e: Double, val n: Double) {
    operator fun plus(o: EN) = EN(e + o.e, n + o.n)
    operator fun minus(o: EN) = EN(e - o.e, n - o.n)
    operator fun times(k: Double) = EN(e * k, n * k)
    infix fun dot(o: EN) = e * o.e + n * o.n
    val norm: Double get() = hypot(e, n)
}

object Geo {
    /** IUGG mean Earth radius. */
    const val EARTH_R_M = 6_371_008.8

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    /** Great-circle (haversine) distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R_M * atan2(sqrt(a), sqrt(1 - a))
    }

    fun distanceNm(a: LatLon, b: LatLon) = Units.mToNm(distanceM(a.lat, a.lon, b.lat, b.lon))

    /** Initial great-circle bearing from 1 to 2, degrees true [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1); val p2 = rad(lat2); val dl = rad(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normDeg(deg(atan2(y, x)))
    }

    fun normDeg(d: Double): Double { val r = d % 360.0; return if (r < 0) r + 360.0 else r }

    /**
     * Local tangent-plane (equirectangular) offset of [p] from [origin], metres.
     * Accurate to well under 0.5% inside the 30 nm radius Sentry cares about,
     * which is what CPA and polygon-edge distance need.
     */
    fun toEN(origin: LatLon, p: LatLon): EN {
        val n = rad(p.lat - origin.lat) * EARTH_R_M
        var dLon = p.lon - origin.lon
        if (dLon > 180) dLon -= 360.0
        if (dLon < -180) dLon += 360.0
        val e = rad(dLon) * EARTH_R_M * cos(rad((p.lat + origin.lat) / 2))
        return EN(e, n)
    }

    fun fromEN(origin: LatLon, v: EN): LatLon {
        val lat = origin.lat + deg(v.n / EARTH_R_M)
        val lon = origin.lon + deg(v.e / (EARTH_R_M * cos(rad((lat + origin.lat) / 2))))
        return LatLon(lat, lon)
    }

    /** Velocity vector (east, north) in m/s from ground speed + true track. */
    fun velocity(gsKt: Double, trackDeg: Double): EN {
        val v = Units.ktToMs(gsKt)
        return EN(v * sin(rad(trackDeg)), v * cos(rad(trackDeg)))
    }

    /** 8-point compass word for a bearing, e.g. 225 -> "southwest". */
    fun cardinalWord(bearing: Double): String = CARDINAL_WORDS[cardinalIndex(bearing)]
    fun cardinalAbbrev(bearing: Double): String = CARDINAL_ABBR[cardinalIndex(bearing)]
    private fun cardinalIndex(b: Double) = (((normDeg(b) + 22.5) / 45.0).toInt()) % 8
    private val CARDINAL_WORDS = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
    private val CARDINAL_ABBR = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** Ray-casting point-in-ring (ring as lat/lon, closed or open). */
    fun pointInRing(p: LatLon, ring: List<LatLon>): Boolean {
        var inside = false
        val n = ring.size
        if (n < 3) return false
        var j = n - 1
        for (i in 0 until n) {
            val a = ring[i]; val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat)) {
                val xCross = (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
                if (p.lon < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    fun pointInPolygon(p: LatLon, poly: Polygon): Boolean =
        pointInRing(p, poly.outer) && poly.holes.none { pointInRing(p, it) }

    /** Shortest distance (m) from [p] to the boundary of [ring]; 0 if inside is NOT implied. */
    fun distanceToRingM(p: LatLon, ring: List<LatLon>): Double {
        if (ring.isEmpty()) return Double.POSITIVE_INFINITY
        var best = Double.POSITIVE_INFINITY
        val pts = ring.map { toEN(p, it) }
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            best = minOf(best, segmentDistance(EN(0.0, 0.0), a, b))
        }
        return best
    }

    /** Distance (m) from point to polygon: 0 when inside. */
    fun distanceToPolygonM(p: LatLon, poly: Polygon): Double =
        if (pointInPolygon(p, poly)) 0.0 else distanceToRingM(p, poly.outer)

    private fun segmentDistance(p: EN, a: EN, b: EN): Double {
        val ab = b - a
        val len2 = ab dot ab
        val t = if (len2 == 0.0) 0.0 else (((p - a) dot ab) / len2).coerceIn(0.0, 1.0)
        return (a + ab * t - p).norm
    }
}

/**
 * Closest point of approach for two straight-line movers in the local plane.
 * [rel] = target position minus ownship position (m), [vRel] = target velocity
 * minus ownship velocity (m/s).
 */
data class Cpa(val tSec: Double, val distM: Double)

object CpaMath {
    /**
     * Time (s, >= 0) and distance (m) of closest approach. If the pair is
     * already diverging, CPA is "now" (t = 0, current range).
     */
    fun cpa(rel: EN, vRel: EN): Cpa {
        val v2 = vRel dot vRel
        if (v2 < 1e-9) return Cpa(0.0, rel.norm)
        val t = -(rel dot vRel) / v2
        if (t <= 0.0) return Cpa(0.0, rel.norm)
        return Cpa(t, (rel + vRel * t).norm)
    }

    /** Range rate (m/s): negative = closing. */
    fun rangeRate(rel: EN, vRel: EN): Double {
        val r = rel.norm
        if (r < 1e-6) return -vRel.norm
        return (rel dot vRel) / r
    }

}
