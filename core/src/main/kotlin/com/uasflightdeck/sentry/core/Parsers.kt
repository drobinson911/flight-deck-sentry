package com.uasflightdeck.sentry.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parsers for every feed Sentry reads. All TIMES are made skew-proof: a
 * position's age comes from the feed's own RELATIVE field (seen_pos, _ageMs)
 * subtracted from OUR receive time, so a truck laptop with a wrong clock
 * cannot make a stale target look fresh.
 */
object Parsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): JsonElement = json.parseToJsonElement(text)

    // ── tiny tree helpers ────────────────────────────────────────────────
    internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    internal fun JsonElement?.num(): Double? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
    internal fun JsonElement?.str(): String? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content
    }
    internal fun JsonObject.num(k: String) = this[k].num()
    internal fun JsonObject.str(k: String) = this[k].str()

    // ── readsb aircraft.json (Overwatch station AND cloud /api/live/adsb) ─
    /**
     * Station: `{"now":s,"aircraft":[...],"ac":[...]}`; cloud: `{"ts":ms,"ac":[...]}`.
     * Remote ID drones (`src:"rid"`) are dropped unless [includeRid]: Sentry is
     * about CREWED aircraft, and our own drone would otherwise alert on itself.
     */
    fun parseReadsb(text: String, receivedAtMs: Long, sourceName: String, includeRid: Boolean = false): List<Target> {
        val root = parse(text).obj() ?: return emptyList()
        val rows = (root["aircraft"].arr().orEmpty() + root["ac"].arr().orEmpty())
        val out = LinkedHashMap<String, Target>()
        for (el in rows) {
            val a = el.obj() ?: continue
            val hex = a.str("hex")?.lowercase()?.trim() ?: continue
            if (!includeRid && a.str("src") == "rid") continue
            val lat = a.num("lat") ?: continue
            val lon = a.num("lon") ?: continue
            val altBaroEl = a["alt_baro"]
            val ground = altBaroEl.str() == "ground"
            val seenPos = a.num("seen_pos") ?: a.num("seen") ?: 0.0
            val t = Target(
                hex = hex,
                callsign = a.str("flight")?.trim()?.takeIf { it.isNotEmpty() },
                registration = a.str("r")?.trim()?.takeIf { it.isNotEmpty() },
                type = a.str("t"),
                lat = lat, lon = lon,
                altBaroFt = if (ground) null else altBaroEl.num(),
                altGeomFt = a.num("alt_geom"),
                reportsGround = ground,
                gsKt = a.num("gs"),
                trackDeg = a.num("track"),
                vsFpm = a.num("baro_rate") ?: a.num("geom_rate"),
                posTimeMs = receivedAtMs - (seenPos * 1000).toLong(),
                sources = setOf(sourceName),
            )
            val prev = out[hex]
            if (prev == null || t.posTimeMs > prev.posTimeMs) out[hex] = t
        }
        return out.values.toList()
    }

    // ── fleet: /api/live/our-drones (Flight Deck Air ingest shape) ───────
    data class FleetParse(val drones: List<Ownship>, val airsense: List<Target>)

    /**
     * `{"ts":ms,"total":n,"drones":[{_ageMs, ts, drone:{id,model,callsign,pilot},
     *   pos:{lat,lon,hdg,altAglFt,altHatFt,altMslFt}, airsense:[{icao,callsign,lat,lon,altFt,vsFpm,gsKt,trk}]}]}`
     * (contract: flightdeck-air IngestClient.encode + live-poller.js /snapshot/our-drones).
     */
    fun parseOurDrones(text: String, receivedAtMs: Long): FleetParse {
        val root = parse(text).obj() ?: return FleetParse(emptyList(), emptyList())
        val drones = ArrayList<Ownship>()
        val airsense = ArrayList<Target>()
        for (el in root["drones"].arr().orEmpty()) {
            val d = el.obj() ?: continue
            val info = d["drone"].obj() ?: continue
            val pos = d["pos"].obj() ?: continue
            val id = info.str("id") ?: continue
            val lat = pos.num("lat") ?: continue
            val lon = pos.num("lon") ?: continue
            val ageMs = (d.num("_ageMs") ?: 0.0).toLong().coerceAtLeast(0)
            val t = receivedAtMs - ageMs
            drones += Ownship(
                id = id,
                name = info.str("callsign")?.takeIf { it.isNotBlank() } ?: id,
                lat = lat, lon = lon,
                altMslFt = pos.num("altMslFt"),
                altAglFt = pos.num("altAglFt"),
                posTimeMs = t,
                source = OwnshipSource.FLEET_FDA,
            )
            // The drone's own AirSense receiver is a THIRD traffic source.
            for (cEl in d["airsense"].arr().orEmpty()) {
                val c = cEl.obj() ?: continue
                val hex = c.str("icao")?.lowercase()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                airsense += Target(
                    hex = hex,
                    callsign = c.str("callsign")?.trim()?.takeIf { it.isNotEmpty() },
                    lat = c.num("lat") ?: continue, lon = c.num("lon") ?: continue,
                    // AirSense altitude basis is not documented; treat as pressure
                    // altitude so it is labelled "estimated" and corrected.
                    altBaroFt = c.num("altFt"),
                    gsKt = c.num("gsKt"), trackDeg = c.num("trk"), vsFpm = c.num("vsFpm"),
                    posTimeMs = t,
                    sources = setOf("airsense"),
                )
            }
        }
        return FleetParse(drones, airsense)
    }

    // ── fleet: /api/live/dronesense  {ts, body:[...]} ─────────────────────
    /**
     * DroneSense `/v1/drones/with-sensors` elements: latitude, longitude,
     * altitudeMsl / altitudeAgl (METRES), lastUpdate (unix SECONDS), callSign.
     * `lastUpdate` is DroneSense's wall clock; the controller is NTP/GPS-synced,
     * so age = now - lastUpdate (clamped at 0). Never reads sensors/rtsp_url.
     */
    fun parseDroneSense(text: String, nowMs: Long): List<Ownship> {
        val root = parse(text)
        val body = root.obj()?.get("body") ?: root
        val list = body.arr() ?: body.obj()?.get("drones").arr() ?: return emptyList()
        val out = ArrayList<Ownship>()
        for (el in list) {
            val d = el.obj() ?: continue
            val lat = d.num("latitude") ?: continue
            val lon = d.num("longitude") ?: continue
            val id = d.str("id") ?: d.str("serialNumber") ?: continue
            val lu = d.num("lastUpdate")
            val t = if (lu != null) minOf(nowMs, (lu * 1000).toLong()) else nowMs
            out += Ownship(
                id = id,
                name = d.str("callSign")?.takeIf { it.isNotBlank() } ?: d.str("name") ?: id,
                lat = lat, lon = lon,
                altMslFt = d.num("altitudeMsl")?.let { Units.mToFt(it) },
                altAglFt = d.num("altitudeAgl")?.let { Units.mToFt(it) },
                posTimeMs = t,
                source = OwnshipSource.FLEET_DRONESENSE,
            )
        }
        return out
    }

    // ── TFRs: /api/tfrs GeoJSON ───────────────────────────────────────────
    /**
     * Properties (uas-flight-deck worker.js + tfr-enrich.js): NOTAM_NUMBER,
     * NAME, _ALT_L_VAL/_ALT_L_UOM/_ALT_L_CODE, _ALT_H_*; CODE "ALT" = MSL,
     * "HEI" = AGL; UOM "FT" or "FL". Missing = "see NOTAM".
     */
    fun parseTfrs(text: String): List<Zone> {
        val root = parse(text).obj() ?: return emptyList()
        val out = ArrayList<Zone>()
        root["features"].arr().orEmpty().forEachIndexed { i, fEl ->
            val f = fEl.obj() ?: return@forEachIndexed
            val p = f["properties"].obj() ?: JsonObject(emptyMap())
            val polys = geometryPolygons(f["geometry"].obj())
            if (polys.isEmpty()) return@forEachIndexed
            val name = p.str("NOTAM_NUMBER")?.takeIf { it.isNotBlank() } ?: p.str("NAME") ?: "TFR"
            val floor = altLimit(p.num("_ALT_L_VAL"), p.str("_ALT_L_UOM"), p.str("_ALT_L_CODE")) ?: AltLimit.SURFACE
            val ceil = altLimit(p.num("_ALT_H_VAL"), p.str("_ALT_H_UOM"), p.str("_ALT_H_CODE")) ?: AltLimit.UNKNOWN
            out += Zone("tfr:$name:$i", name, ZoneKind.TFR, polys, floor, ceil)
        }
        return out
    }

    private fun altLimit(v: Double?, uom: String?, code: String?): AltLimit? {
        if (v == null) return null
        val ft = if (uom.equals("FL", true)) v * 100 else v
        val ref = when {
            uom.equals("FL", true) -> AltRef.MSL
            code.equals("HEI", true) -> AltRef.AGL
            code.equals("ALT", true) -> AltRef.MSL
            else -> AltRef.UNKNOWN
        }
        return AltLimit(ft, ref)
    }

    /** Custom geofences from any GeoJSON (FeatureCollection, Feature or bare geometry). */
    fun parseGeofences(text: String, fileLabel: String): List<Zone> {
        val root = parse(text).obj() ?: return emptyList()
        val feats: List<JsonObject> = when (root.str("type")) {
            "FeatureCollection" -> root["features"].arr().orEmpty().mapNotNull { it.obj() }
            "Feature" -> listOf(root)
            else -> listOf(JsonObject(mapOf("geometry" to root)))
        }
        val out = ArrayList<Zone>()
        feats.forEachIndexed { i, f ->
            val polys = geometryPolygons(f["geometry"].obj())
            if (polys.isEmpty()) return@forEachIndexed
            val p = f["properties"].obj()
            val name = p?.str("name") ?: p?.str("NAME") ?: if (feats.size > 1) "$fileLabel ${i + 1}" else fileLabel
            out += Zone("geo:$fileLabel:$i", name, ZoneKind.GEOFENCE, polys)
        }
        return out
    }

    fun geometryPolygons(g: JsonObject?): List<Polygon> {
        g ?: return emptyList()
        fun ring(a: JsonArray?): List<LatLon> = a.orEmpty().mapNotNull { pt ->
            val c = pt.arr() ?: return@mapNotNull null
            val lon = c.getOrNull(0).num() ?: return@mapNotNull null
            val lat = c.getOrNull(1).num() ?: return@mapNotNull null
            LatLon(lat, lon)
        }
        fun poly(a: JsonArray?): Polygon? {
            val rings = a.orEmpty().map { ring(it.arr()) }.filter { it.size >= 3 }
            if (rings.isEmpty()) return null
            return Polygon(rings[0], rings.drop(1))
        }
        return when (g.str("type")) {
            "Polygon" -> listOfNotNull(poly(g["coordinates"].arr()))
            "MultiPolygon" -> g["coordinates"].arr().orEmpty().mapNotNull { poly(it.arr()) }
            else -> emptyList()
        }
    }

    /** A circle geofence as a 48-gon. */
    fun circleZone(id: String, name: String, center: LatLon, radiusNm: Double): Zone {
        val r = Units.nmToM(radiusNm)
        val ring = (0 until 48).map { i ->
            val a = Math.toRadians(i * 7.5)
            Geo.fromEN(center, EN(r * kotlin.math.sin(a), r * kotlin.math.cos(a)))
        }
        return Zone(id, name, ZoneKind.GEOFENCE, listOf(Polygon(ring)))
    }

    internal fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}

/** Merge the same aircraft seen by several feeds: newest POSITION wins, identity fields fill in. */
object TrafficMerger {
    fun merge(vararg lists: List<Target>): List<Target> = merge(lists.toList())

    fun merge(lists: List<List<Target>>): List<Target> {
        val out = HashMap<String, Target>()
        for (l in lists) for (t in l) {
            val p = out[t.hex]
            out[t.hex] = if (p == null) t else {
                val (fresh, old) = if (t.posTimeMs >= p.posTimeMs) t to p else p to t
                fresh.copy(
                    callsign = fresh.callsign ?: old.callsign,
                    registration = fresh.registration ?: old.registration,
                    type = fresh.type ?: old.type,
                    sources = fresh.sources + old.sources,
                )
            }
        }
        return out.values.toList()
    }

    fun within(targets: List<Target>, center: LatLon, nm: Double): List<Target> =
        targets.filter { Geo.distanceNm(center, it.pos) <= nm }
}
