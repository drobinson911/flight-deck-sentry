package com.uasflightdeck.sentry.core

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Time-to-close prediction (v0.4.0): straight-line extrapolation of one aircraft against the drone.
 * Pure, no state: [AlertEngine] feeds it the geometry of one tick.
 *
 * Inputs are RELATIVE: [rel] = aircraft minus drone position (m, local east/north), [vRel] = aircraft minus
 * drone velocity (m/s), [dvFt] = aircraft minus drone altitude now (null = aircraft altitude unknown),
 * [vsRelFpm] = aircraft minus drone vertical rate.
 */
object Prediction {
    data class Result(
        /** Seconds to closest approach; 0 when not converging. */
        val tCpaSec: Double,
        /** Horizontal miss at CPA (nm). */
        val missNm: Double,
        /** Vertical offset at CPA (aircraft minus drone, ft); null = altitude unknown. */
        val dvAtCpaFt: Double?,
        /** Current range (nm). */
        val rangeNm: Double,
        /** Range rate, m/s (negative = closing). */
        val rangeRateMs: Double,
        /**
         * The aircraft's altitude crosses the drone's altitude while it is inside the crossing radius, within the
         * look-ahead: seconds until that crossing, else null.
         */
        val crossingInSec: Double?,
    ) {
        val converging get() = tCpaSec > 0.0
        val missFt get() = missNm * Units.FT_PER_NM
    }

    fun predict(rel: EN, vRel: EN?, dvFt: Double?, vsRelFpm: Double, crossingRadiusNm: Double, crossingHorizonSec: Double): Result {
        val range = rel.norm
        val v = vRel ?: EN(0.0, 0.0)
        val cpa = CpaMath.cpa(rel, v)
        val dvAt = dvFt?.let { it + vsRelFpm * cpa.tSec / 60.0 }
        val crossing = if (dvFt == null) null else crossingTime(rel, v, dvFt, vsRelFpm, Units.nmToM(crossingRadiusNm), crossingHorizonSec)
        return Result(cpa.tSec, Units.mToNm(cpa.distM), dvAt, Units.mToNm(range), CpaMath.rangeRate(rel, v), crossing)
    }

    /** Horizontal miss threshold widened with distance: [baseNm] + [rangeNm] x tan([deg]). */
    fun corridorNm(baseNm: Double, rangeNm: Double, deg: Double): Double = baseNm + rangeNm * tan(Math.toRadians(deg))

    /**
     * The time window (s, clipped to [0, horizon]) during which the aircraft is within [radiusM] horizontally,
     * or null when it never is.
     */
    fun insideWindow(rel: EN, vRel: EN, radiusM: Double, horizonSec: Double): Pair<Double, Double>? {
        val a = vRel dot vRel
        val b = 2 * (rel dot vRel)
        val c = (rel dot rel) - radiusM * radiusM
        if (a < 1e-9) return if (c <= 0) 0.0 to horizonSec else null
        val disc = b * b - 4 * a * c
        if (disc < 0) return null
        val s = sqrt(disc)
        val t1 = maxOf(0.0, (-b - s) / (2 * a))
        val t2 = minOf(horizonSec, (-b + s) / (2 * a))
        return if (t1 <= t2) t1 to t2 else null
    }

    /**
     * The COLLISION RISK crossing test: the aircraft is inside [radiusM] at some time in the next [horizonSec] AND
     * the relative vertical rate carries it THROUGH the drone's altitude during that window (the sign of the
     * vertical offset changes, or it is already level with the drone inside the window). Seconds to the crossing.
     */
    fun crossingTime(rel: EN, vRel: EN, dvFt: Double, vsRelFpm: Double, radiusM: Double, horizonSec: Double): Double? {
        val w = insideWindow(rel, vRel, radiusM, horizonSec) ?: return null
        if (abs(vsRelFpm) < 1e-6) return null                       // level: not crossing (the CPA test covers co-altitude)
        val tc = -dvFt / (vsRelFpm / 60.0)
        if (tc < 0) return null                                     // moving apart vertically
        return if (tc >= w.first && tc <= w.second) tc else null
    }
}
