package com.uasflightdeck.sentry.core

/** Where the ownship (drone) position came from. Shown and spoken — never hidden. */
enum class OwnshipSource(val label: String) {
    FLEET_FDA("fleet feed (Flight Deck Air)"),
    FLEET_DRONESENSE("fleet feed (DroneSense)"),
    MANUAL_PINNED("manual position"),
    DEVICE_GPS("controller GPS"),
    REPLAY("replay"),
    /** No drone selected: Sentry protects cylinders centred on this controller. */
    CONTROLLER("this controller (GPS)"),
}

/**
 * The drone Sentry protects. [posTimeMs] is the wall-clock time the position
 * was TRUE (receive time minus the feed's own age), so age = now - posTimeMs.
 */
data class Ownship(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val altMslFt: Double?,
    val altAglFt: Double?,
    val posTimeMs: Long,
    val source: OwnshipSource,
    /** The feed's callsign (DroneSense `callSign`, FDA `drone.callsign`); null if the feed had none. */
    val callsign: String? = null,
    /** Airframe serial (DroneSense `serial`, FDA `drone.serial`): what a controller is pinned to. */
    val serial: String? = null,
    /** Aircraft model when the feed gives one (e.g. "M4T"), for the "aircraft in feed now" list. */
    val model: String? = null,
    /** Ground speed in m/s when the feed gives one (DroneSense `speed`). */
    val speedMs: Double? = null,
) {
    val pos get() = LatLon(lat, lon)
    val isManual get() = source == OwnshipSource.MANUAL_PINNED || source == OwnshipSource.DEVICE_GPS
    val isController get() = source == OwnshipSource.CONTROLLER

    /**
     * Airborne = off the pad: AGL >= 5 ft or moving >= 0.5 m/s. A feed that
     * gives neither counts as airborne (it listed the drone, and failing wide
     * is safer than ignoring our own aircraft).
     */
    val isAirborne: Boolean
        get() {
            if (altAglFt == null && speedMs == null) return true
            return (altAglFt ?: 0.0) >= 5.0 || (speedMs ?: 0.0) >= 0.5
        }
}

/**
 * One aircraft, normalized from any feed. Altitudes in feet.
 * [altBaroFt] is PRESSURE altitude, [altGeomFt] GPS/geometric (MSL-ish).
 * [reportsGround] = the transponder said "ground". That is NOT trusted on its
 * own: N388KM reported "ground" at 160 kt through the whole 2026-09-23 pass.
 */
data class Target(
    val hex: String,
    val callsign: String? = null,
    val registration: String? = null,
    val type: String? = null,
    val lat: Double,
    val lon: Double,
    val altBaroFt: Double? = null,
    val altGeomFt: Double? = null,
    val reportsGround: Boolean = false,
    val gsKt: Double? = null,
    val trackDeg: Double? = null,
    val vsFpm: Double? = null,
    val posTimeMs: Long,
    val sources: Set<String> = emptySet(),
) {
    val pos get() = LatLon(lat, lon)

    /** What a human (and TTS) calls this aircraft. */
    val displayId: String
        get() = callsign?.trim()?.takeIf { it.isNotEmpty() }
            ?: registration?.trim()?.takeIf { it.isNotEmpty() }
            ?: "hex ${hex.uppercase()}"
}

data class Polygon(val outer: List<LatLon>, val holes: List<List<LatLon>> = emptyList())

enum class AltRef { MSL, AGL, UNKNOWN }

/** A vertical limit. value == null + ref UNKNOWN = "see NOTAM". */
data class AltLimit(val valueFt: Double?, val ref: AltRef) {
    companion object {
        val SURFACE = AltLimit(0.0, AltRef.AGL)
        val UNKNOWN = AltLimit(null, AltRef.UNKNOWN)
    }
}

enum class ZoneKind { TFR, GEOFENCE, CYLINDER }

/** An exact circle (used by controller cylinders instead of a polygon test). */
data class Circle(val center: LatLon, val radiusNm: Double)

