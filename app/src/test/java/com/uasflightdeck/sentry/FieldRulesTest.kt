package com.uasflightdeck.sentry

import com.uasflightdeck.sentry.FieldRules.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldRulesTest {
    private val ring = FieldRules.RING

    @Test fun parsesPlainDecimalsInRange() {
        assertEquals(Parsed.Ok(3.0), FieldRules.parseNumber("3", ring))
        assertEquals(Parsed.Ok(0.5), FieldRules.parseNumber(" 0.5 ", ring))
        assertEquals(Parsed.Ok(0.5), FieldRules.parseNumber(".5", ring))
        assertEquals(Parsed.Ok(2.0), FieldRules.parseNumber("2.", ring))
        assertEquals(Parsed.Ok(50.0), FieldRules.parseNumber("50", ring))      // bounds inclusive
        assertEquals(Parsed.Ok(0.1), FieldRules.parseNumber("0.1", ring))
    }

    @Test fun rejectsGarbageAndOutOfRangeWithTheRangeAsHelperText() {
        for (t in listOf("", " ", "abc", "1..2", "1,5", "1e3", "NaN", "Infinity", "1d", "0x10", "-", ".", "3 nm")) {
            assertTrue("'$t' must be invalid", FieldRules.parseNumber(t, ring) is Parsed.Bad)
        }
        assertEquals(Parsed.Bad("0.1–50 nm"), FieldRules.parseNumber("51", ring))
        assertEquals(Parsed.Bad("0.1–50 nm"), FieldRules.parseNumber("0.05", ring))
        assertEquals(Parsed.Bad("0.1–50 nm"), FieldRules.parseNumber("-1", ring))
        assertTrue((FieldRules.parseNumber("x", ring) as Parsed.Bad).why.endsWith("0.1–50 nm"))
    }

    @Test fun signedRangesAcceptNegatives() {
        assertEquals(Parsed.Ok(-120.5), FieldRules.parseNumber("-120.5", FieldRules.CIRCLE_LON))
        assertEquals(Parsed.Ok(-200.0), FieldRules.parseNumber("-200", FieldRules.BARO_CORRECTION))
        assertTrue(FieldRules.BARO_CORRECTION.signed)
        assertFalse(FieldRules.RING.signed)
        assertTrue(FieldRules.parseNumber("-181", FieldRules.CIRCLE_LON) is Parsed.Bad)
    }

    @Test fun blankIsValidOnlyWhereItMeansSomething() {
        val e = FieldRules.parseNumber("", FieldRules.CONTROLLER_ELEV)
        assertTrue(e is Parsed.Ok && (e as Parsed.Ok).value.isNaN())   // blank = use GPS
        assertTrue(FieldRules.parseNumber("", FieldRules.TRAFFIC_RADIUS) is Parsed.Bad)
    }

    @Test fun lastValidValueIsKeptWhileTheTextIsInvalid() {
        // The pilot types "4" over "3": stored 4. Then "4x" / "" / "99": stored stays 4. Then "5": stored 5.
        var stored = 3.0
        stored = FieldRules.keepLastValid("4", ring, stored); assertEquals(4.0, stored, 0.0)
        for (t in listOf("4x", "", "99", "abc")) { stored = FieldRules.keepLastValid(t, ring, stored); assertEquals(4.0, stored, 0.0) }
        stored = FieldRules.keepLastValid("5", ring, stored); assertEquals(5.0, stored, 0.0)
        // a blank-allowed field stores the blank meaning, not the last value
        assertTrue(FieldRules.keepLastValid("", FieldRules.CONTROLLER_ELEV, 5100.0).isNaN())
        assertEquals(5100.0, FieldRules.keepLastValid("51OO", FieldRules.CONTROLLER_ELEV, 5100.0), 0.0)
    }

    @Test fun ringsAreStoredOnlyAsAnOrderedTrio() {
        val stored = Triple(3.0, 1.0, 0.5)
        assertEquals(Triple(4.0, 1.0, 0.5), FieldRules.rings("4", "1", "0.5", ring, stored))
        assertNull(FieldRules.rings("0.8", "1", "0.5", ring, stored))                // advisory < caution: keep stored
        assertEquals(Triple(3.0, 2.0, 0.5), FieldRules.rings("3", "2", "junk", ring, stored))   // invalid field keeps its stored value
        assertNull(FieldRules.rings("3", "0.4", "junk", ring, stored))               // caution below the stored warning
        assertTrue(FieldRules.ringsOrdered(1.0, 1.0, 1.0))
        assertFalse(FieldRules.ringsOrdered(1.0, 1.0, 0.0))
    }

    @Test fun serialIsTrimmedAndUpperCased() {
        assertEquals("1581F7K3C251F00C9B34", FieldRules.normaliseSerial("  1581f7k3c251f00c9b34 \n"))
        assertEquals("", FieldRules.normaliseSerial("   "))
    }

    @Test fun tokenIsTrimmed() {
        assertEquals("abc123", FieldRules.normaliseToken(" abc123\n"))
        assertEquals("", FieldRules.normaliseToken("\t "))
    }

    @Test fun workerBaseNeedsSchemeAndHost() {
        assertEquals("https://example.workers.dev", FieldRules.workerBase(" https://example.workers.dev/ "))
        assertEquals("http://10.0.2.2:18081", FieldRules.workerBase("http://10.0.2.2:18081"))
        assertNull(FieldRules.workerBase("example.workers.dev"))
        assertNull(FieldRules.workerBase("https://"))
        assertNull(FieldRules.workerBase(""))
        assertNull(FieldRules.workerBase("https://exa mple.dev"))
    }

    @Test fun rangeTextFormatsCleanly() {
        assertEquals("0.1–50 nm", FieldRules.RING.rangeText)
        assertEquals("500–60000 ft", FieldRules.TARGETS_CEILING.rangeText)
        assertEquals("3", FieldRules.fmt(3.0)); assertEquals("0.25", FieldRules.fmt(0.25))
    }

    @Test fun everyStoredDefaultIsValidInItsOwnRange() {
        // A fresh install must not open Settings with red fields.
        val defaults = listOf(3.0 to ring, 1.0 to ring, 0.5 to ring, 2000.0 to FieldRules.CEILING_ABOVE, 300.0 to FieldRules.BARO_CORRECTION,
            60.0 to FieldRules.CPA_HORIZON, 10.0 to FieldRules.TFR_RELEVANCE, 10.0 to FieldRules.TARGETS_AIRCRAFT,
            15.0 to FieldRules.TARGETS_CONTROLLER, 18_000.0 to FieldRules.TARGETS_CEILING, 30.0 to FieldRules.TRAFFIC_RADIUS,
            2.0 to FieldRules.CIRCLE_RADIUS)
        for ((v, spec) in defaults) assertTrue("$v in ${spec.rangeText}", FieldRules.parseNumber(FieldRules.fmt(v), spec) is Parsed.Ok)
    }
}
