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
import com.uasflightdeck.sentry.core.Parsers
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

    private val pickGeo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importGeofence(it) } }
    private val askLoc = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

        section(left, "Fleet feed (drone position)").apply {
            val token = field("Fleet token (X-Fleet-Token)", if (s.fleetTokenIsDefault) "" else s.fleetToken,
                hint = if (s.fleetTokenIsDefault) "using token built into this APK" else "paste token", password = true)
            addView(button("Paste token from clipboard", secondary = true) {
                val cm = getSystemService(ClipboardManager::class.java)
                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this@SettingsActivity)?.toString()?.trim()
                if (t.isNullOrEmpty()) toast("Clipboard is empty") else { token.setText(t); toast("Token pasted — tap Save") }
            })
            val filter = field("Drone callsign / id (blank = auto)", s.droneFilter, hint = "e.g. DEMO-1")
            val base = field("Worker base URL", s.workerBase)
            savers += { if (token.text.isNotBlank() || !s.fleetTokenIsDefault) s.fleetToken = token.text.toString(); s.droneFilter = filter.text.toString(); s.workerBase = base.text.toString() }
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

        section(right, "Manual position (fallback when the feed is down)").apply {
            addView(note("Used ONLY when the fleet feed has no fresh position. Sentry says \"using manual position\" when it switches."))
            val g = RadioGroup(this@SettingsActivity).apply { orientation = RadioGroup.HORIZONTAL }
            g.addView(radio("Off", 201)); g.addView(radio("Pinned", 202)); g.addView(radio("This controller's GPS", 203))
            g.check(when (s.manualMode) { ManualMode.OFF -> 201; ManualMode.PINNED -> 202; ManualMode.DEVICE_GPS -> 203 })
            addView(g)
            val lat = num("Latitude", s.manualLat, signed = true)
            val lon = num("Longitude", s.manualLon, signed = true)
            val alt = num("Drone altitude MSL (ft)", s.manualAltMslFt)
            val agl = num("GPS mode: drone height above controller (ft)", s.gpsAglFt)
            savers += {
                s.manualMode = when (g.checkedRadioButtonId) { 202 -> ManualMode.PINNED; 203 -> ManualMode.DEVICE_GPS; else -> ManualMode.OFF }
                s.manualLat = lat.d() ?: Double.NaN; s.manualLon = lon.d() ?: Double.NaN; s.manualAltMslFt = alt.d() ?: Double.NaN
                agl.d()?.let { s.gpsAglFt = it }
                if (s.manualMode == ManualMode.DEVICE_GPS && ContextCompat.checkSelfPermission(this@SettingsActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    askLoc.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
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

    override fun onResume() { super.onResume(); if (::batteryStatus.isInitialized) batteryStatus.text = batteryText() }
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