/**
 * A TFR or custom geofence. For a moving circle geofence (centred on the
 * drone) the service rebuilds the polygon each tick.
 */
data class Zone(
    val id: String,
    val name: String,
    val kind: ZoneKind,
    val polygons: List<Polygon>,
    val floor: AltLimit = AltLimit.SURFACE,
    val ceiling: AltLimit = AltLimit.UNKNOWN,
    /** When set, the horizontal test is this exact circle, not [polygons]. */
    val circle: Circle? = null,
) {
    fun containsHoriz(p: LatLon): Boolean =
        if (circle != null) Geo.distanceNm(circle.center, p) <= circle.radiusNm
        else polygons.any { Geo.pointInPolygon(p, it) }

    /** How the zone is spoken: "TFR 0 0000" (slash is read as a date otherwise). */
    val spokenName: String
        get() = when (kind) {
            ZoneKind.TFR -> "TFR " + name.replace("/", " ")
            ZoneKind.GEOFENCE -> "geofence $name"
            ZoneKind.CYLINDER -> name
        }
    val displayName: String
        get() = when (kind) {
            ZoneKind.TFR -> "TFR $name"
            ZoneKind.GEOFENCE -> "geofence $name"
            ZoneKind.CYLINDER -> name
        }
}

enum class Severity(val rank: Int, val label: String) {
    NONE(0, "none"),
    INFO(1, "info"),
    ADVISORY(2, "advisory"),
    CAUTION(3, "caution"),
    WARNING(4, "warning");
}

/**
 * The traffic alert tiers (v0.4.0), lowest to highest. "Highest wins" and every escalation fires at once.
 *  - ADVISORY: actually inside the advisory ring (3 nm) and the protected volume.
 *  - TRACK: TRACK ALERT, predicted to pass within 1 nm (+corridor) within 180 s, inside the volume at that time.
 *  - CAUTION: actually inside the caution ring (1 nm) and the volume.
 *  - WARNING: predicted within 0.5 nm (+corridor) within 90 s inside the volume, or actually inside the 0.5 nm ring.
 *  - COLLISION: COLLISION RISK, predicted within 500 ft / 300 ft within 60 s, or crossing the drone's altitude
 *    while inside 0.5 nm within the next 60 s.
 * TRACK sits between advisory and caution: a predicted conflict outranks mere proximity, and an aircraft on a
 * TRACK ALERT that then enters the 1 nm ring still escalates (and sounds).
 */
enum class Tier(val rank: Int, val label: String, val severity: Severity) {
    NONE(0, "none", Severity.NONE),
    ADVISORY(1, "advisory", Severity.ADVISORY),
    TRACK(2, "track alert", Severity.CAUTION),
    CAUTION(3, "caution", Severity.CAUTION),
    WARNING(4, "warning", Severity.WARNING),
    COLLISION(5, "collision risk", Severity.WARNING);
}

enum class EventKind {
    TFR_ENTRY, GEOFENCE_ENTRY, CYLINDER_ENTRY,
    /** A traffic tier change or cadence repeat for one aircraft: see [AlertEvent.phase]. */
    TRAFFIC,
    /** Range opening after a close pass: "PASSING · diverging". Sound only after a WARNING / COLLISION RISK. */
    PASSING,
    /** A TRACK ALERT whose prediction left the corridor for >= 5 s: banner update, no sound. */
    NO_LONGER_FACTOR,
    CLEAR, TRACK_LOST,
    OWNSHIP_ACQUIRED, OWNSHIP_LOST, OWNSHIP_REGAINED, OWNSHIP_MANUAL,
    TRAFFIC_STALE, TRAFFIC_RESTORED,
    SOURCE_LOST, SOURCE_REGAINED,
    /** Drone selection changed (bound aircraft acquired / waiting for it / controller). */
    SELECTION,
    CONTROLLER_GPS_LOST, CONTROLLER_GPS_REGAINED,
    INTERNET_LOST, INTERNET_REGAINED,
    PREFLIGHT,
    /** "Sentry sounds on": Quiet 5 min ran out. */
    SOUNDS_ON,
    /** 0.4.2: "Sentry restarted" (crash / system kill) or "Sentry armed after restart" (power cycle). */
    RESTARTED,
    TEST, SYSTEM,
}

