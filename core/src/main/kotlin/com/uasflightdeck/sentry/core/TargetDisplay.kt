package com.uasflightdeck.sentry.core

/**
 * Which targets the Targets list and the compass rose SHOW (owner, v0.3.3). The alert rings, TFR and cylinder
 * checks are separate and unaffected: the engine still evaluates every aircraft within the traffic radius.
 *
 *  - around this controller's aircraft (bound drone watched): within [Config.aroundAircraftNm] (10 nm);
 *  - otherwise around the controller: within [Config.aroundControllerNm] (15 nm);
 *  - above [Config.ceilingFt] (18,000 ft) is hidden, judged on geometric altitude when reported, else baro;
 *    an aircraft with UNKNOWN altitude stays shown (the list labels it "alt ?").
 *
 * An aircraft that is alerting (advisory or worse) is always shown, whatever its distance or altitude:
 * the screen must never hide the aircraft Sentry is talking about.
 */
object TargetDisplay {
    data class Config(
        val aroundAircraftNm: Double = 10.0,
        val aroundControllerNm: Double = 15.0,
        val ceilingFt: Double = 18_000.0,
    )

    fun radiusNm(aroundAircraft: Boolean, cfg: Config) = if (aroundAircraft) cfg.aroundAircraftNm else cfg.aroundControllerNm

    fun filter(targets: List<AlertEngine.TargetView>, aroundAircraft: Boolean, cfg: Config): List<AlertEngine.TargetView> {
        val r = radiusNm(aroundAircraft, cfg)
        return targets.filter { t ->
            t.severity >= Severity.ADVISORY || (t.distNm <= r && (t.altFt == null || t.altFt <= cfg.ceilingFt))
        }
    }
}
