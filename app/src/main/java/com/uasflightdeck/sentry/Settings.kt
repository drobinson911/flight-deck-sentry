package com.uasflightdeck.sentry

import android.content.Context
import android.content.SharedPreferences
import com.uasflightdeck.sentry.core.Cylinder
import com.uasflightdeck.sentry.core.DroneSelector
import com.uasflightdeck.sentry.core.SentryConfig

/**
 * All user settings, in plain SharedPreferences (small, synchronous, survives
 * process death; the service re-reads it every tick so a change applies within
 * a second without restarting anything).
 */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("sentry", Context.MODE_PRIVATE)

    // fleet / ownship
    var fleetToken: String
        get() = p.getString("fleetToken", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.FLEET_TOKEN
        set(v) = p.edit().putString("fleetToken", v.trim()).apply()
    val fleetTokenIsDefault get() = p.getString("fleetToken", null).isNullOrBlank() && BuildConfig.FLEET_TOKEN.isNotBlank()
    /**
     * "This controller's aircraft": the airframe serial this controller is bound to, kept until the pilot
     * clears it. The ONLY drone Sentry will ever watch (v0.3.3); blank = protect the controller only.
     * (The pre-0.3.3 callsignPattern / serials / protectController prefs are no longer read.)
     */
    var pinnedSerial by str("pinnedSerial", "")
    var workerBase by str("workerBase", "https://uas-app.drobinson911.workers.dev")

    // traffic
    var stationEnabled by bool("stationEnabled", false)
    var stationUrl by str("stationUrl", "")
    var stationAutoDiscover by bool("stationAutoDiscover", true)
    var cloudEnabled by bool("cloudEnabled", true)
    var trafficRadiusNm by dbl("trafficRadiusNm", 30.0)

    // rings / engine
    var advisoryNm by dbl("advisoryNm", 3.0)
    var cautionNm by dbl("cautionNm", 1.0)
    var warningNm by dbl("warningNm", 0.5)
    /** Protected volume: surface up to this far above the drone (v0.3.3; replaces the ± band "verticalBandFt"). */
    var ceilingAboveFt by dbl("ceilingAboveFt", 2000.0)
    var baroCorrectionFt by dbl("baroCorrectionFt", 300.0)
    var cpaHorizonSec by dbl("cpaHorizonSec", 60.0)
    var tfrRelevanceNm by dbl("tfrRelevanceNm", 10.0)

    // targets SHOWN (list + compass); alerts are unaffected
    var targetsAircraftNm by dbl("targetsAircraftNm", 10.0)
    var targetsControllerNm by dbl("targetsControllerNm", 15.0)
    var targetsCeilingFt by dbl("targetsCeilingFt", 18_000.0)
    fun targetDisplay() = com.uasflightdeck.sentry.core.TargetDisplay.Config(
        aroundAircraftNm = targetsAircraftNm.takeIf { it > 0 } ?: 10.0,
        aroundControllerNm = targetsControllerNm.takeIf { it > 0 } ?: 15.0,
        ceilingFt = targetsCeilingFt.takeIf { it > 0 } ?: 18_000.0,
    )

    // voice
    var voiceOn by bool("voiceOn", true)
    var volume by dbl("volume", 1.0)
    /** Debug builds / tests: skip TTS and use the bundled voice, as on the DJI RC Plus (no TTS engine). */
    var voiceForceBundled by bool("voiceForceBundled", false)

    // controller protection (when this controller's aircraft is not in the feed, or none is pinned)
    var cylinders: List<Cylinder>
        get() = p.getString("cylinders", null)?.let { Cylinder.fromJson(it) } ?: Cylinder.DEFAULTS
        set(v) = p.edit().putString("cylinders", Cylinder.toJson(v)).apply()
    /** Controller elevation override, ft MSL (NaN = use GPS). */
    var controllerElevFt by dbl("controllerElevFt", Double.NaN)

    // serial / callsign history for the serial autocomplete
    var knownDronesJson by str("knownDrones", "")

    // geofences
    var circleEnabled by bool("circleEnabled", false)
    var circleOnDrone by bool("circleOnDrone", true)
    var circleLat by dbl("circleLat", Double.NaN)
    var circleLon by dbl("circleLon", Double.NaN)
    var circleRadiusNm by dbl("circleRadiusNm", 2.0)
    var geofenceFileName by str("geofenceFileName", "")

    // service
    var armed by bool("armed", false)
    var autoStartOnBoot by bool("autoStartOnBoot", false)
    var replaySpeed by dbl("replaySpeed", 1.0)
    var replayCloudView by bool("replayCloudView", false)
    var batteryPrompted by bool("batteryPrompted", false)

    // self-update (GitHub releases): the last check's result, so the banner survives restarts
    var updateLatestTag by str("updateLatestTag", "")
    var updateNotes by str("updateNotes", "")
    var updateApkUrl by str("updateApkUrl", "")
    var updateApkSize by lng("updateApkSize", 0L)
    var updateLastSuccessMs by lng("updateLastSuccessMs", 0L)
    var updateLastAttemptMs by lng("updateLastAttemptMs", 0L)
    var updateLastMessage by str("updateLastMessage", "")
    var updateNotifiedVersion by str("updateNotifiedVersion", "")

    fun selectorConfig() = DroneSelector.SelectorConfig(pinnedSerial = pinnedSerial)

    fun engineConfig(): SentryConfig {
        val c = SentryConfig(
            advisoryNm = advisoryNm, cautionNm = cautionNm, warningNm = warningNm,
            ceilingAboveFt = ceilingAboveFt, baroCorrectionFt = baroCorrectionFt,
            cpaHorizonSec = cpaHorizonSec, tfrRelevanceNm = tfrRelevanceNm,
        )
        return if (c.ringsValid()) c else SentryConfig()
    }

    // ── delegates ────────────────────────────────────────────────────────
    private fun str(k: String, d: String) = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = p.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) { p.edit().putString(k, value.trim()).apply() }
    }
    private fun bool(k: String, d: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = p.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) { p.edit().putBoolean(k, value).apply() }
    }
    private fun lng(k: String, d: Long) = object : kotlin.properties.ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = p.getLong(k, d)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Long) { p.edit().putLong(k, value).apply() }
    }
    private fun dbl(k: String, d: Double) = object : kotlin.properties.ReadWriteProperty<Any?, Double> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) =
            if (p.contains(k)) java.lang.Double.longBitsToDouble(p.getLong(k, 0)) else d
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Double) {
            p.edit().putLong(k, java.lang.Double.doubleToRawLongBits(value)).apply()
        }
    }

    companion object {
        /** "192.168.1.5" -> "http://192.168.1.5:8080"; keeps an explicit scheme/port. */
        fun normalizeStationBase(raw: String): String? {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            val hostPart = s.substringAfter("://").substringBefore('/')
            if (!hostPart.contains(':')) s = s.replaceFirst(hostPart, "$hostPart:8080")
            return s.removeSuffix("/data/aircraft.json")
        }
    }
}