/** What a traffic event is, for the cadence and the output (sound / popup). */
enum class Phase {
    /** Tier went up: full sound, vibrate, heads-up POPUP. Never rate limited. */
    ESCALATION,
    /** Same tier, the cadence timer ran out: short sound (or banner-only), silent banner update. */
    REPEAT,
    /** Banner-only refresh at the cadence (TRACK every 30 s, advisory): no sound. */
    UPDATE,
    /** Tier went down: silent banner update. */
    DOWNGRADE,
    /** Anything else (passing, clear, zone entry, housekeeping). */
    OTHER,
}

/** The sound a traffic event asks for, before mutes and alert style: the level's full sound, its short variant, or none. */
enum class Cue { FULL, SHORT, NONE }

/**
 * One alert. [text] is the one-line form for the log and the on-screen "Last alerts"; [banner] the four-line
 * heads-up text for traffic events. [cue] and [phase] are the engine's cadence decision; the app applies mutes
 * and the alert style on top ([OutputPlanner]).
 */
data class AlertEvent(
    val timeMs: Long,
    val kind: EventKind,
    val severity: Severity,
    val text: String,
    val hex: String? = null,
    /** Distance to the aircraft when the alert was made (nm); orders two aircraft at the same tier, closer first. */
    val distNm: Double? = null,
    val tier: Tier? = null,
    val phase: Phase = Phase.OTHER,
    val cue: Cue = Cue.NONE,
    /** Re-post the banner as a heads-up. Only escalations and zone entries pop up. */
    val popup: Boolean = false,
    val banner: BannerText? = null,
    /** Best estimate of when the tier condition became true (interpolated between ticks), for the latency metric. */
    val crossedAtMs: Long? = null,
    /** A reminder of something already alerted (e.g. "still lost"): screen only. */
    val repeat: Boolean = false,
    /** The worst (lowest) closeness score S of this aircraft so far this pass ([Closeness]). */
    val closenessS: Double? = null,
)

/**
 * The fleet simulation's incident "closeness score", so field data is scored the same way:
 * S = sqrt((h / 2000 ft)^2 + (v / 500 ft)^2), h = horizontal and v = vertical separation now. Lower is closer
 * (S <= 1 is inside the 2000 ft / 500 ft ellipsoid). Unknown altitude scores on h alone (v = 0: fail wide).
 */
object Closeness {
    fun score(hFt: Double, vFt: Double?): Double = kotlin.math.sqrt((hFt / 2000.0).let { it * it } + ((vFt ?: 0.0) / 500.0).let { it * it })
}

