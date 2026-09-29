package com.vinex.chargemonitor.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.components.ColorDot
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.components.Tag
import com.vinex.chargemonitor.ui.theme.Readout
import java.time.LocalDate

/** A guessed source only matters when it is a power bank: that is the one total it would distort. */
fun needsConfirmation(s: ChargeSession, source: PowerSource?): Boolean =
    !s.sourceConfirmed && source?.kind == SourceKind.POWER_BANK

fun kindLabel(kind: SourceKind): String = when (kind) {
    SourceKind.POWER_BANK -> "Power bank"
    SourceKind.CHARGER -> "Charger"
    SourceKind.LAPTOP -> "Laptop USB"
    SourceKind.WIRELESS -> "Wireless"
}

@Composable
fun sourceColor(source: PowerSource?): Color = source?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.outline

/**
 * Flags shown next to a session. "Screen on" replaces the old "used" tag: the screen was on for
 * more than a fifth of the session, so capacity and charger estimates leave it out by default.
 */
@Composable
fun SessionFlags(s: ChargeSession, source: PowerSource? = null, showCharging: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (showCharging && s.isOpen) Tag("Charging now", color = MaterialTheme.colorScheme.primary)
        if (needsConfirmation(s, source)) Tag("unconfirmed", color = MaterialTheme.colorScheme.error)
        if (s.reachedFull) Tag("full", color = MaterialTheme.colorScheme.primary)
        if (s.usedWhileCharging) Tag("screen on")
        if (s.avgW >= 10.0) Tag("fast")
    }
}

/**
 * Battery level track, 0 to 100 %, with the part this session covered filled in the source colour.
 * A drop (end below start) is drawn in the error colour.
 */
@Composable
fun LevelBar(from: Float, to: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val lo = minOf(from, to).coerceIn(0f, 100f) / 100f
    val hi = maxOf(from, to).coerceIn(0f, 100f) / 100f
    val fill = if (to < from) MaterialTheme.colorScheme.error else color
    val shape = RoundedCornerShape(height / 2)
    BoxWithConstraints(
        modifier
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
    ) {
        val start = maxWidth * lo
        // Always show a sliver so a session that added nothing still marks where it sat.
        val width = (maxWidth * (hi - lo)).coerceAtLeast(3.dp)
        Box(
            Modifier
                .offset(x = start.coerceAtMost(maxWidth - width))
                .width(width)
                .fillMaxHeight()
                .clip(shape)
                .background(fill),
        )
    }
}

/** Which figure a row puts in large type: the one the list is ranked by. */
enum class RowEmphasis { LEVEL, ENERGY, DURATION }

fun emphasisFor(sort: HistorySort): RowEmphasis = when (sort) {
    HistorySort.MOST_ENERGY -> RowEmphasis.ENERGY
    HistorySort.LONGEST -> RowEmphasis.DURATION
    else -> RowEmphasis.LEVEL
}

/**
 * One session: a source colour stripe and name, the level range with a bar, and the numbers
 * right-aligned. [liveLevel] is the level right now if this is the session being recorded.
 */
@Composable
fun SessionRow(
    s: ChargeSession,
    source: PowerSource?,
    showDate: Boolean,
    emphasis: RowEmphasis,
    liveLevel: Float?,
    onClick: () -> Unit,
    onConfirm: () -> Unit,
) {
    val color = sourceColor(source)
    val end = liveLevel ?: s.endLevel ?: s.startLevel
    val delta = end - s.startLevel
    val durationMs = HistoryQuery.durationMs(s)
    // Right after plug-in the counters are still settling; a tiny negative reads as an error.
    val settling = s.isOpen && durationMs < 120_000
    val scheme = MaterialTheme.colorScheme

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (s.isOpen) scheme.primary.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(end = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(start = Space.s, top = Space.m, bottom = Space.m)
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Column(
            Modifier.weight(1f).padding(start = Space.m, top = Space.m, bottom = Space.m),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    source?.name ?: "Unknown source",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (s.isOpen) Tag("Charging now", color = scheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    "${Format.levelInt(s.startLevel)} → ${Format.levelInt(end)} %",
                    style = Readout.small.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                )
                LevelBar(s.startLevel, end, color, Modifier.weight(1f).widthIn(max = 140.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    if (showDate) "${Format.shortDay(s.startMs)} · ${Format.clock(s.startMs)}" else Format.clock(s.startMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                SessionFlags(s, source, showCharging = false)
            }
        }

        Column(
            Modifier.padding(start = Space.m).widthIn(min = 76.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val levelText = HistoryText.signedLevel(delta)
            val levelColor = when {
                delta < -0.05f -> scheme.error
                delta > 0.05f -> scheme.primary
                else -> scheme.onSurfaceVariant
            }
            val whText = if (settling) "-- Wh" else HistoryText.signedWh(s.whIn)
            val whColor = if (!settling && s.whIn <= -0.005) scheme.error else scheme.onSurface
            val durText = Format.duration(durationMs)
            val big = Readout.tile.copy(fontSize = 18.sp)
            when (emphasis) {
                RowEmphasis.LEVEL -> {
                    Text(levelText, style = big, color = levelColor, maxLines = 1)
                    Text(whText, style = Readout.small, color = whColor, maxLines = 1)
                    Text(durText, style = Readout.small, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                RowEmphasis.ENERGY -> {
                    Text(whText, style = big, color = whColor, maxLines = 1)
                    Text(levelText, style = Readout.small, color = levelColor, maxLines = 1)
                    Text(durText, style = Readout.small, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                RowEmphasis.DURATION -> {
                    Text(durText, style = big, maxLines = 1)
                    Text(levelText, style = Readout.small, color = levelColor, maxLines = 1)
                    Text(whText, style = Readout.small, color = whColor, maxLines = 1)
                }
            }
            if (needsConfirmation(s, source)) {
                TextButton(
                    onClick = onConfirm,
                    contentPadding = PaddingValues(horizontal = Space.s),
                    modifier = Modifier.heightIn(min = 32.dp),
                ) { Text("Confirm") }
            }
        }
    }
}

/** Day header: date on the left, what that day's shown sessions added on the right. */
@Composable
fun DayHeader(day: LocalDate, totals: HistoryTotals, today: LocalDate) {
    SectionBar(title = Format.dayHeader(day, today), totals = totals, dot = null)
}

@Composable
fun SourceHeader(source: PowerSource?, totals: HistoryTotals) {
    SectionBar(title = source?.name ?: "Unknown source", totals = totals, dot = sourceColor(source))
}

@Composable
private fun SectionBar(title: String, totals: HistoryTotals, dot: Color?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (dot != null) ColorDot(dot)
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            HistoryText.header(totals),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
