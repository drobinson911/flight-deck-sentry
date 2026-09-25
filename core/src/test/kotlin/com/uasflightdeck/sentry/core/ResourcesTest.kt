package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.2 ResourceMonitor: /proc parsing and the averaging, with synthetic samples. */
class ResourcesTest {
    // A real line shape from Android 10 (fields 14/15 = utime 1234, stime 567).
    private val stat = "4242 (uasflightdeck.sentry) S 612 612 0 0 -1 1077952832 52311 0 12 0 1234 567 0 0 20 0 41 0 " +
        "9876543 1480028160 30120 18446744073709551615 1 1 0 0 0 0 4612 1 1073775864 0 0 0 17 5 0 0 0 0 0"

    @Test fun procStatUtimePlusStime() = assertEquals(1801L, ProcStat.cpuTicks(stat))

    @Test fun procStatCommWithSpacesAndParens() {
        val odd = "77 (my (odd) app ) R 1 1 0 0 -1 0 0 0 0 0 10 5 0 0 20 0 1 0 1 1 1"
        assertEquals(15L, ProcStat.cpuTicks(odd))
    }

    @Test fun procStatMalformedIsNull() {
        assertNull(ProcStat.cpuTicks(""))
        assertNull(ProcStat.cpuTicks("12 (x) S 1 2 3"))
        assertNull(ProcStat.cpuTicks("12 (x) S 1 1 0 0 -1 0 0 0 0 0 abc 5 0"))
    }

    private fun sample(sec: Int, ticks: Long?, pssMb: Int = 100, rx: Long? = null, tx: Long? = null, level: Int? = null,
                       cc: Long? = null, charging: Boolean? = false, mon: Long? = null) =
        ResourceSample(wallMs = 1_000_000L + sec * 1000L, cpuTicks = ticks, pssKb = pssMb * 1024L, heapUsedKb = 8 * 1024,
            heapMaxKb = 256 * 1024, batteryLevel = level, chargeCounterUah = cc, charging = charging, rxBytes = rx, txBytes = tx,
            wakeHeldMs = sec * 1000L, monitorCpuNs = mon)

    @Test fun cpuPercentOfOneCoreAndOfTheDevice() {
        val m = ResourceMeter(clkTck = 100, cores = 8)
        assertNull(m.add(sample(0, 1000)))
        val p = m.add(sample(10, 1020))!!                         // 20 ticks = 0.2 s CPU in 10 s = 2 % of one core
        assertEquals(2.0, p.cpuCorePct!!, 1e-9)
        val s = m.snapshot()!!
        assertEquals(2.0, s.cpuNow!!, 1e-9)
        assertEquals(0.25, s.device(s.cpuNow)!!, 1e-9)           // 8 cores
        assertEquals("CPU 0.25 % (2.0 % of one core) · 100 MB · batt — · —", ResourceText.row(s))
    }

    @Test fun windowsAreTimeWeightedAndFlightUsesTheCounters() {
        val m = ResourceMeter(clkTck = 100, cores = 4)
        // 6 minutes: first 5 minutes at 0.1 % of a core (1 tick = 10 ms per 10 s), last minute at 1 % (10 ticks per 10 s).
        var ticks = 0L
        m.add(sample(0, ticks))
        for (i in 1..36) { ticks += if (i <= 30) 1 else 10; m.add(sample(i * 10, ticks)) }
        val s = m.snapshot()!!
        assertEquals(1.0, s.cpu1m!!, 1e-9)                        // last 6 intervals
        assertEquals((24 * 1 + 6 * 10) / 100.0 / 300.0 * 100, s.cpu5m!!, 1e-9)  // last 30 intervals: 0.84 s in 300 s
        assertEquals(0.25, s.cpuFlight!!, 1e-9)                  // 90 ticks = 0.9 s in 360 s = 0.25 %
        assertEquals(1.0, s.cpuPeak!!, 1e-9)
        assertEquals(0.0625, s.device(s.cpuFlight)!!, 1e-9)       // 4 cores
        assertEquals(360_000L, s.armedMs)
    }

    @Test fun lateSampleDoesNotSkewTheAverage() {
        val m = ResourceMeter(clkTck = 100, cores = 1)
        m.add(sample(0, 0)); m.add(sample(10, 10)); m.add(sample(40, 40))  // 1 % throughout, one 30 s gap
        assertEquals(1.0, m.snapshot()!!.cpu1m!!, 1e-9)
    }

    @Test fun missingCpuCounterIsUnknownNotZero() {
        val m = ResourceMeter()
        m.add(sample(0, null)); m.add(sample(10, null))
        val s = m.snapshot()!!
        assertNull(s.cpuNow); assertNull(s.cpu1m); assertNull(s.cpuFlight)
    }

