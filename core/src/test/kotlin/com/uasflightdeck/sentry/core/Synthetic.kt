package com.uasflightdeck.sentry.core

import kotlin.math.cos
import kotlin.math.sin

/** Shared synthetic geometry for the engine tests: the drone hovers at [O], 8,000 ft MSL (400 ft AGL). */
object Synthetic {
    val O = LatLon(39.4, -120.0)
    const val T0 = 1_790_000_000_000L
    const val DRONE_FT = 8000.0

    fun sec(n: Int) = T0 + n * 1000L
    fun sec(n: Double) = T0 + (n * 1000).toLong()

    fun own(t: Long, src: OwnshipSource = OwnshipSource.FLEET_FDA, posT: Long = t, at: LatLon = O, alt: Double = DRONE_FT) =
        Ownship("D1", "DEMO-1", at.lat, at.lon, alt, alt - 7600.0, posT, src)

    fun at(bearing: Double, nm: Double, from: LatLon = O): LatLon {
        val b = Math.toRadians(bearing); val d = Units.nmToM(nm)
        return Geo.fromEN(from, EN(d * sin(b), d * cos(b)))
    }

    /** A point [nm] along [bearing] from O, then [lateralNm] to the right of that bearing. */
    fun offset(bearing: Double, nm: Double, lateralNm: Double): LatLon = at(bearing + 90.0, lateralNm, at(bearing, nm))

    fun tgt(
        t: Long, bearing: Double, nm: Double, geomFt: Double? = DRONE_FT, gs: Double = 0.0, trk: Double = 0.0,
        hex: String = "abc123", baroFt: Double? = null, ground: Boolean = false, posT: Long = t, vs: Double? = null,
        pos: LatLon? = null, type: String? = null,
    ): Target {
        val p = pos ?: at(bearing, nm)
        return Target(hex = hex, callsign = "N1234", type = type, lat = p.lat, lon = p.lon, altGeomFt = geomFt, altBaroFt = baroFt,
            reportsGround = ground, gsKt = gs, trackDeg = trk, vsFpm = vs, posTimeMs = posT, sources = setOf("test"))
    }

    /** A straight-line mover: at time [t] (s from T0) it is at [start] + velocity x t. */
    fun mover(t: Int, start: LatLon, gs: Double, trk: Double, alt0: Double? = DRONE_FT, vs: Double? = null, hex: String = "abc123",
              tOff: Int = 0): Target {
        val v = Geo.velocity(gs, trk) * t.toDouble()
        val p = Geo.fromEN(start, v)
        val alt = alt0?.let { it + (vs ?: 0.0) * t / 60.0 }
        return Target(hex = hex, callsign = "N1234", lat = p.lat, lon = p.lon, altGeomFt = alt, gsKt = gs, trackDeg = trk,
            vsFpm = vs, posTimeMs = sec(t + tOff), sources = setOf("test"))
    }

    fun AlertEngine.s(t: Long, targets: List<Target>, zones: List<Zone> = emptyList(), o: Ownship? = own(t), trafficAge: Double = 0.0) =
        step(t, o, targets, zones, trafficAge)

    /** Run a mover for [range] seconds; returns all events (the first step at t=0 has no traffic, to settle ownship). */
    fun run(e: AlertEngine, range: IntRange, zones: List<Zone> = emptyList(), o: (Int) -> Ownship? = { own(sec(it)) },
            targets: (Int) -> List<Target>): List<AlertEvent> =
        range.flatMap { i -> e.step(sec(i), o(i), targets(i), zones, 0.0).events }

    fun traffic(ev: List<AlertEvent>) = ev.filter { it.hex != null }
}
