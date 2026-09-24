package com.uasflightdeck.sentry.core

/**
 * How often a callout about ONE aircraft is repeated while its alert level stays the same (v0.3.5, owner-approved).
 * Escalation is never subject to this: it is spoken at once. Pure and table-driven so every row is unit-tested.
 *
 * | Where the aircraft is                                   | Repeat                              |
 * |---------------------------------------------------------|-------------------------------------|
 * | beyond the advisory ring (3 nm), or diverging            | none (entry / escalation only)      |
 * | 1–3 nm, not diverging, caution or warning level          | every 20 s                          |
 * | 1–3 nm, advisory level                                   | every 30 s                          |
 * | 0.5–1 nm, not diverging                                  | every 12 s                          |
 * | inside 0.5 nm, or predicted pass inside 0.5 nm in ≤ 30 s | every 6 s, short sentence           |
 * | opening after a close pass ("passing, diverging" said)   | every 45 s while inside 3 nm        |
 *
 * The rings are the configured ones (defaults 3 / 1 / 0.5 nm).
 */
object Cadence {
    enum class Band(val short: Boolean = false) {
        /** No repeats. */
        NONE,
        ADVISORY,
        MID,
        NEAR,
        CLOSE(short = true),
        OPENING,
    }

    /**
     * @param passingSaid "passing, diverging" has already been said for this aircraft since its close pass.
     * @param cpaTSec / [cpaDistNm] the predicted closest approach (null when there is no velocity).
     */
    fun band(
        sev: Severity, distNm: Double, trend: Trend?, cpaTSec: Double?, cpaDistNm: Double?,
        passingSaid: Boolean, cfg: SentryConfig,
    ): Band {
        if (sev < Severity.ADVISORY) return Band.NONE
        if (trend == Trend.DIVERGING) return if (passingSaid && distNm <= cfg.advisoryNm + cfg.ringHysteresisNm) Band.OPENING else Band.NONE
        val closeCpa = cpaTSec != null && cpaDistNm != null && cpaTSec > 0 && cpaTSec <= cfg.closeCpaSec && cpaDistNm <= cfg.warningNm
        if (distNm < cfg.warningNm || closeCpa) return Band.CLOSE
        if (distNm > cfg.advisoryNm) return Band.NONE
        if (distNm < cfg.cautionNm) return Band.NEAR
        return if (sev == Severity.ADVISORY) Band.ADVISORY else Band.MID
    }

    /** Seconds between repeats for [b], or null for none. */
    fun intervalSec(b: Band, cfg: SentryConfig): Double? = when (b) {
        Band.NONE -> null
        Band.ADVISORY -> cfg.advisoryReannounceSec
        Band.MID -> cfg.midRepeatSec
        Band.NEAR -> cfg.nearRepeatSec
        Band.CLOSE -> cfg.closeRepeatSec
        Band.OPENING -> cfg.openingRepeatSec
    }
}
