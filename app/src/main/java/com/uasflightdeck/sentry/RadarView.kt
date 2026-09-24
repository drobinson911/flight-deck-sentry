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
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = Color.WHITE; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Color.GRAY }

    private fun sevColor(s: Severity) = when (s) {
        Severity.WARNING -> c(R.color.warning)
        Severity.CAUTION -> c(R.color.caution)
        Severity.ADVISORY -> c(R.color.advisory)
        else -> c(R.color.dim)
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = min(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        setMeasuredDimension(size, size)
    }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val rMax = min(cx, cy) * 0.88f
        val cyl = cylinderRadii.filter { it > 0 }.sortedDescending()
        val outerNm = cyl.firstOrNull() ?: rings.first
        fun r(nm: Double) = (nm / outerNm * rMax).toFloat()

        fill.color = c(R.color.panel); cv.drawCircle(cx, cy, min(cx, cy) * 0.98f, fill)
        val drawn = if (cyl.isNotEmpty()) cyl.mapIndexed { i, nm -> nm to if (i == cyl.lastIndex) R.color.caution else R.color.advisory }
            else listOf(rings.first to R.color.advisory, rings.second to R.color.caution, rings.third to R.color.warning)
        drawn.forEach { (nm, col) ->
            ringPaint.color = c(col); ringPaint.alpha = if (active) 200 else 70
            cv.drawCircle(cx, cy, r(nm), ringPaint)
            small.color = c(col)
            cv.drawText(fmt(nm), cx + r(nm) * 0.72f + 4, cy - r(nm) * 0.72f, small)
        }
        // cardinal ticks
        small.color = c(R.color.dim)
        cv.drawText("N", cx - 8, cy - rMax - 8, text)
        cv.drawText("S", cx - 8, cy + rMax + 34, small)
        cv.drawText("E", cx + rMax + 10, cy + 8, small)
        cv.drawText("W", cx - rMax - 30, cy + 8, small)

        // drone (dot) or controller (square)
        fill.color = Color.WHITE
        if (cyl.isNotEmpty()) cv.drawRect(cx - 10, cy - 10, cx + 10, cy + 10, fill) else cv.drawCircle(cx, cy, 9f, fill)

        for (t in targets.sortedBy { it.severity.rank }) {
            val outside = t.distNm > outerNm
            val rr = if (outside) rMax else r(t.distNm)
            val a = Math.toRadians(t.bearingDeg)
            val x = cx + rr * sin(a).toFloat(); val y = cy - rr * cos(a).toFloat()
            val col = sevColor(t.severity)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = if (outside) Paint.Style.STROKE else Paint.Style.FILL; strokeWidth = 4f }
            val size = if (t.severity >= Severity.CAUTION) 20f else if (outside) 9f else 14f
            val path = Path().apply { moveTo(x, y - size); lineTo(x + size, y); lineTo(x, y + size); lineTo(x - size, y); close() }
            cv.drawPath(path, p)
            text.color = col
            val dv = t.dvFt?.let { (if (it >= 0) "+" else "−") + String.format("%02d", (kotlin.math.abs(it) / 100).toInt()) } ?: "?"
            // Rim markers (outside the outer ring) stay unlabelled unless alerting:
            // 30 nm of airliners must not bury the one that matters.
            if (outside && t.severity < Severity.ADVISORY) continue
            val label = "${t.displayId} $dv"
            val w = text.measureText(label)
            val lx = if (x + size + 6 + w > width) x - size - 6 - w else x + size + 6
            cv.drawText(label, lx, y + 10, text)
        }
    }

    private fun fmt(nm: Double) = if (nm % 1.0 == 0.0) "${nm.toInt()} nm" else "${"%.2f".format(java.util.Locale.US, nm).trimEnd('0')} nm"
}