    @Test fun memoryPeakAndAverages() {
        val m = ResourceMeter()
        listOf(100, 110, 130, 120).forEachIndexed { i, mb -> m.add(sample(i * 10, i.toLong(), pssMb = mb)) }
        val s = m.snapshot()!!
        assertEquals(120.0, s.pssNowMb!!, 1e-9)
        assertEquals(130.0, s.pssPeakMb!!, 1e-9)
        assertEquals(120.0, s.pss1mMb!!, 1e-9)                    // intervals end at 10, 20, 30 s: 110, 130, 120
    }

    @Test fun networkPerMinuteAndTotals() {
        val m = ResourceMeter()
        m.add(sample(0, 0, rx = 1_000, tx = 500))
        m.add(sample(10, 1, rx = 3_000, tx = 700))                // 2,200 B in 10 s = 13,200 B/min
        m.add(sample(20, 2, rx = 3_000, tx = 700))                // idle
        val s = m.snapshot()!!
        assertEquals(6_600.0, s.net1mPerMin!!, 1e-9)
        assertEquals(13_200.0, s.netPeakPerMin!!, 1e-9)
        assertEquals(2_000L, s.rxTotal); assertEquals(200L, s.txTotal)
        assertEquals("6.4 KB/min", ResourceText.perMin(s.net1mPerMin))
    }

    @Test fun batteryDrainPerHourFromTheChargeCounter() {
        val m = ResourceMeter()
        // 4,000 mAh at 80 % = 50,000 µAh per percent; 20,000 µAh used in 30 min = 0.4 % in 0.5 h = -0.8 %/h.
        m.add(sample(0, 0, level = 80, cc = 4_000_000))
        m.add(sample(299, 1, level = 80, cc = 3_990_000))
        assertNull("too early for a rate", m.snapshot()!!.batteryPctPerHour)
        m.add(sample(1800, 2, level = 80, cc = 3_980_000))
        val s = m.snapshot()!!
        assertEquals(-0.8, s.batteryPctPerHour!!, 1e-9)
        assertEquals(0.4, s.batteryUsedPct!!, 1e-9)
        assertEquals("−0.8 %/h", ResourceText.battery(s))
    }

    @Test fun batteryWholePercentsNeed15Minutes() {
        val m = ResourceMeter()
        m.add(sample(0, 0, level = 90)); m.add(sample(600, 1, level = 89))
        assertNull(m.snapshot()!!.batteryPctPerHour)
        m.add(sample(1800, 2, level = 89))
        assertEquals(-2.0, m.snapshot()!!.batteryPctPerHour!!, 1e-9)
    }

    @Test fun chargingHasNoRateAndDrainResumesAfterUnplug() {
        val m = ResourceMeter()
        m.add(sample(0, 0, level = 50, charging = true))
        assertEquals("charging", ResourceText.battery(m.snapshot()!!))
        m.add(sample(10, 1, level = 60, charging = false))        // unplugged: the baseline starts here
        m.add(sample(1810, 2, level = 59, charging = false))
        val s = m.snapshot()!!
        assertEquals(-2.0, s.batteryPctPerHour!!, 1e-9)
        assertEquals(1.0, s.batteryUsedPct!!, 1e-9)
    }

    @Test fun monitorCostIsReported() {
        val m = ResourceMeter(sampleSec = 10.0)
        m.add(sample(0, 0)); m.add(sample(10, 1, mon = 2_000_000)); m.add(sample(20, 2, mon = 4_000_000))
        val s = m.snapshot()!!
        assertEquals(3.0, s.monitorMsPerSample!!, 1e-9)
        assertEquals(0.03, s.monitorCorePct!!, 1e-9)
    }

    @Test fun textsReadAsSpecified() {
        assertEquals("1.2 %", ResourceText.pct(1.23)); assertEquals("0.20 %", ResourceText.pct(0.2)); assertEquals("12 %", ResourceText.pct(12.4))
        assertEquals("12 KB/min", ResourceText.perMin(12.0 * 1024))
        assertEquals("1 h 2 min", ResourceText.clock(3_723_000)); assertEquals("5 min", ResourceText.clock(300_000))
        assertTrue(ResourceText.row(null).startsWith("measuring"))
        val m = ResourceMeter(cores = 8)
        var t = 0L
        for (i in 0..36) { m.add(sample(i * 10, t, rx = i * 2048L, tx = 0)); t += 20 }
        val s = m.snapshot()!!
        assertTrue(ResourceText.summary(s), ResourceText.summary(s).startsWith("RESOURCES armed 6 min · CPU 5-min 0.25 % (2.0 % of one core)"))
        assertTrue(ResourceText.flightSummary(s), ResourceText.flightSummary(s).contains("data 72 KB (down 72 KB · up 0 B)"))
        assertEquals(13, ResourceText.detail(s).size)
    }
}
