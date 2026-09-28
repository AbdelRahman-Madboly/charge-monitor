package com.vinex.chargemonitor.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.battery.PlugType
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
import com.vinex.chargemonitor.ui.components.StatTile
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

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (active) {
                AssistChip(
                    onClick = { picking = true },
                    label = { Text(live.source?.name ?: "Choose source") },
                    leadingIcon = {
                        ColorDot(live.source?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.outline)
                    },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.width(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(),
                    modifier = Modifier.semantics { contentDescription = "Charging source, tap to change" },
                )
            } else {
                Text("Live", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.weight(1f))
            if (ui.fastCharging) Tag("FAST", color = MaterialTheme.colorScheme.primary)
            if (live.isMock) Tag("MOCK", color = MaterialTheme.colorScheme.error)
        }

        if (!live.serviceRunning) ServiceWarning()

        if (active) {
            ChargingBody(ui)
        } else {
            IdleBody(ui)
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

@Composable
private fun ChargingBody(ui: LiveUi) {
    val snap = ui.live.snapshot ?: return
    val session = ui.live.session

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Format.watts(snap.powerW),
                style = Readout.hero,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = "${Format.watts(snap.powerW)} watts" },
            )
            Text(
                " W",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Text(statusLine(snap.status, snap.plug), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    TileGrid(
        listOf(
            Triple("Voltage", Format.volts(snap.voltageMv), "V"),
            Triple("Current", Format.amps(snap.currentMa), "A"),
            Triple("Temperature", Format.tempC(snap.tempC), "°C"),
            Triple("Level", Format.level(snap.levelPct), "%"),
        ),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        StatTile("Full in", ui.timeToFullMs?.let { Format.duration(it) } ?: "--", "", Modifier.weight(1f))
        StatTile(
            "${ui.targetLevel} % in",
            when {
                snap.levelPct >= ui.targetLevel -> "reached"
                else -> ui.timeToTargetMs?.let { Format.duration(it) } ?: "--"
            },
            "",
            Modifier.weight(1f),
        )
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("This session", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            ChartLegend()
        }
        val xs = LongArray(ui.points.size) { ui.points[it].tMs }
        val t0 = xs.firstOrNull() ?: 0L
        LineChart(
            series = listOf(
                ChartSeries("W", "W", SeriesColors.power, xs, FloatArray(xs.size) { ui.points[it].watts },
                    fill = true, decimals = 1),
                ChartSeries("A", "A", SeriesColors.current, xs, FloatArray(xs.size) { ui.points[it].amps },
                    axis = Axis.RIGHT, decimals = 2),
            ),
            xLabel = { Format.elapsed(it - t0) },
            emptyText = "Waiting for the first samples",
            description = "Power and current over this session",
        )
        if (session != null) {
            StatRow("Duration", Format.duration(session.durationMs))
            StatRow("Added", "${Format.level(session.startLevel)} → ${Format.level(session.endLevel)} %  (+${Format.level(session.levelAdded)})")
            StatRow("Charge in", "${Format.mah(session.mah)} mAh")
            StatRow("Energy in", "${Format.wh(session.wh)} Wh")
            StatRow("Peak / average", "${Format.watts(session.peakW)} / ${Format.watts(session.avgW)} W")
        }
    }
}

@Composable
private fun IdleBody(ui: LiveUi) {
    val snap = ui.live.snapshot
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.levelInt(snap?.levelPct), style = Readout.hero, color = MaterialTheme.colorScheme.onSurface)
            Text(" %", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(
            if (snap?.isPlugged == true) "Plugged in, waiting for the session to start" else "Not charging",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (snap != null && snap.voltageMv > 0) {
        TileGrid(
            listOf(
                Triple("Voltage", Format.volts(snap.voltageMv), "V"),
                Triple("Temperature", Format.tempC(snap.tempC), "°C"),
            ),
        )
    }

    SectionHeader("Last session")
    val last = ui.lastSession
    if (last == null) {
        Text(
            "Plug in a charger or power bank. Sessions are recorded automatically in the background.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            ColorDot(ui.lastSource?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.outline)
            Text(ui.lastSource?.name ?: "Unknown source", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "${Format.shortDay(last.startMs)} ${Format.clock(last.startMs)}",
                style = Readout.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("+${Format.level(last.levelAdded)}", style = Readout.tile, color = MaterialTheme.colorScheme.primary)
            Text(" %  ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${Format.level(last.startLevel)} → ${Format.level(last.endLevel)} %",
                style = Readout.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        StatRow("Duration", Format.duration(last.endMs?.minus(last.startMs)))
        StatRow("Charge / energy in", "${Format.mah(last.mahIn)} mAh · ${Format.wh(last.whIn)} Wh")
        StatRow("Peak / average", "${Format.watts(last.peakW)} / ${Format.watts(last.avgW)} W")
        last.maxTempC?.let { StatRow("Max temperature", "${Format.tempC(it)} °C") }
    }
}

@Composable
private fun TileGrid(tiles: List<Triple<String, String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                row.forEach { (label, value, unit) -> StatTile(label, value, unit, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChartLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        ColorDot(SeriesColors.power, size = 8.dp)
        Text("W", style = MaterialTheme.typography.labelMedium)
        ColorDot(SeriesColors.current, size = 8.dp)
        Text("A (right)", style = MaterialTheme.typography.labelMedium)
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

private fun statusLine(status: ChargeStatus, plug: PlugType): String {
    val s = when (status) {
        ChargeStatus.CHARGING -> "Charging"
        ChargeStatus.FULL -> "Full"
        ChargeStatus.NOT_CHARGING -> "Plugged in, not charging"
        ChargeStatus.DISCHARGING -> "Discharging"
        ChargeStatus.UNKNOWN -> "Status unknown"
    }
    val p = when (plug) {
        PlugType.AC -> "AC"
        PlugType.USB -> "USB"
        PlugType.WIRELESS -> "Wireless"
        PlugType.NONE -> null
    }
    return listOfNotNull(s, p).joinToString(" · ")
}
