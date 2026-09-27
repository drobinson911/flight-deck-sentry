package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.4: banners readable on any notification surface (own dark card, explicit colours) and a 2-row heads-up. */
class BannerLookTest {
    @Test fun everyTextColourIsAtLeast7to1OnTheCard() {
        val inks = BannerLook.Level.values().map { it.name to it.argb } + BannerLook.Tone.values().map { "Tone." + it.name to it.argb } +
            ("INK" to BannerLook.INK)
        for ((name, c) in inks) {
            val r = BannerLook.contrast(c, BannerLook.BG)
            assertTrue("$name is ${"%.2f".format(r)}:1 on the card", r >= 7.0)
        }
        assertTrue(BannerLook.contrast(BannerLook.INK, BannerLook.BUTTON) >= 7.0)
    }

    @Test fun cardAndTextAreOpaque() {
        (listOf(BannerLook.BG, BannerLook.INK, BannerLook.BUTTON) + BannerLook.Level.values().flatMap { listOf(it.argb, it.stripe) } +
            BannerLook.Tone.values().flatMap { listOf(it.argb, it.stripe) })
            .forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
    }

    @Test fun contrastMatchesWcagReference() {
        assertEquals(21.0, BannerLook.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, BannerLook.contrast(BannerLook.BG, BannerLook.BG), 1e-9)
    }

    @Test fun levelPerTierAndPassingClearAreGrey() {
        assertEquals(BannerLook.Level.COLLISION, BannerLook.level(Tier.COLLISION, "‼ COLLISION RISK · N1"))
        assertEquals(BannerLook.Level.WARNING, BannerLook.level(Tier.WARNING, "⚠ WARNING · N1"))
        assertEquals(BannerLook.Level.CAUTION, BannerLook.level(Tier.CAUTION, "◆ CAUTION · N1"))
        assertEquals(BannerLook.Level.TRACK, BannerLook.level(Tier.TRACK, "▲ TRACK · N1"))
        assertEquals(BannerLook.Level.ADVISORY, BannerLook.level(Tier.ADVISORY, "△ ADVISORY · N1"))
        assertEquals(BannerLook.Level.WARNING, BannerLook.level(Tier.WARNING, "▣ ENTERING TFR 9/9999 · N1"))
        assertEquals(BannerLook.Level.PASSING, BannerLook.level(Tier.WARNING, "● PASSING · N1"))
        assertEquals(BannerLook.Level.PASSING, BannerLook.level(Tier.NONE, "○ CLEAR · N1"))
        assertEquals(BannerLook.Level.PASSING, BannerLook.level(null, "anything"))
    }

    @Test fun headsUpIsTwoRows_levelWhoWhere_thenClosureAndHint() {
        val b = BannerText("⚠ WARNING · N5887", "S 2.9 mi · 150 kt · 4,600 above, level", "Closest 2,700 ft in 40 s", "Clear: move NW ↑")
        val h = BannerLook.headsUpRows(b)
        assertEquals("⚠ WARNING · N5887", h.title)
        assertEquals(" · S 2.9 mi · 150 kt · 4,600 above, level", h.where)
        assertEquals("Closest 2,700 ft in 40 s", h.what)
        assertEquals("Clear: move NW ↑", h.hint)
        assertNull(BannerLook.headsUpRows(BannerText("● PASSING · N1", "SW 1 mi", "Diverging")).hint)
        assertEquals("", BannerLook.headsUpRows(BannerText("t", "", "x")).where)
    }
}
