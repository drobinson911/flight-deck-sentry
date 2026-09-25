package com.uasflightdeck.sentry.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The banner actions' mutes (v0.4.0). They silence cadence REPEATS only; banners keep updating silently.
 *
 *  - Got it: that aircraft's repeats for [gotItSec] (60 s).
 *  - Ignore: that aircraft's repeats until it clears the rings (CLEAR / track lost / gone).
 *  - Quiet 5 min: every traffic repeat for [quietSec]; when it runs out, one "Sentry sounds on" alert.
 *
 * All of them give way INSTANTLY: an escalation (any aircraft; per-aircraft mutes are then dropped), COLLISION RISK,
 * or a muted aircraft turning toward the drone (its predicted miss shrinking, or it converging again after it was
 * not). Every change is reported as a line for the log.
 */
class MuteBook(
    var gotItSec: Double = 60.0,
    var quietSec: Double = 300.0,
) {
    enum class Kind { GOT_IT, IGNORE }

    data class Mute(val kind: Kind, val sinceMs: Long, val untilMs: Long?, val missAtMuteNm: Double?, val id: String)

    private val byHex = HashMap<String, Mute>()
    var quietUntilMs: Long? = null
        private set

    fun gotIt(hex: String, id: String, nowMs: Long, missNm: Double?): String {
        byHex[hex] = Mute(Kind.GOT_IT, nowMs, nowMs + (gotItSec * 1000).toLong(), missNm, id)
        return "Got it: $id repeats muted ${gotItSec.roundToInt()} s"
    }

    fun ignore(hex: String, id: String, nowMs: Long, missNm: Double?): String {
        byHex[hex] = Mute(Kind.IGNORE, nowMs, null, missNm, id)
        return "Ignore: $id muted until it clears the rings"
    }

    fun quiet(nowMs: Long): String {
        quietUntilMs = nowMs + (quietSec * 1000).toLong()
        return "Quiet: traffic sounds off ${(quietSec / 60).roundToInt()} min (banners still update)"
    }

    fun endQuiet(): String? { if (quietUntilMs == null) return null; quietUntilMs = null; return "Quiet ended: sounds on" }

    fun quietActive(nowMs: Long) = quietUntilMs?.let { nowMs < it } == true
    fun quietLeftSec(nowMs: Long): Double? = quietUntilMs?.let { (it - nowMs) / 1000.0 }?.takeIf { it > 0 }

    /** The live mute of one aircraft, or null. */
    fun aircraftMute(hex: String, nowMs: Long): Mute? = byHex[hex]?.takeIf { it.untilMs == null || nowMs < it.untilMs }

    /** "muted 47 s" / "ignored" / null, for the target list and the status notification. */
    fun label(hex: String, nowMs: Long): String? = aircraftMute(hex, nowMs)?.let { m ->
        if (m.untilMs == null) "ignored" else "muted ${max(0, ((m.untilMs - nowMs) / 1000.0).roundToInt())} s"
    }

    fun muted(): List<Pair<String, Mute>> = byHex.entries.map { it.key to it.value }

    /**
     * Once per tick, after the engine: expire, and give way. Returns (log lines, a "Sentry sounds on" event when
     * Quiet ran out).
     */
    fun step(nowMs: Long, views: List<AlertEngine.TargetView>, events: List<AlertEvent>): Pair<List<String>, AlertEvent?> {
        val log = ArrayList<String>()
        val byView = views.associateBy { it.hex }
        val escalated = events.filter { it.phase == Phase.ESCALATION && it.hex != null }.map { it.hex!! }.toSet()
        val cleared = events.filter { (it.kind == EventKind.CLEAR || it.kind == EventKind.TRACK_LOST) && it.hex != null }.map { it.hex!! }.toSet()
        for ((hex, m) in byHex.entries.toList()) {
            val v = byView[hex]
            val why = when {
                m.untilMs != null && nowMs >= m.untilMs -> "mute over"
                hex in escalated -> "escalated to ${v?.tier?.label?.uppercase() ?: "a higher tier"}"
                v?.tier == Tier.COLLISION -> "COLLISION RISK"
                v != null && turnedToward(m, v) -> "turning toward the drone"
                hex in cleared || v == null -> if (m.kind == Kind.IGNORE) "cleared the rings" else "gone"
                else -> null
            }
            if (why != null) { byHex.remove(hex); log += "Sounds back for ${m.id}: $why" }
        }
        var soundsOn: AlertEvent? = null
        val q = quietUntilMs
        if (q != null && nowMs >= q) {
            quietUntilMs = null
            log += "Quiet 5 min over: Sentry sounds on"
            soundsOn = AlertEvent(nowMs, EventKind.SOUNDS_ON, Severity.INFO, "Sentry sounds on")
        }
        return log to soundsOn
    }

    companion object {
        /**
         * Turning toward the drone: it converges now after it was not converging when muted, or its predicted miss
         * shrank by at least max(0.1 nm, 25 %) since the mute.
         */
        fun turnedToward(m: Mute, v: AlertEngine.TargetView): Boolean {
            val miss = v.missNm ?: return false                    // not converging: not turning toward
            val was = m.missAtMuteNm ?: return v.trend == Trend.CONVERGING
            return was - miss >= max(0.1, 0.25 * was)
        }
    }
}
