package com.uasflightdeck.sentry.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The whole decision of "does the pilot get alerted right now" (v0.4.0: sounds, vibration and banners; no voice).
 *
 * Call [step] once per tick (1 s) with the CURRENT inputs. Per aircraft it computes the time-to-close prediction
 * ([Prediction]), the tier ([Tier], highest wins), and the cadence ([Cadence]) of what to emit. Every output is
 * derived from those inputs plus a small per-aircraft memory used only for rate limiting, hysteresis, holding a
 * prediction for [SentryConfig.predictionHoldSec], and deriving a velocity when a feed omits track (N388KM's
 * public ADS-B had no track during the 2026-09-23 pass). [StepResult.targets] is recomputed from live data
 * every step: nothing displayed is "last event wins".
 */
class AlertEngine(
    var config: SentryConfig = SentryConfig(),
    /**
     * true = a [DroneSelector] decides what is protected and reports every selection change. The engine then only
     * reports position lost / regained for the SAME drone and resets its tracks silently when the protected thing changes.
     */
    val externalSelection: Boolean = false,
) {

    /** What the UI and the banner show for one aircraft, recomputed every step. */
    data class TargetView(
        val hex: String,
        val displayId: String,
        val distNm: Double,
        val bearingDeg: Double,
        val dvFt: Double?,
        val altEstimated: Boolean,
        val trend: Trend?,
        val tier: Tier,
        val prediction: Prediction.Result?,
        val zones: List<String>,
        val ageSec: Double,
        val sources: Set<String>,
        val groundModeAirborne: Boolean,
        /** Altitude for the display ceiling: geometric (GPS) when reported, else raw baro; null = unknown. */
        val altFt: Double? = null,
        val type: String? = null,
        val gsKt: Double? = null,
        val trackDeg: Double? = null,
        /** HIS reported vertical rate (fpm); null = not reported. */
        val vsFpm: Double? = null,
        val droneVsFpm: Double? = null,
        /** The banner hint: bearing to move ("Clear: move NW"), null without his track. */
        val escapeBearingDeg: Double? = null,
        /** +1 ↑, -1 ↓, 0 none. */
        val escapeVertical: Int = 0,
        /**
         * 0.4.2: PASSING was announced for this aircraft and it has not turned back toward the drone. Its banner stays
         * "● PASSING · <id> · diverging" (grey, silent, in place) until CLEAR, whatever ring it is still inside.
         */
        val passing: Boolean = false,
        /** Closest range this pass (nm), for the PASSING banner. */
        val closestNm: Double? = null,
    ) {
        val severity: Severity get() = tier.severity
        /** The tier comes from a prediction (TRACK / predicted WARNING / COLLISION RISK) rather than a ring. */
        val predictive: Boolean get() = prediction?.converging == true && tier in PREDICTED
        val tCpaSec: Double? get() = prediction?.takeIf { it.converging }?.tCpaSec
        val missNm: Double? get() = prediction?.takeIf { it.converging }?.missNm
    }

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
        /** The tier last alerted (escalation) or stepped down to. */
        var announced: Tier = Tier.NONE
        var lastCueMs: Long = Long.MIN_VALUE / 2
        /** The tier used for hysteresis this step (last step's computed tier). */
        var currentTier: Tier = Tier.NONE
        var maxTier: Tier = Tier.NONE
        /** "PASSING" emitted: no escalation or repeat until it converges again. */
        var passed = false
        var sawConverging = false
        var minRangeNm = Double.MAX_VALUE
        /** Worst (lowest) closeness score S this pass. */
        var minS = Double.MAX_VALUE
        val predLastTrueMs = HashMap<Tier, Long>()
        /** When the tier last stepped down from each level (anti-flap: a quick step back up is not re-alerted). */
        val dropMs = HashMap<Tier, Long>()
        val prevMargin = HashMap<Tier, Double>()
        var prevMarginMs: Long? = null
        val zoneInside = HashMap<String, Boolean>()
        val zoneLastAnnounceMs = HashMap<String, Long>()
        var seenThisStep = false
        var prevTrackDeg: Double? = null
        var prevTrackMs: Long = 0
        var turnDegPerSec: Double? = null
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

        // ── traffic data health (screen only) ─────────────────────────────
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
        val ownVel = if (own?.isController == true) EN(0.0, 0.0) else ownVelocity()
        val ownVs = if (own?.isController == true) 0.0 else ownVerticalFpm()
        // Zone ENTRY alerts only for zones containing the drone or within zoneAlertNm of it (0.3.5 alerted on TFRs 5-9 nm away).
        val alertZones: Set<String> = if (own == null) emptySet() else watched.filter { z ->
            z.containsHoriz(own.pos) || (z.circle?.let { Geo.distanceNm(it.center, own.pos) - it.radiusNm <= cfg.zoneAlertNm }
                ?: z.polygons.any { Geo.distanceToPolygonM(own.pos, it) <= Units.nmToM(cfg.zoneAlertNm) })
        }.map { it.id }.toSet()

        // ── per-aircraft ────────────────────────────────────────────────────
        tracks.values.forEach { it.seenThisStep = false }
        val views = ArrayList<TargetView>()
        for (t in targets) {
            val age = (nowMs - t.posTimeMs) / 1000.0
            if (age > cfg.staleTargetSec) continue            // never alert on a stale target
            // "ground" + slow = really on the ground. "ground" + fast = airborne
            // with an unknown altitude (the N388KM case). Unknown speed + ground = ground.
            val groundAirborne = t.reportsGround && (t.gsKt ?: 0.0) >= cfg.groundSpeedAirborneKt
            if (t.reportsGround && !groundAirborne) {
                val st = tracks.remove(t.hex)
                if (st != null && st.announced >= Tier.ADVISORY) events += AlertEvent(
                    nowMs, EventKind.CLEAR, Severity.INFO, text = "${t.displayId} on the ground.", hex = t.hex,
                    banner = Banner.clear(t.displayId, "On the ground"))
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
            // Vertical rate: the reported baro_rate / geom_rate only (absent = treated as level).
            val vs = if (groundAirborne) null else cur.vsFpm
            val gs = cur.gsKt ?: vel?.let { Units.msToKt(it.norm) }
            val trk = cur.trackDeg ?: vel?.takeIf { it.norm > 0.5 }?.let { Geo.normDeg(Math.toDegrees(kotlin.math.atan2(it.e, it.n))) }
            if (trk != null) {
                val pt = st.prevTrackDeg
                val dt = (nowMs - st.prevTrackMs) / 1000.0
                if (pt != null && dt in 0.5..10.0) {
                    var d = trk - pt; if (d > 180) d -= 360; if (d < -180) d += 360
                    st.turnDegPerSec = d / dt
                }
                st.prevTrackDeg = trk; st.prevTrackMs = nowMs
            }

            val rel = Geo.toEN(own.pos, posX)
            val bearing = Geo.bearingDeg(own.lat, own.lon, posX.lat, posX.lon)
            val dv = if (altMsl != null && own.altMslFt != null) altMsl - own.altMslFt else null
            val vRel = vel?.let { it - (ownVel ?: EN(0.0, 0.0)) }
            val vsRel = (vs ?: 0.0) - (ownVs ?: 0.0)
            val pred = Prediction.predict(rel, vRel, dv, vsRel, cfg.warningNm, cfg.collisionSec)
            val distNm = pred.rangeNm
            val trend = vRel?.let {
                when {
                    pred.rangeRateMs < -TREND_MS -> Trend.CONVERGING
                    pred.rangeRateMs > TREND_MS -> Trend.DIVERGING
                    else -> Trend.PASSING
                }
            }
            val converging = pred.converging && trend != Trend.DIVERGING
            if (converging) st.sawConverging = true

            // ── tier ───────────────────────────────────────────────────────
            val held = st.currentTier
            val bandLimit = cfg.ceilingAboveFt + if (held >= Tier.ADVISORY) cfg.bandHysteresisFt else 0.0
            // Surface up to the ceiling above the drone; unknown altitude counts as inside (fail wide).
            val inVolumeNow = dv == null || dv <= bandLimit
            val inVolumeAtCpa = pred.dvAtCpaFt == null || pred.dvAtCpaFt <= bandLimit
            // Once a predicted tier is up, its thresholds widen by the hysteresis (0.2 nm, +10 s) so a noisy
            // derived track on the edge doesn't flap (seen in the demo replay around 11:52:20).
            fun corr(base: Double, level: Tier) = Prediction.corridorNm(base, distNm, cfg.corridorDeg) +
                if (held >= level) cfg.ringHysteresisNm else 0.0
            fun horizon(sec: Double, level: Tier) = sec + if (held >= level) cfg.predictionHysteresisSec else 0.0
            fun ring(level: Tier, nm: Double) = nm + if (held >= level) cfg.ringHysteresisNm else 0.0

            val margins = HashMap<Tier, Double>()
            fun predMargin(limitSec: Double, missLimitNm: Double, volOk: Boolean): Double =
                if (!pred.converging) 1.0 else maxOf((pred.tCpaSec - limitSec) / limitSec,
                    (pred.missNm - missLimitNm) / missLimitNm, if (volOk) -1.0 else 1.0)

            val cylinders = watched.filter { it.kind == ZoneKind.CYLINDER }
            var ringTier = Tier.NONE
            val trackCond: Boolean; val warnPred: Boolean; val collision: Boolean
            if (own.isController) {
                // CONTROLLER MODE: the rings are replaced by the pilot's cylinders (inside any = CAUTION); the
                // predictions are about this controller, with each cylinder's floor/ceiling as the vertical test.
                val hyst = held >= Tier.CAUTION
                if (cylinders.any { insideCylinder(it, posX, altMsl, groundFt, hyst) }) ringTier = Tier.CAUTION
                val altAtCpa = altMsl?.let { it + (vs ?: 0.0) * pred.tCpaSec / 60.0 }
                val bandOk = cylinders.any { inZoneBand(altMsl, it, groundFt) || inZoneBand(altAtCpa, it, groundFt) }
                margins[Tier.TRACK] = predMargin(horizon(cfg.trackSec, Tier.TRACK), corr(cfg.trackMissNm, Tier.TRACK), bandOk)
                margins[Tier.WARNING] = predMargin(horizon(cfg.warningSec, Tier.WARNING), corr(cfg.warningMissNm, Tier.WARNING), bandOk)
                trackCond = margins.getValue(Tier.TRACK) <= 0
                warnPred = margins.getValue(Tier.WARNING) <= 0
                collision = false                               // the controller is not flying
            } else {
                margins[Tier.ADVISORY] = distNm - cfg.advisoryNm
                margins[Tier.CAUTION] = distNm - cfg.cautionNm
                if (inVolumeNow) ringTier = when {
                    distNm <= ring(Tier.WARNING, cfg.warningNm) -> Tier.WARNING
                    distNm <= ring(Tier.CAUTION, cfg.cautionNm) -> Tier.CAUTION
                    distNm <= ring(Tier.ADVISORY, cfg.advisoryNm) -> Tier.ADVISORY
                    else -> Tier.NONE
                }
                margins[Tier.TRACK] = predMargin(horizon(cfg.trackSec, Tier.TRACK), corr(cfg.trackMissNm, Tier.TRACK), inVolumeAtCpa)
                val wp = predMargin(horizon(cfg.warningSec, Tier.WARNING), corr(cfg.warningMissNm, Tier.WARNING), inVolumeAtCpa)
                margins[Tier.WARNING] = min(wp, (distNm - cfg.warningNm) / cfg.warningNm)
                trackCond = margins.getValue(Tier.TRACK) <= 0
                warnPred = wp <= 0
                // COLLISION RISK: predicted within 500 ft / 300 ft within 60 s (unknown altitude = inside, fail wide),
                // or his vertical rate carries him THROUGH the drone's altitude while inside 0.5 nm in the next 60 s.
                val c1 = if (!pred.converging) 1.0 else maxOf((pred.tCpaSec - cfg.collisionSec) / cfg.collisionSec,
                    (pred.missFt - cfg.collisionMissFt) / cfg.collisionMissFt,
                    pred.dvAtCpaFt?.let { (abs(it) - cfg.collisionVertFt) / cfg.collisionVertFt } ?: -1.0)
                val c2 = if (pred.crossingInSec != null) -1.0 else 1.0
                margins[Tier.COLLISION] = min(c1, c2)
                collision = margins.getValue(Tier.COLLISION) <= 0
            }

            // Predicted tiers hold for predictionHoldSec after the prediction leaves the corridor, unless diverging.
            fun predicted(tier: Tier, now: Boolean): Boolean {
                if (now) { st.predLastTrueMs[tier] = nowMs; return true }
                val lt = st.predLastTrueMs[tier] ?: return false
                // Dropped only after the prediction has been out of the corridor for predictionHoldSec (5 s).
                val keep = trend != Trend.DIVERGING && nowMs - lt <= cfg.predictionHoldSec * 1000
                if (!keep) st.predLastTrueMs.remove(tier)
                return keep
            }
            val predTier = when {
                predicted(Tier.COLLISION, collision) -> Tier.COLLISION
                predicted(Tier.WARNING, warnPred) -> Tier.WARNING
                predicted(Tier.TRACK, trackCond) -> Tier.TRACK
                else -> Tier.NONE
            }
            // (predicted() must run for every tier so each hold timer stays current)
            if (predTier == Tier.COLLISION) { predicted(Tier.WARNING, warnPred); predicted(Tier.TRACK, trackCond) }
            else if (predTier == Tier.WARNING) predicted(Tier.TRACK, trackCond)
            val tier = if (predTier > ringTier) predTier else ringTier
            st.currentTier = tier

            // ── zones ─────────────────────────────────────────────────────
            val inZones = ArrayList<Zone>()
            for (z in watched) {
                val horiz = z.containsHoriz(posX)
                // A TFR / geofence counts only for an aircraft inside the protected volume; cylinders have their own band.
                val vol = z.kind == ZoneKind.CYLINDER || own.isController || inVolumeNow
                if (horiz && vol && inZoneBand(altMsl, z, groundFt)) inZones += z
            }

            val escape = trk?.let { Banner.escapeBearing(it, rel, st.turnDegPerSec) }
            val view = TargetView(
                hex = cur.hex, displayId = cur.displayId, distNm = distNm, bearingDeg = bearing,
                dvFt = dv, altEstimated = estimated, trend = trend, tier = tier, prediction = pred,
                zones = inZones.map { it.displayName }, ageSec = age, sources = cur.sources, groundModeAirborne = groundAirborne,
                altFt = if (groundAirborne) null else cur.altGeomFt ?: cur.altBaroFt,
                type = cur.type, gsKt = gs, trackDeg = trk, vsFpm = vs, droneVsFpm = ownVs,
                escapeBearingDeg = escape, escapeVertical = Banner.escapeVertical(dv, vs, pred.crossingInSec != null),
            )
            views += view

            // Interpolated time the new tier's condition became true (latency metric).
            fun crossedAt(t: Tier): Long? {
                val pm = st.prevMargin[t] ?: return null
                val pms = st.prevMarginMs ?: return null
                val cm = margins[t] ?: return null
                if (pm <= 0 || cm > 0) return null
                return pms + ((nowMs - pms) * (pm / (pm - cm))).toLong()
            }

            // ── alerts ─────────────────────────────────────────────────────
            st.minRangeNm = min(st.minRangeNm, distNm)
            if (tier >= Tier.ADVISORY || st.announced >= Tier.ADVISORY) st.minS = min(st.minS, Closeness.score(distNm * Units.FT_PER_NM, dv))
            val sNow = st.minS.takeIf { it < Double.MAX_VALUE }
            val zoneEvents = ArrayList<AlertEvent>()
            for (z in watched) {
                val inside = inZones.contains(z)
                val was = st.zoneInside[z.id]
                st.zoneInside[z.id] = inside
                if (!inside || was == true) continue
                if (z.id !in alertZones) continue                     // far-off zone: tracked, never alerted
                val lastZ = st.zoneLastAnnounceMs[z.id] ?: Long.MIN_VALUE / 2
                if (nowMs - lastZ < cfg.reannounceSec * 1000) continue
                st.zoneLastAnnounceMs[z.id] = nowMs
                val verb = if (was == null) "inside" else "entering"
                zoneEvents += AlertEvent(
                    nowMs,
                    when (z.kind) {
                        ZoneKind.TFR -> EventKind.TFR_ENTRY
                        ZoneKind.GEOFENCE -> EventKind.GEOFENCE_ENTRY
                        ZoneKind.CYLINDER -> EventKind.CYLINDER_ENTRY
                    },
                    if (tier.severity > Severity.CAUTION) tier.severity else Severity.CAUTION,
                    text = "Traffic $verb ${z.displayName}: ${Banner.line2(view)}",
                    hex = cur.hex, distNm = distNm, tier = tier, cue = Cue.FULL, popup = true,
                    banner = Banner.zone(view, z.displayName), closenessS = sNow,
                )
            }

            fun traffic(phase: Phase, cue: Cue, popup: Boolean, crossed: Long? = null) = AlertEvent(
                nowMs, EventKind.TRAFFIC, tier.severity, text = Banner.traffic(view).oneLine, hex = cur.hex, distNm = distNm,
                tier = tier, phase = phase, cue = cue, popup = popup, banner = Banner.traffic(view), crossedAtMs = crossed,
                closenessS = sNow)

            val out = ArrayList<AlertEvent>()
            when {
                // Escalation: at once, full sound + popup. After a pass it re-triggers only once converging again.
                // (After a pass, turning back toward the drone re-triggers by tier.)
                tier >= Tier.ADVISORY && ((tier > st.announced && !st.passed) || (st.passed && converging && trend == Trend.CONVERGING)) -> {
                    // Anti-flap: back up to a tier already alerted this pass, within rearmSec of stepping down from it,
                    // is a silent update (the COLLISION RISK tone keeps its 3 s rhythm).
                    val reflap = !st.passed && tier <= st.maxTier && st.dropMs[tier]?.let { nowMs - it < cfg.rearmSec * 1000 } == true
                    if (reflap) {
                        val tone = tier == Tier.COLLISION && nowMs - st.lastCueMs >= cfg.collisionRepeatSec * 1000 - 1
                        out += traffic(if (tone) Phase.REPEAT else Phase.UPDATE, if (tone) Cue.FULL else Cue.NONE, false)
                        if (tone) st.lastCueMs = nowMs
                        st.announced = tier
                    } else {
                        out += traffic(Phase.ESCALATION, Cue.FULL, true, crossedAt(tier))
                        st.announced = tier; st.lastCueMs = nowMs; st.passed = false
                        st.sawConverging = converging
                        if (tier > st.maxTier) st.maxTier = tier
                        st.minRangeNm = distNm
                    }
                }
                // Range opening after it was converging: one PASSING (sound only after WARNING / COLLISION RISK).
                st.announced >= Tier.ADVISORY && trend == Trend.DIVERGING && !st.passed && st.sawConverging &&
                    tier >= Tier.ADVISORY && tier < Tier.COLLISION -> {
                    val loud = st.maxTier >= Tier.WARNING
                    out += AlertEvent(nowMs, EventKind.PASSING, Severity.ADVISORY,
                        text = "${cur.displayId} passing, diverging (closest ${Banner.dist(st.minRangeNm)})",
                        hex = cur.hex, distNm = distNm, tier = tier, cue = if (loud) Cue.FULL else Cue.NONE,
                        banner = Banner.passing(view, st.minRangeNm), closenessS = sNow)
                    st.passed = true; st.announced = tier; st.lastCueMs = nowMs
                }
                tier == Tier.NONE && st.announced >= Tier.ADVISORY -> {
                    if (st.announced == Tier.TRACK && trend != Trend.DIVERGING && distNm > cfg.advisoryNm) {
                        // TRACK ALERT whose prediction left the corridor for >= 5 s: banner update, no sound.
                        out += AlertEvent(nowMs, EventKind.NO_LONGER_FACTOR, Severity.INFO,
                            text = "${cur.displayId} no longer a factor", hex = cur.hex, distNm = distNm, tier = Tier.NONE,
                            banner = Banner.noLongerFactor(view), closenessS = sNow)
                    } else {
                        val why = if (!inVolumeNow) "Outside your volume" else "Outside ${Banner.dist(cfg.advisoryNm)}"
                        out += AlertEvent(nowMs, EventKind.CLEAR, Severity.INFO, text = "${cur.displayId} clear ($why)",
                            hex = cur.hex, distNm = distNm, tier = Tier.NONE, banner = Banner.clear(cur.displayId, why), closenessS = sNow)
                    }
                    st.announced = Tier.NONE; st.maxTier = Tier.NONE; st.passed = false; st.sawConverging = false
                    st.minRangeNm = Double.MAX_VALUE; st.minS = Double.MAX_VALUE
                }
                tier < st.announced && tier >= Tier.ADVISORY -> {
                    // Steps down are silent: a banner update ("passing"/downgrade), no sound. A TRACK ALERT whose
                    // prediction lapsed says "no longer a factor" even when the aircraft is still inside a ring.
                    // After PASSING (0.4.2) the step-down keeps the PASSING banner: grey, silent, in place.
                    out += if (st.passed) AlertEvent(nowMs, EventKind.TRAFFIC, tier.severity,
                        text = "${cur.displayId} passing, diverging (closest ${Banner.dist(st.minRangeNm)})", hex = cur.hex,
                        distNm = distNm, tier = tier, phase = Phase.DOWNGRADE, banner = Banner.passing(view, st.minRangeNm),
                        closenessS = sNow)
                    else if (st.announced == Tier.TRACK) AlertEvent(nowMs, EventKind.NO_LONGER_FACTOR, Severity.INFO,
                        text = "${cur.displayId} no longer a factor", hex = cur.hex, distNm = distNm, tier = tier,
                        phase = Phase.DOWNGRADE, banner = Banner.noLongerFactor(view), closenessS = sNow)
                    else traffic(Phase.DOWNGRADE, Cue.NONE, false)
                    Tier.entries.filter { it > tier && it <= st.announced }.forEach { st.dropMs[it] = nowMs }
                    st.announced = tier
                }
                tier >= Tier.ADVISORY && !st.passed -> {
                    val rep = Cadence.repeat(tier, distNm, pred.tCpaSec.takeIf { pred.converging }, converging, cfg)
                    if (rep != null && nowMs - st.lastCueMs >= rep.everySec * 1000 - 1) {
                        out += traffic(rep.phase, rep.cue, false)
                        st.lastCueMs = nowMs
                    }
                }
            }
            // A zone entry is one alert of its own, unless an escalation fires in the same tick (then that one is the
            // alert) or the aircraft is already at WARNING or above (its own alarm cadence must not be displaced).
            if (zoneEvents.isNotEmpty()) {
                val esc = out.any { it.phase == Phase.ESCALATION } || st.announced >= Tier.WARNING
                events += if (esc) zoneEvents.map { it.copy(cue = Cue.NONE, popup = false, banner = null) } else zoneEvents
                if (!esc && tier >= Tier.ADVISORY) {
                    st.lastCueMs = nowMs
                    if (tier > st.announced) { st.announced = tier; if (tier > st.maxTier) st.maxTier = tier }
                    out.removeAll { it.phase == Phase.REPEAT || it.phase == Phase.UPDATE }
                }
            }
            events += out
            // What the banner shows from now on (the per-second refresh uses it): PASSING until CLEAR or turning back.
            if (st.passed && tier >= Tier.ADVISORY)
                views[views.lastIndex] = view.copy(passing = true, closestNm = st.minRangeNm.takeIf { it < Double.MAX_VALUE })
            st.prevMargin.clear(); st.prevMargin.putAll(margins); st.prevMarginMs = nowMs
        }

        // ── targets that vanished or went stale ───────────────────────────
        val gone = tracks.entries.filter { !it.value.seenThisStep }
        for ((hex, st) in gone) {
            val last = st.last
            if (st.announced >= Tier.ADVISORY && last != null && own != null) {
                events += AlertEvent(nowMs, EventKind.TRACK_LOST, Severity.INFO, text = "${last.displayId} track lost.", hex = hex,
                    banner = Banner.clear(last.displayId, "Track lost"))
            }
            tracks.remove(hex)
        }

        views.sortBy { it.distNm }
        return StepResult(
            // Highest tier first; at the same tier the closer aircraft first.
            events = events.sortedWith(compareByDescending<AlertEvent> { it.tier?.rank ?: -1 }
                .thenByDescending { it.severity.rank }.thenBy { it.distNm ?: Double.MAX_VALUE }),
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
                        "Watching ${o.name}$manual")
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
                            "Drone position regained, watching ${o.name}")
                    } else if (prevId != null && prevId != o.id && !o.isManual) {
                        events += AlertEvent(nowMs, EventKind.OWNSHIP_ACQUIRED, Severity.INFO,
                            "Now watching ${o.name}")
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
                    events += AlertEvent(nowMs, EventKind.OWNSHIP_LOST, Severity.CAUTION, "Drone position still lost", repeat = true)
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
                events += AlertEvent(nowMs, EventKind.OWNSHIP_LOST, Severity.CAUTION, "Drone position still lost", repeat = true)
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

    /** The drone's velocity from its last two fixes; slower than [SentryConfig.droneStationaryKt] = stationary. */
    private fun ownVelocity(): EN? {
        val a = ownPrev ?: return null
        val b = ownLast ?: return null
        val dt = (b.posTimeMs - a.posTimeMs) / 1000.0
        if (dt < 0.5 || dt > 30.0) return null
        val v = Geo.toEN(a.pos, b.pos) * (1.0 / dt)
        return if (Units.msToKt(v.norm) < config.droneStationaryKt) EN(0.0, 0.0) else v
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

    companion object {
        /** |range rate| below ~5 kt is "passing" (abeam), not converging/diverging. */
        const val TREND_MS = 2.5
        val PREDICTED = setOf(Tier.TRACK, Tier.WARNING, Tier.COLLISION)
    }
}
