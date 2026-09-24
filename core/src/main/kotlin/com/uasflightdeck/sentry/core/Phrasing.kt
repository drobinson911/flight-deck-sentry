package com.uasflightdeck.sentry.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Closing trend, from the range rate between drone and target. */
enum class Trend(val word: String) {
    CONVERGING("converging"),
    DIVERGING("diverging"),
    PASSING("passing"),
}

/**
 * Formatting shared by the screen and the voice. All numbers are rounded the
 * way a pilot would say them; the screen shows the precise values alongside.
 */
object Phrasing {
    private fun grouped(n: Long) = String.format(Locale.US, "%,d", n)

    /** Spoken distance: nautical miles to one decimal, or feet (nearest 100) under 1 nm. */
    fun spokenDistance(distNm: Double): String {
        if (distNm < 1.0) {
            val ft = (distNm * Units.FT_PER_NM / 100.0).roundToLong() * 100
            return "${grouped(maxOf(ft, 100))} feet"
        }
        val tenth = (distNm * 10).roundToInt() / 10.0
        return String.format(Locale.US, "%.1f miles", tenth)
    }

    fun displayDistance(distNm: Double): String =
        if (distNm < 1.0) String.format(Locale.US, "%.2f nm (%s ft)", distNm, grouped((distNm * Units.FT_PER_NM).roundToLong()))
        else String.format(Locale.US, "%.1f nm", distNm)

    /** "600 below", "1,200 above", "same altitude", "altitude unknown". */
    fun spokenVertical(dvFt: Double?): String {
        if (dvFt == null) return "altitude unknown"
        val r = (abs(dvFt) / 100.0).roundToLong() * 100
        if (r < 100) return "same altitude"
        return "${grouped(r)} ${if (dvFt > 0) "above" else "below"}"
    }

    fun displayVertical(dvFt: Double?, estimated: Boolean): String {
        if (dvFt == null) return "alt unknown"
        val r = abs(dvFt).roundToLong()
        val s = if (r < 50) "co-alt" else "${grouped(r)} ft ${if (dvFt > 0) "above" else "below"}"
        return if (estimated) "$s (est.)" else s
    }

    /**
     * Ids spelled for TTS: "N388KM" -> "N 3 8 8 K M". A TTS engine otherwise
     * reads it as "N three hundred eighty-eight K M", which is harder to
     * catch over rotor and road noise.
     */
    fun spelledId(id: String): String {
        if (id.startsWith("hex ")) return "hex " + id.removePrefix("hex ").toCharArray().joinToString(" ")
        if (id.any { it.isWhitespace() }) return id          // a name like "manual pin", not a callsign
        return id.filter { it.isLetterOrDigit() }.toCharArray().joinToString(" ")
    }

    fun seconds(t: Double): String = t.roundToInt().let { if (it == 1) "1 second" else "$it seconds" }
}
