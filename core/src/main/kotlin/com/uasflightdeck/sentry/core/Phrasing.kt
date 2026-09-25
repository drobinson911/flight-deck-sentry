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
 * Formatting for the screen. (Voice was removed in 0.4.0; [Banner] formats the heads-up banner.)
 */
object Phrasing {
    private fun grouped(n: Long) = String.format(Locale.US, "%,d", n)

    fun displayDistance(distNm: Double): String =
        if (distNm < 1.0) String.format(Locale.US, "%.2f nm (%s ft)", distNm, grouped((distNm * Units.FT_PER_NM).roundToLong()))
        else String.format(Locale.US, "%.1f nm", distNm)

    fun displayVertical(dvFt: Double?, estimated: Boolean): String {
        if (dvFt == null) return "alt unknown"
        val r = abs(dvFt).roundToLong()
        val s = if (r < 50) "co-alt" else "${grouped(r)} ft ${if (dvFt > 0) "above" else "below"}"
        return if (estimated) "$s (est.)" else s
    }

}
