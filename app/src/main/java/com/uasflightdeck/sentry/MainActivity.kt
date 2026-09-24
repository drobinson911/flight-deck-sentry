package com.uasflightdeck.sentry

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.uasflightdeck.sentry.core.Geo
import com.uasflightdeck.sentry.core.HealthMonitor
import com.uasflightdeck.sentry.core.OwnshipSource
import com.uasflightdeck.sentry.core.Phrasing
import com.uasflightdeck.sentry.core.Severity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Glanceable status for an outdoor tablet: big type, high contrast, no map.
 * Everything is rendered from [SentryBus.state]; a 1 s ticker re-renders so
 * that if the service stops ticking the banner turns red instead of freezing
 * on the last good state.
 */
class MainActivity : AppCompatActivity() {
    private companion object {
        val SRC_ABBR = mapOf("station" to "stn", "cloud" to "cld", "airsense" to "air", "replay" to "rpl")
    }
    private lateinit var settings: Settings
    private lateinit var banner: TextView
    private lateinit var drone: TextView
    private lateinit var droneDetail: TextView
    private lateinit var sources: TextView
    private lateinit var targets: TextView
    private lateinit var targetsLabel: TextView
    private lateinit var callouts: TextView
    private lateinit var logView: TextView
    private lateinit var zones: TextView
    private lateinit var radar: RadarView
    private lateinit var btnArm: MaterialButton
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        banner = findViewById(R.id.banner); drone = findViewById(R.id.drone); droneDetail = findViewById(R.id.droneDetail)
        sources = findViewById(R.id.sources); targets = findViewById(R.id.targets); targetsLabel = findViewById(R.id.targetsLabel)
        callouts = findViewById(R.id.callouts); logView = findViewById(R.id.log); zones = findViewById(R.id.zones)
        radar = findViewById(R.id.radar); btnArm = findViewById(R.id.btnArm); progress = findViewById(R.id.replayProgress)

