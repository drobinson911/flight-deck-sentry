package com.uasflightdeck.sentry.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * 0.4.2: how much of the controller Sentry itself uses while armed (owner: "Is there any way to measure the compute
 * power or how much resources the app is using from the controller"). The app samples every 10 s ([ResourceSample]);
 * everything here is pure so the parsing and the averaging are unit-tested with synthetic samples.
 */
object ProcStat {
    /**
     * utime + stime (clock ticks) from one line of `/proc/<pid>/stat`. The command name (field 2) is in parentheses
     * and may itself contain spaces and ')', so the fields are counted from the LAST ')': after it come state
     * (field 3) ... utime (14) and stime (15). Null when the line is malformed.
     */
    fun cpuTicks(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val f = stat.substring(close + 1).trim().split(Regex("\\s+"))
        if (f.size < 13) return null
        val u = f[11].toLongOrNull() ?: return null
        val s = f[12].toLongOrNull() ?: return null
        return if (u < 0 || s < 0) null else u + s
    }
}

/** One 10 s sample. Every field may be unavailable (null) on some controller; nothing is invented to fill it. */
data class ResourceSample(
    val wallMs: Long,
    /** Process utime + stime, clock ticks ([ProcStat.cpuTicks]). */
    val cpuTicks: Long? = null,
    /** Proportional set size of this process (kB). */
    val pssKb: Long? = null,
    val heapUsedKb: Long? = null,
    val heapMaxKb: Long? = null,
    /** Battery level 0-100 (BatteryManager.BATTERY_PROPERTY_CAPACITY). */
    val batteryLevel: Int? = null,
    /** BATTERY_PROPERTY_CHARGE_COUNTER (µAh): finer than the whole-percent level where the controller reports it. */
    val chargeCounterUah: Long? = null,
    val charging: Boolean? = null,
    /** TrafficStats bytes for our uid since boot. */
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    /** Total time our wake lock has been held since ARM (ms). */
    val wakeHeldMs: Long? = null,
    /** CPU the monitor itself spent taking the PREVIOUS sample (thread CPU time, ns). */
    val monitorCpuNs: Long? = null,
)

/** Rates over one sample interval. */
data class ResourcePoint(
    val wallMs: Long,
    val dtSec: Double,
    /** CPU seconds used in the interval (null = unavailable). */
    val cpuSec: Double?,
    val pssMb: Double?,
    val netBytes: Long?,
) {
    /** % of ONE core over the interval (100 = one core flat out). */
    val cpuCorePct: Double? get() = cpuSec?.let { it / dtSec * 100.0 }
    val netBytesPerMin: Double? get() = netBytes?.let { it / dtSec * 60.0 }
}

/** Everything the Resources row, the Settings detail and the logs show. CPU figures are % of ONE core. */
data class ResourceSnapshot(
    val cores: Int,
    val armedAtMs: Long,
    val nowMs: Long,
    val samples: Int,
    val cpuNow: Double?,
    val cpu1m: Double?,
    val cpu5m: Double?,
    val cpuFlight: Double?,
    val cpuPeak: Double?,
    val pssNowMb: Double?,
    val pss1mMb: Double?,
    val pss5mMb: Double?,
    val pssPeakMb: Double?,
    val heapUsedMb: Double?,
    val heapMaxMb: Double?,
    val net1mPerMin: Double?,
    val net5mPerMin: Double?,
    val netFlightPerMin: Double?,
    val netPeakPerMin: Double?,
    val rxTotal: Long?,
    val txTotal: Long?,
    /** Battery change per hour since ARM (negative = drain); null until enough time or while charging. */
    val batteryPctPerHour: Double?,
    /** Battery percent used since ARM (or since unplugged), fractional when the charge counter is available. */
    val batteryUsedPct: Double?,
    val batteryLevel: Int?,
    val charging: Boolean?,
    val wakeHeldMs: Long?,
    /** Mean CPU per sample the monitor itself costs (ms). */
    val monitorMsPerSample: Double?,
    val sampleSec: Double,
) {
    val armedMs: Long get() = nowMs - armedAtMs
    fun device(pctOfOneCore: Double?): Double? = pctOfOneCore?.let { it / max(1, cores) }
    val netTotal: Long? get() = if (rxTotal != null && txTotal != null) rxTotal + txTotal else null
    /** The monitor's own share of one core. */
    val monitorCorePct: Double? get() = monitorMsPerSample?.let { it / (sampleSec * 1000.0) * 100.0 }
}

/**
 * Accumulates [ResourceSample]s from ARM to DISARM. Window averages are time-weighted (sum of CPU seconds over sum of
 * wall seconds), so a late or early sample doesn't skew them. "Flight" = since ARM, straight from the first and last
 * counters. Peaks are the highest single 10 s interval.
 */
class ResourceMeter(
    val clkTck: Long = 100,
    val cores: Int = 1,
    val sampleSec: Double = 10.0,
    /** Battery rate needs this long with the fine charge counter, [minBatteryCoarseSec] with whole percents only. */
    val minBatterySec: Double = 300.0,
    val minBatteryCoarseSec: Double = 900.0,
) {
    private var first: ResourceSample? = null
    private var last: ResourceSample? = null
    private val points = ArrayDeque<ResourcePoint>()
    private var peakCpu: Double? = null
    private var peakPss: Double? = null
    private var peakNet: Double? = null
    private var batBase: ResourceSample? = null
    private var batUsedBefore = 0.0          // drain in earlier discharge stretches (charging in between)
    private var monitorNsSum = 0L
    private var monitorN = 0
    var samples = 0; private set

    val armedAtMs: Long? get() = first?.wallMs

    fun reset() {
        first = null; last = null; points.clear(); peakCpu = null; peakPss = null; peakNet = null
        batBase = null; batUsedBefore = 0.0; monitorNsSum = 0; monitorN = 0; samples = 0
    }

    /** Adds a sample; returns the interval since the previous one (null for the first, or a clock that went back). */
    fun add(s: ResourceSample): ResourcePoint? {
        samples++
        s.monitorCpuNs?.let { monitorNsSum += it; monitorN++ }
        s.pssKb?.let { peakPss = max(peakPss ?: 0.0, it / 1024.0) }
        trackBattery(s)
        val prev = last
        if (first == null) first = s
        last = s
        if (prev == null) return null
        val dt = (s.wallMs - prev.wallMs) / 1000.0
        if (dt <= 0.0) return null
        val cpu = delta(prev.cpuTicks, s.cpuTicks)?.let { it.toDouble() / clkTck }
        val net = sum(delta(prev.rxBytes, s.rxBytes), delta(prev.txBytes, s.txBytes))
        val p = ResourcePoint(s.wallMs, dt, cpu, s.pssKb?.let { it / 1024.0 }, net)
        points.addLast(p)
        while (points.isNotEmpty() && s.wallMs - points.first().wallMs >= 300_000L + (sampleSec * 500).toLong()) points.removeFirst()
        p.cpuCorePct?.let { peakCpu = max(peakCpu ?: 0.0, it) }
        p.netBytesPerMin?.let { peakNet = max(peakNet ?: 0.0, it) }
        return p
    }

    private fun trackBattery(s: ResourceSample) {
        val base = batBase
        when {
            s.charging == true -> { if (base != null) batUsedBefore += drainPct(base, last ?: base) ?: 0.0; batBase = null }
            base == null -> batBase = s.takeIf { it.batteryLevel != null }
        }
    }

    /** Percent drained from [a] to [b]: the charge counter when both have it, else whole percents. */
    private fun drainPct(a: ResourceSample, b: ResourceSample): Double? {
        val ca = a.chargeCounterUah; val cb = b.chargeCounterUah; val la = a.batteryLevel; val lb = b.batteryLevel
        if (ca != null && cb != null && la != null && la > 0 && ca > 0) return (ca - cb) / (ca.toDouble() / la)
        if (la != null && lb != null) return (la - lb).toDouble()
        return null
    }

    fun snapshot(): ResourceSnapshot? {
        val f = first ?: return null
        val l = last ?: return null
        fun window(sec: Int): List<ResourcePoint> = points.filter { l.wallMs - it.wallMs < sec * 1000L }
        fun cpuAvg(ps: List<ResourcePoint>): Double? {
            val c = ps.filter { it.cpuSec != null }
            val t = c.sumOf { it.dtSec }
            return if (c.isEmpty() || t <= 0) null else c.sumOf { it.cpuSec!! } / t * 100.0
        }
        fun pssAvg(ps: List<ResourcePoint>): Double? = ps.mapNotNull { it.pssMb }.takeIf { it.isNotEmpty() }?.average()
        fun netAvg(ps: List<ResourcePoint>): Double? {
            val c = ps.filter { it.netBytes != null }
            val t = c.sumOf { it.dtSec }
            return if (c.isEmpty() || t <= 0) null else c.sumOf { it.netBytes!! } / t * 60.0
        }
        val w1 = window(60); val w5 = window(300)
        val flightSec = (l.wallMs - f.wallMs) / 1000.0
        val cpuFlight = delta(f.cpuTicks, l.cpuTicks)?.takeIf { flightSec > 0 }?.let { it.toDouble() / clkTck / flightSec * 100.0 }
        val rx = delta(f.rxBytes, l.rxBytes); val tx = delta(f.txBytes, l.txBytes)
        val netFlight = sum(rx, tx)?.takeIf { flightSec > 0 }?.let { it / flightSec * 60.0 }

        // Battery: drain since ARM over the time spent discharging.
        val base = batBase
        val stretch = if (base != null) drainPct(base, l) else null
        val used = if (stretch == null && batUsedBefore == 0.0) null else batUsedBefore + (stretch ?: 0.0)
        val rate = if (base != null && l.charging != true) {
            val sec = (l.wallMs - base.wallMs) / 1000.0
            val fine = base.chargeCounterUah != null && l.chargeCounterUah != null
            if (stretch != null && sec >= (if (fine) minBatterySec else minBatteryCoarseSec)) -stretch / (sec / 3600.0) else null
        } else null

        return ResourceSnapshot(
            cores = cores, armedAtMs = f.wallMs, nowMs = l.wallMs, samples = samples,
            cpuNow = points.lastOrNull()?.cpuCorePct, cpu1m = cpuAvg(w1), cpu5m = cpuAvg(w5), cpuFlight = cpuFlight, cpuPeak = peakCpu,
            pssNowMb = l.pssKb?.let { it / 1024.0 }, pss1mMb = pssAvg(w1), pss5mMb = pssAvg(w5), pssPeakMb = peakPss,
            heapUsedMb = l.heapUsedKb?.let { it / 1024.0 }, heapMaxMb = l.heapMaxKb?.let { it / 1024.0 },
            net1mPerMin = netAvg(w1), net5mPerMin = netAvg(w5), netFlightPerMin = netFlight, netPeakPerMin = peakNet,
            rxTotal = rx, txTotal = tx,
            batteryPctPerHour = rate, batteryUsedPct = used, batteryLevel = l.batteryLevel, charging = l.charging,
            wakeHeldMs = l.wakeHeldMs,
            monitorMsPerSample = if (monitorN == 0) null else monitorNsSum / 1e6 / monitorN,
            sampleSec = sampleSec,
        )
    }

    private fun delta(a: Long?, b: Long?): Long? = if (a == null || b == null || b < a) null else b - a
    private fun sum(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
}

/** The words for [ResourceSnapshot]: main-screen row, Settings detail, the 5-min log line and the flight summary. */
object ResourceText {
    private fun f(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)

    /** "1.2 %": two significant decimals under 1 %. */
    fun pct(v: Double?): String = when {
        v == null -> "—"
        abs(v) < 1 -> f(v, 2) + " %"
        abs(v) < 10 -> f(v, 1) + " %"
        else -> f(v, 0) + " %"
    }

    fun mb(v: Double?): String = v?.let { f(it, 0) + " MB" } ?: "—"

    fun bytes(b: Double?): String = when {
        b == null -> "—"
        b < 1024 -> f(b, 0) + " B"
        b < 1024 * 1024 -> f(b / 1024, if (b < 10 * 1024) 1 else 0) + " KB"
        else -> f(b / 1024 / 1024, 1) + " MB"
    }

    fun perMin(b: Double?): String = if (b == null) "—" else bytes(b) + "/min"

    /** "−0.8 %/h" (drain), "+0.3 %/h", "charging", or "batt —" before there is enough time. */
    fun battery(s: ResourceSnapshot): String = when {
        s.charging == true -> "charging"
        s.batteryPctPerHour == null -> "batt —"
        else -> signed(s.batteryPctPerHour) + " %/h"
    }

    private fun signed(v: Double): String {
        val r = f(abs(v), if (abs(v) < 10) 1 else 0)
        return if (r.trimEnd('0', '.').isEmpty()) "0.0" else (if (v < 0) "−" else "+") + r
    }

    fun clock(ms: Long): String = Banner.duration(ms / 1000.0)

    /** CPU as "% of the controller (all cores)" with "% of one core" alongside. */
    fun cpu(s: ResourceSnapshot, oneCore: Double?): String =
        if (oneCore == null) "—" else "${pct(s.device(oneCore))} (${pct(oneCore)} of one core)"

    /** Main screen: "CPU 0.20 % (1.6 % of one core) · 118 MB · −0.8 %/h · 12 KB/min" (last-minute averages). */
    fun row(s: ResourceSnapshot?): String {
        if (s == null || s.samples < 2) return "measuring… (every 10 s while armed)"
        val cpu = s.cpu1m ?: s.cpuNow
        return "CPU ${cpu(s, cpu)} · ${mb(s.pssNowMb)} · ${battery(s)} · ${perMin(s.net1mPerMin ?: s.netFlightPerMin)}"
    }

    /** Settings → Resources: one line per figure. */
    fun detail(s: ResourceSnapshot?): List<Pair<String, String>> {
        if (s == null) return listOf("Resources" to "Not armed: measured every 10 s while armed, from ARM to DISARM.")
        val out = ArrayList<Pair<String, String>>()
        out += "Armed" to "${clock(s.armedMs)} · ${s.samples} samples · ${s.cores} cores"
        out += "CPU now (10 s)" to cpu(s, s.cpuNow)
        out += "CPU 1-min avg" to cpu(s, s.cpu1m)
        out += "CPU 5-min avg" to cpu(s, s.cpu5m)
        out += "CPU flight avg" to cpu(s, s.cpuFlight)
        out += "CPU peak (10 s)" to cpu(s, s.cpuPeak)
        out += "Memory (PSS)" to "now ${mb(s.pssNowMb)} · 1-min ${mb(s.pss1mMb)} · 5-min ${mb(s.pss5mMb)} · peak ${mb(s.pssPeakMb)}"
        out += "Java heap" to "${mb(s.heapUsedMb)} used of ${mb(s.heapMaxMb)}"
        out += "Battery" to when {
            s.charging == true -> "charging (no drain rate while plugged in)" + (s.batteryLevel?.let { " · $it %" } ?: "")
            else -> "${battery(s)} · used ${s.batteryUsedPct?.let { f(it, 1) + " %" } ?: "—"} since ARM" + (s.batteryLevel?.let { " · now $it %" } ?: "")
        }
        out += "Network" to "1-min ${perMin(s.net1mPerMin)} · 5-min ${perMin(s.net5mPerMin)} · flight ${perMin(s.netFlightPerMin)} · peak ${perMin(s.netPeakPerMin)}"
        out += "Data since ARM" to "${bytes(s.netTotal?.toDouble())} (down ${bytes(s.rxTotal?.toDouble())} · up ${bytes(s.txTotal?.toDouble())})"
        out += "Wake lock" to (s.wakeHeldMs?.let { w -> "held ${clock(w)}" + (if (s.armedMs > 0) " (${f(100.0 * w / s.armedMs, 0)} % of armed time)" else "") } ?: "—")
        out += "This monitor" to (s.monitorMsPerSample?.let { "${f(it, 2)} ms CPU per sample ≈ ${pct(s.monitorCorePct)} of one core" } ?: "—")
        return out
    }

    /** The log line every 5 min. */
    fun summary(s: ResourceSnapshot): String =
        "RESOURCES armed ${clock(s.armedMs)} · CPU 5-min ${cpu(s, s.cpu5m)} · peak ${cpu(s, s.cpuPeak)} · PSS ${mb(s.pssNowMb)} " +
            "(peak ${mb(s.pssPeakMb)}) · heap ${mb(s.heapUsedMb)} · ${battery(s)} · net 5-min ${perMin(s.net5mPerMin)} · " +
            "monitor ${s.monitorMsPerSample?.let { f(it, 2) + " ms/sample" } ?: "—"}"

    /** The log line at DISARM. */
    fun flightSummary(s: ResourceSnapshot): String =
        "FLIGHT SUMMARY armed ${clock(s.armedMs)} · CPU avg ${cpu(s, s.cpuFlight)} · peak ${cpu(s, s.cpuPeak)} · " +
            "PSS peak ${mb(s.pssPeakMb)} · battery used ${s.batteryUsedPct?.let { f(it, 1) + " %" } ?: "—"} (${battery(s)}) · " +
            "data ${bytes(s.netTotal?.toDouble())} (down ${bytes(s.rxTotal?.toDouble())} · up ${bytes(s.txTotal?.toDouble())}) · " +
            "wake lock ${s.wakeHeldMs?.let { clock(it) } ?: "—"}"
}
