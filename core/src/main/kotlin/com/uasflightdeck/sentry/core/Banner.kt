package com.uasflightdeck.sentry.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The heads-up banner, fixed shape (owner's plan, v0.4.0). One banner per aircraft, updated in place:
 *
 *     ▲ TRACK · N388KM Cessna                      <- title: glyph, tier, id (+ maker on TRACK / ADVISORY)
 *     SW 7.9 mi · 160 kt · 300 below, climbing     <- where he is now
 *     Passing within 0.3 mi in 2:58                <- the prediction (live countdown)
 *     Clear: move NW ↑                             <- the hint: never an order beyond "Clear:"
 *
 * "mi" is the pilot's "miles" = NAUTICAL miles (as ATC says "traffic, 3 miles"); under 1 nm distances are in feet.
 */
data class BannerText(val title: String, val line2: String, val line3: String, val line4: String? = null) {
    val body: String get() = listOfNotNull(line2, line3, line4).joinToString("\n")
    val oneLine: String get() = listOfNotNull(title, line2, line3, line4).joinToString(" · ")
}

object Banner {
    private val US = Locale.US
    private fun grouped(n: Long) = String.format(US, "%,d", n)

    // ── titles ────────────────────────────────────────────────────────────
    fun glyphTitle(tier: Tier): String = when (tier) {
        Tier.COLLISION -> "‼ COLLISION RISK"
        Tier.WARNING -> "⚠ WARNING"
        Tier.CAUTION -> "◆ CAUTION"
        Tier.TRACK -> "▲ TRACK"
        Tier.ADVISORY -> "△ ADVISORY"
        Tier.NONE -> "○ CLEAR"
    }

    fun title(tier: Tier, id: String, type: String?): String {
        val maker = if (tier == Tier.TRACK || tier == Tier.ADVISORY) maker(type) else null
        return "${glyphTitle(tier)} · $id" + (maker?.let { " $it" } ?: "")
    }

    // ── numbers ───────────────────────────────────────────────────────────
    /** "7.9 mi" at 1 nm and beyond, else feet to the nearest 100 ("2,700 ft", never below 100). */
    fun dist(nm: Double): String =
        if (nm >= 1.0) String.format(US, "%.1f mi", (nm * 10).roundToInt() / 10.0)
        else "${grouped(maxOf(100L, (nm * Units.FT_PER_NM / 100.0).roundToLong() * 100))} ft"

    /** Always miles to one decimal, except under 0.1 nm (then feet): the TRACK line "Passing within 0.3 mi". */
    fun miles(nm: Double): String = if (nm < 0.1) dist(nm) else String.format(US, "%.1f mi", (nm * 10).roundToInt() / 10.0)

    /** m:ss, "2:58", "0:09". */
    fun clock(sec: Double): String { val s = maxOf(0, sec.roundToInt()); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    /** "300 below", "1,200 above", "same alt", "alt unknown"; "≈" when the altitude is estimated (baro + correction). */
    fun vertical(dvFt: Double?, estimated: Boolean = false): String {
        if (dvFt == null) return "alt unknown"
        val r = (abs(dvFt) / 100.0).roundToLong() * 100
        val s = if (r < 100) "same alt" else "${grouped(r)} ${if (dvFt > 0) "above" else "below"}"
        return if (estimated) "≈$s" else s
    }

    /** His vertical trend: climbing / descending beyond 300 fpm, else level; null = unknown. */
    fun trendWord(vsFpm: Double?): String? = when {
        vsFpm == null -> null
        vsFpm >= 300 -> "climbing"
        vsFpm <= -300 -> "descending"
        else -> "level"
    }

    fun line2(v: AlertEngine.TargetView): String {
        val parts = ArrayList<String>()
        parts += "${Geo.cardinalAbbrev(v.bearingDeg)} ${dist(v.distNm)}"
        v.gsKt?.let { parts += "${it.roundToInt()} kt" }
        val vert = vertical(v.dvFt, v.altEstimated)
        parts += trendWord(v.vsFpm)?.takeIf { v.dvFt != null }?.let { "$vert, $it" } ?: vert
        return parts.joinToString(" · ")
    }

    fun line3(v: AlertEngine.TargetView): String {
        val p = v.prediction
        return when (v.tier) {
            Tier.COLLISION -> {
                val cross = p?.crossingInSec
                if (cross != null && v.dvFt != null) {
                    val dir = if ((v.vsFpm ?: 0.0) - (v.droneVsFpm ?: 0.0) >= 0) "climbing" else "descending"
                    "${vertical(v.dvFt, v.altEstimated)}, $dir through your altitude · ${cross.roundToInt()} s"
                } else if (p != null && p.converging) "Closest ${dist(p.missNm)} in ${clock(p.tCpaSec)}, ${vertical(p.dvAtCpaFt, v.altEstimated)}"
                else "Inside ${dist(v.distNm)}, ${vertical(v.dvFt, v.altEstimated)}"
            }
            Tier.TRACK -> if (p != null && p.converging) "Passing within ${miles(p.missNm)} in ${clock(p.tCpaSec)}" else "Not closing"
            Tier.WARNING, Tier.CAUTION, Tier.ADVISORY ->
                if (p != null && p.converging) "Closest ${dist(p.missNm)} in ${clock(p.tCpaSec)}" else "Not closing"
            Tier.NONE -> "Clear"
        }
    }

    /**
     * "Clear: move NW ↑". Horizontal: perpendicular to HIS track, on the side the drone is already offset to; when
     * centred, away from his turn, else perpendicular-right. Vertical arrow only when he is changing altitude
     * toward/through the drone: above and descending -> ↓; below (or crossing) and climbing -> ↑.
     */
    fun hint(v: AlertEngine.TargetView): String? {
        val b = v.escapeBearingDeg ?: return null
        val arrow = when (v.escapeVertical) { 1 -> " ↑"; -1 -> " ↓"; else -> "" }
        return "Clear: move ${Geo.cardinalAbbrev(b)}$arrow"
    }

    /** The escape bearing, pure: see [hint]. [rel] = aircraft minus drone (m); [turnDegPerSec] > 0 = turning right. */
    fun escapeBearing(trackDeg: Double, rel: EN, turnDegPerSec: Double?, centredFt: Double = 150.0): Double {
        // Drone relative to the aircraft = -rel. Cross product of his heading unit vector and that offset:
        // negative = the drone is to his RIGHT (east of a northbound track).
        val h = EN(kotlin.math.sin(Math.toRadians(trackDeg)), kotlin.math.cos(Math.toRadians(trackDeg)))
        val d = EN(-rel.e, -rel.n)
        val cross = h.e * d.n - h.n * d.e
        val lateralFt = abs(cross) / Units.M_PER_FT
        val right = when {
            lateralFt >= centredFt -> cross < 0
            turnDegPerSec != null && turnDegPerSec > 0.5 -> false        // he turns right: go left
            turnDegPerSec != null && turnDegPerSec < -0.5 -> true        // he turns left: go right
            else -> true
        }
        return Geo.normDeg(trackDeg + if (right) 90.0 else -90.0)
    }

    /** +1 = ↑, -1 = ↓, 0 = no arrow. [dvFt] aircraft minus drone now; [vsFpm] HIS vertical rate. */
    fun escapeVertical(dvFt: Double?, vsFpm: Double?, crossing: Boolean): Int {
        if (dvFt == null || vsFpm == null) return 0
        return when {
            dvFt > 0 && vsFpm <= -300 -> -1
            (dvFt <= 0 || crossing) && vsFpm >= 300 -> 1
            else -> 0
        }
    }

    // ── whole banners ─────────────────────────────────────────────────────
    fun traffic(v: AlertEngine.TargetView): BannerText =
        BannerText(title(v.tier, v.displayId, v.type), line2(v), line3(v), if (v.tier == Tier.COLLISION || v.prediction?.converging == true) hint(v) else null)

    fun passing(v: AlertEngine.TargetView, closestNm: Double?): BannerText =
        BannerText("● PASSING · ${v.displayId}", line2(v), "Diverging" + (closestNm?.let { " · closest was ${dist(it)}" } ?: ""))

    fun noLongerFactor(v: AlertEngine.TargetView): BannerText =
        BannerText("○ CLEAR · ${v.displayId}", line2(v), "No longer a factor")

    fun clear(id: String, why: String): BannerText = BannerText("○ CLEAR · $id", why, "")

    fun zone(v: AlertEngine.TargetView, zoneName: String): BannerText =
        BannerText("▣ ENTERING ${zoneName.uppercase(US)} · ${v.displayId}", line2(v), line3(v), hint(v))

    /**
     * The maker for an ICAO type designator ("C172" -> "Cessna"), for the TRACK title. Unknown designators are
     * shown as-is; null / blank -> null.
     */
    fun maker(type: String?): String? {
        val t = type?.trim()?.uppercase(US)?.takeIf { it.isNotEmpty() } ?: return null
        return MAKERS.firstOrNull { (re, _) -> re.matches(t) }?.second ?: t
    }

    /** First match wins (military / airliner codes that look like Cessna's "C" + digits come first). */
    private val MAKERS = listOf(
        Regex("C130|C30J|C5M|C17") to "Lockheed",
        Regex("CRJ[0-9X]") to "Bombardier",
        Regex("C[1-9][0-9]{1,2}[A-Z]?|T210") to "Cessna",
        Regex("S22T|SR2[02]|SF50") to "Cirrus",
        Regex("P28[A-Z]|PA[0-9]{2}[A-Z]?|P32[A-Z]|P46T") to "Piper",
        Regex("BE[0-9]{2}[A-Z]?|B350") to "Beechcraft",
        Regex("M20[A-Z]") to "Mooney",
        Regex("DA[0-9]{2}") to "Diamond",
        Regex("PC12|PC6T|PC24") to "Pilatus",
        Regex("TBM[0-9]") to "Daher",
        Regex("R22|R44|R66") to "Helicopter",
        Regex("B06T?|B407|B412|B429|B505|B212|B427|B430|UH1Y?") to "Bell",
        Regex("AS50|AS55|AS65|EC[1-7][0-9]|H1[2-7][0-9]") to "Airbus Heli",
        Regex("A109|A119|A139|A169|AW09|AW69|AW89") to "Leonardo",
        Regex("S76|S92|H60|UH60|S70") to "Sikorsky",
        Regex("AT[3-8]T|AT3P") to "Air Tractor",
        Regex("AT4[0-9]|AT7[0-9]") to "ATR",
        Regex("A3[0-9]{2}[A-Z0-9]?|A2[0-9]N") to "Airbus",
        Regex("B7[0-9]{2}|B7[0-9][A-Z0-9]|B3[0-9]M") to "Boeing",
        Regex("E1[0-9]{2}|E[0-9]{2}[A-Z]|E75[LS]") to "Embraer",
        Regex("DH8[A-D]|DHC[0-9]") to "De Havilland",
    )
}
