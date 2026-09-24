package com.uasflightdeck.sentry

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import com.uasflightdeck.sentry.core.SelectionMode
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
        const val REQ_ARM = 1
        val SRC_ABBR = mapOf("station" to "stn", "cloud" to "cld", "airsense" to "air", "replay" to "rpl")
    }
    private lateinit var settings: Settings
    private lateinit var banner: TextView
    private lateinit var drone: TextView
    private lateinit var droneLabel: TextView
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
    private lateinit var updateBanner: TextView
    private lateinit var plan: ScreenLayout.MainPlan

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        banner = findViewById(R.id.banner); drone = findViewById(R.id.drone); droneLabel = findViewById(R.id.droneLabel); droneDetail = findViewById(R.id.droneDetail)
        sources = findViewById(R.id.sources); targets = findViewById(R.id.targets); targetsLabel = findViewById(R.id.targetsLabel)
        callouts = findViewById(R.id.callouts); logView = findViewById(R.id.log); zones = findViewById(R.id.zones)
        radar = findViewById(R.id.radar); btnArm = findViewById(R.id.btnArm); progress = findViewById(R.id.replayProgress)
        updateBanner = findViewById(R.id.updateBanner)
        applyPlan(ScreenLayout.mainPlan(resources.configuration.screenWidthDp))
        updateBanner.setOnClickListener { startUpdate(this, settings) }

        btnArm.setOnClickListener {
            val st = SentryBus.state.value
            if (settings.armed && st.mode != Mode.OFF) SentryService.send(this, SentryService.ACTION_DISARM)
            // Any permission prompt happens HERE, before arming, on the pilot's own tap in Sentry; never while
            // armed. The battery-optimisation prompt is only in Settings (never on ARM).
            else if (!askPermissionsBeforeArming()) SentryService.send(this, SentryService.ACTION_ARM)
        }
        findViewById<View>(R.id.btnTest).setOnClickListener { SentryService.send(this, SentryService.ACTION_TEST) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { SentryBus.state.collect { render() } }
                launch { SentryBus.calloutsFlow.collect { renderCallouts() } }
                launch { SentryBus.logFlow.collect { logView.text = it.take(4).joinToString("\n") } }
                launch { while (true) { delay(1000); render() } }
                launch { Updater.state.collect { renderUpdateBanner() } }
            }
        }
        // Sticky state: if Sentry was armed but the service isn't running (e.g. app updated), re-arm.
        if (settings.armed && SentryBus.state.value.mode == Mode.OFF) SentryService.send(this, SentryService.ACTION_ARM)
        handleDebugIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Updater.check(this)          // rate-limited: at most once a day, quiet when offline
        renderUpdateBanner()
    }

    private fun renderUpdateBanner() {
        val ui = Updater.state.value
        val avail = Updater.available(settings)
        updateBanner.visibility = if (avail || ui.downloading) View.VISIBLE else View.GONE
        if (updateBanner.visibility != View.VISIBLE) return
        val v = Updater.latest(settings)?.version
        updateBanner.text = when {
            ui.downloading -> ui.message
            ui.message.isNotEmpty() && !ui.message.startsWith("Sentry ") && !ui.message.startsWith("Up to date") -> "Sentry $v available · ${ui.message}"
            else -> "Sentry $v available (you have ${Updater.current}) · tap to download and install"
        }
    }

    /**
     * What this controller is bound to, shown on EVERY render (armed or not) so a pilot can never be misled
     * about which aircraft is protected: "Bound to: <serial> · <callsign>", or "NO AIRCRAFT PINNED …".
     */
    private fun boundLine(watched: com.uasflightdeck.sentry.core.Ownship?): CharSequence {
        val pin = settings.pinnedSerial.trim()
        if (pin.isEmpty()) return SpannableStringBuilder().add("NO AIRCRAFT PINNED — protecting this controller only (Settings)", col(R.color.caution), true)
        val cs = watched?.takeIf { it.serial?.trim().equals(pin, ignoreCase = true) }?.let { it.callsign ?: it.name }
            ?: DroneHistory.entries(this).firstOrNull { it.serial?.trim()?.equals(pin, ignoreCase = true) == true }?.callsign
        return SpannableStringBuilder().add("Bound to: ", col(R.color.dim)).add(pin, col(R.color.ink), true)
            .add(" · ${cs ?: "not seen yet"}", col(R.color.ink))
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
            // --es station_url http://10.0.2.2:18080 --ez station true
            // --es pinned 1581F7K3C251F00C9B34 --es worker http://10.0.2.2:18081
            // --ef elev 5100 (controller elevation override; NaN clears it)
            "set" -> {
                i.getStringExtra("station_url")?.let { settings.stationUrl = it }
                if (i.hasExtra("station")) settings.stationEnabled = i.getBooleanExtra("station", false)
                i.getStringExtra("pinned")?.let { settings.pinnedSerial = it }
                i.getStringExtra("worker")?.let { settings.workerBase = it }
                if (i.hasExtra("elev")) settings.controllerElevFt = i.getFloatExtra("elev", Float.NaN).toDouble()
            }
        }
        i.removeExtra("sentry_action")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Reflow for the width we actually have. The RC Plus (1920x1200 at density 2.5) gives ~768 x 480 dp:
     * smaller compass with the drone panel under it, smaller type. Only rearranges and resizes; the
     * action bar is pinned in the layout and each column scrolls, so nothing is ever out of reach.
     */
    private fun applyPlan(p: ScreenLayout.MainPlan) {
        plan = p
        fun weight(id: Int, w: Float) { (findViewById<View>(id).layoutParams as LinearLayout.LayoutParams).weight = w }
        weight(R.id.colLeft, p.leftWeight); weight(R.id.colCentre, p.centreWeight); weight(R.id.colRight, p.rightWeight)

        banner.textSize = p.bannerSp; banner.minHeight = dp(p.bannerMinHeightDp)
        updateBanner.textSize = p.bodySp + 1
        drone.textSize = p.droneNameSp; droneDetail.textSize = p.bodySp
        sources.textSize = if (p.compact) p.monoSp else p.monoSp + 1; targets.textSize = p.monoSp; callouts.textSize = p.bodySp
        zones.textSize = p.bodySp; logView.textSize = p.logSp; logView.maxLines = p.logLines
        for (id in intArrayOf(R.id.droneLabel, R.id.sourcesLabel, R.id.targetsLabel, R.id.calloutsLabel)) findViewById<TextView>(id).textSize = p.labelSp

        for (id in intArrayOf(R.id.btnArm, R.id.btnTest, R.id.btnSettings))
            findViewById<View>(id).layoutParams.height = dp(p.buttonHeightDp)

        if (p.droneUnderCompass) {
            // Drone panel: from the left column to under the compass (and the zones line).
            val panel = findViewById<View>(R.id.dronePanel)
            (panel.parent as ViewGroup).removeView(panel)
            findViewById<LinearLayout>(R.id.colCentreInner).addView(panel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
    }

    /** Characters of monospace text that fit on one line of [tv] (0 before the first layout). */
    private fun monoChars(tv: TextView): Int {
        val w = tv.width - tv.paddingLeft - tv.paddingRight
        if (w <= 0) return 0
        return (w / tv.paint.measureText("0")).toInt()
    }

    /**
     * Appends a table row's trailing [tail] on the same line when it fits in [chars], else on its
     * own indented line: a narrow screen moves text, it never cuts it.
     */
    private fun SpannableStringBuilder.tail(usedChars: Int, tail: String, color: Int, chars: Int): SpannableStringBuilder {
        val t = tail.trim()
        if (t.isEmpty()) return add("\n")
        return if (chars == 0 || usedChars + 1 + t.length <= chars) add(" $t\n", color) else add("\n  $t\n", color)
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
            // Bound, aircraft not in the feed: say so, unless a cylinder alert around the controller is up.
            st.waitingForBound && (top == null || top.severity < Severity.ADVISORY) ->
                "WAITING FOR THIS CONTROLLER'S AIRCRAFT · ${st.boundSerial}" to R.color.caution
            st.selectionMode == SelectionMode.CONTROLLER && st.ownship == null -> "NO AIRCRAFT PINNED · CONTROLLER GPS UNAVAILABLE" to R.color.warning
            st.ownship == null -> "NO DRONE POSITION" to R.color.warning
            !st.ownshipFresh -> "DRONE POSITION LOST · ${age(st.ownshipAgeSec)} old" to R.color.warning
            top != null && top.severity >= Severity.ADVISORY ->
                "${top.severity.label.uppercase()} · ${top.displayId} ${Geo.cardinalAbbrev(top.bearingDeg)} ${Phrasing.displayDistance(top.distNm)}" to sevColRes(top.severity)
            st.trafficStale -> "TRAFFIC DATA STALE" to R.color.caution
            st.selectionMode == SelectionMode.CONTROLLER && st.cylinders.isEmpty() -> "NO AIRCRAFT PINNED · NO CYLINDER ENABLED" to R.color.caution
            st.selectionMode == SelectionMode.CONTROLLER -> "ARMED · PROTECTING THIS CONTROLLER" to R.color.ok
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
        val controllerMode = st.selectionMode == SelectionMode.CONTROLLER
        droneLabel.text = when {
            st.mode == Mode.OFF || st.selectionMode == null -> "Protecting"
            st.selectionMode == SelectionMode.PINNED -> "Protecting · this controller's aircraft"
            st.waitingForBound -> "Protecting · this controller (waiting for its aircraft)"
            else -> "Protecting · this controller (no aircraft pinned)"
        }
        val bound = boundLine(o)
        if (controllerMode && st.mode != Mode.OFF) {
            val sb = SpannableStringBuilder().add("This controller", bold = true)
            sb.add("  ${if (st.controllerUsable) "GPS OK" else "NO GPS FIX"}", col(if (st.controllerUsable) R.color.ok else R.color.warning), true)
            drone.text = sb
            val f = st.controllerFix
            val pos = if (f == null) "no fix yet" else "%.5f, %.5f · %s".format(Locale.US, f.lat, f.lon,
                f.elevMslFt?.let { "%,d ft".format(Locale.US, it.toInt()) } ?: "elev unknown")
            val cyl = if (st.cylinders.isEmpty()) "No cylinder enabled: nothing protected (Settings)" else st.cylinders.joinToString("\n") { "◯ " + it.describe() }
            droneDetail.text = SpannableStringBuilder(bound).append("\n" + listOf(st.ownshipNote, pos, cyl).filter { it.isNotEmpty() }.joinToString("\n"))
        } else if (o == null) {
            drone.text = if (st.mode == Mode.OFF) "—" else "No drone"
            droneDetail.text = SpannableStringBuilder(bound).append("\n" + st.ownshipNote.ifEmpty { if (st.mode == Mode.OFF) "Arm to start watching" else "" })
        } else {
            val sb = SpannableStringBuilder().add(o.name, bold = true)
            sb.add("  ${age(st.ownshipAgeSec)}", if (st.ownshipFresh) col(R.color.ok) else col(R.color.warning))
            if (!st.ownshipFresh) sb.add("  LOST", col(R.color.warning), true)
            drone.text = sb
            val alt = listOfNotNull(o.altMslFt?.let { "%,d ft MSL".format(Locale.US, it.toInt()) }, o.altAglFt?.let { "%,d AGL".format(Locale.US, it.toInt()) }).joinToString(" · ")
            droneDetail.text = SpannableStringBuilder(bound).append("\n" + listOfNotNull(o.model, o.source.label).joinToString(" · ") +
                " · %.5f, %.5f · %s".format(Locale.US, o.lat, o.lon, alt.ifEmpty { "alt unknown" }) +
                (if (st.ownshipNote.isNotEmpty()) "\n${st.ownshipNote}" else ""))
        }

        // ── sources ──
        val sb = SpannableStringBuilder()
        val sc = monoChars(sources)
        if (st.mode == Mode.REPLAY) sb.add("Replay    ", col(R.color.dim)).add("PLAYING ", col(R.color.replay), true).add("${st.replayTitle}\n", col(R.color.dim))
        for (r in st.sources) {
            val (label, c) = when (r.state) {
                HealthMonitor.State.OK -> "OK     " to R.color.ok
                HealthMonitor.State.LOST -> "LOST   " to R.color.warning
                HealthMonitor.State.WAITING -> "WAIT   " to R.color.caution
                HealthMonitor.State.DISABLED -> "OFF    " to R.color.dim
            }
            sb.add(r.name.padEnd(14).take(14), col(R.color.ink)).add(label, col(c), true)
                .add(age(r.ageSec).padEnd(6), col(R.color.ink)).tail(27, r.detail.take(60), col(R.color.dim), sc)
        }
        if (st.mode != Mode.OFF && st.selectionMode != null) {
            val (lbl, c) = when {
                st.selectionMode == SelectionMode.PINNED -> "BOUND    " to R.color.ok
                st.waitingForBound -> "WAITING  " to R.color.caution
                else -> "CONTROL  " to R.color.caution
            }
            val det = when (st.selectionMode) {
                SelectionMode.PINNED -> st.boundSerial ?: ""
                else -> "${st.cylinders.size} cylinder${if (st.cylinders.size == 1) "" else "s"}"
            }
            sb.add("Selection".padEnd(14), col(R.color.ink)).add(lbl, col(c), true).tail(23, det, col(R.color.dim), sc)
            // Controller GPS health, derived at render time from the fix itself
            val f = st.controllerFix
            val fixAge = st.controllerFixAgeSec?.let { it + if (st.mode == Mode.LIVE) (now - st.tickMs) / 1000.0 else 0.0 }
            val (gl, gc) = when {
                f == null -> "NO FIX " to R.color.warning
                st.controllerUsable -> "OK     " to R.color.ok
                else -> "OLD    " to R.color.warning
            }
            val acc = f?.accuracyM?.let { " ±${it.toInt()} m" } ?: ""
            sb.add("Ctrl GPS".padEnd(14), col(R.color.ink)).add(gl, col(gc), true).add(age(fixAge).padEnd(6), col(R.color.ink))
                .tail(27, "$acc ${f?.label?.removePrefix("controller GPS")?.removePrefix(" · ") ?: ""}".take(17), col(R.color.dim), sc)
        }
        if (st.mode == Mode.OFF) {
            sb.add("Voice".padEnd(14), col(R.color.ink)).add("OFF    ", col(R.color.dim), true).tail(21, "starts when armed", col(R.color.dim), sc)
            sb.add("\nNot armed: no source is being polled and nothing will be announced.", col(R.color.dim))
        } else {
            val vl = if (st.voiceOk) "OK     " else "UNAVAILABLE "
            sb.add("Voice".padEnd(14), col(R.color.ink)).add(vl, col(if (st.voiceOk) R.color.ok else R.color.warning), true)
                .tail(14 + vl.length, st.voice.replace("com.google.android.tts", "Google").take(20), col(R.color.dim), sc)
        }
        sources.text = sb

        // ── targets ──
        targetsLabel.text = if (st.mode == Mode.OFF) "Targets" else
            "Targets · ${st.targets.size} within ${trimNm(st.displayRadiusNm)} nm of ${if (st.displayAroundAircraft) "aircraft" else "controller"}" +
                " · ≤${"%,d".format(Locale.US, st.displayCeilingFt.toInt())} ft" + (if (st.hiddenTargets > 0) " · ${st.hiddenTargets} hidden" else "")
        val tb = SpannableStringBuilder()
        val tc = monoChars(targets)
        if (st.targets.isEmpty()) tb.add(if (st.mode == Mode.OFF) "—" else if (o == null || !st.ownshipFresh) (if (controllerMode) "No controller GPS: nothing computed" else "No ownship: proximity not computed") else "No traffic", col(R.color.dim))
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
                .tail(29, "${tr.padEnd(4)} ${age(t.ageSec)} ${t.sources.joinToString("+") { SRC_ABBR[it] ?: it }}", col(R.color.dim), tc)
            val extra = ArrayList<String>()
            val cpa = t.cpa
            if (t.predictive && cpa != null) extra += "CPA ${Phrasing.displayDistance(cpa.distM / 1852.0)} in ${cpa.tSec.toInt()}s"
            if (t.zones.isNotEmpty()) extra += "IN ${t.zones.joinToString()}"
            if (t.groundModeAirborne) extra += "reports GND at speed: alt unknown"
            if (extra.isNotEmpty()) tb.add("   ${extra.joinToString(" · ")}\n", if (t.predictive) col(R.color.warning) else col(R.color.caution))
        }
        targets.text = tb

        radar.rings = st.ringsNm
        radar.cylinderRadii = if (controllerMode) st.cylinders.map { it.radiusNm } else emptyList()
        radar.targets = if (st.ownshipFresh) st.targets else emptyList()
        radar.active = st.mode != Mode.OFF && st.ownshipFresh
        zones.text = if (st.mode == Mode.OFF) "" else if (st.watchedZones.isEmpty()) "No TFR / geofence within ${settings.tfrRelevanceNm.toInt()} nm"
            else "Watching: " + st.watchedZones.joinToString(" · ")
    }

    private fun trimNm(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

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

    /**
     * Notifications, and location (the controller's GPS is the fallback protected position). Returns true when a
     * prompt was shown: arming then happens in [onRequestPermissionsResult], after the system dialog is gone.
     */
    private fun askPermissionsBeforeArming(): Boolean {
        val want = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            want += Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            want += Manifest.permission.ACCESS_FINE_LOCATION; want += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (want.isEmpty()) return false
        requestPermissions(want.toTypedArray(), REQ_ARM)
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Arm whatever the answer: without location Sentry still watches the pinned aircraft (and says the controller GPS is unavailable).
        if (requestCode == REQ_ARM) SentryService.send(this, SentryService.ACTION_ARM)
    }
}
