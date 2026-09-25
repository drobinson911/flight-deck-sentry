package com.uasflightdeck.sentry

import android.content.Context
import android.content.SharedPreferences
import com.uasflightdeck.sentry.core.AlertStyle
import com.uasflightdeck.sentry.core.Cylinder
import com.uasflightdeck.sentry.core.DroneSelector
import com.uasflightdeck.sentry.core.SentryConfig
import com.uasflightdeck.sentry.core.SoundLevel

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
    /** Protected volume: surface up to this far above the drone ("Ceiling above aircraft", 1,500 from 0.4.0). */
    var ceilingAboveFt by dbl("ceilingAboveFt", 1500.0)
    var baroCorrectionFt by dbl("baroCorrectionFt", 300.0)
    var tfrRelevanceNm by dbl("tfrRelevanceNm", 10.0)
    /** Zone ENTRY alerts only for zones containing the drone or within this distance of it. */
    var zoneAlertNm by dbl("zoneAlertNm", 2.0)

    // prediction (time to closest approach)
    var trackSec by dbl("trackSec", 120.0)
    var warningSec by dbl("warningSec", 60.0)
    var collisionSec by dbl("collisionSec", 60.0)
    var trackMissNm by dbl("trackMissNm", 1.0)
    var warningMissNm by dbl("warningMissNm", 0.5)
    var collisionMissFt by dbl("collisionMissFt", 500.0)
    var collisionVertFt by dbl("collisionVertFt", 300.0)
    var corridorDeg by dbl("corridorDeg", 0.0)

    // cadence, alert style, banner, mutes
    var alertStyle: AlertStyle
        get() = runCatching { AlertStyle.valueOf(p.getString("alertStyle", "STANDARD")!!) }.getOrDefault(AlertStyle.STANDARD)
        set(v) = p.edit().putString("alertStyle", v.name).apply()
    var trackUpdateSec by dbl("trackUpdateSec", 30.0)
    var cautionRepeatSec by dbl("cautionRepeatSec", 20.0)
    var warnFarSec by dbl("warnFarSec", 20.0)
    var warnNearSec by dbl("warnNearSec", 12.0)
    var warnCloseSec by dbl("warnCloseSec", 6.0)
    var collisionRepeatSec by dbl("collisionRepeatSec", 3.0)
    /** Every banner cancels after this long (traffic: restarted by each cadence refresh). */
    var bannerSec by dbl("bannerSec", 5.0)
    var gotItSec by dbl("gotItSec", 60.0)
    var quietMin by dbl("quietMin", 5.0)
    /** Offer "Ignore" (mute until it clears the rings) on traffic banners. */
    var ignoreEnabled by bool("ignoreEnabled", true)

    // sounds & vibration (per level: picked URI, "" = Sentry's default; volume 0-100 %)
    fun soundUri(l: SoundLevel): String = p.getString("sound_${l.key}", "") ?: ""
    fun setSoundUri(l: SoundLevel, uri: String) = p.edit().putString("sound_${l.key}", uri).apply()
    fun soundVolume(l: SoundLevel): Int = p.getInt("vol_${l.key}", 100)
    fun setSoundVolume(l: SoundLevel, pct: Int) = p.edit().putInt("vol_${l.key}", pct.coerceIn(0, 100)).apply()
    var vibrationOn by bool("vibrationOn", true)

    // targets SHOWN (list + compass); alerts are unaffected
    var targetsAircraftNm by dbl("targetsAircraftNm", 10.0)
    var targetsControllerNm by dbl("targetsControllerNm", 15.0)
    var targetsCeilingFt by dbl("targetsCeilingFt", 18_000.0)
    fun targetDisplay() = com.uasflightdeck.sentry.core.TargetDisplay.Config(
        aroundAircraftNm = targetsAircraftNm.takeIf { it > 0 } ?: 10.0,
        aroundControllerNm = targetsControllerNm.takeIf { it > 0 } ?: 15.0,
        ceilingFt = targetsCeilingFt.takeIf { it > 0 } ?: 18_000.0,
    )

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
    /** Replay the SYNTHETIC crossing variant (N388KM climbing through the drone's altitude: COLLISION RISK). */
    var replayCrossing by bool("replayCrossing", false)
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
        val style = alertStyle
        var c = SentryConfig(
            advisoryNm = advisoryNm, cautionNm = cautionNm, warningNm = warningNm,
            ceilingAboveFt = ceilingAboveFt, baroCorrectionFt = baroCorrectionFt,
            tfrRelevanceNm = tfrRelevanceNm, zoneAlertNm = zoneAlertNm,
            trackSec = trackSec, warningSec = warningSec, collisionSec = collisionSec,
            trackMissNm = trackMissNm, warningMissNm = warningMissNm, collisionMissFt = collisionMissFt,
            collisionVertFt = collisionVertFt, corridorDeg = corridorDeg,
            trackUpdateSec = trackUpdateSec, advisoryRepeatSec = trackUpdateSec, cautionRepeatSec = cautionRepeatSec,
            warnFarSec = warnFarSec, warnNearSec = warnNearSec, warnCloseSec = warnCloseSec,
            collisionRepeatSec = collisionRepeatSec,
            cadenceScale = style.cadenceScale, advisorySound = style.advisorySound,
        )
        val d = SentryConfig()
        if (!c.ringsValid()) c = c.copy(advisoryNm = d.advisoryNm, cautionNm = d.cautionNm, warningNm = d.warningNm)
        if (!c.predictionValid()) c = c.copy(trackSec = d.trackSec, warningSec = d.warningSec, collisionSec = d.collisionSec,
            trackMissNm = d.trackMissNm, warningMissNm = d.warningMissNm, collisionMissFt = d.collisionMissFt, collisionVertFt = d.collisionVertFt)
        return c
    }

    /**
     * One-time moves to the 0.4.0 defaults: the old default ceiling (2,000, stored by 0.3.4's auto-save) becomes
     * 1,500; the voice prefs are dropped. A value the pilot chose (anything but the old default) is kept.
     * Returns what changed, for the log.
     */
    fun migrate(): List<String> {
        if (p.getInt("schema", 0) >= 400) return emptyList()
        val out = ArrayList<String>()
        if (p.contains("ceilingAboveFt") && ceilingAboveFt == 2000.0) { ceilingAboveFt = 1500.0; out += "Ceiling above aircraft 2,000 -> 1,500 ft (0.4.0 default)" }
        p.edit().remove("voiceOn").remove("volume").remove("voiceForceBundled").remove("cpaHorizonSec").putInt("schema", 400).apply()
        return out
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
