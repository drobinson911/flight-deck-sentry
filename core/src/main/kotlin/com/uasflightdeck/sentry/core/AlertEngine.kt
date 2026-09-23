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
class AlertEngine(var config: SentryConfig = SentryConfig()) {

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
    private var ownLostAnnounceMs = 0L
    private var ownPrev: Ownship? = null
    private var ownLast: Ownship? = null
    private var firstStepMs: Long? = null
    private var trafficStale = false

    fun reset() {
        tracks.clear(); ownState = OwnState.UNKNOWN; ownLastSource = null
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
            z.kind == ZoneKind.GEOFENCE ||
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
            if (t.reportsGround && !groundAirborne) continue

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

            // vertical band (with hysteresis once we're already alerting on it)
            val bandLimit = cfg.verticalBandFt + if (st.currentSev >= Severity.ADVISORY) cfg.bandHysteresisFt else 0.0
            val inBand = dv == null || abs(dv) <= bandLimit

            var sev = Severity.NONE
            if (inBand) {
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
            var predictive = false
            if (cpa != null && cpa.tSec > 0 && cpa.tSec <= cfg.cpaHorizonSec &&
                Units.mToNm(cpa.distM) <= cfg.warningNm && sev < Severity.WARNING
            ) {
                val ownVs = ownVerticalFpm() ?: 0.0
                val dvAtCpa = if (dv != null) dv + ((vs ?: 0.0) - ownVs) * cpa.tSec / 60.0 else null
                val bandOk = dv == null || min(abs(dv), abs(dvAtCpa!!)) <= bandLimit
                if (bandOk) { predictive = true; sev = Severity.WARNING }
            }
            st.currentSev = sev

            // zones
            val inZones = ArrayList<Zone>()
            for (z in watched) {
                val horiz = z.polygons.any { Geo.pointInPolygon(posX, it) }
                val inside = horiz && inZoneBand(altMsl, z, groundFt)
                if (inside) inZones += z
            }

            val view = TargetView(
                hex = cur.hex, displayId = cur.displayId, distNm = distNm, bearingDeg = bearing,
                dvFt = dv, altEstimated = estimated, trend = trend, severity = sev,
                predictive = predictive, cpa = cpa, zones = inZones.map { it.displayName },
                ageSec = age, sources = cur.sources, groundModeAirborne = groundAirborne,
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
                    if (z.kind == ZoneKind.TFR) EventKind.TFR_ENTRY else EventKind.GEOFENCE_ENTRY,
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
                    if (prev != null && prev != o.source) {
                        if (o.isManual && !isManual(prev)) events += AlertEvent(nowMs, EventKind.OWNSHIP_MANUAL,
                            Severity.CAUTION, "Drone feed lost, using ${o.source.label}")
                        else if (!o.isManual && isManual(prev)) events += AlertEvent(nowMs,
                            EventKind.OWNSHIP_REGAINED, Severity.INFO, "Drone position regained")
                    }
                }
            }
            ownState = OwnState.OK
            ownLastSource = o.source
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
