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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinex.chargemonitor.ui.Format

/** One bar: [label] goes under the axis when there is room, [detail] into the scrub readout. */
@Immutable
data class Bar(val value: Float, val color: Color, val label: String, val detail: String)

/**
 * Vertical bar chart, one bar per item in order. Bars thin out to hairlines rather than scroll
 * when there are many sessions; the scrub readout still identifies each one. An optional dashed
 * [reference] line marks a meaningful level (100 % = one full charge).
 */
@Composable
fun BarChart(
    bars: List<Bar>,
    modifier: Modifier = Modifier,
    unit: String = "",
    decimals: Int = 1,
    height: Dp = 180.dp,
    reference: Float? = null,
    emptyText: String = "No sessions yet",
    description: String = "Bar chart",
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = colors.onSurfaceVariant)
    val tipStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurface)
    val gridColor = colors.outline.copy(alpha = 0.25f)
    val refColor = colors.onSurfaceVariant.copy(alpha = 0.7f)
    val tipBg = colors.surface
    var selected by remember(bars.size) { mutableStateOf<Int?>(null) }
    // Plain array, not state: written while drawing, read only by the gesture handlers.
    val plotBounds = remember { floatArrayOf(0f, 1f) }

    fun indexAt(x: Float): Int? {
        if (bars.isEmpty()) return null
        val l = plotBounds[0]
        val w = plotBounds[1]
        return (((x - l) / w) * bars.size).toInt().coerceIn(0, bars.size - 1)
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(bars.size) {
                detectTapGestures { pos ->
                    val i = indexAt(pos.x)
                    selected = if (i == selected) null else i
                }
            }
            .pointerInput(bars.size) {
                detectHorizontalDragGestures(
                    onDragStart = { selected = indexAt(it.x) },
                    onHorizontalDrag = { change, _ -> selected = indexAt(change.position.x) },
                )
            },
    ) {
        if (bars.isEmpty()) {
            drawEmpty(measurer, emptyText, labelStyle, gridColor)
            return@Canvas
        }
        val hi = maxOf(bars.maxOf { it.value }, reference ?: 0f).toDouble()
        val lo = minOf(bars.minOf { it.value }.toDouble(), 0.0)
        val scale = ChartMath.niceScale(lo, hi, target = 4, includeZero = true)
        val d = ChartMath.decimalsFor(scale.ticks.zipWithNext { a, b -> b - a }.firstOrNull() ?: 1.0)
        val tickTexts = scale.ticks.map { Format.f(it, d) }
        val tickLayouts = tickTexts.map { measurer.measure(it, labelStyle) }
        val tickH = tickLayouts.first().size.height.toFloat()
        val padL = tickLayouts.maxOf { it.size.width } + 6.dp.toPx()
        val padR = 8.dp.toPx()
        val padT = tickH / 2 + 2.dp.toPx()
        val padB = tickH + 6.dp.toPx()
        val plotW = (size.width - padL - padR).coerceAtLeast(1f)
        val plotH = (size.height - padT - padB).coerceAtLeast(1f)
        plotBounds[0] = padL
        plotBounds[1] = plotW
        fun yPx(v: Double) = padT + ((1 - scale.fraction(v)) * plotH).toFloat()

        scale.ticks.forEachIndexed { i, t ->
            val y = yPx(t)
            drawLine(gridColor, Offset(padL, y), Offset(padL + plotW, y), strokeWidth = 1f)
            val m = tickLayouts[i]
            drawText(m, topLeft = Offset(padL - 4.dp.toPx() - m.size.width, y - m.size.height / 2))
        }

        val slot = plotW / bars.size
        val gap = if (slot > 6.dp.toPx()) slot * 0.25f else 0f
        val barW = (slot - gap).coerceAtLeast(1f)
        val zeroY = yPx(0.0)
        bars.forEachIndexed { i, b ->
            val x = padL + i * slot + gap / 2
            val y = yPx(b.value.toDouble())
            val top = minOf(y, zeroY)
            val h = kotlin.math.abs(zeroY - y).coerceAtLeast(1f)
            val alpha = if (selected == null || selected == i) 1f else 0.45f
            drawRoundRect(
                b.color.copy(alpha = alpha),
                Offset(x, top),
                Size(barW, h),
                CornerRadius(if (barW > 6.dp.toPx()) 2.dp.toPx() else 0f),
            )
        }

        reference?.let { r ->
            val y = yPx(r.toDouble())
            drawLine(
                refColor,
                Offset(padL, y),
                Offset(padL + plotW, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }

        // Labels under the first and last bar, plus as many in between as fit without touching.
        var lastRight = Float.NEGATIVE_INFINITY
        val labelEvery = maxOf(1, (bars.size / 5.0).toInt())
        bars.forEachIndexed { i, b ->
            if (i % labelEvery != 0 && i != bars.size - 1) return@forEachIndexed
            val m = measurer.measure(b.label, labelStyle)
            val cx = padL + i * slot + slot / 2
            val lx = (cx - m.size.width / 2).coerceIn(padL, (padL + plotW - m.size.width).coerceAtLeast(padL))
            if (lx > lastRight + 6.dp.toPx()) {
                drawText(m, topLeft = Offset(lx, padT + plotH + 4.dp.toPx()))
                lastRight = lx + m.size.width
            }
        }

        val sel = selected ?: return@Canvas
        val b = bars.getOrNull(sel) ?: return@Canvas
        val lines = listOf(b.detail, "${Format.f(b.value.toDouble(), decimals)} $unit".trimEnd())
        drawTooltip(
            measurer, lines, tipStyle, listOf(b.color), tipBg,
            anchorX = padL + sel * slot + slot / 2, minX = padL, maxX = padL + plotW, top = padT,
        )
    }
}
