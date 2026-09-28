package com.vinex.chargemonitor.ui.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.stats.StatsCalculator
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.chart.Bar
import com.vinex.chargemonitor.ui.chart.BarChart
import com.vinex.chargemonitor.ui.chart.ChartSeries
import com.vinex.chargemonitor.ui.chart.LineChart
import com.vinex.chargemonitor.ui.components.AccuracyInfoSheet
import com.vinex.chargemonitor.ui.components.ColorDot
import com.vinex.chargemonitor.ui.components.Panel
import com.vinex.chargemonitor.ui.components.SectionHeader
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.components.StatRow
import com.vinex.chargemonitor.ui.theme.Readout
import com.vinex.chargemonitor.ui.theme.SeriesColors

@Composable
fun DashboardScreen(modifier: Modifier = Modifier, vm: DashboardViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var info by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dashboard", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { info = true }) {
                Icon(Icons.Filled.Info, contentDescription = "How accurate are these numbers")
            }
        }

        FilterRow(ui.sources, ui.filter, vm::setFilter)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Include sessions used while charging", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Affects capacity estimates and average power. Full charges always count every session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ui.includeUsed, onCheckedChange = vm::setIncludeUsed)
        }

        if (ui.loaded && ui.cards.isEmpty()) {
            Text(
                "No sessions yet. Add your power banks in Settings, then charge as usual; " +
                    "every charge is recorded in the background.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ui.cards.forEach { card ->
            if (card.source.kind == SourceKind.POWER_BANK) BankCard(card, ui.lossFactor) else SourceCardView(card)
        }

        Charts(ui.chartSessions, ui.sources, ui.includeUsed)

        ui.health?.let { HealthPanel(it) }
    }

    if (info) AccuracyInfoSheet(onDismiss = { info = false })
}

@Composable
private fun FilterRow(sources: List<PowerSource>, selected: Long?, onSelect: (Long?) -> Unit) {
    val chips = sources.filter { !it.archived || it.id == selected }
        .sortedWith(compareBy<PowerSource> { it.builtIn }.thenBy { it.name.lowercase() })
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") })
        chips.forEach { s ->
            FilterChip(
                selected = selected == s.id,
                onClick = { onSelect(s.id) },
                label = { Text(s.name) },
                leadingIcon = { ColorDot(Color(s.colorArgb), size = 8.dp) },
            )
        }
    }
}

