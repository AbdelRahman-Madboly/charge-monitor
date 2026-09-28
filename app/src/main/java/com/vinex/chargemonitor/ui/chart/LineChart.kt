package com.vinex.chargemonitor.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinex.chargemonitor.ui.Format
import kotlin.math.abs

enum class Axis { LEFT, RIGHT }

/**
 * One line. [xs] must be ascending (time in ms, epoch or relative; the chart only needs order and
 * spacing). Arrays rather than lists because a long session holds thousands of points.
 */
@Immutable
class ChartSeries(
    val label: String,
    val unit: String,
    val color: Color,
    val xs: LongArray,
    val ys: FloatArray,
    val axis: Axis = Axis.LEFT,
    val decimals: Int = 1,
    /** Tint the area under the line; use for the primary series only. */
    val fill: Boolean = false,
    /** Force the axis to include zero (power, current) rather than hugging the data (voltage). */
    val includeZero: Boolean = true,
) {
    val isEmpty: Boolean get() = xs.isEmpty()
}

/**
 * Multi-series line chart drawn with Canvas. Up to two value axes: series on [Axis.LEFT] share the
 * left scale, series on [Axis.RIGHT] the right one, so W and A (or W and %) read honestly on one
 * plot. Tap or drag horizontally to scrub; the readout shows every series at that x. Vertical
 * drags pass through to the parent scroll.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    xLabel: (Long) -> String = { Format.elapsed(it) },
    emptyText: String = "No data yet",
    description: String = "Line chart",
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = colors.onSurfaceVariant)
    val tipStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurface)
    val gridColor = colors.outline.copy(alpha = 0.25f)
    val tipBg = colors.surface
    val scrubColor = colors.onSurface.copy(alpha = 0.55f)

    val visible = series.filter { !it.isEmpty }
    var scrubX by remember { mutableStateOf<Float?>(null) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(visible.size) {
                detectTapGestures { pos ->
                    val cur = scrubX
                    // Tapping the current cursor again hides it.
                    scrubX = if (cur != null && abs(cur - pos.x) < 24.dp.toPx()) null else pos.x
                }
            }
            .pointerInput(visible.size) {
                detectHorizontalDragGestures(
                    onDragStart = { scrubX = it.x },
                    onHorizontalDrag = { change, _ -> scrubX = change.position.x },
                )
            },
    ) {
        if (visible.isEmpty()) {
            drawEmpty(measurer, emptyText, labelStyle, gridColor)
            return@Canvas
        }

        val left = visible.filter { it.axis == Axis.LEFT }.ifEmpty { null }
        val right = visible.filter { it.axis == Axis.RIGHT }.ifEmpty { null }
        val leftScale = left?.let { scaleFor(it) }
        val rightScale = right?.let { scaleFor(it) }

        val xMin = visible.minOf { it.xs.first() }
        val xMax = visible.maxOf { it.xs.last() }.let { if (it == xMin) xMin + 1 else it }

        val tickH = measurer.measure("0", labelStyle).size.height.toFloat()
        val padL = leftScale?.let { axisWidth(measurer, it, labelStyle) + 6.dp.toPx() } ?: 4.dp.toPx()
        val padR = rightScale?.let { axisWidth(measurer, it, labelStyle) + 6.dp.toPx() } ?: 8.dp.toPx()
        val padT = tickH / 2 + 2.dp.toPx()
        val padB = tickH + 6.dp.toPx()
        val plotW = (size.width - padL - padR).coerceAtLeast(1f)
        val plotH = (size.height - padT - padB).coerceAtLeast(1f)

        fun xPx(x: Long) = padL + ((x - xMin).toDouble() / (xMax - xMin) * plotW).toFloat()
        fun yPx(v: Double, s: ChartMath.Scale) = padT + ((1 - s.fraction(v)) * plotH).toFloat()

        // Grid follows whichever axis exists, left preferred.
        val gridScale = leftScale ?: rightScale!!
        gridScale.ticks.forEach { t ->
            val y = yPx(t, gridScale)
            drawLine(gridColor, Offset(padL, y), Offset(padL + plotW, y), strokeWidth = 1f)
        }
        leftScale?.let { s ->
            val d = ChartMath.decimalsFor(s.ticks.zipWithNext { a, b -> b - a }.firstOrNull() ?: 1.0)
            s.ticks.forEach { t ->
                val text = Format.f(t, d)
                val m = measurer.measure(text, labelStyle)
                drawText(m, topLeft = Offset(padL - 4.dp.toPx() - m.size.width, yPx(t, s) - m.size.height / 2))
            }
        }
        rightScale?.let { s ->
            val d = ChartMath.decimalsFor(s.ticks.zipWithNext { a, b -> b - a }.firstOrNull() ?: 1.0)
            s.ticks.forEach { t ->
                val m = measurer.measure(Format.f(t, d), labelStyle)
                drawText(m, topLeft = Offset(padL + plotW + 4.dp.toPx(), yPx(t, s) - m.size.height / 2))
            }
        }

        // X labels: start, end and evenly spaced between, skipping any that would overlap.
        val xTicks = 4
        var lastRight = Float.NEGATIVE_INFINITY
        for (i in 0..xTicks) {
            val x = xMin + (xMax - xMin) * i / xTicks
            val m = measurer.measure(xLabel(x), labelStyle)
            val cx = xPx(x)
            val lx = (cx - m.size.width / 2).coerceIn(padL, padL + plotW - m.size.width)
            if (lx > lastRight + 6.dp.toPx()) {
                drawText(m, topLeft = Offset(lx, padT + plotH + 4.dp.toPx()))
                lastRight = lx + m.size.width
            }
        }

        val buckets = (plotW / 2).toInt().coerceAtLeast(16)
        visible.forEach { s ->
            val scale = if (s.axis == Axis.LEFT) leftScale!! else rightScale!!
            val idx = ChartMath.downsample(s.xs, s.ys, buckets)
            val path = Path()
            idx.forEachIndexed { k, i ->
                val px = xPx(s.xs[i])
                val py = yPx(s.ys[i].toDouble(), scale)
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            if (s.fill && idx.size > 1) {
                val area = Path().apply {
                    addPath(path)
                    val baseY = yPx(scale.min.coerceAtLeast(minOf(0.0, scale.max)), scale)
                    lineTo(xPx(s.xs[idx.last()]), baseY)
                    lineTo(xPx(s.xs[idx.first()]), baseY)
                    close()
                }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        listOf(s.color.copy(alpha = 0.28f), s.color.copy(alpha = 0.02f)),
                        startY = padT,
                        endY = padT + plotH,
                    ),
                )
            }
            if (idx.size == 1) {
                drawCircle(s.color, 3.dp.toPx(), Offset(xPx(s.xs[0]), yPx(s.ys[0].toDouble(), scale)))
            } else {
                drawPath(
                    path,
                    s.color,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        val sx = scrubX ?: return@Canvas
        val cx = sx.coerceIn(padL, padL + plotW)
        val xAt = xMin + ((cx - padL) / plotW * (xMax - xMin)).toLong()
        drawLine(scrubColor, Offset(cx, padT), Offset(cx, padT + plotH), strokeWidth = 1.dp.toPx())
        val lines = mutableListOf(xLabel(xAt))
        visible.forEach { s ->
            val i = ChartMath.nearestIndex(s.xs, xAt)
            if (i < 0) return@forEach
            val scale = if (s.axis == Axis.LEFT) leftScale!! else rightScale!!
            drawCircle(s.color, 4.dp.toPx(), Offset(xPx(s.xs[i]), yPx(s.ys[i].toDouble(), scale)))
            lines += "${s.label} ${Format.f(s.ys[i].toDouble(), s.decimals)} ${s.unit}".trimEnd()
        }
        drawTooltip(measurer, lines, tipStyle, visible.map { it.color }, tipBg, cx, padL, padL + plotW, padT)
    }
}

private fun scaleFor(series: List<ChartSeries>): ChartMath.Scale {
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    series.forEach { s -> s.ys.forEach { v -> if (v < lo) lo = v.toDouble(); if (v > hi) hi = v.toDouble() } }
    return ChartMath.niceScale(lo, hi, target = 4, includeZero = series.any { it.includeZero })
}

private fun DrawScope.axisWidth(measurer: TextMeasurer, s: ChartMath.Scale, style: TextStyle): Float {
    val d = ChartMath.decimalsFor(s.ticks.zipWithNext { a, b -> b - a }.firstOrNull() ?: 1.0)
    return s.ticks.maxOf { measurer.measure(Format.f(it, d), style).size.width }.toFloat()
}

internal fun DrawScope.drawEmpty(measurer: TextMeasurer, text: String, style: TextStyle, grid: Color) {
    val m = measurer.measure(text, style)
    val y = size.height / 2
    drawLine(grid, Offset(0f, y + m.size.height), Offset(size.width, y + m.size.height), strokeWidth = 1f)
    drawText(m, topLeft = Offset((size.width - m.size.width) / 2, y - m.size.height / 2))
}

/** Rounded box near the cursor, flipped to the cursor's left when it would run off the plot. */
internal fun DrawScope.drawTooltip(
    measurer: TextMeasurer,
    lines: List<String>,
    style: TextStyle,
    swatches: List<Color>,
    bg: Color,
    anchorX: Float,
    minX: Float,
    maxX: Float,
    top: Float,
) {
    val layouts = lines.map { measurer.measure(it, style) }
    val pad = 6.dp.toPx()
    val sw = 8.dp.toPx()
    val w = layouts.maxOf { it.size.width } + pad * 2 + sw + pad / 2
    val lineH = layouts.first().size.height.toFloat()
    val h = lineH * layouts.size + pad * 2
    var x = anchorX + 8.dp.toPx()
    if (x + w > maxX) x = anchorX - 8.dp.toPx() - w
    x = x.coerceIn(minX, (maxX - w).coerceAtLeast(minX))
    drawRoundRect(bg.copy(alpha = 0.94f), Offset(x, top), Size(w, h), CornerRadius(6.dp.toPx()))
    // Hairline border so the box separates from bars and fills of any colour.
    drawRoundRect(
        swatches.firstOrNull()?.copy(alpha = 0.6f) ?: Color.Gray,
        Offset(x, top),
        Size(w, h),
        CornerRadius(6.dp.toPx()),
        style = Stroke(width = 1.dp.toPx()),
    )
    layouts.forEachIndexed { i, m ->
        val y = top + pad + i * lineH
        // First line is the x label and gets no swatch.
        if (i > 0) {
            swatches.getOrNull(i - 1)?.let {
                drawCircle(it, sw / 2.5f, Offset(x + pad + sw / 2, y + lineH / 2))
            }
        }
        drawText(m, topLeft = Offset(x + pad + if (i > 0) sw + pad / 2 else 0f, y))
    }
}
