package com.uasflightdeck.sentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLayoutTest {
    // Real RC Plus: 1920x1200 px at density 2.5 -> 768 dp wide (matches the owner's photo).
    // 320 dpi -> 960 dp. Old emulator profile: 240 dpi -> 1280 dp.
    @Test fun rcPlusIsCompactOld240dpiProfileIsNot() {
        assertTrue(ScreenLayout.compactFor(1920 * 160 / 400))
        assertTrue(ScreenLayout.compactFor(1920 / 2))
        assertFalse(ScreenLayout.compactFor(1920 * 160 / 240))
        assertTrue(ScreenLayout.compactFor(999))
        assertFalse(ScreenLayout.compactFor(1000))
    }

    @Test fun unknownWidthFallsBackToThePlanThatFitsEverywhere() {
        assertTrue(ScreenLayout.mainPlan(0).compact)
    }

    @Test fun compactPlanReflowsAndShrinksTheCompass() {
        val c = ScreenLayout.mainPlan(768); val w = ScreenLayout.mainPlan(1280)
        assertTrue(c.droneUnderCompass)
        assertFalse(w.droneUnderCompass)
        // the compass column's share shrinks, the callouts column's share grows
        fun share(p: ScreenLayout.MainPlan, x: Float) = x / (p.leftWeight + p.centreWeight + p.rightWeight)
        assertTrue(share(c, c.centreWeight) < share(w, w.centreWeight))
        assertTrue(share(c, c.rightWeight) > share(w, w.rightWeight))
        // buttons stay glove-sized
        assertTrue(c.buttonHeightDp >= 48)
    }

    @Test fun settingsCollapsesToOneColumnOnTheRcPlus() {
        assertEquals(1, ScreenLayout.settingsColumns(768))
        assertEquals(1, ScreenLayout.settingsColumns(960))
        assertEquals(2, ScreenLayout.settingsColumns(1280))
    }
}
