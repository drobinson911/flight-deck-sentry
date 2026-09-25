package com.uasflightdeck.sentry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.uasflightdeck.sentry.core.ProcStat
import com.uasflightdeck.sentry.core.ResourceMeter
import com.uasflightdeck.sentry.core.ResourceSample
import com.uasflightdeck.sentry.core.ResourceSnapshot
import com.uasflightdeck.sentry.core.ResourceText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 0.4.2: what Sentry itself costs the controller while armed, sampled every 10 s from ARM to DISARM: process CPU
 * (`/proc/self/stat` utime + stime vs wall clock, as % of one core and of all cores), memory (PSS + Java heap), the
 * battery's drain per hour, our uid's network bytes and the wake lock's held time. A summary is logged every 5 min
 * and a flight summary at DISARM. The pure maths is [ResourceMeter] / [ResourceText] (unit-tested).
 *
 * Kept cheap: one small file read, a few binder-free counters, one BatteryManager call and Debug.getPss() per sample;
 * the monitor measures its own thread CPU per sample and shows it ("This monitor" in Settings → Resources).
 */
class ResourceMonitor(private val ctx: Context, private val scope: CoroutineScope, private val wakeHeldTotalMs: () -> Long) {

    data class State(val on: Boolean = false, val running: Boolean = false, val snapshot: ResourceSnapshot? = null)

    companion object {
        const val SAMPLE_MS = 10_000L
        const val LOG_EVERY_MS = 5 * 60_000L
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state
        fun setOn(on: Boolean) { _state.value = _state.value.copy(on = on) }
    }

    private val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).takeIf { it > 0 } ?: 100L
    private val cores = runCatching { Os.sysconf(OsConstants._SC_NPROCESSORS_CONF).toInt() }.getOrDefault(0)
        .takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
    private val bm = ctx.getSystemService(BatteryManager::class.java)
    private val uid = Process.myUid()
    private val statFile = File("/proc/self/stat")
    private var meter = ResourceMeter(clkTck, cores, SAMPLE_MS / 1000.0)
    private var job: Job? = null
    private var wakeAtArm = 0L
    private var lastLogMs = 0L
    private var lastCostNs: Long? = null

    val running: Boolean get() = job?.isActive == true

    @Synchronized
    fun start() {
        if (running) return
        meter = ResourceMeter(clkTck, cores, SAMPLE_MS / 1000.0)
        wakeAtArm = wakeHeldTotalMs()
        lastCostNs = null
        lastLogMs = System.currentTimeMillis()
        SentryBus.log("Resources: measuring every ${SAMPLE_MS / 1000} s ($cores cores, CLK_TCK $clkTck)")
        job = scope.launch {
            while (isActive) {
                val t0 = System.currentTimeMillis()
                sampleOnce()
                if (t0 - lastLogMs >= LOG_EVERY_MS) {
                    lastLogMs = t0
                    meter.snapshot()?.let { SentryBus.log(ResourceText.summary(it)) }
                }
                delay(maxOf(1_000L, SAMPLE_MS - (System.currentTimeMillis() - t0)))
            }
        }
        _state.value = _state.value.copy(running = true)
    }

    /** Stop sampling; with [summary], take a last sample and log the flight summary (DISARM). */
    @Synchronized
    fun stop(summary: Boolean) {
        val wasRunning = running
        job?.cancel(); job = null
        if (wasRunning && summary) {
            runCatching { sampleOnce() }
            meter.snapshot()?.let { SentryBus.log(ResourceText.flightSummary(it)) }
        }
        _state.value = _state.value.copy(running = false)
    }

    private fun sampleOnce() {
        val c0 = Debug.threadCpuTimeNanos()
        val rt = Runtime.getRuntime()
        val charging = runCatching { bm?.isCharging }.getOrNull()
        val level = runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()?.takeIf { it in 0..100 }
        val cc = runCatching { bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrNull()
            ?.takeIf { it > 0 && it != Long.MIN_VALUE && it != Int.MIN_VALUE.toLong() }
        val rx = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 }
        val tx = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 }
        val s = ResourceSample(
            wallMs = System.currentTimeMillis(),
            cpuTicks = runCatching { ProcStat.cpuTicks(statFile.readText()) }.getOrNull(),
            pssKb = runCatching { Debug.getPss() }.getOrNull()?.takeIf { it > 0 },
            heapUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024, heapMaxKb = rt.maxMemory() / 1024,
            batteryLevel = level, chargeCounterUah = cc, charging = charging,
            rxBytes = rx, txBytes = tx,
            wakeHeldMs = wakeHeldTotalMs() - wakeAtArm,
            monitorCpuNs = lastCostNs,
        )
        meter.add(s)
        _state.value = _state.value.copy(snapshot = meter.snapshot())
        lastCostNs = (Debug.threadCpuTimeNanos() - c0).takeIf { it >= 0 }
    }
}
