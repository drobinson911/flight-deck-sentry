package com.uasflightdeck.sentry

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.uasflightdeck.sentry.core.AlertEngine
import com.uasflightdeck.sentry.core.Severity
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * North-up "targets around the drone" rose. Rings are the configured
 * warning/caution/advisory radii; the outer ring sits at 88% of the view.
 * Targets outside the advisory ring are pinned to the rim as hollow markers
 * so an approaching aircraft is visible before it's inside the rings.
 */
class RadarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var targets: List<AlertEngine.TargetView> = emptyList(); set(v) { field = v; invalidate() }
    var rings: Triple<Double, Double, Double> = Triple(3.0, 1.0, 0.5); set(v) { field = v; invalidate() }
    var active = false; set(v) { field = v; invalidate() }
    /** Controller mode: draw these cylinder radii (nm) instead of the drone rings, centre = the controller. */
    var cylinderRadii: List<Double> = emptyList(); set(v) { field = v; invalidate() }

    private fun c(id: Int) = ContextCompat.getColor(context, id)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY }

    /**
     * Scale for every drawing constant. The constants were tuned in px on a 240 dpi (density 1.5)
     * screen with a ~360 dp compass; the RC Plus is density 2.0 with a smaller compass, so scale by
     * density and shrink (never below 70 %) when the compass is smaller than 300 dp.
     */
    private var k = 1f
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val d = resources.displayMetrics.density
        val sizeDp = min(w, h) / d
        k = d / 1.5f * (sizeDp / 300f).coerceIn(0.7f, 1f)
        text.textSize = 30f * k; small.textSize = 22f * k; ringPaint.strokeWidth = 3f * k
    }

    private fun sevColor(s: Severity) = when (s) {
        Severity.WARNING -> c(R.color.warning)
        Severity.CAUTION -> c(R.color.caution)
        Severity.ADVISORY -> c(R.color.advisory)
        else -> c(R.color.dim)
    }

    override fun onMeasure(w: Int, h: Int) {
        val wSize = MeasureSpec.getSize(w)
        // Inside a scrolling column the first measure pass has no height limit: ask only for the
        // minimum, so the compass shrinks before the column starts to scroll; the fill pass then
        // gives it whatever height is left.
        val size = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) min(wSize, suggestedMinimumHeight.takeIf { it > 0 } ?: wSize)
            else min(wSize, MeasureSpec.getSize(h))
        setMeasuredDimension(size, size)
    }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        // Leave room outside the outer ring for the N / S labels so they are never clipped.
        val rMax = min(min(cx, cy) * 0.88f, min(cx, cy) - text.textSize - 4 * k)
        val cyl = cylinderRadii.filter { it > 0 }.sortedDescending()
        val outerNm = cyl.firstOrNull() ?: rings.first
        fun r(nm: Double) = (nm / outerNm * rMax).toFloat()

        fill.color = c(R.color.panel); cv.drawCircle(cx, cy, min(cx, cy) * 0.98f, fill)
        val drawn = if (cyl.isNotEmpty()) cyl.mapIndexed { i, nm -> nm to if (i == cyl.lastIndex) R.color.caution else R.color.advisory }
            else listOf(rings.first to R.color.advisory, rings.second to R.color.caution, rings.third to R.color.warning)
        drawn.forEach { (nm, col) ->
            ringPaint.color = c(col); ringPaint.alpha = if (active) 200 else 70
            cv.drawCircle(cx, cy, r(nm), ringPaint)
        }
        // Ring labels (0.4.3): outer first, up-right of each ring; a label that would overlap one already drawn moves
        // down-left, and is dropped if that collides too ("1 nm" and "0.5 nm" overlapped on the RC Plus). Each label has a
        // panel-coloured backing, so one that crosses a ring line reads cleanly.
        val spots = RadarLabels.place(cx, cy, drawn.map { (nm, _) -> r(nm) to small.measureText(fmt(nm)) }, small.textSize, 4 * k)
        drawn.forEachIndexed { i, (nm, col) ->
            val p = spots[i] ?: return@forEachIndexed
            // A panel-coloured backing (inner labels) so one that sits across another ring stays legible.
            val w = small.measureText(fmt(nm))
            fill.color = c(R.color.panel)
            if (i > 0) cv.drawRect(p.first - 2 * k, p.second - small.textSize * 0.85f, p.first + w + 2 * k, p.second + small.textSize * 0.2f, fill)
            small.color = c(col)
            cv.drawText(fmt(nm), p.first, p.second, small)
        }
        // cardinal ticks
        small.color = c(R.color.dim)
        cv.drawText("N", cx - 8 * k, cy - rMax - 8 * k, text)
        cv.drawText("S", cx - 8 * k, cy + rMax + small.textSize + 2 * k, small)
        cv.drawText("E", cx + rMax + 10 * k, cy + 8 * k, small)
        cv.drawText("W", cx - rMax - 30 * k, cy + 8 * k, small)

        // drone (dot) or controller (square)
        fill.color = Color.WHITE
        if (cyl.isNotEmpty()) cv.drawRect(cx - 10 * k, cy - 10 * k, cx + 10 * k, cy + 10 * k, fill) else cv.drawCircle(cx, cy, 9f * k, fill)

        for (t in targets.sortedBy { it.severity.rank }) {
            val outside = t.distNm > outerNm
            val rr = if (outside) rMax else r(t.distNm)
            val a = Math.toRadians(t.bearingDeg)
            val x = cx + rr * sin(a).toFloat(); val y = cy - rr * cos(a).toFloat()
            val col = sevColor(t.severity)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = if (outside) Paint.Style.STROKE else Paint.Style.FILL; strokeWidth = 4f * k }
            val size = k * if (t.severity >= Severity.CAUTION) 20f else if (outside) 9f else 14f
            val path = Path().apply { moveTo(x, y - size); lineTo(x + size, y); lineTo(x, y + size); lineTo(x - size, y); close() }
            cv.drawPath(path, p)
            text.color = col
            val dv = t.dvFt?.let { (if (it >= 0) "+" else "−") + String.format("%02d", (kotlin.math.abs(it) / 100).toInt()) } ?: "?"
            // Rim markers (outside the outer ring) stay unlabelled unless alerting:
            // 30 nm of airliners must not bury the one that matters.
            if (outside && t.severity < Severity.ADVISORY) continue
            val label = "${t.displayId} $dv"
            val w = text.measureText(label)
            val lx = if (x + size + 6 * k + w > width) x - size - 6 * k - w else x + size + 6 * k
            cv.drawText(label, lx, y + 10 * k, text)
        }
    }

    private fun fmt(nm: Double) = if (nm % 1.0 == 0.0) "${nm.toInt()} nm" else "${"%.2f".format(java.util.Locale.US, nm).trimEnd('0')} nm"
}

/** Ring-label placement, pure (unit-tested): baseline positions, or null where a label is dropped. */
object RadarLabels {
    private data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        fun hits(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
    }

    /** [rings]: (radius px, label width px) outer to inner; [textH]: text size px; [pad]: gap to the ring px. */
    fun place(cx: Float, cy: Float, rings: List<Pair<Float, Float>>, textH: Float, pad: Float): List<Pair<Float, Float>?> {
        val placed = ArrayList<Box>()
        return rings.map { (r, w) ->
            val d = r * 0.72f
            val upRight = (cx + d + pad) to (cy - d)
            val downLeft = (cx - d - pad - w) to (cy + d + textH)
            val pick = listOf(upRight, downLeft).firstOrNull { (x, y) -> val b = Box(x, y - textH, x + w, y + textH * 0.25f); placed.none { it.hits(b) } }
            pick?.also { (x, y) -> placed += Box(x, y - textH, x + w, y + textH * 0.25f) }
        }
    }
}
