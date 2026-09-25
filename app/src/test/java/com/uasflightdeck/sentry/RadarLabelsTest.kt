package com.uasflightdeck.sentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.3: the "1 nm" and "0.5 nm" ring labels overlapped on the RC Plus compass (3 / 1 / 0.5 nm rings). */
class RadarLabelsTest {
    // RC Plus-ish: compass ~200 dp at density 2.5 -> k ~1.17, small text 25.7 px, outer ring ~220 px
    private val textH = 25.7f; private val pad = 4.7f
    private fun ring(nm: Double, w: Float) = (nm / 3.0 * 220).toFloat() to w

    private fun box(p: Pair<Float, Float>, w: Float) = floatArrayOf(p.first, p.second - textH, p.first + w, p.second + textH * 0.25f)
    private fun overlap(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    @Test fun defaultRings_noLabelOverlaps() {
        val rings = listOf(ring(3.0, 45f), ring(1.0, 45f), ring(0.5, 70f))
        val spots = RadarLabels.place(0f, 0f, rings, textH, pad)
        // the naive up-right spots for 1 nm and 0.5 nm DO collide (the 0.4.2 bug) ...
        val naive = rings.map { (r, _) -> (r * 0.72f + pad) to (-r * 0.72f) }
        assertTrue(overlap(box(naive[1], 45f), box(naive[2], 70f)))
        // ... placed: every label still shown, none overlapping; the inner one moved down-left
        spots.forEach { assertNotNull(it) }
        val boxes = spots.mapIndexed { i, p -> box(p!!, rings[i].second) }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertTrue("labels $i and $j overlap", !overlap(boxes[i], boxes[j]))
        assertTrue(spots[2]!!.first < 0f && spots[2]!!.second > 0f)
    }

    @Test fun wellSpacedRings_stayUpRight() {
        val spots = RadarLabels.place(0f, 0f, listOf(400f to 45f, 250f to 45f, 100f to 45f), textH, pad)
        spots.forEach { assertTrue(it!!.first > 0f && it.second < 0f) }
    }

    @Test fun noRoomAnywhere_innerLabelDropped() {
        val spots = RadarLabels.place(0f, 0f, listOf(30f to 60f, 29f to 60f, 28f to 60f), textH, pad)
        assertEquals(null, spots[2])
    }
}