@Composable
private fun CardHeader(source: PowerSource, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        ColorDot(Color(source.colorArgb), size = 12.dp)
        Text(source.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        subtitle?.let {
            Text(it, style = Readout.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BankCard(card: SourceCard, lossFactor: Float) {
    Panel {
        CardHeader(card.source, card.source.ratedMah?.let { "${Format.mah(it.toDouble())} mAh rated" })
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.fullCharges(card.fullCharges), style = Readout.hero.copy(fontSize = Readout.hero.fontSize * 0.8f),
                color = MaterialTheme.colorScheme.primary)
            Text(
                "  full phone charges",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        MiniStats(card)
        CapacityBar(card.capacity, lossFactor)
        if (card.usedExcluded > 0) {
            Text(
                "${card.usedExcluded} session${if (card.usedExcluded == 1) "" else "s"} used while charging left out of the estimate.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceCardView(card: SourceCard) {
    Panel {
        val note = when (card.source.kind) {
            SourceKind.LAPTOP, SourceKind.WIRELESS -> "not counted toward banks"
            else -> null
        }
        CardHeader(card.source, note)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.fullCharges(card.fullCharges), style = Readout.tile, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "  full charges",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        MiniStats(card)
    }
}

@Composable
private fun MiniStats(card: SourceCard) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        Mini("Energy in", "${Format.wh(card.whIn)} Wh", Modifier.weight(1f))
        Mini("Sessions", card.sessionCount.toString(), Modifier.weight(1f))
        Mini("Avg power", "${Format.watts(card.avgW)} W", Modifier.weight(1f))
    }
}

@Composable
private fun Mini(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = Readout.small.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CapacityBar(estimate: StatsCalculator.CapacityEstimate?, lossFactor: Float) {
    val capacity = estimate ?: StatsCalculator.CapacityEstimate(null, null, null, 0.0, 0)
    val est = capacity.estimatedMah
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Real capacity", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                when {
                    est == null -> "not enough data"
                    capacity.ratedMah != null -> "est. ${Format.mah(est)} / ${Format.mah(capacity.ratedMah.toDouble())} mAh"
                    else -> "est. ${Format.mah(est)} mAh"
                },
                style = Readout.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val ratio = capacity.ratioOfRated
        if (ratio != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                LinearProgressIndicator(
                    progress = { ratio.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    strokeCap = StrokeCap.Round,
                )
                Text("${Format.percent(ratio)} %", style = Readout.small.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Text(
            if (est == null) {
                "Appears after the first charge from this bank. Leave the screen off while charging for the best estimate."
            } else {
                "From ${capacity.sessionsUsed} session${if (capacity.sessionsUsed == 1) "" else "s"}, " +
                    "${Format.wh(capacity.whIn)} Wh in, loss factor ${Format.f(lossFactor.toDouble(), 2)}."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Charts(sessions: List<ChargeSession>, sources: List<PowerSource>, includeUsed: Boolean) {
    val colorOf = remember(sources) { sources.associate { it.id to Color(it.colorArgb) } }
    val nameOf = remember(sources) { sources.associate { it.id to it.name } }
    val fallback = MaterialTheme.colorScheme.outline
    fun detail(s: ChargeSession) = "${Format.shortDay(s.startMs)} ${Format.clock(s.startMs)} · ${nameOf[s.sourceId] ?: "?"}"

    SectionHeader("Per session")
    Panel {
        Text("Percent added", style = MaterialTheme.typography.titleSmall)
        BarChart(
            bars = sessions.map { s ->
                Bar(s.levelAdded, colorOf[s.sourceId] ?: fallback, Format.shortDay(s.startMs), detail(s))
            },
            unit = "%",
            decimals = 1,
            reference = if (sessions.isNotEmpty()) 100f else null,
            description = "Percent added per session",
        )
    }

    Panel {
        Text("Cumulative full charges", style = MaterialTheme.typography.titleSmall)
        val xs = LongArray(sessions.size) { sessions[it].endMs ?: sessions[it].startMs }
        var run = 0.0
        val ys = FloatArray(sessions.size) { run += sessions[it].levelAdded / 100.0; run.toFloat() }
        LineChart(
            series = listOf(ChartSeries("Total", "", SeriesColors.power, xs, ys, fill = true, decimals = 2)),
            xLabel = { Format.shortDay(it) },
            emptyText = "No sessions yet",
            description = "Cumulative full charges over time",
        )
    }

    Panel {
        val admitted = sessions.filter { includeUsed || !it.usedWhileCharging }
        Text("Average power", style = MaterialTheme.typography.titleSmall)
        BarChart(
            bars = admitted.map { s ->
                Bar(s.avgW.toFloat(), colorOf[s.sourceId] ?: fallback, Format.shortDay(s.startMs), detail(s))
            },
            unit = "W",
            decimals = 1,
            description = "Average power per session",
        )
    }
}

@Composable
private fun HealthPanel(h: StatsCalculator.HealthEstimate) {
    SectionHeader("Phone battery (est.)")
    Panel {
        StatRow("Estimated capacity", h.estimatedMah?.let { "${Format.mah(it)} mAh" } ?: "not enough data")
        StatRow("Design capacity", h.designMah?.let { "${Format.mah(it.toDouble())} mAh" } ?: "set in Settings")
        StatRow("Health", h.healthPct?.let { "${Format.f(it, 0)} %" } ?: "--")
        Text(
            "Uses charges that added at least ${StatsCalculator.HEALTH_MIN_DELTA_PCT.toInt()} % with the screen mostly off " +
                "(${h.qualifyingSessions} so far).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
