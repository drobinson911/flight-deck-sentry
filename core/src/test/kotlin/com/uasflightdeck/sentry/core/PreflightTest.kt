package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {
    private val good = Preflight.Facts(
        internet = true, tokenAccepted = true, tokenDetail = "", droneFeedOk = true, droneFeedDetail = "3 drones in feed",
        pinnedSerial = "1581F7K3C251F00C9B34", boundAirborne = false, boundCallsign = "DEMO-1 Pilot",
        controllerGps = true, controllerGpsDetail = "fix ±5 m", trafficOk = true, trafficDetail = "12 near",
        tfrOk = true, tfrDetail = "41 TFRs", notificationsEnabled = true, headsUpAllowed = true, batteryExempt = true,
        soundAudible = true, soundDetail = "played the warning sound", vibrator = true)

    @Test fun allPass() {
        val items = Preflight.items(good)
        assertEquals(11, items.size)
        assertTrue(Preflight.passed(items))
        assertEquals("Pre-flight: all 11 checks passed", Preflight.summary(items))
        assertEquals("1581F7K3C251F00C9B34 · DEMO-1 Pilot · on the pad", items.single { it.name == "Bound aircraft" }.detail)
    }

    @Test fun failuresCarryTheirFix() {
        val items = Preflight.items(good.copy(tokenAccepted = false, tokenDetail = "HTTP 401", headsUpAllowed = false, boundAirborne = null))
        assertFalse(Preflight.passed(items))
        assertEquals("Pre-flight: 3 to fix: Fleet token, Bound aircraft, Notifications", Preflight.summary(items))
        val tok = items.single { it.name == "Fleet token" }
        assertEquals("rejected (HTTP 401)", tok.detail)
        assertTrue(tok.fix.isNotBlank())
        assertEquals("on, but traffic banners can't pop up", items.single { it.name == "Notifications" }.detail)
        assertTrue(items.filter { !it.ok }.all { it.fix.isNotBlank() })
    }

    @Test fun noVibratorIsReportedNotFailed() {
        val items = Preflight.items(good.copy(vibrator = false))
        assertTrue(Preflight.passed(items))
        assertEquals("not available on this controller (skipped)", items.single { it.name == "Vibration" }.detail)
    }

    @Test fun nothingPinned() {
        val items = Preflight.items(good.copy(pinnedSerial = null, boundAirborne = null))
        assertEquals("none pinned: protecting this controller only", items.single { it.name == "Bound aircraft" }.detail)
    }
}
