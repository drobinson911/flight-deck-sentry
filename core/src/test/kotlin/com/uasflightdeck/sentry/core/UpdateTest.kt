package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {

    @Test fun parse() {
        assertEquals(SemVer(0, 3, 0), SemVer.parse("v0.3.0"))
        assertEquals(SemVer(0, 3, 0), SemVer.parse(" V0.3 "))
        assertEquals(SemVer(1, 0, 0), SemVer.parse("1"))
        assertEquals(SemVer(1, 2, 3, "beta.1"), SemVer.parse("1.2.3-beta.1+build.7"))
        for (bad in listOf(null, "", "latest", "v", "0.3.x", "release-0.3.0", "0..3")) assertNull(bad, SemVer.parse(bad))
    }

    /** latest tag, running version, is the tag newer? */
    private val newer = listOf(
        Triple("v0.3.0", "0.2.0", true),
        Triple("v0.3.0", "0.2.9", true),        // the updater's end-to-end test build
        Triple("v0.3.0", "0.3.0", false),
        Triple("v0.3.0", "0.3.1", false),       // a local build ahead of the release
        Triple("v0.10.0", "0.9.0", true),        // numeric, not lexical
        Triple("v0.3.1", "0.3.0", true),
        Triple("v1.0.0", "0.99.99", true),
        Triple("v0.3", "0.3.0", false),          // missing patch = 0
        Triple("v0.3.0", "0.3.0-rc.1", true),    // release beats its pre-release
        Triple("v0.3.0-rc.2", "0.3.0-rc.1", true),
        Triple("v0.3.0-rc.1", "0.3.0", false),
        Triple("v0.3.0-rc.10", "0.3.0-rc.9", true),
        Triple("v0.3.0-rc.1", "0.3.0-beta", true), // "rc" > "beta" (ASCII)
        Triple("garbage", "0.2.0", false),
        Triple("v0.3.0", "0.2.0-debug", true),
        Triple("v0.3.0", "not-a-version", false),
    )

    @Test fun isNewerTable() {
        for ((tag, cur, want) in newer) assertEquals("$tag vs $cur", want, SemVer.isNewer(tag, cur))
    }

    @Test fun parseRealReleaseShape() {
        val json = """{"url":"x","html_url":"https://github.com/drobinson911/flight-deck-sentry/releases/tag/v0.3.0",
            "tag_name":"v0.3.0","name":"Flight Deck Sentry 0.3.0","draft":false,"prerelease":false,
            "published_at":"2026-09-24T20:00:00Z",
            "assets":[{"name":"other.txt","size":3,"browser_download_url":"https://example.invalid/other.txt"},
                      {"name":"flight-deck-sentry.apk","size":6702840,"browser_download_url":"https://github.com/drobinson911/flight-deck-sentry/releases/download/v0.3.0/flight-deck-sentry.apk"}],
            "body":"  Pinned serial.\n\nSelf-update.  "}"""
        val r = Releases.parseLatest(json)!!
        assertEquals("v0.3.0", r.tag); assertEquals(SemVer(0, 3, 0), r.version)
        assertEquals("https://github.com/drobinson911/flight-deck-sentry/releases/download/v0.3.0/flight-deck-sentry.apk", r.apkUrl)
        assertEquals(6702840L, r.apkSize)
        assertEquals("Pinned serial.\n\nSelf-update.", r.notes)
    }

    @Test fun releaseWithoutTheApkAssetFallsBackToTheStableLink() {
        val r = Releases.parseLatest("""{"tag_name":"v0.3.1","assets":[],"body":null}""")
        assertNotNull(r)
        assertEquals(Releases.STABLE_APK_URL, r!!.apkUrl)
        assertEquals("https://github.com/drobinson911/flight-deck-sentry/releases/latest/download/flight-deck-sentry.apk", r.apkUrl)
        assertEquals("", r.notes); assertNull(r.apkSize)
    }

    @Test fun unusableReleaseJson() {
        // GitHub's rate-limit body, a draft, a non-semver tag, and junk
        assertNull(Releases.parseLatest("""{"message":"API rate limit exceeded for 1.2.3.4.","documentation_url":"https://docs.github.com"}"""))
        assertNull(Releases.parseLatest("""{"tag_name":"v9.9.9","draft":true}"""))
        assertNull(Releases.parseLatest("""{"tag_name":"nightly"}"""))
        assertNull(Releases.parseLatest("<html>"))
        assertNull(Releases.parseLatest("[]"))
    }

    @Test fun checkPolicy() {
        val h = 3600_000L; val now = 100 * 24 * h
        assertTrue("never checked", UpdatePolicy.shouldCheck(now, 0, 0))
        assertFalse("checked 23 h ago", UpdatePolicy.shouldCheck(now, now - 23 * h, now - 23 * h))
        assertTrue("checked 24 h ago", UpdatePolicy.shouldCheck(now, now - 24 * h, now - 24 * h))
        assertFalse("failed 10 min ago (offline / rate limited): wait", UpdatePolicy.shouldCheck(now, now - 30 * h, now - h / 6))
        assertTrue("failed an hour ago: retry", UpdatePolicy.shouldCheck(now, now - 30 * h, now - h))
        assertTrue("manual always goes", UpdatePolicy.shouldCheck(now, now - 1, now - 1, manual = true))
        assertTrue("clock went backwards", UpdatePolicy.shouldCheck(now, now + 5 * h, now + 5 * h))
    }

    @Test fun notifyOncePerVersion() {
        assertTrue(UpdatePolicy.shouldNotify("v0.3.1", "0.3.0", null))
        assertFalse(UpdatePolicy.shouldNotify("v0.3.1", "0.3.0", "0.3.1"))
        assertTrue(UpdatePolicy.shouldNotify("v0.3.2", "0.3.0", "0.3.1"))
        assertFalse("not newer", UpdatePolicy.shouldNotify("v0.3.0", "0.3.0", null))
    }
}
