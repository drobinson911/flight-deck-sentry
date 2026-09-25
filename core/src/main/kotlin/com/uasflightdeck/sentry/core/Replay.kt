package com.uasflightdeck.sentry.core

import com.uasflightdeck.sentry.core.Parsers.num
import com.uasflightdeck.sentry.core.Parsers.obj
import com.uasflightdeck.sentry.core.Parsers.arr
import kotlinx.serialization.json.JsonArray

/**
 * A recorded scenario played through the SAME engine the live service uses.
 * At simulated time t, each feed yields its latest sample at or before t, with
 * that sample's true timestamp — so ages, staleness and extrapolation behave
 * exactly as they would live.
 */
class ReplayScenario(
    val title: String,
    val ownshipId: String,
    val ownshipName: String,
    private val ownRows: List<Ownship>,
    private val traffic: List<List<Target>>,
    val zones: List<Zone>,
    val startMs: Long,
    val endMs: Long,
    /** Offset added to UTC for the on-screen clock (PDT = -7 h). */
    val clockOffsetMs: Long,
    val clockZoneLabel: String,
) {
    fun ownshipAt(t: Long): Ownship? = latest(ownRows, t) { it.posTimeMs }

    /** The "fleet feed" at time t: the drone, as a DroneSense-style list. */
    fun dronesAt(t: Long): List<Ownship> = listOfNotNull(ownshipAt(t))

    fun trafficAt(t: Long): List<Target> = traffic.mapNotNull { rows -> latest(rows, t) { it.posTimeMs } }

    private fun <T> latest(rows: List<T>, t: Long, ts: (T) -> Long): T? {
        var lo = 0; var hi = rows.size - 1; var best: T? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (ts(rows[mid]) <= t) { best = rows[mid]; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }
}

/**
 * Demo encounter: Cirrus SR22T N388KM (hex a479ef) crossed TFR
 * 0/0000 (SFC-8,500 ft MSL) and passed ~0.24 nm from DEMO-1 (DJI M4T) at
 * 11:53:40 PDT. Data: local incident data
 */
object DemoReplayFixture {
    const val TFR_ENTRY_MS = 1790189602485L     // 11:53:22.485 PDT
    const val TFR_EXIT_MS = 1790189648485L      // 11:54:08.485 PDT
    const val CLOSEST_MS = 1790189620000L       // 11:53:40 PDT
    const val START_MS = 1790189510000L         // 11:51:50 PDT
    const val END_MS = 1790189730000L           // 11:55:30 PDT
    const val PDT_OFFSET_MS = -7 * 3600_000L
    const val HEX = "a479ef"
    const val CALLSIGN = "N388KM"
    /** The synthetic crossing variant's climb rate through the drone's altitude. */
    const val CROSSING_FPM = 500.0

    /**
     * The drone's DroneSense-style callsign in the replay. The recorded DEMO-1
     * track carries no callsign field; "DEMO-1 Pilot" is added so the replay
     * drone reads like one typed in DroneSense. Selection is by its synthetic serial.
     */
    const val DRONE_CALLSIGN = "DEMO-1 Pilot"

    /**
     * SYNTHETIC airframe serial for the replay drone. The recorded DEMO-1 track
     * carries no serial; this placeholder (DJI-style shape, NOT DEMO-1's real
     * serial) is added so the replay exercises "This controller's aircraft".
     */
    const val DRONE_SERIAL = "1581F7K3C251F00C9B34"

    /** DEMO-1's launch point, used as the simulated controller position in replays. */
    const val LAUNCH_LAT = 39.4290
    const val LAUNCH_LON = -120.0344
    const val LAUNCH_ELEV_FT = 5100.0

    fun launchController(timeMs: Long) = ControllerFix(LAUNCH_LAT, LAUNCH_LON, LAUNCH_ELEV_FT, 5.0, timeMs,
        label = "controller GPS · replay pad")

    /**
     * @param cloudView true = replay N388KM as the PUBLIC feed saw it: alt_baro
     *   "ground" with no altitude and no track (its transponder was in ground
     *   mode 11:50:42-11:54:42). false = the merged track with the truck's
     *   Mode S pressure altitude.
     */
    /**
     * @param crossing the SYNTHETIC "crossing" variant (v0.4.0), to exercise COLLISION RISK: N388KM's real horizontal
     *   track, but the drone held level at its altitude at the closest approach, and N388KM given a GPS altitude that
     *   climbs at [CROSSING_FPM] through the drone's altitude exactly at the closest approach (160 ft below it
     *   19.2 s before). Its reported vertical rate is +500 fpm.
     */
    fun load(droneJson: String, n388Json: String, tfrJson: String, cloudView: Boolean = false, crossing: Boolean = false): ReplayScenario {
        // demo_drone.json: {"results":[...]} possibly wrapped in a one-element list
        val u = Parsers.parse(droneJson)
        val uObj = (u as? JsonArray)?.firstOrNull().obj() ?: u.obj()
        val own = uObj?.get("results").arr().orEmpty().mapNotNull { el ->
            val r = el.obj() ?: return@mapNotNull null
            Ownship(
                id = "DEMO-1", name = DRONE_CALLSIGN, callsign = DRONE_CALLSIGN, serial = DRONE_SERIAL,
                lat = r.num("lat") ?: return@mapNotNull null,
                lon = r.num("lon") ?: return@mapNotNull null,
                altMslFt = r.num("msl_ft"), altAglFt = r.num("agl_ft"),
                posTimeMs = r.num("ts")?.toLong() ?: return@mapNotNull null,
                source = OwnshipSource.REPLAY,
            )
        }.sortedBy { it.posTimeMs }.let { rows ->
            if (!crossing) rows else {
                val level = rows.lastOrNull { it.posTimeMs <= CLOSEST_MS }?.altMslFt ?: rows.first().altMslFt
                rows.map { it.copy(altMslFt = level) }
            }
        }
        val levelFt = own.lastOrNull { it.posTimeMs <= CLOSEST_MS }?.altMslFt

        val n = Parsers.parse(n388Json).arr().orEmpty().mapNotNull { el ->
            val r = el.obj() ?: return@mapNotNull null
            Target(
                hex = HEX, callsign = CALLSIGN, registration = CALLSIGN, type = "S22T",
                lat = r.num("lat") ?: return@mapNotNull null,
                lon = r.num("lon") ?: return@mapNotNull null,
                altBaroFt = if (cloudView) null else r.num("alt_baro"),
                reportsGround = cloudView,
                gsKt = r.num("gs"),
                trackDeg = if (cloudView) null else r.num("track"),
                posTimeMs = r.num("ts")?.toLong() ?: return@mapNotNull null,
                sources = setOf("replay"),
            )
        }.sortedBy { it.posTimeMs }.let { rows ->
            if (!crossing || levelFt == null) rows else rows.map {
                it.copy(altBaroFt = null, altGeomFt = levelFt + CROSSING_FPM * (it.posTimeMs - CLOSEST_MS) / 60_000.0,
                    vsFpm = CROSSING_FPM, reportsGround = false)
            }
        }

        val tfr = Parsers.parse(tfrJson).obj()
        val ring = tfr?.get("verts").arr().orEmpty().mapNotNull { v ->
            val a = v.arr() ?: return@mapNotNull null
            LatLon(a[0].num() ?: return@mapNotNull null, a[1].num() ?: return@mapNotNull null)
        }
        val zone = Zone("tfr:0/0000", "0/0000", ZoneKind.TFR, listOf(Polygon(ring)),
            floor = AltLimit.SURFACE, ceiling = AltLimit(8500.0, AltRef.MSL))

        return ReplayScenario(
            title = "Demo encounter: DEMO-1 vs N388KM" + when {
                crossing -> " (SYNTHETIC crossing variant)"
                cloudView -> " (public-feed view)"
                else -> ""
            },
            ownshipId = "DEMO-1", ownshipName = DRONE_CALLSIGN,
            ownRows = own, traffic = listOf(n), zones = listOf(zone),
            startMs = START_MS, endMs = END_MS,
            clockOffsetMs = PDT_OFFSET_MS, clockZoneLabel = "PDT",
        )
    }

    private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())
}
