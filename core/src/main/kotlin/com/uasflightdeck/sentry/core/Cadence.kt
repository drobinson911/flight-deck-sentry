package com.uasflightdeck.sentry.core

/**
 * How often ONE aircraft is repeated while its tier stays the same (v0.4.0, owner's plan). Escalations are never
 * subject to this: they fire at once. Pure and table-driven so every row is unit-tested.
 *
 * | Tier            | While converging                                          | What a repeat is                    |
 * |-----------------|-----------------------------------------------------------|-------------------------------------|
 * | COLLISION RISK  | every 3 s (continuous tone)                               | full alarm sound + long vibrate     |
 * | WARNING         | >= 1 nm: 20 s; 0.5-1 nm: 12 s; < 0.5 nm or tCPA < 30 s: 6 s | short sound, vibrate, silent banner |
 * | CAUTION         | every 20 s                                                | short sound, vibrate, silent banner |
 * | TRACK ALERT     | every 30 s                                                | banner-only (no sound)              |
 * | ADVISORY        | every 30 s                                                | banner-only (sound: Loud style only; entry sounds in Standard) |
 *
 * Not converging (range steady or opening): no repeats at all (COLLISION RISK excepted: its tone runs until the
 * tier drops). Never faster than [SentryConfig.minRepeatSec]
 * except COLLISION RISK. The Quiet alert style multiplies every interval by [SentryConfig.cadenceScale] (2), except
 * the COLLISION RISK tone.
 */
object Cadence {
    data class Repeat(val everySec: Double, val cue: Cue, val phase: Phase)

    /** Null = no repeats (not converging, or no tier). */
    fun repeat(tier: Tier, rangeNm: Double, tCpaSec: Double?, converging: Boolean, cfg: SentryConfig): Repeat? {
        if (tier == Tier.NONE) return null
        // COLLISION RISK sounds until it stops being one; every other tier repeats only while converging.
        if (tier != Tier.COLLISION && !converging) return null
        val k = cfg.cadenceScale.coerceAtLeast(1.0)
        fun limited(s: Double) = maxOf(cfg.minRepeatSec, s * k)
        return when (tier) {
            Tier.COLLISION -> Repeat(cfg.collisionRepeatSec, Cue.FULL, Phase.REPEAT)        // never scaled: continuous
            Tier.WARNING -> {
                val close = rangeNm < cfg.warningNm || (tCpaSec != null && tCpaSec > 0 && tCpaSec < cfg.warnCloseCpaSec)
                val s = when {
                    close -> cfg.warnCloseSec
                    rangeNm < cfg.cautionNm -> cfg.warnNearSec
                    else -> cfg.warnFarSec
                }
                Repeat(limited(s), Cue.SHORT, Phase.REPEAT)
            }
            Tier.CAUTION -> Repeat(limited(cfg.cautionRepeatSec), Cue.SHORT, Phase.REPEAT)
            Tier.TRACK -> Repeat(limited(cfg.trackUpdateSec), Cue.NONE, Phase.UPDATE)
            Tier.ADVISORY -> if (cfg.advisorySound) Repeat(limited(cfg.advisoryRepeatSec), Cue.SHORT, Phase.REPEAT)
                else Repeat(limited(cfg.advisoryRepeatSec), Cue.NONE, Phase.UPDATE)
            Tier.NONE -> null
        }
    }
}
