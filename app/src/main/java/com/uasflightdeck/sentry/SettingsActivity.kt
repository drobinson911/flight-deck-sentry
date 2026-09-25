package com.uasflightdeck.sentry

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.text.InputType
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.uasflightdeck.sentry.core.AlertStyle
import com.uasflightdeck.sentry.core.Cylinder
import com.uasflightdeck.sentry.core.SoundLevel
import com.uasflightdeck.sentry.core.DroneSelector
import com.uasflightdeck.sentry.core.FleetStatus
import com.uasflightdeck.sentry.core.CylinderAltRef
import com.uasflightdeck.sentry.core.Parsers
import com.uasflightdeck.sentry.core.Units
import com.uasflightdeck.sentry.core.ResourceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * One scrolling page: two columns at 1000 dp and wider, one column on the RC Plus (~768 dp). On the
 * controller the order is (owner, 0.4.0): rings & volume (+ targets shown), controller cylinders, this
 * controller's aircraft, prediction, cadence & alert style & banner duration & mute timings, sounds &
 * vibration, fleet token / Overwatch station / TFR zones, geofences, background, app update, and the replay last.
 *
 * Auto-save (0.3.4): every field persists ~400 ms after the last keystroke (switches at once), and again
 * on Save, Back and when leaving the screen. A numeric field that isn't a valid number in range shows a
 * red outline + its range, and storage keeps the LAST VALID value ([FieldRules]). Enter/Done, Save,
 * Back and a tap outside a field close the keyboard. The service re-reads Settings every 1 s tick, so
 * a saved value is live within about a second without restarting anything.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var s: Settings
    private val savers = ArrayList<() -> Unit>()
    private lateinit var geofenceStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var pinnedField: AutoCompleteTextView
    private lateinit var pinnedNote: TextView
    private lateinit var updateInfo: TextView
    private lateinit var updateNotes: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateInstallBtn: MaterialButton
    private lateinit var feedList: LinearLayout
    private lateinit var cylinderList: LinearLayout
    private lateinit var locStatus: TextView
    private lateinit var rootView: LinearLayout

    // ── auto-save ─────────────────────────────────────────────────────────
    private val ui = Handler(Looper.getMainLooper())
    private val autoSave = Runnable { saveAll() }
    private val helpers = HashMap<EditText, TextView>()
    private val validators = HashMap<EditText, () -> Unit>()
    private val specs = HashMap<EditText, FieldRules.NumSpec>()
    private var building = true
    private fun scheduleAutoSave() { if (building) return; ui.removeCallbacks(autoSave); ui.postDelayed(autoSave, AUTOSAVE_MS) }
    /** Persist now (Save, Back, Done, leaving the screen). */
    private fun flush() { ui.removeCallbacks(autoSave); saveAll() }

    private val pickGeo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importGeofence(it) } }
    private val askLoc = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (::locStatus.isInitialized) locStatus.text = locText() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)

        // Focusable root: it takes focus when a field lets go, so no other field grabs it (and the keyboard stays closed on open).
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)); isFocusableInTouchMode = true
            // ...without the grey "focused" wash Android 8+ paints over a focused view with no focus state of its own.
            defaultFocusHighlightEnabled = false }
        rootView = root
        // header bar
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        header.addView(TextView(this).apply { text = "Settings"; textSize = 28f; setTextColor(col(R.color.ink)); setTypeface(typeface, android.graphics.Typeface.BOLD) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("Back", secondary = true) { dismissKeyboard(); flush(); finish() })
        header.addView(button("Save") {
            dismissKeyboard(); flush()
            val bad = helpers.keys.count { it.isShown && helpers[it]?.visibility == View.VISIBLE }
            toast(if (bad == 0) "Saved" else "Saved · $bad field(s) invalid: kept the last valid value")
        }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8) })
        root.addView(header)

        // Two columns only when each gets ~500 dp; the RC Plus (~960 dp) gets one scrolling column.
        val twoCols = ScreenLayout.settingsColumns(resources.configuration.screenWidthDp) == 2
        val cols = LinearLayout(this).apply {
            orientation = if (twoCols) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), dp(10)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (twoCols) {
            cols.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cols.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        } else {
            cols.addView(left, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cols.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(ScrollView(this).apply { addView(cols) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // ── Column A (top on the controller): alert rings + targets shown, controller cylinders, this controller's aircraft ──
        section(left, "Alert rings & protected volume").apply {
            addView(note("Rings around the drone are live even with it on the pad. Advisory = inside 3 mi (banner only in the " +
                "Standard style), caution = inside 1 mi, warning = inside 0.5 mi; each only for aircraft inside the volume."))
            val a = num("Advisory ring (nm)", s.advisoryNm, FieldRules.RING)
            val c = num("Caution ring (nm)", s.cautionNm, FieldRules.RING)
            val w = num("Warning ring (nm)", s.warningNm, FieldRules.RING)
            val band = num("Ceiling above aircraft (ft): protected from the surface up to this far above it", s.ceilingAboveFt, FieldRules.CEILING_ABOVE)
            val baro = num("Baro correction when no GPS altitude (ft, \"estimated\")", s.baroCorrectionFt, FieldRules.BARO_CORRECTION)
            addView(label("TARGETS SHOWN in the list and on the compass (alerts are not affected)"))
            val ta = num("Show targets within (nm of this controller's aircraft)", s.targetsAircraftNm, FieldRules.TARGETS_AIRCRAFT)
            val tc = num("Show targets within (nm of the controller, when the aircraft isn't in the feed)", s.targetsControllerNm, FieldRules.TARGETS_CONTROLLER)
            val tceil = num("Hide targets above (ft: GPS altitude, else baro; unknown altitude stays shown)", s.targetsCeilingFt, FieldRules.TARGETS_CEILING)
            addView(note("An aircraft Sentry is alerting on is always shown, wherever it is."))
            // The three rings are also checked together: advisory ≥ caution ≥ warning.
            val rings = listOf(a, c, w)
            val checkRings = {
                rings.forEach { validate(it) }
                val v = rings.map { FieldRules.parseNumber(it.text.toString(), FieldRules.RING) }
                if (v.all { it is FieldRules.Parsed.Ok }) {
                    val (av, cv, wv) = v.map { (it as FieldRules.Parsed.Ok).value }
                    if (!FieldRules.ringsOrdered(av, cv, wv)) rings.forEach { it.showError("Rings must be advisory ≥ caution ≥ warning") }
                }
            }
            rings.forEach { validators[it] = checkRings }
            checkRings()
            savers += {
                FieldRules.rings(a.text.toString(), c.text.toString(), w.text.toString(), FieldRules.RING,
                    Triple(s.advisoryNm, s.cautionNm, s.warningNm))?.let { (av, cv, wv) -> s.advisoryNm = av; s.cautionNm = cv; s.warningNm = wv }
                s.ceilingAboveFt = band.valueOr(s.ceilingAboveFt); s.baroCorrectionFt = baro.valueOr(s.baroCorrectionFt)
                s.targetsAircraftNm = ta.valueOr(s.targetsAircraftNm); s.targetsControllerNm = tc.valueOr(s.targetsControllerNm)
                s.targetsCeilingFt = tceil.valueOr(s.targetsCeilingFt)
            }
        }

        section(left, "Controller cylinders (when the aircraft isn't in the feed)").apply {
            addView(note("While this controller's aircraft is not in the feed (or none is pinned), Sentry protects these cylinders centred on this controller's GPS. It calls aircraft entering them, and warns early when one is predicted to pass within the warning ring of the controller."))
            cylinderList = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(cylinderList)
            renderCylinders()
            addView(row(button("Add cylinder", secondary = true) { editCylinder(null) }))
            val elev = num("Controller elevation override (ft MSL, blank = GPS)", s.controllerElevFt, FieldRules.CONTROLLER_ELEV)
            savers += { s.controllerElevFt = elev.valueOr(s.controllerElevFt) }
            locStatus = note(locText()); addView(locStatus)
            addView(row(button("Allow location…", secondary = true) { askLoc.launch(Manifest.permission.ACCESS_FINE_LOCATION) }))
        }

        section(left, "This controller's aircraft").apply {
            addView(note("Sentry protects ONLY this airframe, by serial. No other drone is ever watched. When it is not in the feed, Sentry says \"Waiting for this controller's aircraft\", protects the controller cylinders above, and switches to the aircraft the moment it appears."))
            addView(label("Aircraft serial"))
            pinnedField = AutoCompleteTextView(this@SettingsActivity).apply {
                styleField(this)
                // Serials are stored upper-case: show them that way while typing.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                filters = arrayOf(InputFilter.AllCaps())
                setText(s.pinnedSerial); hint = "type the airframe serial, or tap an aircraft below"
            }
            addView(pinnedField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            pinnedNote = note(""); addView(pinnedNote)
            addView(row(button("Clear", secondary = true) { pinnedField.setText(""); s.pinnedSerial = ""; dismissKeyboard(); updatePinnedNote(); renderFeedList() }))
            addView(label("Aircraft in the feed now: tap one to pin it"))
            feedList = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(feedList)
            addView(row(button("Refresh list", secondary = true) { fetchLiveAircraft() }))
            pinnedField.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { updatePinnedNote(); scheduleAutoSave() }
            })
            savers += { s.pinnedSerial = FieldRules.normaliseSerial(pinnedField.text.toString()) }
            refreshSuggestions()
            updatePinnedNote()
            renderFeedList()
        }

        section(left, "Prediction (time to closest approach)").apply {
            addView(note("Every second each aircraft is extrapolated in a straight line (track, speed, vertical rate) against the drone's own " +
                "motion. TRACK ALERT: predicted within the track miss inside the track time. WARNING: within the warning miss inside the " +
                "warning time (or actually inside the warning ring). COLLISION RISK: within the collision miss and vertical inside the " +
                "collision time, or climbing / descending THROUGH your altitude while inside the warning ring in the next collision time."))
            val tt = num("TRACK ALERT: time to closest (s)", s.trackSec, FieldRules.PRED_SEC)
            val wt = num("WARNING: time to closest (s)", s.warningSec, FieldRules.PRED_SEC)
            val ct = num("COLLISION RISK: time to closest (s)", s.collisionSec, FieldRules.PRED_SEC)
            val tm = num("TRACK ALERT: predicted miss (nm)", s.trackMissNm, FieldRules.PRED_MISS_NM)
            val wm = num("WARNING: predicted miss (nm)", s.warningMissNm, FieldRules.PRED_MISS_NM)
            val cm = num("COLLISION RISK: predicted miss (ft)", s.collisionMissFt, FieldRules.COLLISION_MISS_FT)
            val cv = num("COLLISION RISK: vertical at closest (ft)", s.collisionVertFt, FieldRules.COLLISION_VERT_FT)
            val cd = num("Corridor widening (degrees; 0 = off): miss + distance × tan", s.corridorDeg, FieldRules.CORRIDOR_DEG)
            addView(note("Defaults 120 / 60 / 60 s, 1 / 0.5 nm, 500 / 300 ft, no widening: the fleet simulation's best (86 real flights)."))
            val times = listOf(tt, wt, ct, tm, wm)
            val checkOrder = {
                times.forEach { validate(it) }
                val v = times.map { (FieldRules.parseNumber(it.text.toString(), specs.getValue(it)) as? FieldRules.Parsed.Ok)?.value }
                if (v.all { it != null } && !FieldRules.predictionOrdered(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!))
                    times.forEach { it.showError("Needs track ≥ warning ≥ collision time, and track miss ≥ warning miss") }
            }
            times.forEach { validators[it] = checkOrder }
            checkOrder()
            savers += {
                val v = times.map { (FieldRules.parseNumber(it.text.toString(), specs.getValue(it)) as? FieldRules.Parsed.Ok)?.value }
                if (v.all { it != null } && FieldRules.predictionOrdered(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!)) {
                    s.trackSec = v[0]!!; s.warningSec = v[1]!!; s.collisionSec = v[2]!!; s.trackMissNm = v[3]!!; s.warningMissNm = v[4]!!
                }
                s.collisionMissFt = cm.valueOr(s.collisionMissFt); s.collisionVertFt = cv.valueOr(s.collisionVertFt)
                s.corridorDeg = cd.valueOr(s.corridorDeg)
            }
        }

        section(right, "Cadence, alert style, banners & mutes").apply {
            addView(label("Alert style"))
            val styles = RadioGroup(this@SettingsActivity).apply { orientation = RadioGroup.HORIZONTAL; setOnCheckedChangeListener { _, _ -> scheduleAutoSave() } }
            styles.addView(radio("Standard", 401)); styles.addView(radio("Quiet", 402)); styles.addView(radio("Loud", 403))
            styles.check(when (s.alertStyle) { AlertStyle.QUIET -> 402; AlertStyle.LOUD -> 403; else -> 401 })
            addView(styles)
            addView(note("Standard: every alert sounds (advisory = one soft tone + short vibrate on entry; its repeats are banner-only). " +
                "Quiet: advisories are silent, caution is banner-only and every repeat interval is doubled. Loud: advisory repeats sound too. " +
                "Escalations always sound; the COLLISION RISK tone is never slowed."))
            val wf = num("WARNING repeat, 1 mi and beyond (s)", s.warnFarSec, FieldRules.REPEAT_SEC)
            val wn = num("WARNING repeat, 0.5–1 mi (s)", s.warnNearSec, FieldRules.REPEAT_SEC)
            val wc = num("WARNING repeat, inside 0.5 mi or closest in < 30 s (s; never faster than 6)", s.warnCloseSec, FieldRules.REPEAT_SEC)
            val cr = num("CAUTION repeat (s)", s.cautionRepeatSec, FieldRules.REPEAT_SEC)
            val tu = num("TRACK / advisory banner update (s, no sound)", s.trackUpdateSec, FieldRules.REPEAT_SEC)
            val col = num("COLLISION RISK tone every (s)", s.collisionRepeatSec, FieldRules.COLLISION_REPEAT_SEC)
            val bd = num("Banner duration (s): every banner clears itself after this", s.bannerSec, FieldRules.BANNER_SEC)
            val gi = num("\"Got it\" mutes that aircraft's repeats for (s)", s.gotItSec, FieldRules.GOT_IT_SEC)
            val qm = num("\"Quiet\" turns traffic sounds off for (min)", s.quietMin, FieldRules.QUIET_MIN)
            val ig = switch("Offer \"Ignore\" on banners (mute an aircraft until it clears the rings)", s.ignoreEnabled)
            addView(note("Every mute gives way at once to an escalation, a COLLISION RISK, or the aircraft turning toward the drone."))
            savers += {
                s.alertStyle = when (styles.checkedRadioButtonId) { 402 -> AlertStyle.QUIET; 403 -> AlertStyle.LOUD; else -> AlertStyle.STANDARD }
                s.warnFarSec = wf.valueOr(s.warnFarSec); s.warnNearSec = wn.valueOr(s.warnNearSec); s.warnCloseSec = wc.valueOr(s.warnCloseSec)
                s.cautionRepeatSec = cr.valueOr(s.cautionRepeatSec); s.trackUpdateSec = tu.valueOr(s.trackUpdateSec)
                s.collisionRepeatSec = col.valueOr(s.collisionRepeatSec); s.bannerSec = bd.valueOr(s.bannerSec)
                s.gotItSec = gi.valueOr(s.gotItSec); s.quietMin = qm.valueOr(s.quietMin); s.ignoreEnabled = ig.isChecked
            }
        }

        section(right, "Sounds & vibration").apply {
            addView(note("Each alert plays one of this controller's own sounds (warning and collision risk on the alarm volume, the rest on " +
                "the notification volume), then vibrates. Nothing is spoken. A sound that can't be found falls back to the controller's " +
                "default sound: never silence."))
            for (level in SoundLevel.entries) addView(soundRow(level))
            val vib = switch("Vibrate with each alert", s.vibrationOn)
            addView(note(if (player.hasVibrator) "Vibration: available on this controller." else "Vibration: not available on this controller (skipped)."))
            savers += { s.vibrationOn = vib.isChecked }
        }

        // ── then: feeds, TFR / geofences, background, app update, replay ──
        section(right, "Fleet token & worker").apply {
            addView(note("Fleet feed (drone position)"))
            val token = field("Fleet token (X-Fleet-Token)", if (s.fleetTokenIsDefault) "" else s.fleetToken,
                hint = if (s.fleetTokenIsDefault) "using token built into this APK" else "paste token", password = true)
            addView(button("Paste token from clipboard", secondary = true) {
                val cm = getSystemService(ClipboardManager::class.java)
                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this@SettingsActivity)?.toString()?.trim()
                if (t.isNullOrEmpty()) toast("Clipboard is empty") else { token.setText(t); flush(); toast("Token pasted and saved") }
            })
            val base = field("Worker base URL", s.workerBase)
            validators[base] = { base.showError(if (FieldRules.workerBase(base.text.toString()) == null) "Must be http:// or https:// and a host" else null) }
            savers += {
                val t = FieldRules.normaliseToken(token.text.toString())
                if (t.isNotEmpty() || !s.fleetTokenIsDefault) s.fleetToken = t
                FieldRules.workerBase(base.text.toString())?.let { s.workerBase = it }
            }
        }

        section(right, "Overwatch ADS-B station (URL)").apply {
            addView(note("The local feed: an Overwatch station's /data/aircraft.json (readsb-shaped) over Wi-Fi. Without it, " +
                "all traffic comes from the online feed."))
            val st = switch("Use the Overwatch station", s.stationEnabled)
            val url = field("Overwatch station URL", s.stationUrl, hint = "192.168.1.20 or http://host:8080")
            val auto = switch("Auto-discover the Overwatch station (LAN beacon)", s.stationAutoDiscover)
            savers += { s.stationEnabled = st.isChecked; s.stationUrl = url.text.toString().trim(); s.stationAutoDiscover = auto.isChecked }
        }

        section(right, "Traffic sources (all run together)").apply {
            val cloud = switch("Online ADS-B feed (uas-app worker)", s.cloudEnabled)
            val radius = num("Traffic radius (nm)", s.trafficRadiusNm, FieldRules.TRAFFIC_RADIUS)
            savers += { s.cloudEnabled = cloud.isChecked; s.trafficRadiusNm = radius.valueOr(s.trafficRadiusNm) }
            val tfr = num("Watch (show) TFRs within (nm of drone)", s.tfrRelevanceNm, FieldRules.TFR_RELEVANCE)
            val za = num("Zone entry alerts only for TFRs / geofences containing the drone or within (nm)", s.zoneAlertNm, FieldRules.ZONE_ALERT_NM)
            savers += { s.tfrRelevanceNm = tfr.valueOr(s.tfrRelevanceNm); s.zoneAlertNm = za.valueOr(s.zoneAlertNm) }
        }

        section(right, "Geofences").apply {
            val ce = switch("Circle geofence", s.circleEnabled)
            val onDrone = switch("Centre on the drone (off = fixed point below)", s.circleOnDrone)
            val clat = num("Circle centre latitude", s.circleLat, FieldRules.CIRCLE_LAT)
            val clon = num("Circle centre longitude", s.circleLon, FieldRules.CIRCLE_LON)
            val cr = num("Circle radius (nm)", s.circleRadiusNm, FieldRules.CIRCLE_RADIUS)
            savers += { s.circleEnabled = ce.isChecked; s.circleOnDrone = onDrone.isChecked
                s.circleLat = clat.valueOr(s.circleLat); s.circleLon = clon.valueOr(s.circleLon); s.circleRadiusNm = cr.valueOr(s.circleRadiusNm) }
            geofenceStatus = note(geofenceText()); addView(geofenceStatus)
            addView(row(
                button("Import GeoJSON…", secondary = true) { pickGeo.launch(arrayOf("application/json", "application/geo+json", "application/octet-stream", "*/*")) },
                button("Remove file", secondary = true) { File(filesDir, "geofences.geojson").delete(); s.geofenceFileName = ""; geofenceStatus.text = geofenceText() },
            ))
        }

        section(right, "Background").apply {
            val boot = switch("Resume armed after power-off", s.resumeAfterPowerOff)
            addView(note("Armed stays armed until you DISARM or swipe Sentry away: after a power cycle it comes back armed " +
                "(\"Sentry armed after restart\"), after a crash it restarts itself (\"Sentry restarted\")."))
            savers += { s.resumeAfterPowerOff = boot.isChecked }
            batteryStatus = note(batteryText()); addView(batteryStatus)
            addView(button("Battery optimisation exemption…", secondary = true) { requestBattery() })
        }
        section(right, "Resources (what Sentry uses while armed)").apply {
            addView(note("Measured every 10 s from ARM to DISARM: CPU from /proc (as a share of all the controller's cores, " +
                "and of one core), memory (PSS + Java heap), the battery's drain per hour, Sentry's own network data and its " +
                "wake lock. Battery is the WHOLE controller's drain (screen, DroneSense and radios included): Android gives " +
                "apps no per-app battery figure. A summary goes to the log every 5 min and a flight summary at DISARM."))
            val on = switch("Measure resources while armed", s.resourceMonitorOn)
            savers += { s.resourceMonitorOn = on.isChecked }
            val detail = TextView(this@SettingsActivity).apply { textSize = 15f; setTextColor(col(R.color.ink)); setPadding(0, dp(6), 0, dp(4)) }
            addView(detail)
            fun render(st: ResourceMonitor.State) {
                val sb = android.text.SpannableStringBuilder()
                if (!st.on) sb.append("Off.")
                else ResourceText.detail(st.snapshot).forEach { (k, v) ->
                    val a = sb.length; sb.append(k)
                    sb.setSpan(android.text.style.ForegroundColorSpan(col(R.color.dim)), a, sb.length, 0)
                    sb.append("  ").append(v).append("\n")
                }
                if (st.on && !st.running && st.snapshot != null) sb.append("(last armed period; not armed now)")
                detail.text = sb.trimEnd()
            }
            lifecycleScope.launch {
                repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { ResourceMonitor.state.collect { render(it) } }
            }
        }
        section(right, "Diagnostics").apply {
            addView(note("Share log sends the last 24 hours as a text file: every alert (time, tier, sound, banner text), " +
                "what you tapped and the mutes, source health changes, pre-flight results and resource summaries, with the " +
                "app version and this controller's model, Android version and screen. Drone callsigns, the fleet token and " +
                "server / station addresses are removed; the bound serial is kept."))
            addView(row(button("Share log") { shareLog(copy = false) }, button("Copy", secondary = true) { shareLog(copy = true) }))
        }
        section(right, "App update (GitHub releases)").apply {
            updateInfo = TextView(this@SettingsActivity).apply { textSize = 17f; setTextColor(col(R.color.ink)); setPadding(0, dp(4), 0, dp(4)) }
            addView(updateInfo)
            updateNotes = note(""); updateNotes.maxLines = 14; addView(updateNotes)
            updateStatus = note(""); addView(updateStatus)
            updateInstallBtn = button("Download and install") { startUpdate(this@SettingsActivity, s) }
            addView(row(button("Check for update", secondary = true) { Updater.check(this@SettingsActivity, manual = true) }, updateInstallBtn))
            lifecycleScope.launch {
                repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { Updater.state.collect { renderUpdate() } }
            }
        }

        section(right, "Replay (see it work)").apply {
            addView(note("Plays a real encounter recorded from public ADS-B data (demo drone DEMO-1 vs N388KM, Demo TFR 0/0000) through the live engine, sounds and banners."))
            val speeds = RadioGroup(this@SettingsActivity).apply { orientation = RadioGroup.HORIZONTAL; setOnCheckedChangeListener { _, _ -> scheduleAutoSave() } }
            val r1 = radio("1×", 101); val r4 = radio("4×", 104)
            speeds.addView(r1); speeds.addView(r4)
            speeds.check(if (s.replaySpeed >= 4) 104 else 101)
            addView(speeds)
            val cloud = CheckBox(this@SettingsActivity).apply {
                text = "Public-feed view (N388KM reporting alt \"ground\", no track)"; setTextColor(col(R.color.ink)); textSize = 16f; isChecked = s.replayCloudView
                setOnCheckedChangeListener { _, _ -> scheduleAutoSave() } }
            addView(cloud)
            val crossing = CheckBox(this@SettingsActivity).apply {
                text = "SYNTHETIC crossing variant (N388KM climbing through the drone's altitude: COLLISION RISK)"; setTextColor(col(R.color.ink)); textSize = 16f
                isChecked = s.replayCrossing; setOnCheckedChangeListener { _, _ -> scheduleAutoSave() } }
            addView(crossing)
            savers += { s.replaySpeed = if (speeds.checkedRadioButtonId == 104) 4.0 else 1.0; s.replayCloudView = cloud.isChecked; s.replayCrossing = crossing.isChecked }
            addView(row(
                button("Replay: demo encounter") {
                    dismissKeyboard(); flush()
                    SentryService.send(this@SettingsActivity, SentryService.ACTION_REPLAY) {
                        it.putExtra(SentryService.EXTRA_SPEED, s.replaySpeed); it.putExtra(SentryService.EXTRA_CLOUD_VIEW, s.replayCloudView)
                        it.putExtra(SentryService.EXTRA_CROSSING, s.replayCrossing)
                    }
                    finish()
                },
                button("Stop replay", secondary = true) { SentryService.send(this@SettingsActivity, SentryService.ACTION_REPLAY_STOP) },
            ))
        }

        building = false
    }

    // ── sounds ────────────────────────────────────────────────────────────
    private val player by lazy { SoundPlayer(this) }
    private val soundTitles = HashMap<SoundLevel, TextView>()
    private var picking: SoundLevel? = null
    private val pickSound = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val level = picking ?: return@registerForActivityResult
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            else @Suppress("DEPRECATION") r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (r.resultCode == RESULT_OK && uri != null) {
            s.setSoundUri(level, uri.toString()); player.forget()
            soundTitles[level]?.text = player.title(level, s.soundUri(level))
            SentryBus.log("Sound for ${level.key}: ${uri}")
        }
    }

    /** One level: its sound (Choose… opens the controller's own sound picker), Test, Default, and a volume slider. */
    private fun soundRow(level: SoundLevel): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        box.addView(label(level.label + if (level.alarmStream) "  (alarm volume)" else ""))
        val title = TextView(this).apply { textSize = 16f; setTextColor(col(R.color.ink)); text = player.title(level, s.soundUri(level)) }
        soundTitles[level] = title
        box.addView(title)
        box.addView(row(
            button("Choose…", secondary = true) {
                picking = level
                val cur = player.resolve(level, s.soundUri(level)).uri?.let { Uri.parse(it) }
                val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, if (level.alarmStream) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Sound for: ${level.label}")
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur)
                runCatching { pickSound.launch(i) }.onFailure { toast("No sound picker on this controller") }
            },
            button("Test", secondary = true) {
                player.play(level, com.uasflightdeck.sentry.core.Cue.FULL, s.soundUri(level), s.soundVolume(level))
                if (s.vibrationOn) player.vibrate(level)
            },
            button("Default", secondary = true) { s.setSoundUri(level, ""); player.forget(); title.text = player.title(level, "") },
        ))
        val vol = SeekBar(this).apply {
            max = 100; progress = s.soundVolume(level)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) s.setSoundVolume(level, p) }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }
        box.addView(TextView(this).apply { textSize = 14f; setTextColor(col(R.color.dim)); text = "Volume (of the stream's volume)" })
        box.addView(vol)
        return box
    }

    override fun onDestroy() { super.onDestroy(); runCatching { player.shutdown() } }

    // ── keyboard ──────────────────────────────────────────────────────────
    /** Hide the soft keyboard and take focus off the field (the focusable root takes it). */
    private fun dismissKeyboard(target: View? = currentFocus) {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow((target ?: window.decorView).windowToken, 0)
        if (target is EditText) {
            target.clearFocus()
            if (::rootView.isInitialized && target.rootView === window.decorView) rootView.requestFocus()
        }
    }

    /** A tap outside the focused field closes the keyboard (the tap still reaches whatever it hit). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val f = currentFocus
            if (f is EditText) {
                val x = ev.rawX.toInt(); val y = ev.rawY.toInt()
                fun hit(v: View): Boolean { val r = Rect(); return v.isShown && v.getGlobalVisibleRect(r) && r.contains(x, y) }
                // Tapping another field just moves focus there (no keyboard flicker); anywhere else closes it.
                if (!hit(f) && helpers.keys.none { it !== f && hit(it) } && !(::pinnedField.isInitialized && hit(pinnedField))) dismissKeyboard(f)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { dismissKeyboard(); flush(); @Suppress("DEPRECATION") super.onBackPressed() }
    override fun onSupportNavigateUp(): Boolean { dismissKeyboard(); flush(); finish(); return true }

    override fun onResume() {
        super.onResume()
        if (::batteryStatus.isInitialized) batteryStatus.text = batteryText()
        if (::locStatus.isInitialized) locStatus.text = locText()
        fetchLiveAircraft()
        renderUpdate()
        Updater.check(this)          // rate-limited: at most daily
    }

    // ── update panel ────────────────────────────────────────────────────────
    private fun renderUpdate() {
        if (!::updateInfo.isInitialized) return
        val ui = Updater.state.value
        val latest = Updater.latest(s)
        val avail = Updater.available(s)
        val checked = s.updateLastSuccessMs.takeIf { it > 0 }?.let { DroneHistory.ago(System.currentTimeMillis() - it) }
        val sb = android.text.SpannableStringBuilder()
        sb.append("Installed: ${Updater.current} (build ${BuildConfig.VERSION_CODE})\n")
        sb.append("Latest on GitHub: ")
        val a = sb.length
        sb.append(when {
            latest == null -> "not checked yet"
            avail -> "${latest.version}  UPDATE AVAILABLE"
            latest.version == com.uasflightdeck.sentry.core.SemVer.parse(Updater.current) -> "${latest.version}  up to date"
            else -> "${latest.version}  (this build is newer)"
        })
        sb.setSpan(android.text.style.ForegroundColorSpan(col(if (avail) R.color.caution else if (latest == null) R.color.dim else R.color.ok)), a, sb.length, 0)
        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a, sb.length, 0)
        if (checked != null) sb.append("  (checked $checked)")
        val release = Updater.signedWithReleaseKey(this)
        sb.append("\nSigning: ")
        val b = sb.length
        sb.append(if (release) "release key (GitHub updates install in place)" else "NOT the release key: uninstall once, then install from GitHub")
        sb.setSpan(android.text.style.ForegroundColorSpan(col(if (release) R.color.dim else R.color.caution)), b, sb.length, 0)
        updateInfo.text = sb
        updateNotes.text = latest?.notes?.takeIf { it.isNotBlank() }?.let { "Release notes (${latest.tag}):\n" + it.take(900) } ?: ""
        updateNotes.visibility = if (updateNotes.text.isEmpty()) View.GONE else View.VISIBLE
        // Derived from live state, never a message persisted before an update (it said "0.3.0 is available"
        // right after 0.3.0 was installed). A failed last check is appended as-is.
        val derived = when {
            latest == null -> ""
            avail -> "Sentry ${latest.version} is available"
            com.uasflightdeck.sentry.core.SemVer.isNewer(Updater.current, latest.tag) -> "This build is newer than the latest release"
            else -> "Up to date"
        }
        val lastFailed = s.updateLastAttemptMs > s.updateLastSuccessMs && s.updateLastMessage.isNotEmpty()
        updateStatus.text = when {
            ui.checking || ui.downloading -> ui.message
            ui.message.isNotEmpty() && !ui.message.startsWith("Sentry ") && ui.message != "Up to date" &&
                !ui.message.startsWith("This build") && !lastFailed -> ui.message   // download / install errors
            lastFailed -> listOf(derived, "last check: ${s.updateLastMessage}").filter { it.isNotEmpty() }.joinToString(" · ")
            else -> derived.ifEmpty { s.updateLastMessage }
        }
        updateStatus.setTextColor(col(if (ui.downloading) R.color.advisory else R.color.dim))
        updateInstallBtn.isEnabled = avail && !ui.downloading
        updateInstallBtn.alpha = if (updateInstallBtn.isEnabled) 1f else 0.4f
        updateInstallBtn.text = if (ui.downloading) "Downloading ${ui.progressPct ?: 0}%" else if (avail) "Download and install ${latest?.version}" else "Download and install"
    }

    // ── this controller's aircraft ─────────────────────────────────────────
    private fun updatePinnedNote() {
        if (!::pinnedNote.isInitialized) return
        val v = pinnedField.text.toString().trim()
        val e = DroneHistory.entries(this)
        val cs = e.firstOrNull { it.serial?.trim()?.equals(v, ignoreCase = true) == true }?.callsign
        pinnedNote.setTextColor(col(if (v.isEmpty()) R.color.caution else if (cs != null) R.color.ok else R.color.caution))
        pinnedNote.text = when {
            v.isEmpty() -> "Not pinned: Sentry protects this controller only."
            cs != null -> "Bound to $v · last seen as $cs."
            else -> "Bound to $v · not seen yet: Sentry waits for it and switches the moment it appears."
        }
    }

    /** The aircraft in the latest fleet poll (serial · callsign · model); tapping one pins it. */
    private fun renderFeedList() {
        if (!::feedList.isInitialized) return
        feedList.removeAllViews()
        val pinned = DroneSelector.normaliseSerial(pinnedField.text.toString())
        val list = DroneHistory.inFeedNow.sortedBy { (it.callsign ?: it.name).lowercase() }
        if (list.isEmpty()) {
            val why = DroneHistory.feedStatus.ifEmpty { "not fetched yet" }
            feedList.addView(note("No aircraft in the feed right now ($why)."))
            return
        }
        for (d in list) {
            val serial = d.serial
            val mine = serial != null && DroneSelector.normaliseSerial(serial) == pinned
            val text = listOfNotNull(serial ?: "no serial in feed", d.callsign ?: d.name, d.model).joinToString("  ·  ") + if (mine) "   ✓ pinned" else ""
            val b = button(text, secondary = !mine) {
                if (serial == null) toast("The feed gives no serial for ${d.callsign ?: d.name}: it can't be pinned")
                else {
                    pinnedField.setText(serial); s.pinnedSerial = serial
                    toast("Pinned $serial (${d.callsign ?: d.name}) as this controller's aircraft")
                    renderFeedList()
                }
            }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL; b.letterSpacing = 0f
            if (serial == null) b.alpha = 0.5f
            feedList.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
    }

    // ── drone selection helpers ─────────────────────────────────────────────
    private fun styleField(e: EditText) {
        e.textSize = 18f; e.setTextColor(col(R.color.ink)); e.setHintTextColor(col(R.color.dim))
        e.setBackgroundResource(R.drawable.field_bg); e.setPadding(dp(10), dp(8), dp(10), dp(8)); e.isSingleLine = true
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        doneCloses(e)
        if (e is AutoCompleteTextView) {
            e.threshold = 1
            e.setDropDownBackgroundDrawable(android.graphics.drawable.ColorDrawable(col(R.color.panel2)))
            e.setOnFocusChangeListener { _, has -> if (has && e.adapter?.count ?: 0 > 0) e.post { runCatching { e.showDropDown() } } }
            e.setOnClickListener { if ((e.adapter?.count ?: 0) > 0) e.showDropDown() }
        }
    }

    private fun darkAdapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(col(R.color.ink)); setBackgroundColor(col(R.color.panel2)); textSize = 18f; setPadding(dp(14), dp(12), dp(14), dp(12))
            }
    }

    private fun refreshSuggestions() {
        if (::pinnedField.isInitialized) pinnedField.setAdapter(darkAdapter(DroneHistory.entries(this).mapNotNull { it.serial }.distinct()))
    }

    /** One fetch of the fleet feed when Settings opens (and on "Refresh list"), so the aircraft airborne right now can be pinned without arming. */
    private fun fetchLiveAircraft() {
        val token = s.fleetToken
        if (token.isBlank()) { DroneHistory.feedStatus = FleetStatus.NO_TOKEN; renderFeedList(); return }
        val base = s.workerBase.trimEnd('/')
        lifecycleScope.launch {
            val (drones, status) = withContext(Dispatchers.IO) {
                val http = Http(); val hdr = mapOf("X-Fleet-Token" to token); val now = System.currentTimeMillis()
                val ds = runCatching { Parsers.parseDroneSense(http.get("$base/api/live/dronesense", hdr), now) }
                val fda = runCatching { Parsers.parseOurDrones(http.get("$base/api/live/our-drones", hdr), now).drones }
                fun <T : List<*>> fetch(r: Result<T>) = FleetStatus.Fetch(r.getOrNull()?.size, r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
                val st = FleetStatus.of(false, fetch(fda), fetch(ds))
                ((ds.getOrNull().orEmpty() + fda.getOrNull().orEmpty()) to st)
            }
            DroneHistory.feedStatus = status.detail
            if (status.ok) DroneHistory.observeLive(this@SettingsActivity, drones, System.currentTimeMillis())
            refreshSuggestions(); updatePinnedNote(); renderFeedList()
        }
    }

    // ── cylinders ─────────────────────────────────────────────────────────
    private fun renderCylinders() {
        cylinderList.removeAllViews()
        val list = s.cylinders
        if (list.isEmpty()) cylinderList.addView(note("No cylinders: with no drone selected, nothing is protected."))
        list.forEachIndexed { i, c ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            r.addView(TextView(this).apply {
                text = (if (c.enabled) "◉ " else "○ OFF · ") + c.describe(); textSize = 17f
                setTextColor(col(if (c.enabled) R.color.ink else R.color.dim))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(button("Edit", secondary = true) { editCylinder(i) })
            cylinderList.addView(r)
        }
    }

    /** The cylinder editor. [index] null = new. Radius in nm or ft; limits in ft above the controller or ft MSL. */
    private fun editCylinder(index: Int?) {
        val list = s.cylinders.toMutableList()
        val c = index?.let { list[it] } ?: Cylinder("c" + System.currentTimeMillis().toString(36), "cylinder ${list.size + 1}", 1.0, 0.0, 1500.0)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = box.field("Name (spoken: \"Traffic entering <name>\")", c.name, autoSave = false)
        val useFt = c.radiusNm < 1.0 && c.radiusNm * Units.FT_PER_NM % 100 == 0.0
        val radius = box.num("Radius", if (useFt) c.radiusNm * Units.FT_PER_NM else c.radiusNm,
            if (useFt) FieldRules.CYL_RADIUS_FT else FieldRules.CYL_RADIUS_NM, autoSave = false)
        val unit = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        unit.addView(radio("nm", 301)); unit.addView(radio("ft", 302)); unit.check(if (useFt) 302 else 301); box.addView(unit)
        unit.setOnCheckedChangeListener { _, id -> specs[radius] = if (id == 302) FieldRules.CYL_RADIUS_FT else FieldRules.CYL_RADIUS_NM; validate(radius) }
        val floor = box.num("Floor (ft; 0 = SFC, the surface)", c.floorFt, FieldRules.CYL_ALT, autoSave = false)
        val ceil = box.num("Ceiling (ft)", c.ceilingFt, FieldRules.CYL_ALT, autoSave = false)
        val ref = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        ref.addView(radio("ft above controller", 311)); ref.addView(radio("ft MSL", 312))
        ref.check(if (c.ref == CylinderAltRef.MSL) 312 else 311); box.addView(ref)
        val en = box.switch("Enabled", c.enabled)
        val boxFields = listOf(name, radius, floor, ceil)
        val scroll = ScrollView(this).apply { addView(box) }
        val dlg = AlertDialog.Builder(this, R.style.Sentry_Dialog)
            .setTitle(if (index == null) "New cylinder" else "Edit cylinder")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (index != null) setNeutralButton("Delete") { _, _ -> list.removeAt(index); s.cylinders = list; renderCylinders() } }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rv = radius.valid(); val fv = floor.valid(); val cv = ceil.valid()
                if (rv == null || fv == null || cv == null) { toast("Fix the fields outlined in red"); return@setOnClickListener }
                val rNm = rv?.let { if (unit.checkedRadioButtonId == 302) it / Units.FT_PER_NM else it }
                val out = c.copy(name = name.text.toString().trim(), radiusNm = rNm ?: 0.0, floorFt = fv, ceilingFt = cv ?: 0.0,
                    ref = if (ref.checkedRadioButtonId == 312) CylinderAltRef.MSL else CylinderAltRef.AGL_CONTROLLER, enabled = en.isChecked)
                if (!out.valid()) { toast("Needs a name, a radius > 0, and a ceiling above the floor"); return@setOnClickListener }
                if (index == null) list += out else list[index] = out
                s.cylinders = list; renderCylinders(); dlg.dismiss()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    askLoc.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        dlg.setOnDismissListener { boxFields.forEach { helpers.remove(it); specs.remove(it); validators.remove(it) } }
        dlg.show()
    }

    private fun locText(): String {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (ok) "Location permission: granted (controller GPS available)" else "Location permission: NOT granted — the controller cannot be protected"
    }
    override fun onPause() { super.onPause(); dismissKeyboard(); flush() }

    private fun saveAll() = savers.forEach { runCatching { it() } }

    private fun geofenceText(): String {
        val f = File(filesDir, "geofences.geojson")
        if (!f.exists()) return "No GeoJSON geofence imported."
        val n = runCatching { Parsers.parseGeofences(f.readText(), s.geofenceFileName).size }.getOrDefault(0)
        return "Imported: ${s.geofenceFileName} — $n polygon zone(s)."
    }

    private fun importGeofence(uri: Uri) {
        val text = runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrElse { toast("Could not read file"); return }
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "imported"
        val zones = runCatching { Parsers.parseGeofences(text, name) }.getOrDefault(emptyList())
        if (zones.isEmpty()) { toast("No Polygon / MultiPolygon found in that file"); return }
        File(filesDir, "geofences.geojson").writeText(text)
        s.geofenceFileName = name
        geofenceStatus.text = geofenceText()
        toast("Imported ${zones.size} zone(s)")
    }

    private fun batteryText(): String {
        val ok = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        return if (ok) "Battery optimisation: EXEMPT (good)" else "Battery optimisation: NOT exempt — Android may throttle Sentry with the screen off"
    }

    @SuppressLint("BatteryLife")
    private fun requestBattery() {
        runCatching { startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            .onFailure { runCatching { startActivity(Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    /** Diagnostics → Share log / Copy: build off the UI thread, then the share sheet (or the clipboard). */
    private fun shareLog(copy: Boolean) {
        lifecycleScope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { LogStore.build(this@SettingsActivity) } }
                .getOrElse { toast("Log unavailable: ${it.message}"); return@launch }
            if (copy) {
                // The clipboard goes through a binder transaction: keep the newest ~200 KB.
                val clip = if (text.length > 200_000) "…(older lines cut for the clipboard; use Share log for all of it)\n" + text.takeLast(200_000) else text
                getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Sentry log", clip))
                toast("Log copied (${text.lines().size} lines)")
                return@launch
            }
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val name = "sentry-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(java.util.Date()) + ".txt"
            val f = File(dir, name).apply { writeText(text) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this@SettingsActivity, "$packageName.logs", f)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Flight Deck Sentry log ${name.removePrefix("sentry-log-").removeSuffix(".txt")}")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(Intent.createChooser(send, "Share Sentry log")) }
                .onFailure { toast("No app to share with on this controller: use Copy") }
        }
    }

    // ── tiny view helpers ─────────────────────────────────────────────────
    private fun section(parent: LinearLayout, title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.panel_bg); setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        box.addView(TextView(this).apply { text = title.uppercase(Locale.US); textSize = 15f; letterSpacing = 0.06f; setTextColor(col(R.color.advisory)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        parent.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        return box
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 15f; setTextColor(col(R.color.dim)); setPadding(0, dp(8), 0, dp(2)) }
    private fun note(t: String) = TextView(this).apply { text = t; textSize = 15f; setTextColor(col(R.color.dim)); setPadding(0, dp(4), 0, dp(4)) }

    /** Single-line field: Enter/Done saves now, closes the keyboard and drops focus. */
    private fun doneCloses(e: EditText) {
        e.isSingleLine = true
        e.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        e.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL || enter) {
                if (event == null || event.action == android.view.KeyEvent.ACTION_DOWN) { validators[e]?.invoke(); dismissKeyboard(e); flush() }
                true
            } else false
        }
    }

    /**
     * A labelled single-line field with an (initially hidden) error line under it. [autoSave] false for the
     * cylinder dialog, whose values are applied by its own Save button.
     */
    private fun LinearLayout.field(lbl: String, value: String, hint: String = "", password: Boolean = false, autoSave: Boolean = true): EditText {
        addView(label(lbl))
        val e = EditText(this@SettingsActivity).apply {
            setText(value); this.hint = hint; textSize = 18f; setTextColor(col(R.color.ink)); setHintTextColor(col(R.color.dim))
            setBackgroundResource(R.drawable.field_bg); setPadding(dp(10), dp(8), dp(10), dp(8))
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        doneCloses(e)
        addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        val helper = TextView(this@SettingsActivity).apply { textSize = 15f; setTextColor(col(R.color.warning)); setPadding(dp(2), dp(2), 0, 0); visibility = View.GONE }
        addView(helper)
        helpers[e] = helper
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(x: android.text.Editable?) { validators[e]?.invoke(); if (autoSave) scheduleAutoSave() }
        })
        return e
    }

    /** A numeric field validated live against [spec]: red outline + the range while the text isn't valid. */
    private fun LinearLayout.num(lbl: String, v: Double, spec: FieldRules.NumSpec, autoSave: Boolean = true): EditText {
        val e = field(lbl, if (v.isFinite()) FieldRules.fmt(v) else "", hint = spec.rangeText, autoSave = autoSave)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or (if (spec.signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
        specs[e] = spec
        validators[e] = { validate(e) }
        validate(e)
        return e
    }

    private fun validate(e: EditText) {
        val spec = specs[e] ?: return
        e.showError((FieldRules.parseNumber(e.text.toString(), spec) as? FieldRules.Parsed.Bad)?.why)
    }

    /** The value to store for a numeric field: the typed one if valid, else [last] (the stored value, unchanged). */
    private fun EditText.valueOr(last: Double): Double = FieldRules.keepLastValid(text.toString(), specs.getValue(this), last)
    /** The typed value if valid, else null (cylinder dialog). */
    private fun EditText.valid(): Double? = (FieldRules.parseNumber(text.toString(), specs.getValue(this)) as? FieldRules.Parsed.Ok)?.value

    private fun EditText.showError(msg: String?) {
        val h = helpers[this] ?: return
        val l = paddingLeft; val t = paddingTop; val r = paddingRight; val b = paddingBottom
        background = if (msg == null) ContextCompat.getDrawable(this@SettingsActivity, R.drawable.field_bg) else GradientDrawable().apply {
            setColor(col(R.color.panel2)); setStroke(dp(2), col(R.color.warning)); cornerRadius = dp(8).toFloat()
        }
        setPadding(l, t, r, b)
        h.text = msg ?: ""; h.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun LinearLayout.switch(lbl: String, v: Boolean): SwitchCompat {
        val sw = SwitchCompat(this@SettingsActivity).apply { text = lbl; isChecked = v; textSize = 17f; setTextColor(col(R.color.ink)); minHeight = dp(48)
            setOnCheckedChangeListener { _, _ -> scheduleAutoSave() } }
        addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sw
    }

    private fun radio(t: String, id: Int) = RadioButton(this).apply { text = t; this.id = id; textSize = 17f; setTextColor(col(R.color.ink)); minHeight = dp(48) }

    private fun button(t: String, secondary: Boolean = false, onClick: () -> Unit) = MaterialButton(this).apply {
        text = t; textSize = 16f; isAllCaps = false; minHeight = dp(52); cornerRadius = dp(10)
        backgroundTintList = android.content.res.ColorStateList.valueOf(col(if (secondary) R.color.panel2 else R.color.advisory))
        setTextColor(col(if (secondary) R.color.ink else R.color.bg))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, x -> addView(x, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(8) }) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    companion object { const val AUTOSAVE_MS = 400L }
}