        btnArm.setOnClickListener {
            val st = SentryBus.state.value
            if (settings.armed && st.mode != Mode.OFF) SentryService.send(this, SentryService.ACTION_DISARM)
            else { askPermissionsOnce(); SentryService.send(this, SentryService.ACTION_ARM); maybeAskBatteryExemption() }
        }
        findViewById<View>(R.id.btnTest).setOnClickListener { SentryService.send(this, SentryService.ACTION_TEST) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { SentryBus.state.collect { render() } }
                launch { SentryBus.calloutsFlow.collect { renderCallouts() } }
                launch { SentryBus.logFlow.collect { logView.text = it.take(4).joinToString("\n") } }
                launch { while (true) { delay(1000); render() } }
            }
        }
        // Sticky state: if Sentry was armed but the service isn't running (e.g. app updated), re-arm.
        if (settings.armed && SentryBus.state.value.mode == Mode.OFF) SentryService.send(this, SentryService.ACTION_ARM)
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * DEBUG builds only, for scripted demos over adb:
     *   adb shell am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action replay --ef speed 1
     * Only replay / test are accepted — never disarm — and release builds ignore it.
     */
    private fun handleDebugIntent(i: Intent?) {
        if (!BuildConfig.DEBUG || i == null) return
        when (i.getStringExtra("sentry_action")) {
            "replay" -> SentryService.send(this, SentryService.ACTION_REPLAY) {
                it.putExtra(SentryService.EXTRA_SPEED, i.getFloatExtra("speed", 1f).toDouble())
                it.putExtra(SentryService.EXTRA_CLOUD_VIEW, i.getBooleanExtra("cloud_view", false))
            }
            "test" -> SentryService.send(this, SentryService.ACTION_TEST)
            // manual-position fallback for demos: --ef lat .. --ef lon .. --ef alt ..  (no lat = turn it off)
            "pin" -> if (i.hasExtra("lat")) {
                settings.manualLat = i.getFloatExtra("lat", 0f).toDouble(); settings.manualLon = i.getFloatExtra("lon", 0f).toDouble()
                settings.manualAltMslFt = i.getFloatExtra("alt", Float.NaN).toDouble(); settings.manualMode = ManualMode.PINNED
            } else settings.manualMode = ManualMode.OFF
            // --es station_url http://10.0.2.2:18080 --ez station true --es filter DEMO-1
            "set" -> {
                i.getStringExtra("station_url")?.let { settings.stationUrl = it }
                if (i.hasExtra("station")) settings.stationEnabled = i.getBooleanExtra("station", false)
                i.getStringExtra("filter")?.let { settings.droneFilter = it }
            }
        }
        i.removeExtra("sentry_action")
    }

    private fun col(id: Int) = ContextCompat.getColor(this, id)
    private fun sevCol(s: Severity) = col(when (s) {
        Severity.WARNING -> R.color.warning; Severity.CAUTION -> R.color.caution
        Severity.ADVISORY -> R.color.advisory; else -> R.color.ok })

    private fun SpannableStringBuilder.add(s: String, color: Int? = null, bold: Boolean = false): SpannableStringBuilder {
        val a = length; append(s)
        if (color != null) setSpan(ForegroundColorSpan(color), a, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) setSpan(StyleSpan(Typeface.BOLD), a, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }

    private fun age(s: Double?) = when {
        s == null -> "—"
        s < 10 -> String.format(Locale.US, "%.1fs", s)
        s < 120 -> "${s.toInt()}s"
        s < 7200 -> "${(s / 60).toInt()}m"
        else -> "${(s / 3600).toInt()}h"
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        val st = SentryBus.state.value
        val now = System.currentTimeMillis()
        val stalled = st.mode != Mode.OFF && now - st.tickMs > 3000
        val top = st.targets.maxByOrNull { it.severity.rank }

        // ── banner ──
        val (text, bg) = when {
            stalled -> "SENTRY NOT RUNNING — engine stalled ${age((now - st.tickMs) / 1000.0)}" to R.color.warning
            st.mode == Mode.OFF -> "DISARMED — NOT WATCHING" to R.color.dim
            st.mode == Mode.REPLAY && top != null && top.severity >= Severity.ADVISORY ->
                "REPLAY ${st.replayClock} · ${top.severity.label.uppercase()} ${top.displayId}" to sevColRes(top.severity)
            st.mode == Mode.REPLAY -> "REPLAY ${st.replayClock}" to R.color.replay
            st.ownship == null -> "NO DRONE POSITION" to R.color.warning
            !st.ownshipFresh -> "DRONE POSITION LOST · ${age(st.ownshipAgeSec)} old" to R.color.warning
            top != null && top.severity >= Severity.ADVISORY ->
                "${top.severity.label.uppercase()} · ${top.displayId} ${Geo.cardinalAbbrev(top.bearingDeg)} ${Phrasing.displayDistance(top.distNm)}" to sevColRes(top.severity)
            st.trafficStale -> "TRAFFIC DATA STALE" to R.color.caution
            st.ownship.isManual -> "ARMED · MANUAL POSITION" to R.color.caution
            else -> "ARMED · WATCHING ${st.ownship.name}" to R.color.ok
        }
        banner.text = text
        banner.background.mutate().setTint(col(bg))
        banner.setTextColor(col(R.color.bg))

        progress.visibility = if (st.mode == Mode.REPLAY) View.VISIBLE else View.GONE
        progress.progress = (st.replayProgress * 1000).toInt()

        val armedLabel = settings.armed && st.mode != Mode.OFF
        btnArm.text = if (armedLabel) "DISARM" else "ARM"
        btnArm.backgroundTintList = android.content.res.ColorStateList.valueOf(col(if (armedLabel) R.color.panel2 else R.color.ok))
        btnArm.setTextColor(col(if (armedLabel) R.color.warning else R.color.bg))

        // ── drone ──
        val o = st.ownship
        if (o == null) {
            drone.text = if (st.mode == Mode.OFF) "—" else "No drone"
            droneDetail.text = st.ownshipNote.ifEmpty { if (st.mode == Mode.OFF) "Arm to start watching" else "" }
        } else {
            val sb = SpannableStringBuilder().add(o.name, bold = true)
            sb.add("  ${age(st.ownshipAgeSec)}", if (st.ownshipFresh) col(R.color.ok) else col(R.color.warning))
            if (!st.ownshipFresh) sb.add("  LOST", col(R.color.warning), true)
            drone.text = sb
            val alt = listOfNotNull(o.altMslFt?.let { "%,d ft MSL".format(Locale.US, it.toInt()) }, o.altAglFt?.let { "%,d AGL".format(Locale.US, it.toInt()) }).joinToString(" · ")
            val manual = if (o.source == OwnshipSource.MANUAL_PINNED || o.source == OwnshipSource.DEVICE_GPS) "USING MANUAL POSITION · " else ""
            droneDetail.text = "$manual${o.source.label} · %.5f, %.5f · %s".format(Locale.US, o.lat, o.lon, alt.ifEmpty { "alt unknown" }) +
                (if (st.ownshipNote.isNotEmpty()) "\n${st.ownshipNote}" else "")
        }

        // ── sources ──
        val sb = SpannableStringBuilder()
        if (st.mode == Mode.REPLAY) sb.add("Replay    ", col(R.color.dim)).add("PLAYING ", col(R.color.replay), true).add("${st.replayTitle}\n", col(R.color.dim))
        for (r in st.sources) {
            val (label, c) = when (r.state) {
                HealthMonitor.State.OK -> "OK     " to R.color.ok
                HealthMonitor.State.LOST -> "LOST   " to R.color.warning
                HealthMonitor.State.WAITING -> "WAIT   " to R.color.caution
                HealthMonitor.State.DISABLED -> "OFF    " to R.color.dim
            }
            sb.add(r.name.padEnd(14).take(14), col(R.color.ink)).add(label, col(c), true)
                .add(age(r.ageSec).padEnd(6), col(R.color.ink)).add(" ${r.detail.take(20)}\n", col(R.color.dim))
        }
        if (st.mode == Mode.OFF) {
            sb.add("Voice".padEnd(14), col(R.color.ink)).add("OFF    ", col(R.color.dim), true).add("starts when armed", col(R.color.dim))
            sb.add("\n\nNot armed: no source is being polled and nothing will be announced.", col(R.color.dim))
        } else {
            sb.add("Voice".padEnd(14), col(R.color.ink)).add(if (st.voiceOk) "OK     " else "UNAVAILABLE ", col(if (st.voiceOk) R.color.ok else R.color.warning), true)
                .add(st.voice.replace("com.google.android.tts", "Google").take(20), col(R.color.dim))
        }
        sources.text = sb

        // ── targets ──
        targetsLabel.text = if (st.mode == Mode.OFF) "Targets" else "Targets · ${st.targets.size} within ${settings.trafficRadiusNm.toInt()} nm"
        val tb = SpannableStringBuilder()
        if (st.targets.isEmpty()) tb.add(if (st.mode == Mode.OFF) "—" else if (o == null || !st.ownshipFresh) "No ownship: proximity not computed" else "No traffic", col(R.color.dim))
        for (t in st.targets.take(9)) {
            val c = if (t.severity >= Severity.ADVISORY) sevCol(t.severity) else col(R.color.ink)
            val v = t.dvFt?.let { (if (t.altEstimated) "≈" else "") + (if (it >= 0) "+" else "-") +
                String.format(Locale.US, "%,d", kotlin.math.abs(it).toInt()) + "ft" } ?: "alt ?"
            val tr = when (t.trend) { null -> ""; com.uasflightdeck.sentry.core.Trend.CONVERGING -> "conv"
                com.uasflightdeck.sentry.core.Trend.DIVERGING -> "div"; com.uasflightdeck.sentry.core.Trend.PASSING -> "pass" }
            tb.add(t.displayId.padEnd(8).take(8), c, true)
                .add(" ${Geo.cardinalAbbrev(t.bearingDeg).padEnd(2)}", c)
                .add(String.format(Locale.US, if (t.distNm < 1) " %4.2fnm " else " %4.1fnm ", t.distNm), c)
                .add(v.padEnd(10), c)
                .add(" ${tr.padEnd(4)} ${age(t.ageSec)} ${t.sources.joinToString("+") { SRC_ABBR[it] ?: it }}\n", col(R.color.dim))
            val extra = ArrayList<String>()
            val cpa = t.cpa
            if (t.predictive && cpa != null) extra += "CPA ${Phrasing.displayDistance(cpa.distM / 1852.0)} in ${cpa.tSec.toInt()}s"
            if (t.zones.isNotEmpty()) extra += "IN ${t.zones.joinToString()}"
            if (t.groundModeAirborne) extra += "reports GND at speed: alt unknown"
            if (extra.isNotEmpty()) tb.add("   ${extra.joinToString(" · ")}\n", if (t.predictive) col(R.color.warning) else col(R.color.caution))
        }
        targets.text = tb

        radar.rings = st.ringsNm
        radar.targets = if (st.ownshipFresh) st.targets else emptyList()
        radar.active = st.mode != Mode.OFF && st.ownshipFresh
        zones.text = if (st.mode == Mode.OFF) "" else if (st.watchedZones.isEmpty()) "No TFR / geofence within ${settings.tfrRelevanceNm.toInt()} nm"
            else "Watching: " + st.watchedZones.joinToString(" · ")
    }

    private fun sevColRes(s: Severity) = when (s) {
        Severity.WARNING -> R.color.warning; Severity.CAUTION -> R.color.caution
        Severity.ADVISORY -> R.color.advisory; else -> R.color.ok }

    private fun renderCallouts() {
        val list = SentryBus.calloutsFlow.value.take(5)
        val sb = SpannableStringBuilder()
        if (list.isEmpty()) sb.add("None yet", col(R.color.dim))
        for (c in list) {
            sb.add("${c.clock}  ", col(R.color.dim)).add("${c.ev.text}\n", if (c.ev.severity >= Severity.ADVISORY) sevCol(c.ev.severity) else col(R.color.ink), c.ev.severity >= Severity.WARNING)
        }
        callouts.text = sb
    }

    private fun askPermissionsOnce() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @SuppressLint("BatteryLife")
    private fun maybeAskBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || settings.batteryPrompted) return
        settings.batteryPrompted = true
        runCatching {
            startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }
}
