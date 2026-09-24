package com.uasflightdeck.sentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLayoutTest {
    // RC Plus: 1920x1200 px at density 2.0 -> 960 dp wide. Old emulator profile: 240 dpi -> 1280 dp.
    @Test fun rcPlusIsCompactOld240dpiProfileIsNot() {
        assertTrue(ScreenLayout.compactFor(1920 / 2))
        assertFalse(ScreenLayout.compactFor(1920 * 160 / 240))
        assertTrue(ScreenLayout.compactFor(999))
        assertFalse(ScreenLayout.compactFor(1000))
        assertTrue(ScreenLayout.compactFor(800))     // display size "large" on the controller
    }

    @Test fun unknownWidthFallsBackToThePlanThatFitsEverywhere() {
        assertTrue(ScreenLayout.mainPlan(0).compact)
    }

    @Test fun compactPlanReflowsAndShrinksTheCompass() {
        val c = ScreenLayout.mainPlan(960); val w = ScreenLayout.mainPlan(1280)
        assertTrue(c.droneUnderCompass && c.buttonsTwoRows)
        assertFalse(w.droneUnderCompass || w.buttonsTwoRows)
        // the compass column's share shrinks, the callouts column's share grows
        fun share(p: ScreenLayout.MainPlan, x: Float) = x / (p.leftWeight + p.centreWeight + p.rightWeight)
        assertTrue(share(c, c.centreWeight) < share(w, w.centreWeight))
        assertTrue(share(c, c.rightWeight) > share(w, w.rightWeight))
        // buttons stay glove-sized
        assertTrue(c.buttonHeightDp >= 48)
    }

    @Test fun settingsCollapsesToOneColumnOnTheRcPlus() {
        assertEquals(1, ScreenLayout.settingsColumns(960))
        assertEquals(2, ScreenLayout.settingsColumns(1280))
    }
}
