package com.vinex.chargemonitor.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
import com.vinex.chargemonitor.ui.components.ColorDot
import com.vinex.chargemonitor.ui.components.Panel
import com.vinex.chargemonitor.ui.components.SectionHeader
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.components.StatRow
import com.vinex.chargemonitor.ui.components.StatTile
import com.vinex.chargemonitor.ui.theme.SeriesColors

@Composable
fun DashboardScreen(modifier: Modifier = Modifier, vm: DashboardViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var info by rememberSaveable { mutableStateOf(false) }
    var chartsOpen by rememberSaveable { mutableStateOf(false) }

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
                Icon(Icons.Filled.Info, contentDescription = "How to read these numbers")
            }
        }

        if (ui.loaded && ui.cards.isEmpty()) {
            Panel {
                Text("Nothing recorded yet", style = MaterialTheme.typography.titleSmall)
                Note(
                    "Add your power banks and chargers in Settings, then charge as usual; " +
                        "every charge is recorded in the background.",
                )
            }
        }

        if (ui.summary.sessions > 0) Summary(ui.summary)

        if (ui.tabs.isNotEmpty()) {
            TabSelector(ui.tabs, ui.tab, vm::setTab)
            SortBar(ui.tab, ui.tabCards.size, ui.sort) { vm.setSort(ui.tab, it) }
            if (ui.tabCards.isEmpty()) {
                Note("Nothing in this group yet.")
            }
            ui.tabCards.forEachIndexed { index, card ->
                val rank = if (ui.tabCards.size > 1 && ui.sort != DashboardSort.NAME) index + 1 else null
                key(card.source.id) {
                    when (card.source.kind) {
                        SourceKind.POWER_BANK -> BankCard(
                            card,
                            rank,
                            ui.lossFactor,
                            onMark = { kind -> vm.markBank(card.source.id, kind) },
                            onUndo = { id -> vm.undoMark(id) },
                        )
                        SourceKind.CHARGER -> ChargerCard(card, rank)
                        SourceKind.LAPTOP, SourceKind.WIRELESS -> OtherCard(card, rank)
                    }
                }
            }
        }

        if (ui.cards.isNotEmpty()) IncludeUsedToggle(ui.includeUsed, vm::setIncludeUsed)

        if (ui.summary.sessions > 0) {
            SectionHeader("Charts") {
                TextButton(onClick = { chartsOpen = !chartsOpen }) {
                    Text(if (chartsOpen) "Hide" else "Show")
                    Icon(
                        if (chartsOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
            }
            if (chartsOpen) {
                ChartSourcePicker(ui.sources, ui.filter, vm::setFilter)
                Charts(ui.chartSessions, ui.sources, ui.includeUsed)
            }
        }

        ui.health?.let { HealthPanel(it) }
    }

    if (info) DashboardInfoSheet(onDismiss = { info = false })
}

@Composable
private fun Summary(summary: DashboardSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        val fair = summary.fairFullCharges
        StatTile(
            if (fair != null) "Phone charges (incl. use)" else "Phone charges",
            Format.fullCharges(fair ?: summary.fullCharges),
            "",
            Modifier.weight(1f),
            accent = MaterialTheme.colorScheme.primary,
        )
        StatTile("Energy, Wh", Format.wh(summary.whIn), "", Modifier.weight(1f))
        StatTile("Sessions", summary.sessions.toString(), "", Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabSelector(tabs: List<DashboardTab>, selected: DashboardTab, onSelect: (DashboardTab) -> Unit) {
    // A lone group needs no switch, only a heading.
    if (tabs.size == 1 && tabs.first() == selected) {
        SectionHeader(selected.label)
        return
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        tabs.forEachIndexed { i, tab ->
            SegmentedButton(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                icon = {},
                label = {
                    Text(
                        if (tabs.size > 2 && tab == DashboardTab.BANKS) "Banks" else tab.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun SortBar(tab: DashboardTab, count: Int, sort: DashboardSort, onSort: (DashboardSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val noun = when (tab) {
            DashboardTab.BANKS -> "bank"
            DashboardTab.CHARGERS -> "charger"
            DashboardTab.OTHER -> "source"
        }
        Text(
            plural(count, noun),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { open = true }) {
                Text("Sort: ${sort.label}", maxLines = 1)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                tab.sorts.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = { onSort(option); open = false },
                        trailingIcon = if (option == sort) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun IncludeUsedToggle(includeUsed: Boolean, onChange: (Boolean) -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.s)) {
                Text("Include sessions used while charging", style = MaterialTheme.typography.bodyLarge)
                Note("Affects capacity, power and speed. Phone charges always count every session.")
            }
            Switch(checked = includeUsed, onCheckedChange = onChange)
        }
    }
}

/** Narrows the charts to one source; the cards above always show every source. */
@Composable
private fun ChartSourcePicker(sources: List<PowerSource>, selected: Long?, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = sources.filter { !it.archived || it.id == selected }
        .sortedWith(compareBy<PowerSource> { it.kind.ordinal }.thenBy { it.builtIn }.thenBy { it.name.lowercase() })
    val current = sources.firstOrNull { it.id == selected }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text("Showing", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            current?.let { ColorDot(Color(it.colorArgb), size = 8.dp) }
            Text(
                current?.name ?: "All sources",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose a source")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All sources") }, onClick = { onSelect(null); open = false })
            var lastKind: SourceKind? = null
            options.forEach { s ->
                if (lastKind != null && lastKind != s.kind) HorizontalDivider()
                lastKind = s.kind
                DropdownMenuItem(
                    text = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { ColorDot(Color(s.colorArgb), size = 10.dp) },
                    onClick = { onSelect(s.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun Charts(sessions: List<ChargeSession>, sources: List<PowerSource>, includeUsed: Boolean) {
    val colorOf = remember(sources) { sources.associate { it.id to Color(it.colorArgb) } }
    val nameOf = remember(sources) { sources.associate { it.id to it.name } }
    val fallback = MaterialTheme.colorScheme.outline
    fun detail(s: ChargeSession) = "${Format.shortDay(s.startMs)} ${Format.clock(s.startMs)} · ${nameOf[s.sourceId] ?: "?"}"

    Panel {
        Text("Percent added per session", style = MaterialTheme.typography.titleSmall)
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
        Text("Cumulative phone charges", style = MaterialTheme.typography.titleSmall)
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
        Text("Average power per session", style = MaterialTheme.typography.titleSmall)
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
        if (h.estimatedMah == null) {
            Note(
                "Not enough data yet. Needs a charge that adds at least " +
                    "${StatsCalculator.HEALTH_MIN_DELTA_PCT.toInt()} % with the screen mostly off.",
            )
        } else {
            StatRow("Estimated capacity", "${Format.mah(h.estimatedMah)} mAh")
        }
        StatRow("Design capacity", h.designMah?.let { "${Format.mah(it.toDouble())} mAh" } ?: "set in Settings")
        h.healthPct?.let { StatRow("Health", "${Format.f(it, 0)} %") }
        if (h.estimatedMah != null) {
            Note("From ${plural(h.qualifyingSessions, "qualifying charge")}.")
        }
    }
}
