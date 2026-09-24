package com.uasflightdeck.sentry.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The whole decision of "does the pilot hear something right now".
 *
 * Call [step] once per tick (1 s) with the CURRENT inputs. Every output is
 * derived from those inputs plus a small per-aircraft memory used only for
 * rate limiting, hysteresis and deriving a velocity when a feed omits track
 * (N388KM's public ADS-B had no track during the 2026-09-23 pass). Nothing
 * displayed is "last event wins": [StepResult.targets] is recomputed from live
 * data every step.
 */
class AlertEngine(
    var config: SentryConfig = SentryConfig(),
    /**
     * true = a [DroneSelector] decides what is protected and speaks every
     * selection change ("Watching …", "Protecting this controller"). The
     * engine then only speaks position lost / regained for the SAME drone and
     * resets its tracks silently when the protected thing changes.
     */
    val externalSelection: Boolean = false,
) {

    /** What the UI shows for one aircraft, recomputed every step. */
    data class TargetView(
        val hex: String,
        val displayId: String,
        val distNm: Double,
        val bearingDeg: Double,
        val dvFt: Double?,
        val altEstimated: Boolean,
        val trend: Trend?,
        val severity: Severity,
        val predictive: Boolean,
        val cpa: Cpa?,
        val zones: List<String>,
        val ageSec: Double,
        val sources: Set<String>,
        val groundModeAirborne: Boolean,
        /** Altitude for the display ceiling: geometric (GPS) when reported, else raw baro; null = unknown. */
        val altFt: Double? = null,
    )

    data class StepResult(
        val events: List<AlertEvent>,
        val targets: List<TargetView>,
        val ownshipFresh: Boolean,
        val ownshipAgeSec: Double?,
        val trafficStale: Boolean,
        val watchedZones: List<String>,
    )

    private class TrackState {
        var last: Target? = null
        var prev: Target? = null
        var announced: Severity = Severity.NONE
        var lastAnnounceMs: Long = Long.MIN_VALUE / 2
        var currentSev: Severity = Severity.NONE
        val zoneInside = HashMap<String, Boolean>()
        val zoneLastAnnounceMs = HashMap<String, Long>()
        var seenThisStep = false
    }

    private enum class OwnState { UNKNOWN, OK, LOST }

    private val tracks = HashMap<String, TrackState>()
    private var ownState = OwnState.UNKNOWN
    private var ownLastSource: OwnshipSource? = null
    private var ownLastId: String? = null
    private var ownLostAnnounceMs = 0L
    private var ownPrev: Ownship? = null
    private var ownLast: Ownship? = null
    private var firstStepMs: Long? = null
    private var trafficStale = false

    fun reset() {
        tracks.clear(); ownState = OwnState.UNKNOWN; ownLastSource = null; ownLastId = null
        ownPrev = null; ownLast = null; firstStepMs = null; trafficStale = false
    }

    /**
     * @param trafficDataAgeSec age of the freshest SUCCESSFUL traffic fetch
     *   across all sources (an empty sky is fresh data; a failing fetch is not).
     */
    fun step(
        nowMs: Long,
        ownship: Ownship?,
        targets: List<Target>,
        zones: List<Zone>,
        trafficDataAgeSec: Double,
    ): StepResult {
        val cfg = config
        val events = ArrayList<AlertEvent>()
        if (firstStepMs == null) firstStepMs = nowMs

        // ── ownship health ────────────────────────────────────────────────
        val ownAge = ownship?.let { (nowMs - it.posTimeMs) / 1000.0 }
        val ownFresh = ownship != null && ownAge!! <= cfg.ownshipLostSec
        handleOwnship(nowMs, ownship, ownFresh, events)
        if (ownFresh) {
            val o = ownship!!
            val l = ownLast
            if (l == null || l.id != o.id) { ownPrev = null; ownLast = o }
            else if (o.posTimeMs > l.posTimeMs) { ownPrev = l; ownLast = o }
        }

        // ── traffic data health ─────────────────────────────────────────────
        if (trafficDataAgeSec > cfg.trafficStaleSec && !trafficStale) {
            trafficStale = true
            events += AlertEvent(nowMs, EventKind.TRAFFIC_STALE, Severity.CAUTION, "Traffic data stale")
        } else if (trafficDataAgeSec <= cfg.trafficStaleSec && trafficStale) {
            trafficStale = false
            events += AlertEvent(nowMs, EventKind.TRAFFIC_RESTORED, Severity.INFO, "Traffic data restored")
        }

        // ── zones in play ───────────────────────────────────────────────────
        val own = if (ownFresh) ownship else null
        val watched: List<Zone> = if (own == null) emptyList() else zones.filter { z ->
            z.kind == ZoneKind.GEOFENCE || z.kind == ZoneKind.CYLINDER ||
                z.polygons.any { Geo.distanceToPolygonM(own.pos, it) <= Units.nmToM(cfg.tfrRelevanceNm) }
        }
        val groundFt = own?.let { o -> if (o.altMslFt != null && o.altAglFt != null) o.altMslFt - o.altAglFt else null }
        val ownVel = ownVelocity()

        // ── per-aircraft ────────────────────────────────────────────────────
        tracks.values.forEach { it.seenThisStep = false }
        val views = ArrayList<TargetView>()
        for (t in targets) {
            val age = (nowMs - t.posTimeMs) / 1000.0
            if (age > cfg.staleTargetSec) continue            // never announce a stale target
            // "ground" + slow = really on the ground. "ground" + fast = airborne
            // with an unknown altitude (the N388KM case). Unknown speed + ground = ground.
            val groundAirborne = t.reportsGround && (t.gsKt ?: 0.0) >= cfg.groundSpeedAirborneKt
            if (t.reportsGround && !groundAirborne) {
                // It landed (or is taxiing). If we'd been calling it, say so once —
                // "track lost" would wrongly suggest it might still be out there.
                val st = tracks.remove(t.hex)
                if (st != null && st.announced >= Severity.ADVISORY) events += AlertEvent(
                    nowMs, EventKind.CLEAR, Severity.INFO,
                    text = "${t.displayId} on the ground.",
                    speech = "${Phrasing.spelledId(t.displayId)} on the ground.", hex = t.hex)
                continue
            }

            val st = tracks.getOrPut(t.hex) { TrackState() }
            st.seenThisStep = true
            val l = st.last
            if (l == null || t.posTimeMs > l.posTimeMs) { st.prev = l; st.last = t }
            val cur = st.last!!

            if (own == null) continue                         // nothing to be near

            val vel = targetVelocity(cur, st.prev)
            val dtx = min(max(0.0, (nowMs - cur.posTimeMs) / 1000.0), cfg.maxExtrapolationSec)
            val posX = if (vel != null) Geo.fromEN(cur.pos, vel * dtx) else cur.pos

            val altMsl: Double?; val estimated: Boolean
            when {
                groundAirborne -> { altMsl = null; estimated = false }
                cur.altGeomFt != null -> { altMsl = cur.altGeomFt; estimated = false }
                cur.altBaroFt != null -> { altMsl = cur.altBaroFt + cfg.baroCorrectionFt; estimated = true }
                else -> { altMsl = null; estimated = false }
            }
            val vs = if (groundAirborne) null else (cur.vsFpm ?: derivedVs(cur, st.prev))

            val rel = Geo.toEN(own.pos, posX)
            val distNm = Units.mToNm(rel.norm)
            val bearing = Geo.bearingDeg(own.lat, own.lon, posX.lat, posX.lon)
            val dv = if (altMsl != null && own.altMslFt != null) altMsl - own.altMslFt else null

            val vRel = vel?.let { it - (ownVel ?: EN(0.0, 0.0)) }
            val cpa = vRel?.let { CpaMath.cpa(rel, it) }
            val trend = vRel?.let {
                val rr = CpaMath.rangeRate(rel, it)
                when {
                    rr < -TREND_MS -> Trend.CONVERGING
                    rr > TREND_MS -> Trend.DIVERGING
                    else -> Trend.PASSING
                }
            }

            // Vertical: from the SURFACE up to ceilingAboveFt above the drone (hysteresis once alerting).
            // Anything below the drone is inside, however far below; unknown altitude is inside (fail wide).
            val bandLimit = cfg.ceilingAboveFt + if (st.currentSev >= Severity.ADVISORY) cfg.bandHysteresisFt else 0.0
            val inBand = dv == null || dv <= bandLimit

            var sev = Severity.NONE
            var predictive = false
            if (own.isController) {
                // CONTROLLER MODE: the rings are replaced by the user's cylinders.
                // Inside any cylinder = caution; the predictive rule is the same CPA
                // test as for a drone, about the cylinder centre (this controller),
                // and fires when the aircraft's altitude now or at CPA is inside a
                // cylinder's floor/ceiling (unknown altitude counts as inside).
                val cyl = watched.filter { it.kind == ZoneKind.CYLINDER }
                val hyst = st.currentSev >= Severity.CAUTION
                if (cyl.any { insideCylinder(it, posX, altMsl, groundFt, hyst) }) sev = Severity.CAUTION
                // Hysteresis: once warning, the CPA may drift out to warning + 0.2 nm and
                // the look-ahead to +15 s before it stops. There are no rings under the
                // warning here to hold it, so a noisy derived track would otherwise flap
                // warning / clear / warning (seen in the demo replay at 11:53:06).
                val held = st.currentSev >= Severity.WARNING
                val cpaLimitNm = cfg.warningNm + if (held) cfg.ringHysteresisNm else 0.0
                val horizon = cfg.cpaHorizonSec + if (held) 15.0 else 0.0
                if (cpa != null && cpa.tSec > 0 && cpa.tSec <= horizon &&
                    Units.mToNm(cpa.distM) <= cpaLimitNm && sev < Severity.WARNING
                ) {
                    val altAtCpa = altMsl?.let { it + (vs ?: 0.0) * cpa.tSec / 60.0 }
                    val bandOk = cyl.any { inZoneBand(altMsl, it, groundFt) || inZoneBand(altAtCpa, it, groundFt) }
                    if (bandOk) { predictive = true; sev = Severity.WARNING }
                }
            } else if (inBand) {
                fun within(level: Severity, ringNm: Double) =
                    distNm <= ringNm + if (st.currentSev >= level) cfg.ringHysteresisNm else 0.0
                sev = when {
                    within(Severity.WARNING, cfg.warningNm) -> Severity.WARNING
                    within(Severity.CAUTION, cfg.cautionNm) -> Severity.CAUTION
                    within(Severity.ADVISORY, cfg.advisoryNm) -> Severity.ADVISORY
                    else -> Severity.NONE
                }
            }
            // predictive: CPA inside the warning ring within the horizon
            if (!own.isController && cpa != null && cpa.tSec > 0 && cpa.tSec <= cfg.cpaHorizonSec &&
                Units.mToNm(cpa.distM) <= cfg.warningNm && sev < Severity.WARNING
            ) {
                val ownVs = ownVerticalFpm() ?: 0.0
                val dvAtCpa = if (dv != null) dv + ((vs ?: 0.0) - ownVs) * cpa.tSec / 60.0 else null
                // inside the volume now OR at the CPA (a climber from below, or a descender from above)
                val bandOk = dv == null || min(dv, dvAtCpa!!) <= bandLimit
                if (bandOk) { predictive = true; sev = Severity.WARNING }
            }
            st.currentSev = sev

            // zones
            val inZones = ArrayList<Zone>()
            for (z in watched) {
                val horiz = z.containsHoriz(posX)
                val inside = horiz && inZoneBand(altMsl, z, groundFt)
                if (inside) inZones += z
            }

            val view = TargetView(
                hex = cur.hex, displayId = cur.displayId, distNm = distNm, bearingDeg = bearing,
                dvFt = dv, altEstimated = estimated, trend = trend, severity = sev,
                predictive = predictive, cpa = cpa, zones = inZones.map { it.displayName },
                ageSec = age, sources = cur.sources, groundModeAirborne = groundAirborne,
                altFt = if (groundAirborne) null else cur.altGeomFt ?: cur.altBaroFt,
            )
            views += view

            // ── announcements ──────────────────────────────────────────────
            var spokeForTarget = false
            for (z in watched) {
                val inside = inZones.contains(z)
                val was = st.zoneInside[z.id]
                st.zoneInside[z.id] = inside
                if (!inside || was == true) continue
                val lastZ = st.zoneLastAnnounceMs[z.id] ?: Long.MIN_VALUE / 2
                if (nowMs - lastZ < cfg.reannounceSec * 1000) continue
                st.zoneLastAnnounceMs[z.id] = nowMs
                val verb = if (was == null) "inside" else "entering"
                val zsev = if (sev > Severity.CAUTION) sev else Severity.CAUTION
                events += AlertEvent(
                    nowMs,
                    when (z.kind) {
                        ZoneKind.TFR -> EventKind.TFR_ENTRY
                        ZoneKind.GEOFENCE -> EventKind.GEOFENCE_ENTRY
                        ZoneKind.CYLINDER -> EventKind.CYLINDER_ENTRY
                    },
                    zsev,
                    text = "Traffic $verb ${z.displayName}, ${describe(view, false)}.",
                    speech = "Traffic $verb ${z.spokenName}, ${describe(view, true)}.",
                    hex = cur.hex,
                )
                spokeForTarget = true
            }
            if (spokeForTarget) {
                if (sev >= Severity.ADVISORY) {
                    if (sev > st.announced) st.announced = sev
                    st.lastAnnounceMs = nowMs
                }
                continue
            }

            val interval = if (sev == Severity.ADVISORY) cfg.advisoryReannounceSec else cfg.reannounceSec
            when {
                sev >= Severity.ADVISORY && sev > st.announced -> {
                    events += proximityEvent(nowMs, view)
                    st.announced = sev; st.lastAnnounceMs = nowMs
                }
                sev >= Severity.ADVISORY && sev == st.announced &&
                    nowMs - st.lastAnnounceMs >= interval * 1000 && trend != Trend.DIVERGING -> {
                    // A target already moving away is not re-announced every 20 s;
                    // it gets its "clear" when it leaves the rings.
                    events += proximityEvent(nowMs, view)
                    st.lastAnnounceMs = nowMs
                }
                sev >= Severity.ADVISORY && sev < st.announced -> st.announced = sev  // silent de-escalation
                sev == Severity.NONE && st.announced >= Severity.ADVISORY -> {
                    val tail = if (trend == Trend.DIVERGING) ", diverging" else ""
                    events += AlertEvent(
                        nowMs, EventKind.CLEAR, Severity.INFO,
                        text = "${cur.displayId} clear$tail.",
                        speech = "${Phrasing.spelledId(cur.displayId)} clear$tail.",
                        hex = cur.hex,
                    )
                    st.announced = Severity.NONE
                }
            }
        }

        // ── targets that vanished or went stale ───────────────────────────
        val gone = tracks.entries.filter { !it.value.seenThisStep }
        for ((hex, st) in gone) {
            val last = st.last
            if (st.announced >= Severity.ADVISORY && last != null && own != null) {
                events += AlertEvent(
                    nowMs, EventKind.TRACK_LOST, Severity.INFO,
                    text = "${last.displayId} track lost.",
                    speech = "${Phrasing.spelledId(last.displayId)} track lost.",
                    hex = hex,
                )
            }
            tracks.remove(hex)
        }

        views.sortBy { it.distNm }
        return StepResult(
            events = events.sortedByDescending { it.severity.rank },
            targets = views,
            ownshipFresh = ownFresh,
            ownshipAgeSec = ownAge,
            trafficStale = trafficStale,
            watchedZones = watched.map { it.displayName },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun handleOwnship(nowMs: Long, o: Ownship?, fresh: Boolean, events: MutableList<AlertEvent>) {
        if (externalSelection) { handleOwnshipExternal(nowMs, o, fresh, events); return }
        val cfg = config
        if (fresh) {
            o!!
            when (ownState) {
                OwnState.UNKNOWN -> {
                    val manual = if (o.isManual) ", using ${o.source.label}" else ""
                    events += AlertEvent(nowMs, EventKind.OWNSHIP_ACQUIRED, Severity.INFO,
                        "Watching ${o.name}$manual", "Watching ${Phrasing.spelledId(o.name)}$manual")
                }
                OwnState.LOST -> events += if (o.isManual)
                    AlertEvent(nowMs, EventKind.OWNSHIP_MANUAL, Severity.CAUTION, "Using ${o.source.label}")
                else AlertEvent(nowMs, EventKind.OWNSHIP_REGAINED, Severity.INFO, "Drone position regained")
                OwnState.OK -> {
                    val prev = ownLastSource
                    val prevId = ownLastId
                    if (prev != null && prev != o.source && o.isManual && !isManual(prev)) {
                        events += AlertEvent(nowMs, EventKind.OWNSHIP_MANUAL,
                            Severity.CAUTION, "Drone feed lost, using ${o.source.label}")
                    } else if (prev != null && !o.isManual && isManual(prev)) {
                        events += AlertEvent(nowMs, EventKind.OWNSHIP_REGAINED, Severity.INFO,
                            "Drone position regained, watching ${o.name}", "Drone position regained, watching ${Phrasing.spelledId(o.name)}")
                    } else if (prevId != null && prevId != o.id && !o.isManual) {
                        events += AlertEvent(nowMs, EventKind.OWNSHIP_ACQUIRED, Severity.INFO,
                            "Now watching ${o.name}", "Now watching ${Phrasing.spelledId(o.name)}")
                    }
                }
            }
            // A different aircraft is now "ownship": every range/zone memory is
            // relative to the old one, so drop it silently rather than emit a
            // burst of "track lost" for traffic that is simply far from the new drone.
            if (ownLastId != null && ownLastId != o.id) tracks.clear()
            ownState = OwnState.OK
            ownLastSource = o.source
            ownLastId = o.id
        } else {
            when (ownState) {
                OwnState.OK -> {
                    events += AlertEvent(nowMs, EventKind.OWNSHIP_LOST, Severity.CAUTION, "Drone position lost")
                    ownState = OwnState.LOST; ownLostAnnounceMs = nowMs
                }
                OwnState.LOST -> if (nowMs - ownLostAnnounceMs >= cfg.ownshipLostReminderSec * 1000) {
                    events += AlertEvent(nowMs, EventKind.OWNSHIP_LOST, Severity.CAUTION, "Drone position still lost")
                    ownLostAnnounceMs = nowMs
                }
                OwnState.UNKNOWN -> if (nowMs - (firstStepMs ?: nowMs) >= cfg.ownshipLostSec * 1000) {
                    events += AlertEvent(nowMs, EventKind.OWNSHIP_LOST, Severity.CAUTION, "No drone position")
                    ownState = OwnState.LOST; ownLostAnnounceMs = nowMs
                }
            }
            ownPrev = null; ownLast = null
        }
    }

    /** Selection speech belongs to the DroneSelector; only lost/regained for the same drone is said here. */
    private fun handleOwnshipExternal(nowMs: Long, o: Ownship?, fresh: Boolean, events: MutableList<AlertEvent>) {
        if (fresh) {
            o!!
            if (ownState == OwnState.LOST && ownLastId == o.id && !o.isController)
                events += AlertEvent(nowMs, EventKind.OWNSHIP_REGAINED, Severity.INFO, "Drone position regained")
            if (ownLastId != null && ownLastId != o.id) tracks.clear()
            ownState = OwnState.OK; ownLastSource = o.source; ownLastId = o.id
            return
        }
        val isDrone = o != null && !o.isController
        when (ownState) {
            OwnState.OK -> {
                if (isDrone) events += AlertEvent(nowMs, EventKind.OWNSHIP_LOST, Severity.CAUTION, "Drone position lost")
                ownState = OwnState.LOST; ownLostAnnounceMs = nowMs
            }
            OwnState.LOST -> if (isDrone && nowMs - ownLostAnnounceMs >= config.ownshipLostReminderSec * 1000) {
                events += AlertEvent(nowMs, EventKind.OWNSHIP_LOST, Severity.CAUTION, "Drone position still lost")
                ownLostAnnounceMs = nowMs
            }
            OwnState.UNKNOWN -> Unit
        }
        if (o != null) { ownLastId = o.id; ownLastSource = o.source }
        ownPrev = null; ownLast = null
    }

    /** Inside a cylinder zone, optionally widened by the ring/band hysteresis. Unknown altitude counts as inside. */
    private fun insideCylinder(z: Zone, p: LatLon, altMsl: Double?, groundFt: Double?, hyst: Boolean): Boolean {
        val c = z.circle
        val horiz = if (c != null) Geo.distanceNm(c.center, p) <= c.radiusNm + if (hyst) config.ringHysteresisNm else 0.0
            else z.containsHoriz(p)
        if (!horiz) return false
        if (altMsl == null) return true
        val pad = if (hyst) config.bandHysteresisFt else 0.0
        return altMsl >= limitMsl(z.floor, groundFt, isFloor = true) - pad &&
            altMsl <= limitMsl(z.ceiling, groundFt, isFloor = false) + pad
    }

    private fun isManual(s: OwnshipSource) = s == OwnshipSource.MANUAL_PINNED || s == OwnshipSource.DEVICE_GPS

    private fun targetVelocity(cur: Target, prev: Target?): EN? {
        if (cur.gsKt != null && cur.trackDeg != null) return Geo.velocity(cur.gsKt, cur.trackDeg)
        if (prev == null) return null
        val dt = (cur.posTimeMs - prev.posTimeMs) / 1000.0
        if (dt < 0.5 || dt > 30.0) return null
        return Geo.toEN(prev.pos, cur.pos) * (1.0 / dt)
    }

    private fun derivedVs(cur: Target, prev: Target?): Double? {
        prev ?: return null
        val a = cur.altGeomFt ?: cur.altBaroFt ?: return null
        val b = prev.altGeomFt ?: prev.altBaroFt ?: return null
        val dt = (cur.posTimeMs - prev.posTimeMs) / 1000.0
        if (dt < 0.5 || dt > 30.0) return null
        return (a - b) / dt * 60.0
    }

    private fun ownVelocity(): EN? {
        val a = ownPrev ?: return null
        val b = ownLast ?: return null
        val dt = (b.posTimeMs - a.posTimeMs) / 1000.0
        if (dt < 0.5 || dt > 30.0) return null
        return Geo.toEN(a.pos, b.pos) * (1.0 / dt)
    }

    private fun ownVerticalFpm(): Double? {
        val a = ownPrev ?: return null
        val b = ownLast ?: return null
        val am = a.altMslFt ?: return null
        val bm = b.altMslFt ?: return null
        val dt = (b.posTimeMs - a.posTimeMs) / 1000.0
        if (dt < 0.5 || dt > 30.0) return null
        return (bm - am) / dt * 60.0
    }

    /** Unknown aircraft altitude is treated as INSIDE the band (and spoken as unknown). */
    private fun inZoneBand(altMsl: Double?, z: Zone, groundFt: Double?): Boolean {
        if (altMsl == null) return true
        val floor = limitMsl(z.floor, groundFt, isFloor = true)
        val ceil = limitMsl(z.ceiling, groundFt, isFloor = false)
        return altMsl >= floor && altMsl <= ceil
    }

    private fun limitMsl(l: AltLimit, groundFt: Double?, isFloor: Boolean): Double {
        val v = l.valueFt
        return when {
            v == null || l.ref == AltRef.UNKNOWN ->
                if (isFloor) Double.NEGATIVE_INFINITY else config.unknownTfrCeilingFt
            l.ref == AltRef.MSL -> v
            // AGL: surface floor is simply "the ground". Any other AGL value
            // needs terrain; the ground under the drone (MSL - AGL) is used.
            // Without it, fail wide (never silently narrow the zone).
            isFloor && v <= 0.0 -> Double.NEGATIVE_INFINITY
            groundFt != null -> groundFt + v
            else -> if (isFloor) Double.NEGATIVE_INFINITY else config.unknownTfrCeilingFt
        }
    }

    private fun describe(v: TargetView, speech: Boolean): String {
        val id = if (speech) Phrasing.spelledId(v.displayId) else v.displayId
        val dir = Geo.cardinalWord(v.bearingDeg)
        val dist = Phrasing.spokenDistance(v.distNm)
        val vert = Phrasing.spokenVertical(v.dvFt)
        val trend = v.trend?.let { ", ${it.word}" } ?: ""
        return "$id, $dir, $dist, $vert$trend"
    }

    private fun proximityEvent(nowMs: Long, v: TargetView): AlertEvent {
        val prefix = when (v.severity) {
            Severity.WARNING -> "Warning. Traffic, "
            Severity.CAUTION -> "Caution. Traffic, "
            else -> "Traffic, "
        }
        if (v.predictive && v.cpa != null) {
            val cpaNm = Units.mToNm(v.cpa.distM)
            val tail = "closest ${Phrasing.spokenDistance(cpaNm)} in ${Phrasing.seconds(v.cpa.tSec)}"
            return AlertEvent(nowMs, EventKind.PREDICTIVE, Severity.WARNING,
                text = "${prefix}${describe(v, false)}, $tail.",
                speech = "${prefix}${describe(v, true)}, $tail.", hex = v.hex)
        }
        return AlertEvent(nowMs, EventKind.PROXIMITY, v.severity,
            text = "${prefix}${describe(v, false)}.",
            speech = "${prefix}${describe(v, true)}.", hex = v.hex)
    }

    companion object {
        /** |range rate| below ~5 kt is "passing", not converging/diverging. */
        const val TREND_MS = 2.5
    }
}
