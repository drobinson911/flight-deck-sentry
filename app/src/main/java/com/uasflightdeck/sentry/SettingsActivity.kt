package com.uasflightdeck.sentry

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.MultiAutoCompleteTextView
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
import com.uasflightdeck.sentry.core.CallsignPattern
import com.uasflightdeck.sentry.core.Cylinder
import com.uasflightdeck.sentry.core.CylinderAltRef
import com.uasflightdeck.sentry.core.Parsers
import com.uasflightdeck.sentry.core.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * One scrolling page, two columns on the landscape tablet. Values are saved
 * on "Save" and when leaving the screen; the service picks them up on its
 * next 1 s tick.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var s: Settings
    private val savers = ArrayList<() -> Unit>()
    private lateinit var geofenceStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var patternField: AutoCompleteTextView
    private lateinit var pinnedField: AutoCompleteTextView
    private lateinit var pinnedNote: TextView
    private lateinit var updateInfo: TextView
    private lateinit var updateNotes: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateInstallBtn: MaterialButton
    private lateinit var serialField: MultiAutoCompleteTextView
    private lateinit var patternPreview: TextView
    private lateinit var protectSwitch: SwitchCompat
    private lateinit var cylinderList: LinearLayout
    private lateinit var locStatus: TextView

    private val pickGeo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importGeofence(it) } }
    private val askLoc = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (::locStatus.isInitialized) locStatus.text = locText() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        // header bar
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        header.addView(TextView(this).apply { text = "Settings"; textSize = 28f; setTextColor(col(R.color.ink)); setTypeface(typeface, android.graphics.Typeface.BOLD) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("Back", secondary = true) { saveAll(); finish() })
        header.addView(button("Save") { saveAll(); toast("Saved") }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8) })
        root.addView(header)

        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), 0, dp(10), dp(10)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cols.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        root.addView(ScrollView(this).apply { addView(cols) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // ── LEFT column ────────────────────────────────────────────────────
        section(left, "Replay (see it work)").apply {
            addView(note("Plays a real encounter recorded from public ADS-B data (DEMO-1 vs N388KM, TFR 0/0000) through the live engine, voice and notifications."))
            val speeds = RadioGroup(this@SettingsActivity).apply { orientation = RadioGroup.HORIZONTAL }
            val r1 = radio("1×", 101); val r4 = radio("4×", 104)
            speeds.addView(r1); speeds.addView(r4)
            speeds.check(if (s.replaySpeed >= 4) 104 else 101)
            addView(speeds)
            val cloud = CheckBox(this@SettingsActivity).apply {
                text = "Public-feed view (N388KM reporting alt \"ground\", no track)"; setTextColor(col(R.color.ink)); textSize = 16f; isChecked = s.replayCloudView }
            addView(cloud)
            savers += { s.replaySpeed = if (speeds.checkedRadioButtonId == 104) 4.0 else 1.0; s.replayCloudView = cloud.isChecked }
            addView(row(
                button("Replay: demo encounter") {
                    saveAll()
                    SentryService.send(this@SettingsActivity, SentryService.ACTION_REPLAY) {
                        it.putExtra(SentryService.EXTRA_SPEED, s.replaySpeed); it.putExtra(SentryService.EXTRA_CLOUD_VIEW, s.replayCloudView)
                    }
                    finish()
                },
                button("Stop replay", secondary = true) { SentryService.send(this@SettingsActivity, SentryService.ACTION_REPLAY_STOP) },
            ))
        }

        section(left, "Drone selection").apply {
            addView(label("This controller's aircraft (serial)"))
            pinnedField = AutoCompleteTextView(this@SettingsActivity).apply { styleField(this); setText(s.pinnedSerial); hint = "type it once: Sentry watches this airframe first, forever" }
            addView(pinnedField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            pinnedNote = note(""); addView(pinnedNote)
            addView(row(
                button("Use the drone I'm watching now", secondary = true) { useWatchedDrone() },
                button("Clear", secondary = true) { pinnedField.setText(""); s.pinnedSerial = ""; updatePinnedNote() },
            ))
            pinnedField.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) = updatePinnedNote()
            })
            savers += { s.pinnedSerial = pinnedField.text.toString() }
            addView(label("My callsign pattern"))
            patternField = AutoCompleteTextView(this@SettingsActivity).apply { styleField(this); setText(s.callsignPattern); hint = "e.g. DEMO-# Pilot" }
            addView(patternField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            patternPreview = note(""); addView(patternPreview)
            addView(note("# = any digit · * = anything · spaces and hyphens are optional · start with re: for a regex. Suggestions are callsigns Sentry has seen."))
            addView(label("My aircraft serials (comma or newline separated)"))
            serialField = MultiAutoCompleteTextView(this@SettingsActivity).apply {
                styleField(this); setText(s.serials); hint = "used when no callsign matches"; setTokenizer(MultiAutoCompleteTextView.CommaTokenizer())
            }
            addView(serialField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            protectSwitch = switch("Protect this controller (ignore drones)", s.protectController)
            addView(row(button("Pick a drone…", secondary = true) {
                saveAll(); DronePicker.show(this@SettingsActivity, s) { patternField.setText(s.callsignPattern); protectSwitch.isChecked = s.protectController; updatePreview() }
            }))
            patternField.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) = updatePreview()
            })
            savers += { s.callsignPattern = patternField.text.toString(); s.serials = serialField.text.toString(); s.protectController = protectSwitch.isChecked }
            refreshSuggestions()
            updatePreview()
            updatePinnedNote()
        }

        section(left, "Fleet feed (drone position)").apply {
            val token = field("Fleet token (X-Fleet-Token)", if (s.fleetTokenIsDefault) "" else s.fleetToken,
                hint = if (s.fleetTokenIsDefault) "using token built into this APK" else "paste token", password = true)
            addView(button("Paste token from clipboard", secondary = true) {
                val cm = getSystemService(ClipboardManager::class.java)
                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this@SettingsActivity)?.toString()?.trim()
                if (t.isNullOrEmpty()) toast("Clipboard is empty") else { token.setText(t); toast("Token pasted — tap Save") }
            })
            val base = field("Worker base URL", s.workerBase)
            savers += { if (token.text.isNotBlank() || !s.fleetTokenIsDefault) s.fleetToken = token.text.toString(); s.workerBase = base.text.toString() }
        }

        section(left, "Traffic sources (all run together)").apply {
            val st = switch("Truck station (Overwatch) over Wi-Fi", s.stationEnabled)
            val url = field("Station address", s.stationUrl, hint = "192.168.1.20 or http://host:8080")
            val auto = switch("Auto-discover station (Overwatch LAN beacon)", s.stationAutoDiscover)
            val cloud = switch("Cloud ADS-B (uas-app worker)", s.cloudEnabled)
            val radius = num("Traffic radius (nm)", s.trafficRadiusNm)
            savers += { s.stationEnabled = st.isChecked; s.stationUrl = url.text.toString(); s.stationAutoDiscover = auto.isChecked
                s.cloudEnabled = cloud.isChecked; radius.d()?.let { s.trafficRadiusNm = it } }
        }

        section(left, "Voice").apply {
            val v = switch("Voice callouts", s.voiceOn)
            addView(label("Volume"))
            val vol = SeekBar(this@SettingsActivity).apply { max = 100; progress = (s.volume * 100).toInt() }
            addView(vol)
            savers += { s.voiceOn = v.isChecked; s.volume = vol.progress / 100.0 }
            addView(button("Test callout", secondary = true) { saveAll(); SentryService.send(this@SettingsActivity, SentryService.ACTION_TEST) })
        }

        // ── RIGHT column ───────────────────────────────────────────────────
        section(right, "App update (GitHub releases)").apply {
            updateInfo = TextView(this@SettingsActivity).apply { textSize = 17f; setTextColor(col(R.color.ink)); setPadding(0, dp(4), 0, dp(4)) }
            addView(updateInfo)
            updateNotes = note(""); updateNotes.maxLines = 8; addView(updateNotes)
            updateStatus = note(""); addView(updateStatus)
            updateInstallBtn = button("Download and install") { startUpdate(this@SettingsActivity, s) }
            addView(row(button("Check for update", secondary = true) { Updater.check(this@SettingsActivity, manual = true) }, updateInstallBtn))
            lifecycleScope.launch {
                repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { Updater.state.collect { renderUpdate() } }
            }
        }

        section(right, "Alert rings around the drone").apply {
            val a = num("Advisory ring (nm)", s.advisoryNm)
            val c = num("Caution ring (nm)", s.cautionNm)
            val w = num("Warning ring (nm)", s.warningNm)
            val band = num("Vertical band ± (ft)", s.verticalBandFt)
            val baro = num("Baro correction when no GPS altitude (ft, \"estimated\")", s.baroCorrectionFt)
            val cpa = num("Predictive look-ahead (s)", s.cpaHorizonSec)
            val tfr = num("Watch TFRs within (nm of drone)", s.tfrRelevanceNm)
            savers += {
                val av = a.d(); val cv = c.d(); val wv = w.d()
                if (av != null && cv != null && wv != null && wv > 0 && cv >= wv && av >= cv) { s.advisoryNm = av; s.cautionNm = cv; s.warningNm = wv }
                else toast("Rings must be advisory ≥ caution ≥ warning > 0 — kept previous")
                band.d()?.let { s.verticalBandFt = it }; baro.d()?.let { s.baroCorrectionFt = it }
                cpa.d()?.let { s.cpaHorizonSec = it }; tfr.d()?.let { s.tfrRelevanceNm = it }
            }
        }

        section(right, "Controller protection (no drone selected)").apply {
            addView(note("When no drone is selected (no callsign or serial match, the drone's position lost for 30 s, or \"Protect this controller\"), Sentry protects these cylinders centred on this controller's GPS. It calls aircraft entering them, and warns early when one is predicted to pass within the warning ring of the controller."))
            cylinderList = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(cylinderList)
            renderCylinders()
            addView(row(button("Add cylinder", secondary = true) { editCylinder(null) }))
            val elev = num("Controller elevation override (ft MSL, blank = GPS)", s.controllerElevFt)
            savers += { s.controllerElevFt = elev.d() ?: Double.NaN }
            locStatus = note(locText()); addView(locStatus)
            addView(row(button("Allow location…", secondary = true) { askLoc.launch(Manifest.permission.ACCESS_FINE_LOCATION) }))
        }

        section(right, "Geofences").apply {
            val ce = switch("Circle geofence", s.circleEnabled)
            val onDrone = switch("Centre on the drone (off = fixed point below)", s.circleOnDrone)
            val clat = num("Circle centre latitude", s.circleLat, signed = true)
            val clon = num("Circle centre longitude", s.circleLon, signed = true)
            val cr = num("Circle radius (nm)", s.circleRadiusNm)
            savers += { s.circleEnabled = ce.isChecked; s.circleOnDrone = onDrone.isChecked
                s.circleLat = clat.d() ?: Double.NaN; s.circleLon = clon.d() ?: Double.NaN; cr.d()?.let { s.circleRadiusNm = it } }
            geofenceStatus = note(geofenceText()); addView(geofenceStatus)
            addView(row(
                button("Import GeoJSON…", secondary = true) { pickGeo.launch(arrayOf("application/json", "application/geo+json", "application/octet-stream", "*/*")) },
                button("Remove file", secondary = true) { File(filesDir, "geofences.geojson").delete(); s.geofenceFileName = ""; geofenceStatus.text = geofenceText() },
            ))
        }

        section(right, "Background").apply {
            val boot = switch("Re-arm automatically after reboot (if armed)", s.autoStartOnBoot)
            savers += { s.autoStartOnBoot = boot.isChecked }
            batteryStatus = note(batteryText()); addView(batteryStatus)
            addView(button("Battery optimisation exemption…", secondary = true) { requestBattery() })
        }
    }

    override fun onResume() {
        super.onResume()
        if (::batteryStatus.isInitialized) batteryStatus.text = batteryText()
        if (::locStatus.isInitialized) locStatus.text = locText()
        fetchLiveCallsigns()
        renderUpdate()
        Updater.check(this)          // rate-limited: at most daily
    }

    // ── update panel ────────────────────────────────────────────────────────
    private fun renderUpdate() {
        if (!::updateInfo.isInitialized) return
        val ui = Updater.state.value
        val latest = Updater.latest(s)
        val avail = Updater.available(s)
        val checked = s.updateLastSuccessMs.takeIf { it > 0 }?.let { DronePicker.ago(System.currentTimeMillis() - it) }
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
        updateStatus.text = ui.message.ifEmpty { s.updateLastMessage }
        updateStatus.setTextColor(col(if (ui.downloading) R.color.advisory else R.color.dim))
        updateInstallBtn.isEnabled = avail && !ui.downloading
        updateInstallBtn.alpha = if (updateInstallBtn.isEnabled) 1f else 0.4f
        updateInstallBtn.text = if (ui.downloading) "Downloading ${ui.progressPct ?: 0}%" else if (avail) "Download and install ${latest?.version}" else "Download and install"
    }

    // ── pinned serial ───────────────────────────────────────────────────────
    private fun useWatchedDrone() {
        val st = SentryBus.state.value
        val o = st.ownship
        when {
            st.mode == Mode.OFF -> toast("Sentry isn't armed: arm it (or run the replay) so it is watching a drone")
            o == null || st.selectionMode == com.uasflightdeck.sentry.core.SelectionMode.CONTROLLER -> toast("Not watching a drone right now")
            o.serial.isNullOrBlank() -> toast("The feed gives no serial for ${o.callsign ?: o.name}")
            else -> {
                pinnedField.setText(o.serial); s.pinnedSerial = o.serial!!
                toast("Pinned ${o.serial} (${o.callsign ?: o.name}) as this controller's aircraft")
            }
        }
        updatePinnedNote()
    }

    private fun updatePinnedNote() {
        if (!::pinnedNote.isInitialized) return
        val v = pinnedField.text.toString().trim()
        val e = DroneHistory.entries(this)
        val cs = e.firstOrNull { it.serial?.trim()?.equals(v, ignoreCase = true) == true }?.callsign
        pinnedNote.setTextColor(col(if (v.isEmpty()) R.color.dim else if (cs != null) R.color.ok else R.color.caution))
        pinnedNote.text = when {
            v.isEmpty() -> "Not set. When set, this airframe is watched first (airborne, else on the pad), even if the callsign pattern matches another drone."
            cs != null -> "Pinned: $v · last seen as $cs. Watched first whenever it is in the feed."
            else -> "Pinned: $v · not seen yet. Sentry keeps looking for it and switches the moment it appears."
        }
    }

    // ── drone selection helpers ─────────────────────────────────────────────
    private fun styleField(e: EditText) {
        e.textSize = 18f; e.setTextColor(col(R.color.ink)); e.setHintTextColor(col(R.color.dim))
        e.setBackgroundResource(R.drawable.field_bg); e.setPadding(dp(10), dp(8), dp(10), dp(8)); e.isSingleLine = true
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
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
        val e = DroneHistory.entries(this)
        patternField.setAdapter(darkAdapter(e.map { it.callsign }))
        serialField.setAdapter(darkAdapter(e.mapNotNull { it.serial }.distinct()))
        if (::pinnedField.isInitialized) pinnedField.setAdapter(darkAdapter(e.mapNotNull { it.serial }.distinct()))
    }

    /** Live preview of what the typed pattern matches among the callsigns Sentry knows. */
    private fun updatePreview() {
        if (!::patternPreview.isInitialized) return
        val p = CallsignPattern.compile(patternField.text.toString())
        val known = DroneHistory.entries(this).map { it.callsign }
        val hits = known.filter { p.matches(it) }
        patternPreview.setTextColor(col(if (p.invalid) R.color.warning else if (hits.isEmpty()) R.color.dim else R.color.ok))
        patternPreview.text = when {
            p.invalid -> "Not a valid regular expression"
            p.isBlank -> "No pattern: Sentry protects this controller unless a serial matches"
            hits.isEmpty() -> "Matches none of the ${known.size} callsign(s) seen so far"
            else -> "Matches: " + hits.take(6).joinToString(", ") + if (hits.size > 6) " …" else ""
        }
    }

    /** One fetch of the live fleet feed when Settings opens, so callsigns airborne right now are suggested even if Sentry is not armed. */
    private fun fetchLiveCallsigns() {
        val token = s.fleetToken.takeIf { it.isNotBlank() } ?: return
        val base = s.workerBase.trimEnd('/')
        lifecycleScope.launch {
            val drones = withContext(Dispatchers.IO) {
                val http = Http(); val hdr = mapOf("X-Fleet-Token" to token); val now = System.currentTimeMillis()
                runCatching { Parsers.parseDroneSense(http.get("$base/api/live/dronesense", hdr), now) }.getOrDefault(emptyList()) +
                    runCatching { Parsers.parseOurDrones(http.get("$base/api/live/our-drones", hdr), now).drones }.getOrDefault(emptyList())
            }
            if (drones.isNotEmpty()) DroneHistory.observeLive(this@SettingsActivity, drones, System.currentTimeMillis())
            if (::patternField.isInitialized) { refreshSuggestions(); updatePreview(); updatePinnedNote() }
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
        val name = box.field("Name (spoken: \"Traffic entering <name>\")", c.name)
        val useFt = c.radiusNm < 1.0 && c.radiusNm * Units.FT_PER_NM % 100 == 0.0
        val radius = box.num("Radius", if (useFt) c.radiusNm * Units.FT_PER_NM else c.radiusNm)
        val unit = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        unit.addView(radio("nm", 301)); unit.addView(radio("ft", 302)); unit.check(if (useFt) 302 else 301); box.addView(unit)
        val floor = box.num("Floor (ft)", c.floorFt)
        val ceil = box.num("Ceiling (ft)", c.ceilingFt)
        val ref = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        ref.addView(radio("ft above controller", 311)); ref.addView(radio("ft MSL", 312))
        ref.check(if (c.ref == CylinderAltRef.MSL) 312 else 311); box.addView(ref)
        val en = box.switch("Enabled", c.enabled)
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
                val rv = radius.d(); val fv = floor.d() ?: 0.0; val cv = ceil.d()
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
        dlg.show()
    }

    private fun locText(): String {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (ok) "Location permission: granted (controller GPS available)" else "Location permission: NOT granted — the controller cannot be protected"
    }
    override fun onPause() { super.onPause(); saveAll() }

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

    private fun LinearLayout.field(lbl: String, value: String, hint: String = "", password: Boolean = false): EditText {
        addView(label(lbl))
        val e = EditText(this@SettingsActivity).apply {
            setText(value); this.hint = hint; textSize = 18f; setTextColor(col(R.color.ink)); setHintTextColor(col(R.color.dim))
            setBackgroundResource(R.drawable.field_bg); setPadding(dp(10), dp(8), dp(10), dp(8)); isSingleLine = true
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        return e
    }

    private fun LinearLayout.num(lbl: String, v: Double, signed: Boolean = false): EditText {
        val e = field(lbl, if (v.isFinite()) trimNum(v) else "")
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
        return e
    }

    private fun trimNum(v: Double) = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e7) v.toLong().toString()
        else String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
    private fun EditText.d(): Double? = text.toString().trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun LinearLayout.switch(lbl: String, v: Boolean): SwitchCompat {
        val sw = SwitchCompat(this@SettingsActivity).apply { text = lbl; isChecked = v; textSize = 17f; setTextColor(col(R.color.ink)); minHeight = dp(48) }
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
}
