package com.uasflightdeck.sentry.core

import kotlin.math.pow

/**
 * 0.4.4: how a traffic banner looks, as pure data (the app's RemoteViews apply it).
 *
 * The owner could not read 0.4.2/0.4.3 banners on the RC Plus: the body lines used the platform notification text
 * appearance, whose colour is black (#de000000) unless the system is in night mode, and the controller draws its
 * heads-up on a dark surface without being in night mode. Every banner now paints its OWN opaque dark card and gives
 * every line an explicit colour, so nothing depends on the system notification theme. All text is >= 7:1 on [BG].
 *
 * The heads-up is compact: two text rows ([headsUpRows]) plus the action row. The pulled-down (expanded) form keeps
 * the full four lines.
 */
object BannerLook {
    /** The card behind every banner line (opaque). */
    const val BG: Int = 0xFF10161D.toInt()
    /** Body text: near-white. */
    const val INK: Int = 0xFFF2F5F8.toInt()
    /** Action button face. Its label is [INK]. */
    const val BUTTON: Int = 0xFF26313D.toInt()

    enum class Level(val argb: Int, val stripe: Int) {
        COLLISION(0xFFFF8A80.toInt(), 0xFFFF3B30.toInt()),  // red
        WARNING(0xFFFFB547.toInt(), 0xFFFFB547.toInt()),    // amber
        CAUTION(0xFFFFE45C.toInt(), 0xFFFFE45C.toInt()),    // yellow
        TRACK(0xFFA8C8EA.toInt(), 0xFFA8C8EA.toInt()),      // grey-blue
        ADVISORY(0xFFA8C8EA.toInt(), 0xFFA8C8EA.toInt()),   // grey-blue
        PASSING(0xFFB4BEC8.toInt(), 0xFF6B7680.toInt()),    // grey: passing / clear
    }

    /** PASSING ("●") and CLEAR ("○") are grey whatever the tier; otherwise the tier decides. */
    fun level(tier: Tier?, title: String): Level = when {
        title.startsWith("●") || title.startsWith("○") -> Level.PASSING
        tier == Tier.COLLISION -> Level.COLLISION
        tier == Tier.WARNING -> Level.WARNING
        tier == Tier.CAUTION -> Level.CAUTION
        tier == Tier.TRACK -> Level.TRACK
        tier == Tier.ADVISORY -> Level.ADVISORY
        else -> Level.PASSING
    }

    /**
     * The heads-up's two text rows. Row 1 = title (coloured) + [HeadsUp.where]; row 2 = [HeadsUp.what] (closure / time)
     * with the "Clear:" hint on the same row ([HeadsUp.hint], right-aligned by the layout).
     */
    data class HeadsUp(val title: String, val where: String, val what: String, val hint: String?)

    fun headsUpRows(b: BannerText): HeadsUp =
        HeadsUp(b.title, if (b.line2.isEmpty()) "" else " · ${b.line2}", b.line3, b.line4?.takeIf { it.isNotEmpty() })

    // ── WCAG 2 contrast ──────────────────────────────────────────────────
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double { val c = v / 255.0; return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
