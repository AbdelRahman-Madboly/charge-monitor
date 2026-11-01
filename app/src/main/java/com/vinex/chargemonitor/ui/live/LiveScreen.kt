package com.vinex.chargemonitor.ui.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.session.SessionTotals
import com.vinex.chargemonitor.stats.EnergyModel
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.chart.Axis
import com.vinex.chargemonitor.ui.chart.ChartSeries
import com.vinex.chargemonitor.ui.chart.LineChart
import com.vinex.chargemonitor.ui.components.ColorDot
import com.vinex.chargemonitor.ui.components.Panel
import com.vinex.chargemonitor.ui.components.SectionHeader
import com.vinex.chargemonitor.ui.components.SourcePickerSheet
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.components.StatRow
import com.vinex.chargemonitor.ui.components.Tag
import com.vinex.chargemonitor.ui.theme.Readout
import com.vinex.chargemonitor.ui.theme.SeriesColors

@Composable
fun LiveScreen(
    modifier: Modifier = Modifier,
    openPicker: Boolean = false,
    onPickerOpened: () -> Unit = {},
    vm: LiveViewModel = viewModel(),
) {
    // Tells the service to sample at the fast cadence only while this screen is actually resumed.
    LifecycleResumeEffect(Unit) {
        LiveBus.setLiveVisible(true)
        onPauseOrDispose { LiveBus.setLiveVisible(false) }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var picking by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openPicker) {
        if (openPicker) {
            picking = true
            onPickerOpened()
        }
    }
    val live = ui.live
    val snap = live.snapshot
    val active = live.sessionId != null && snap != null && snap.isPlugged
    val headline = LiveStatus.headline(snap, live.isCharging)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (live.isMock) {
            Row { Spacer(Modifier.weight(1f)); Tag("MOCK DATA", color = MaterialTheme.colorScheme.error) }
        }
        if (!live.serviceRunning) ServiceWarning()

        StatusHero(ui, headline)

        if (active) {
            SourceCard(live.source, onChange = { picking = true })
            if (live.source?.kind == SourceKind.POWER_BANK) ui.flow?.let { PowerFlowCard(ui, it, live.source) }
            ui.live.session?.let { SessionCard(it) }
            SessionChart(ui)
        } else {
            LastSession(ui)
        }
    }

    if (picking) {
        SourcePickerSheet(
            sources = ui.sources,
            selectedId = live.source?.id,
            onPick = {
                vm.changeSource(context, it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Headline: battery level first, then what is happening to it.
// ---------------------------------------------------------------------------------------------

@Composable
private fun headlineColor(h: Headline): Color = when (h) {
    Headline.CHARGING, Headline.FULL -> MaterialTheme.colorScheme.primary
    Headline.PLUGGED_IDLE, Headline.PLUGGED_DRAINING -> SeriesColors.voltage
    Headline.ON_BATTERY, Headline.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun headlineText(h: Headline, snap: BatterySnapshot?): String {
    val w = snap?.powerW ?: 0.0
    return when (h) {
        Headline.WAITING -> "Waiting for a reading"
        Headline.CHARGING -> "Charging at ${Format.watts(w)} W"
        Headline.FULL -> "Full"
        Headline.PLUGGED_IDLE -> "Plugged in, not charging"
        Headline.PLUGGED_DRAINING -> "Plugged in, draining ${Format.watts(-w)} W"
        Headline.ON_BATTERY -> "On battery"
    }
}

@Composable
private fun StatusHero(ui: LiveUi, headline: Headline) {
    val live = ui.live
    val snap = live.snapshot?.takeIf { headline != Headline.WAITING }
    val stateColor = headlineColor(headline)
    val stateText = headlineText(headline, snap)

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        contentDescription = "Battery ${Format.levelInt(snap?.levelPct)} percent. $stateText"
                    },
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(Format.levelInt(snap?.levelPct), style = Readout.hero, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    " %",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            if (ui.fastCharging) Tag("FAST", color = MaterialTheme.colorScheme.primary)
            snap?.let { LiveStatus.plugLabel(it.plug) }?.let { Tag(it) }
        }

        LevelBar(
            level = (snap?.levelPct ?: 0f) / 100f,
            target = ui.targetLevel.takeIf { it in 1..99 }?.let { it / 100f },
            color = stateColor,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            ColorDot(stateColor, size = 10.dp)
            Text(
                stateText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (headline == Headline.ON_BATTERY || headline == Headline.WAITING) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    stateColor
                },
            )
        }

        val subline = heroSubline(ui, headline)
        if (subline != null) {
            Text(subline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val etas = LiveStatus.etas(
            headline,
            snap?.levelPct ?: 0f,
            ui.targetLevel,
            ui.timeToTargetMs,
            ui.timeToFullMs,
        )
        if (etas.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                etas.forEach { (label, ms) -> InlineReadout(label, Format.duration(ms), "") }
            }
        }

        if (snap != null && snap.voltageMv > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InlineReadout("Voltage", Format.volts(snap.voltageMv), "V")
                InlineReadout("Current", Format.amps(snap.currentMa), "A")
                InlineReadout("Temp", Format.tempC(snap.tempC), "°C")
            }
        }
    }
}

/** Second line under the state: rated-power share while charging, or a plain hint otherwise. */
private fun heroSubline(ui: LiveUi, headline: Headline): String? {
    val live = ui.live
    val snap = live.snapshot
    return when (headline) {
        Headline.CHARGING -> if (live.source?.kind == SourceKind.POWER_BANK) {
            ui.flow?.let { f ->
                "The bank gives ≈${Format.watts(f.cellsW)} W from its cells; ${Format.percent(f.efficiency)} % reaches the battery"
            }
        } else {
            live.source?.ratedW?.takeIf { it > 0.0 }?.let { rated ->
                "${Format.percent(snap!!.powerW / rated)} % of the ${Format.watts(rated)} W rating"
            }
        }
        Headline.PLUGGED_IDLE -> if (live.sessionId == null) {
            "Waiting for the session to start"
        } else {
            "The source is connected but no power is going in"
        }
        Headline.PLUGGED_DRAINING -> "The phone uses more than the source gives"
        Headline.ON_BATTERY -> "Plug in a charger or power bank to record a session"
        Headline.WAITING, Headline.FULL -> null
    }
}

/** Battery bar with an optional tick at the charge target. */
@Composable
private fun LevelBar(level: Float, target: Float?, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val w = size.width * level.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
        target?.let {
            val x = size.width * it
            drawLine(tick, Offset(x, -2f), Offset(x, size.height + 2f), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Caption over a monospace value; smaller than a tile so three fit across the hero. */
@Composable
private fun InlineReadout(label: String, value: String, unit: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Readout.tile.copy(fontSize = 18.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Source: the one decision the user makes on this screen, so it gets a full-width card.
// ---------------------------------------------------------------------------------------------

@Composable
private fun SourceCard(source: PowerSource?, onChange: () -> Unit) {
    val sourceColor = source?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.outline
    val unknown = source == null
    Card(
        onClick = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = if (unknown) {
                    "No charging source chosen. Tap to choose one"
                } else {
                    "Charging from ${source!!.name}. Tap to change"
                }
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (unknown) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = BorderStroke(if (unknown) 1.5.dp else 1.dp, if (unknown) MaterialTheme.colorScheme.primary else sourceColor.copy(alpha = 0.6f)),
    ) {
        Row(
            Modifier.padding(Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            ColorDot(sourceColor, size = 28.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    if (unknown) "WHICH SOURCE IS THIS?" else "CHARGING FROM",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    source?.name ?: "Choose a source",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                source?.let {
                    Text(
                        LiveStatus.sourceDetail(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledTonalButton(onClick = onChange) { Text(if (unknown) "Choose" else "Change") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Power flow: where the bank's power goes right now, and how much charge the bank has left.
// ---------------------------------------------------------------------------------------------

@Composable
private fun PowerFlowCard(ui: LiveUi, flow: EnergyModel.Flow, bank: PowerSource) {
    val session = ui.live.session
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Power flow", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Tag(if (ui.moduleEfficiency != null) "MEASURED LOSS" else "ESTIMATED")
        }
        FlowRow("From the cells", "${Format.watts(flow.cellsW)} W", strong = true)
        FlowRow("Bank module loss", "−${Format.watts(flow.bankLossW)} W")
        FlowRow("Cable and phone charger loss", "−${Format.watts(flow.phoneLossW)} W")
        FlowRow("Phone running", "−${Format.watts(flow.phoneUseW)} W")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FlowRow("Into the battery (measured)", "${Format.watts(flow.batteryW.coerceAtLeast(0.0))} W", strong = true)

        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SummaryCell("Efficiency now", Format.percent(flow.efficiency), "%", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            SummaryCell(
                "This session",
                Format.percent(session?.let { t -> ui.sessionCellsWh?.takeIf { it > 0.0 }?.let { t.wh.coerceAtLeast(0.0) / it } }),
                "%",
                Modifier.weight(1f),
            )
        }
        ui.moduleEfficiency?.let {
            Text(
                "Bank module ${Format.percent(it)} % efficient, measured from its full drains.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        session?.let { t -> ui.sessionCellsWh?.takeIf { it > 0.0 }?.let { cells -> t to cells } }?.let { (t, cells) ->
            val volts = bank.nominalV.takeIf { it > 0.0 } ?: 3.7
            StatRow("Taken from the cells", "${Format.wh(cells)} Wh · ${Format.mah(cells / volts * 1000.0)} mAh")
            StatRow("Lost on the way", "${Format.wh((cells - t.wh.coerceAtLeast(0.0)).coerceAtLeast(0.0))} Wh")
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val share = ui.bankLeftShare
        if (share != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Bank charge left",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${Format.percent(share)} %" + (ui.phoneChargesLeft?.let { " · ≈${Format.f(it, 1)} phone charges" } ?: ""),
                    style = Readout.small.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            LevelBar(level = share.toFloat(), target = null, color = MaterialTheme.colorScheme.primary)
            Text(
                "${Format.wh(ui.bankLeftWh)} of ${Format.wh(bank.usableCellWh)} Wh cell energy since Recharged; " +
                    "the module should show ${kotlin.math.ceil(share * 4).toInt().coerceIn(0, 4)} of 4 lights.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Mark Recharged on the Dashboard when the bank is full to track the charge it has left.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FlowRow(label: String, value: String, strong: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (strong) FontWeight.SemiBold else null,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = Readout.small.copy(fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Session so far.
// ---------------------------------------------------------------------------------------------

@Composable
private fun SessionCard(session: SessionTotals) {
    Panel {
        Text("This session", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SummaryCell("Added", "+${Format.level(session.levelAdded)}", "%", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            SummaryCell("Duration", Format.duration(session.durationMs), "", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SummaryCell("Charge in", Format.mah(session.mah), "mAh", Modifier.weight(1f))
            SummaryCell("Energy in", Format.wh(session.wh), "Wh", Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatRow("Level", "${Format.level(session.startLevel)} → ${Format.level(session.endLevel)} %")
        StatRow("Peak / average", "${Format.watts(session.peakW)} / ${Format.watts(session.avgW)} W")
        session.maxTempC?.let { StatRow("Max temperature", "${Format.tempC(it)} °C") }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, unit: String, modifier: Modifier, accent: Color? = null) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Readout.tile, color = accent ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionChart(ui: LiveUi) {
    val pts = remember(ui.points) { LiveStatus.chartPoints(ui.points) }
    val clamped = remember(ui.points) { LiveStatus.hasNegative(ui.points) }
    Panel {
        Text("Power and current", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
            LegendItem(SeriesColors.power, "Power, W (left axis)")
            LegendItem(SeriesColors.current, "Current, A (right axis)")
        }
        val xs = LongArray(pts.size) { pts[it].tMs }
        val t0 = xs.firstOrNull() ?: 0L
        LineChart(
            series = listOf(
                ChartSeries("Power", "W", SeriesColors.power, xs, FloatArray(xs.size) { pts[it].watts },
                    fill = true, decimals = 1),
                ChartSeries("Current", "A", SeriesColors.current, xs, FloatArray(xs.size) { pts[it].amps },
                    axis = Axis.RIGHT, decimals = 2),
            ),
            height = 180.dp,
            xLabel = { Format.elapsed(it - t0) },
            emptyText = "Waiting for the first samples",
            description = "Power in watts and current in amps over this session",
        )
        Text(
            if (clamped) {
                "Time since plug-in. Moments when the phone drew from its battery are drawn at 0. Tap or drag to read values."
            } else {
                "Time since plug-in. Tap or drag to read values."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorDot(color, size = 8.dp)
        Spacer(Modifier.width(Space.xs))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------------------------
// Not in a session.
// ---------------------------------------------------------------------------------------------

@Composable
private fun LastSession(ui: LiveUi) {
    val last = ui.lastSession ?: return
    SectionHeader("Last session")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            ColorDot(ui.lastSource?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.outline)
            Text(
                ui.lastSource?.name ?: "Unknown source",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${Format.shortDay(last.startMs)} ${Format.clock(last.startMs)}",
                style = Readout.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SummaryCell("Added", "+${Format.level(last.levelAdded)}", "%", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            SummaryCell("Duration", Format.duration(last.endMs?.minus(last.startMs)), "", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SummaryCell("Charge in", Format.mah(last.mahIn), "mAh", Modifier.weight(1f))
            SummaryCell("Energy in", Format.wh(last.whIn), "Wh", Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatRow("Level", "${Format.level(last.startLevel)} → ${Format.level(last.endLevel)} %")
        StatRow("Peak / average", "${Format.watts(last.peakW)} / ${Format.watts(last.avgW)} W")
        last.maxTempC?.let { StatRow("Max temperature", "${Format.tempC(it)} °C") }
    }
}

@Composable
private fun ServiceWarning() {
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                "Monitoring is not running. Charging will not be recorded until the app is opened " +
                    "again or the phone restarts. Check battery optimisation in Settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