/** Every tunable in one place. Defaults are the owner's plan (2026-09-24). */
data class SentryConfig(
    val advisoryNm: Double = 3.0,
    val cautionNm: Double = 1.0,
    val warningNm: Double = 0.5,
    /**
     * The protected volume around the drone runs from the SURFACE up to this many feet above the drone.
     * There is no lower limit; unknown altitude counts as inside. (2,000 in 0.3.3-0.3.5; 1,500 from 0.4.0.)
     */
    val ceilingAboveFt: Double = 1500.0,
    val baroCorrectionFt: Double = 300.0,

    // ── prediction (time to closest approach) ──
    // Fleet simulation 2026-09-24 (86 real flights): track 120 s / warning 60 s with NO corridor widening had 67 %
    // fewer false alarms than 180/90 + 5 deg, warnings a median 69 s before closest approach, no missed conflict.
    val trackSec: Double = 120.0,
    val trackMissNm: Double = 1.0,
    val warningSec: Double = 60.0,
    val warningMissNm: Double = 0.5,
    val collisionSec: Double = 60.0,
    val collisionMissFt: Double = 500.0,
    val collisionVertFt: Double = 300.0,
    /** The horizontal miss threshold widens with distance: threshold + distance x tan(corridorDeg). 0 = off (default). */
    val corridorDeg: Double = 0.0,
    /** A predicted tier holds this long after its prediction leaves the corridor (while not diverging). */
    val predictionHoldSec: Double = 5.0,
    /** Once a predicted tier is up, its time limit is extended by this much (its miss limit by [ringHysteresisNm]). */
    val predictionHysteresisSec: Double = 10.0,
    /** Stepping back up to a tier already alerted this pass within this many seconds is not re-alerted (anti-flap). */
    val rearmSec: Double = 15.0,
    /** Drone slower than this is treated as stationary. */
    val droneStationaryKt: Double = 1.0,

    // ── cadence ([Cadence]) ──
    val trackUpdateSec: Double = 30.0,
    val advisoryRepeatSec: Double = 30.0,
    val cautionRepeatSec: Double = 20.0,
    /** WARNING, converging: 1-3 nm (and beyond). */
    val warnFarSec: Double = 20.0,
    /** WARNING, converging: 0.5-1 nm. */
    val warnNearSec: Double = 12.0,
    /** WARNING, converging: inside 0.5 nm or tCPA < [warnCloseCpaSec]. */
    val warnCloseSec: Double = 6.0,
    val warnCloseCpaSec: Double = 30.0,
    /** No repeat of one aircraft faster than this (COLLISION RISK has its own tone rate). */
    val minRepeatSec: Double = 6.0,
    val collisionRepeatSec: Double = 3.0,
    /** Quiet alert style = 2.0 (every interval doubled). */
    val cadenceScale: Double = 1.0,
    /** Advisory REPEATS make a sound (the Loud alert style only). The advisory entry sound is decided by the style in OutputPlanner. */
    val advisorySound: Boolean = false,

    /** A zone (TFR, geofence, cylinder) re-entered within this many seconds is not alerted again. */
    val reannounceSec: Double = 20.0,
    val staleTargetSec: Double = 30.0,
    val ownshipLostSec: Double = 15.0,
    val trafficStaleSec: Double = 30.0,
    /** Only TFRs whose edge is within this distance of the drone are watched (shown). */
    val tfrRelevanceNm: Double = 10.0,
    /**
     * Zone ENTRY alerts only for zones that contain the drone or whose edge is within this distance of it (fleet
     * simulation: 100 of 101 zone alerts in 0.3.5 were a TFR 5-9 nm from the drone).
     */
    val zoneAlertNm: Double = 2.0,
    /** Dead-reckon a target at most this far past its last position report. */
    val maxExtrapolationSec: Double = 10.0,
    /** A "ground" report faster than this is treated as AIRBORNE, altitude unknown. */
    val groundSpeedAirborneKt: Double = 50.0,
    /** TFR with no published ceiling: assume this MSL top so airliners at FL350 don't trip it. */
    val unknownTfrCeilingFt: Double = 18_000.0,
    /** Hysteresis on ring exits so a target on a ring edge doesn't flap. */
    val ringHysteresisNm: Double = 0.2,
    /** Hysteresis on the ceiling above the drone once alerting (so a target at the ceiling doesn't flap). */
    val bandHysteresisFt: Double = 200.0,
    /** Reminder cadence while the drone position stays lost (screen only). */
    val ownshipLostReminderSec: Double = 120.0,
) {
    fun ringsValid(): Boolean = warningNm > 0 && cautionNm >= warningNm && advisoryNm >= cautionNm
    fun predictionValid(): Boolean = trackSec >= warningSec && warningSec >= collisionSec && collisionSec > 0 &&
        trackMissNm >= warningMissNm && warningMissNm > 0 && collisionMissFt > 0 && collisionVertFt > 0
}
