package com.uasflightdeck.sentry

import java.util.Locale

/**
 * Pure rules behind Settings' auto-save (0.3.4): what a typed value means, and what gets stored.
 * The rule for every numeric field: while the text is not a valid number in range, the field shows an
 * error and storage keeps the LAST VALID value. Garbage is never persisted.
 */
object FieldRules {
    /** A numeric field's accepted range. [blankMeans] non-null = an empty field is valid and stores that value (e.g. NaN = "use GPS"). */
    data class NumSpec(val min: Double, val max: Double, val unit: String = "", val blankMeans: Double? = null) {
        val signed get() = min < 0
        /** "0.1–50 nm" */
        val rangeText: String get() = listOf("${fmt(min)}–${fmt(max)}", unit).filter { it.isNotEmpty() }.joinToString(" ")
    }

    sealed class Parsed {
        data class Ok(val value: Double) : Parsed()
        data class Bad(val why: String) : Parsed()
    }

    // Plain decimal only: no exponent, no "NaN"/"Infinity", no Java's trailing d/f, no thousands separators.
    private val DECIMAL = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)$")

    fun parseNumber(text: String, spec: NumSpec): Parsed {
        val t = text.trim()
        if (t.isEmpty()) return spec.blankMeans?.let { Parsed.Ok(it) } ?: Parsed.Bad("Required: ${spec.rangeText}")
        if (!DECIMAL.matches(t)) return Parsed.Bad("Not a number: ${spec.rangeText}")
        val v = t.toDouble()
        if (!v.isFinite() || v < spec.min || v > spec.max) return Parsed.Bad(spec.rangeText)
        return Parsed.Ok(v)
    }

    /** The value to store: the parsed one if valid, else [lastValid] (unchanged). */
    fun keepLastValid(text: String, spec: NumSpec, lastValid: Double): Double =
        (parseNumber(text, spec) as? Parsed.Ok)?.value ?: lastValid

    /** TRACK >= WARNING >= COLLISION horizons, and TRACK miss >= WARNING miss. */
    fun predictionOrdered(track: Double, warning: Double, collision: Double, trackMiss: Double, warnMiss: Double) =
        track >= warning && warning >= collision && trackMiss >= warnMiss

    /** Advisory ≥ caution ≥ warning > 0. */
    fun ringsOrdered(advisory: Double, caution: Double, warning: Double) =
        warning > 0 && caution >= warning && advisory >= caution

    /**
     * The rings to store given the three texts and the stored trio: each field falls back to its stored
     * value when invalid; the trio is only replaced when the result is ordered. Returns null = keep the stored trio.
     */
    fun rings(a: String, c: String, w: String, spec: NumSpec, stored: Triple<Double, Double, Double>): Triple<Double, Double, Double>? {
        val t = Triple(keepLastValid(a, spec, stored.first), keepLastValid(c, spec, stored.second), keepLastValid(w, spec, stored.third))
        return if (ringsOrdered(t.first, t.second, t.third)) t else null
    }

    /** Pinned serial as stored: trimmed and upper-case ("" = not pinned). */
    fun normaliseSerial(text: String) = text.trim().uppercase(Locale.US)

    /** Fleet token as stored: surrounding whitespace (a pasted newline, a stray space) removed. */
    fun normaliseToken(text: String) = text.trim()

    /** Worker base URL: must be http(s)://host; returns the trimmed URL without a trailing slash, or null if unusable. */
    fun workerBase(text: String): String? {
        val t = text.trim().trimEnd('/')
        val scheme = listOf("https://", "http://").firstOrNull { t.startsWith(it, ignoreCase = true) } ?: return null
        val host = t.substring(scheme.length).substringBefore('/')
        return if (host.isBlank() || host.any { it.isWhitespace() }) null else t
    }

    fun fmt(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e7) v.toLong().toString()
        else String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')

    // ── the ranges (one place) ───────────────────────────────────────────
    val RING = NumSpec(0.1, 50.0, "nm")
    val CEILING_ABOVE = NumSpec(100.0, 20_000.0, "ft")
    val BARO_CORRECTION = NumSpec(-2_000.0, 2_000.0, "ft")
    val PRED_SEC = NumSpec(10.0, 600.0, "s")
    val PRED_MISS_NM = NumSpec(0.05, 5.0, "nm")
    val COLLISION_MISS_FT = NumSpec(100.0, 3_000.0, "ft")
    val COLLISION_VERT_FT = NumSpec(50.0, 2_000.0, "ft")
    val CORRIDOR_DEG = NumSpec(0.0, 20.0, "°")
    val ZONE_ALERT_NM = NumSpec(0.0, 50.0, "nm")
    val REPEAT_SEC = NumSpec(6.0, 300.0, "s")
    val COLLISION_REPEAT_SEC = NumSpec(1.0, 30.0, "s")
    val BANNER_SEC = NumSpec(2.0, 30.0, "s")
    val GOT_IT_SEC = NumSpec(10.0, 600.0, "s")
    val QUIET_MIN = NumSpec(1.0, 60.0, "min")
    val TFR_RELEVANCE = NumSpec(0.0, 100.0, "nm")
    val TARGETS_AIRCRAFT = NumSpec(0.5, 100.0, "nm")
    val TARGETS_CONTROLLER = NumSpec(0.5, 100.0, "nm")
    val TARGETS_CEILING = NumSpec(500.0, 60_000.0, "ft")
    val CONTROLLER_ELEV = NumSpec(-1_500.0, 30_000.0, "ft, or blank", blankMeans = Double.NaN)
    val TRAFFIC_RADIUS = NumSpec(1.0, 250.0, "nm")
    val CIRCLE_LAT = NumSpec(-90.0, 90.0, "°, or blank", blankMeans = Double.NaN)
    val CIRCLE_LON = NumSpec(-180.0, 180.0, "°, or blank", blankMeans = Double.NaN)
    val CIRCLE_RADIUS = NumSpec(0.05, 100.0, "nm")
    val CYL_RADIUS_NM = NumSpec(0.01, 50.0, "nm")
    val CYL_RADIUS_FT = NumSpec(100.0, 300_000.0, "ft")
    val CYL_ALT = NumSpec(-2_000.0, 60_000.0, "ft")
}
