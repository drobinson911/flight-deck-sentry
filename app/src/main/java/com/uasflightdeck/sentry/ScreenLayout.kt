package com.uasflightdeck.sentry

/**
 * Layout decisions by available width in dp (pure Kotlin, unit-tested).
 *
 * The DJI RC Plus is a 7" 1920x1200 panel that runs at density 2.5 (400 dpi: this reproduces the
 * owner's photo of the real controller exactly), so the app gets only about 768 x 480 dp, not the
 * 1280 x 800 dp of a 240 dpi tablet profile. Below [COMPACT_BELOW_DP] the main screen reflows (smaller
 * compass, drone panel moves under it, smaller type) and Settings collapses to one column. The action
 * bar is pinned and every column scrolls (activity_main.xml), so nothing is dropped or out of reach.
 */
object ScreenLayout {
    const val COMPACT_BELOW_DP = 1000

    fun compactFor(widthDp: Int): Boolean = widthDp in 1 until COMPACT_BELOW_DP

    data class MainPlan(
        val compact: Boolean,
        /** Column weights: status / compass / targets+callouts. */
        val leftWeight: Float,
        val centreWeight: Float,
        val rightWeight: Float,
        /** Compact: the drone panel sits under the compass so the left column keeps the sources table + buttons. */
        val droneUnderCompass: Boolean,
        val buttonHeightDp: Int,
        val bannerSp: Float,
        val bannerMinHeightDp: Int,
        val droneNameSp: Float,
        val bodySp: Float,
        val labelSp: Float,
        val monoSp: Float,
        val logSp: Float,
        val logLines: Int,
    )

    private val WIDE = MainPlan(
        compact = false, leftWeight = 1.15f, centreWeight = 0.95f, rightWeight = 1.2f,
        droneUnderCompass = false, buttonHeightDp = 64,
        bannerSp = 28f, bannerMinHeightDp = 78, droneNameSp = 24f, bodySp = 16f, labelSp = 15f,
        monoSp = 15f, logSp = 11f, logLines = 4,
    )

    private val COMPACT = MainPlan(
        compact = true, leftWeight = 1.2f, centreWeight = 0.85f, rightWeight = 1.35f,
        droneUnderCompass = true, buttonHeightDp = 56,
        bannerSp = 20f, bannerMinHeightDp = 56, droneNameSp = 19f, bodySp = 14f, labelSp = 13f,
        monoSp = 13f, logSp = 10f, logLines = 3,
    )

    /** An unknown width (0, as some configs report before layout) gets the compact plan: it is the one that fits everywhere. */
    fun mainPlan(widthDp: Int): MainPlan = if (widthDp >= COMPACT_BELOW_DP) WIDE else COMPACT

    /** Settings: two columns only when each can be ~500 dp wide. */
    fun settingsColumns(widthDp: Int): Int = if (widthDp >= COMPACT_BELOW_DP) 2 else 1
}
