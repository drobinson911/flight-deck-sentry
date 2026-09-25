package com.uasflightdeck.sentry.core

import com.uasflightdeck.sentry.core.Synthetic.at
import com.uasflightdeck.sentry.core.Synthetic.offset
import com.uasflightdeck.sentry.core.Synthetic.own
import com.uasflightdeck.sentry.core.Synthetic.sec
import com.uasflightdeck.sentry.core.Synthetic.tgt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.3, bug from the 0.4.2 emulator pass: "▣ ENTERING TFR 9/9999 · DEMO33" was posted and 19 ms later the per-second
 * refresh re-titled it "△ ADVISORY · DEMO33 Helicopter" (`BANNER retitle`), so the zone entry was never visible.
 */
class BannerRefreshTest {
    private val bannerMs = 5_000L

    /** A zone 1 nm east of the drone; the aircraft (stationary, in the band) sits 1.3 nm E, then 1.8 nm E inside it. */
    private val zone = Zone("tfr:9/9999", "9/9999", ZoneKind.TFR, listOf(Polygon(listOf(
        offset(90.0, 1.5, -1.0), offset(90.0, 3.5, -1.0), offset(90.0, 3.5, 1.0), offset(90.0, 1.5, 1.0)))),
        floor = AltLimit.SURFACE, ceiling = AltLimit(18000.0, AltRef.MSL))

    @Test fun zoneEntryBanner_notRetitledByTheRefreshInTheSameTick() {
        val e = AlertEngine()
        e.step(sec(0), own(sec(0)), emptyList(), listOf(zone), 0.0)
        e.step(sec(1), own(sec(1)), listOf(tgt(sec(1), 90.0, 1.3, geomFt = 8700.0, type = "R44")), listOf(zone), 0.0)
        val r = e.step(sec(2), own(sec(2)), listOf(tgt(sec(2), 90.0, 1.8, geomFt = 8700.0, type = "R44")), listOf(zone), 0.0)
        val entry = r.events.firstOrNull { it.kind == EventKind.TFR_ENTRY }
        assertNotNull("no zone entry: ${r.events}", entry)
        val posted = entry!!.banner!!
        assertTrue(posted.title, posted.title.startsWith("▣ ENTERING TFR 9/9999 · N1234"))
        val v = r.targets.single()
        val refresh = Banner.forView(v)                                   // what SentryService's refresh would post
        assertEquals("△ ADVISORY · N1234 Helicopter", refresh.title)
        // The refresh runs 19 ms after the post, in the same tick: the zone phrasing must stay.
        val postedAt = sec(2)
        assertEquals(BannerRefresh.Action.KEEP,
            BannerRefresh.decide(posted, entry.tier, postedAt + bannerMs, refresh, v.tier, postedAt + 19))
        // ... for the whole display window
        assertEquals(BannerRefresh.Action.KEEP,
            BannerRefresh.decide(posted, entry.tier, postedAt + bannerMs, refresh, v.tier, postedAt + bannerMs - 1))
        // then the tier title resumes
        assertEquals(BannerRefresh.Action.UPDATE,
            BannerRefresh.decide(posted, entry.tier, postedAt + bannerMs, refresh, v.tier, postedAt + bannerMs))
    }

    @Test fun countdownUnderTheSameTitleStillUpdatesInsideTheWindow() {
        val a = BannerText("⚠ WARNING · N1", "SW 1.9 mi", "Closest 2,700 ft in 43 s", "Clear: move NW")
        val b = a.copy(line3 = "Closest 2,700 ft in 42 s")
        assertEquals(BannerRefresh.Action.UPDATE, BannerRefresh.decide(a, Tier.WARNING, 10_000, b, Tier.WARNING, 1_000))
        assertEquals(BannerRefresh.Action.KEEP, BannerRefresh.decide(a, Tier.WARNING, 10_000, a, Tier.WARNING, 1_000))
    }

    @Test fun anyEventBannerHoldsItsTitle_stepDownStillWaits() {
        val passing = BannerText("● PASSING · N1", "SW 0.4 mi", "Diverging")
        val tierTitle = BannerText("⚠ WARNING · N1", "SW 0.4 mi", "Not closing")
        assertEquals(BannerRefresh.Action.KEEP, BannerRefresh.decide(passing, Tier.WARNING, 5_000, tierTitle, Tier.WARNING, 4_000))
        // a lower tier never re-titles, even after the window
        val esc = BannerText("⚠ WARNING · N1", "SW 1 mi", "x")
        val lower = BannerText("◆ CAUTION · N1", "SW 1 mi", "x")
        assertEquals(BannerRefresh.Action.KEEP, BannerRefresh.decide(esc, Tier.WARNING, 5_000, lower, Tier.CAUTION, 60_000))
    }
}
