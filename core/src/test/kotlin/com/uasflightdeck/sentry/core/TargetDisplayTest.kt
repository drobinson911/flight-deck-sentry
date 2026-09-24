package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetDisplayTest {
    private val cfg = TargetDisplay.Config()
    private fun t(hex: String, distNm: Double, altFt: Double?, sev: Severity = Severity.NONE) = AlertEngine.TargetView(
        hex = hex, displayId = hex, distNm = distNm, bearingDeg = 0.0, dvFt = null, altEstimated = false, trend = null,
        severity = sev, predictive = false, cpa = null, zones = emptyList(), ageSec = 1.0, sources = setOf("cloud"),
        groundModeAirborne = altFt == null, altFt = altFt)
    private fun shown(list: List<AlertEngine.TargetView>, aroundAircraft: Boolean) =
        TargetDisplay.filter(list, aroundAircraft, cfg).map { it.hex }.toSet()

    @Test fun radiusAroundTheAircraftIs10nm() {
        val l = listOf(t("in", 9.9, 5000.0), t("edge", 10.0, 5000.0), t("out", 10.1, 5000.0), t("far", 14.0, 5000.0))
        assertEquals(setOf("in", "edge"), shown(l, aroundAircraft = true))
    }

    @Test fun radiusAroundTheControllerIs15nm() {
        val l = listOf(t("in", 14.9, 5000.0), t("edge", 15.0, 5000.0), t("out", 15.1, 5000.0), t("near", 3.0, 5000.0))
        assertEquals(setOf("in", "edge", "near"), shown(l, aroundAircraft = false))
    }

    @Test fun ceiling18000ftAndUnknownAltitudeStaysShown() {
        val l = listOf(t("low", 2.0, 17_999.0), t("at", 2.0, 18_000.0), t("high", 2.0, 18_001.0),
            t("airliner", 5.0, 35_000.0), t("unknown", 2.0, null))
        assertEquals(setOf("low", "at", "unknown"), shown(l, aroundAircraft = true))
    }

    @Test fun anAlertingAircraftIsNeverHidden() {
        val l = listOf(t("warn-far", 12.0, 5000.0, Severity.WARNING), t("adv-high", 4.0, 19_000.0, Severity.ADVISORY),
            t("info-far", 12.0, 5000.0, Severity.INFO))
        assertEquals(setOf("warn-far", "adv-high"), shown(l, aroundAircraft = true))
    }

    @Test fun settingsChangeTheNumbers() {
        val c = TargetDisplay.Config(aroundAircraftNm = 5.0, aroundControllerNm = 8.0, ceilingFt = 10_000.0)
        val l = listOf(t("a", 6.0, 5000.0), t("b", 7.0, 12_000.0))
        assertEquals(setOf<String>(), TargetDisplay.filter(l, true, c).map { it.hex }.toSet())
        assertEquals(setOf("a"), TargetDisplay.filter(l, false, c).map { it.hex }.toSet())
    }
}
