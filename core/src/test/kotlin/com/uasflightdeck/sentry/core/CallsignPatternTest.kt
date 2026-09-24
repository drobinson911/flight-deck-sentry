package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsignPatternTest {
    /** pattern, callsign, expected */
    private val table = listOf(
        Triple("DEMO-# Pilot", "DEMO-1 Pilot", true),
        Triple("DEMO-# Pilot", "DEMO-1 Pilot", true),
        Triple("DEMO-# Pilot", "demo1pilot", true),
        Triple("DEMO-# Pilot", "DEMO 1  PILOT", true),
        Triple("DEMO##Pilot", "DEMO-1 Pilot", true),
        Triple("DEMO-## Pilot", "DEMO-1 Pilot", true),
        Triple("DEMO-# Pilot", "DEMO3 Pilot", false),          // # is exactly one digit
        Triple("DEMO-# Pilot", "DEMO-11 Pilot", false),
        Triple("DEMO-# Pilot", "URA1 Pilot", false),
        Triple("DEMO-# Pilot", "DEMO-1 Pilot Jr", false),      // whole callsign must match
        Triple("DEMO-# Pilot", "DEMO-1 Smith", false),
        Triple("DEMO-1", "DEMO-1", true),
        Triple("DEMO-1", "DEMO-1 Pilot", false),
        Triple("DEMO*", "DEMO-2", true),
        Triple("DEMO*", "DEMO", true),                               // * may be empty
        Triple("*Pilot", "DEMO-1 Pilot", true),
        Triple("*Pilot", "DEMO-1 Pilots", false),
        Triple("*31*", "DEMO-1 Pilot", true),
        Triple("U*#", "DEMO-2", true),
        Triple("a.b", "axb", false),                             // literal dot, not regex
        Triple("a.b", "a.b", true),
        Triple("(DEMO)", "(DEMO)", true),                            // regex metacharacters are literal
        Triple("re:^DEMO3[0-9]\\b", "DEMO-1 Pilot", true),
        Triple("re:^DEMO3[0-9]\\b", "DEMO-1 Pilot", false),      // regex sees the RAW callsign
        Triple("re:pilot$", "DEMO-1 PILOT", true),           // case-insensitive
        Triple("re:smith", "DEMO-1 Pilot", false),
        Triple("RE:demo\\d+", "DEMO-1", true),
    )

    @Test fun table() {
        for ((p, cs, want) in table) assertEquals("\"$p\" vs \"$cs\"", want, CallsignPattern.compile(p).matches(cs))
    }

    @Test fun blankMatchesNothingAndIsNotInvalid() {
        val p = CallsignPattern.compile("   ")
        assertTrue(p.isBlank); assertFalse(p.invalid); assertFalse(p.matches("DEMO-1"))
    }

    @Test fun badRegexIsInvalidAndMatchesNothing() {
        val p = CallsignPattern.compile("re:DEMO[")
        assertTrue(p.invalid); assertFalse(p.matches("DEMO["))
    }

    @Test fun nullCallsignNeverMatches() = assertFalse(CallsignPattern.compile("*").matches(null))

    @Test fun serialList() {
        val s = SerialList.parse("1581F5FJ, abc123\n  1581F5FK;x9")
        assertEquals(setOf("1581F5FJ", "ABC123", "1581F5FK", "X9"), s)
        assertTrue(SerialList.contains(s, " abc123 "))
        assertFalse(SerialList.contains(s, null))
        assertFalse(SerialList.contains(s, "ABC12"))
    }
}
