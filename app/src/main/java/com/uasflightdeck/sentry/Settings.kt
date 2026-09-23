package com.uasflightdeck.sentry

import android.content.Context
import android.content.SharedPreferences
import com.uasflightdeck.sentry.core.SentryConfig

/** Manual/fallback ownship modes. */
enum class ManualMode { OFF, PINNED, DEVICE_GPS }

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
    var droneFilter by str("droneFilter", "")
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
    var verticalBandFt by dbl("verticalBandFt", 2000.0)
    var baroCorrectionFt by dbl("baroCorrectionFt", 300.0)
    var cpaHorizonSec by dbl("cpaHorizonSec", 60.0)
    var tfrRelevanceNm by dbl("tfrRelevanceNm", 10.0)

    // voice
    var voiceOn by bool("voiceOn", true)
    var volume by dbl("volume", 1.0)

    // manual ownship
    var manualMode: ManualMode
        get() = runCatching { ManualMode.valueOf(p.getString("manualMode", "OFF")!!) }.getOrDefault(ManualMode.OFF)
        set(v) = p.edit().putString("manualMode", v.name).apply()
    var manualLat by dbl("manualLat", Double.NaN)
    var manualLon by dbl("manualLon", Double.NaN)
    var manualAltMslFt by dbl("manualAltMslFt", Double.NaN)
    var gpsAglFt by dbl("gpsAglFt", 400.0)

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

    fun engineConfig(): SentryConfig {
        val c = SentryConfig(
            advisoryNm = advisoryNm, cautionNm = cautionNm, warningNm = warningNm,
            verticalBandFt = verticalBandFt, baroCorrectionFt = baroCorrectionFt,
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
