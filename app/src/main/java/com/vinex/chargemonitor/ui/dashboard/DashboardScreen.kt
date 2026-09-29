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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.vinex.chargemonitor.data.BankEvent
import com.vinex.chargemonitor.data.BankEventKind
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

        if (ui.loaded && ui.cards.isEmpty()) {
            Text(
                "No sessions yet. Add your power banks and chargers in Settings, then charge as usual; " +
                    "every charge is recorded in the background.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val bankCards = ui.cards.filter { it.source.kind == SourceKind.POWER_BANK }
        val chargerCards = ui.cards.filter { it.source.kind == SourceKind.CHARGER }
        val otherCards = ui.cards.filter { it.source.kind == SourceKind.LAPTOP || it.source.kind == SourceKind.WIRELESS }

        if (bankCards.isNotEmpty()) {
            SectionHeader("Power banks")
            if (ui.filter == null && bankCards.size >= 2) CompareBanks(bankCards)
            bankCards.forEach { card ->
                BankCard(
                    card,
                    ui.lossFactor,
                    onMark = { kind -> vm.markBank(card.source.id, kind) },
                    onUndo = { id -> vm.undoMark(id) },
                )
            }
        }
        if (chargerCards.isNotEmpty()) {
            SectionHeader("Chargers")
            chargerCards.forEach { ChargerCard(it) }
        }
        if (otherCards.isNotEmpty()) {
            SectionHeader("Other sources")
            otherCards.forEach { SourceCardView(it) }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = Space.s)) {
                    Text("Include sessions used while charging", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Affects capacity, average power and charging speed. Full charges always count every session.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ui.includeUsed, onCheckedChange = vm::setIncludeUsed)
            }
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

/** Name on its own line, rating under it, so a long name never squeezes the figures. */
@Composable
private fun CardHeader(source: PowerSource, subtitle: String?) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        ColorDot(Color(source.colorArgb), size = 12.dp, modifier = Modifier.padding(top = 6.dp))
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = Readout.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BankCard(card: SourceCard, lossFactor: Float, onMark: (BankEventKind) -> Unit, onUndo: (Long) -> Unit) {
    Panel {
        CardHeader(card.source, ratedLabel(card.source))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.fullCharges(card.fullCharges), style = Readout.hero.copy(fontSize = Readout.hero.fontSize * 0.75f),
                color = MaterialTheme.colorScheme.primary)
            Text(
                "  phone charges so far",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        PerDrain(card)
        MiniStats(card)
        CapacitySection(card.capacity, lossFactor)
        RefillMarks(card.lastMark, onMark, onUndo)
        if (card.usedExcluded > 0) {
            Note("${plural(card.usedExcluded, "session")} used while charging left out of the capacity estimate.")
        }
        if (card.unconfirmedCount > 0) {
            Note(
                "${plural(card.unconfirmedCount, "session")} (+${Format.fullCharges(card.unconfirmedFullCharges)} full charges) " +
                    "not counted: the source prompt was not answered. Confirm them in History.",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun ratedLabel(source: PowerSource): String? {
    val mah = source.ratedMah ?: return null
    val wh = source.ratedWh?.let { " · ${Format.wh(it)} Wh" } ?: ""
    return "${Format.mah(mah.toDouble())} mAh$wh"
}

/**
 * The number a bank is bought for: phone charges from one full bank. Measured once a drain is
 * marked; until then the label's promise, so there is something to hold the result against.
 */
@Composable
private fun PerDrain(card: SourceCard) {
    val measured = card.capacity?.cyclePhoneCharges
    val promised = card.labelPhoneCharges
    if (measured == null && promised == null) return
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (measured != null) {
            StatRow("Phone charges per full bank", Format.f(measured, 2))
        }
        if (promised != null) {
            StatRow("Label promises", "≈ ${Format.f(promised, 1)}")
        }
    }
}

/**
 * Every bank on one panel, so a bought bank and a built one can be read against each other
 * without scrolling between cards. Wh, not mAh, is the comparable size: two labels in mAh
 * only compare when both are rated at the same cell voltage.
 */
@Composable
private fun CompareBanks(cards: List<SourceCard>) {
    Panel {
        Text("Compare banks", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            CompareCell("", Modifier.weight(1.6f), header = true)
            CompareCell("Rated Wh", Modifier.weight(1f), header = true)
            CompareCell("Real", Modifier.weight(0.8f), header = true)
            CompareCell("Per bank", Modifier.weight(1f), header = true)
            CompareCell("Avg W", Modifier.weight(0.8f), header = true)
        }
        cards.forEach { card ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(
                    Modifier.weight(1.6f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    ColorDot(Color(card.source.colorArgb), size = 8.dp)
                    Text(card.source.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
                CompareCell(Format.wh(card.source.ratedWh), Modifier.weight(1f))
                CompareCell(card.capacity?.ratioOfRated?.let { "${Format.percent(it)} %" } ?: "--", Modifier.weight(0.8f))
                val perBank = card.capacity?.cyclePhoneCharges?.let { Format.f(it, 2) }
                    ?: card.labelPhoneCharges?.let { "≈${Format.f(it, 1)}" }
                    ?: "--"
                CompareCell(perBank, Modifier.weight(1f))
                CompareCell(Format.watts(card.avgW), Modifier.weight(0.8f))
            }
        }
        Note(
            "Real: measured capacity against the label, after one marked drain. Per bank: full phone " +
                "charges from one full bank; ≈ is the label's promise until a drain is measured.",
        )
    }
}

@Composable
private fun CompareCell(text: String, modifier: Modifier = Modifier, header: Boolean = false) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelMedium else Readout.small,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/**
 * A wall charger's test result. Everything is measured at the battery, so the numbers describe
 * the charger and the phone together; the note says so, because a 30 W charger on a phone that
 * accepts 25 W will never show 30.
 */
@Composable
private fun ChargerCard(card: SourceCard) {
    val stats = card.charger ?: StatsCalculator.ChargerStats(null, null, null, 0)
    val rated = card.source.ratedW
    Panel {
        CardHeader(card.source, rated?.let { "${Format.watts(it)} W rated" } ?: if (card.source.builtIn) "any charger" else null)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Format.watts(stats.peakW),
                style = Readout.hero.copy(fontSize = Readout.hero.fontSize * 0.75f),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "  W peak into the battery",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        stats.peakOfRated?.let { ratio ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                LinearProgressIndicator(
                    progress = { ratio.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    strokeCap = StrokeCap.Round,
                )
                Text("${Format.percent(ratio)} % of rated", style = Readout.small.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Mini("Avg power", "${Format.watts(card.avgW)} W", Modifier.weight(1f))
            Mini("Speed", stats.pctPerHour?.let { "${Format.f(it, 0)} %/h" } ?: "--", Modifier.weight(1f))
            Mini("Sessions", card.sessionCount.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Mini("Energy in", "${Format.wh(card.whIn)} Wh", Modifier.weight(1f))
            Mini("Full charges", Format.fullCharges(card.fullCharges), Modifier.weight(1f))
            Mini("Reached 100 %", stats.fullCount.toString(), Modifier.weight(1f))
        }
        if (card.sessionCount == 0) {
            Note("No sessions yet. Plug in, pick this charger on the \"Connected to?\" prompt, and charge with the screen off.")
        } else {
            Note("Measured at the battery, after the phone's own charging circuit. Speed includes the slow top-up above 80 %.")
        }
    }
}

@Composable
private fun Note(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier)
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

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

/**
 * The capacity bar only appears once a full drain has been marked; before that the honest
 * figure is energy delivered so far, which keeps growing and so never gets a bar.
 */
@Composable
private fun CapacitySection(estimate: StatsCalculator.CapacityEstimate?, lossFactor: Float) {
    val capacity = estimate ?: StatsCalculator.CapacityEstimate.NONE
    val est = capacity.cycleMah
    val rated = capacity.ratedMah
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Real capacity", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                when {
                    est == null -> "needs one full drain"
                    rated != null -> "est. ${Format.mah(est)} / ${Format.mah(rated.toDouble())} mAh"
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
        val loss = "loss factor ${Format.f(lossFactor.toDouble(), 2)}"
        if (est != null) {
            Note(
                "Median of ${plural(capacity.completedCycles, "full drain")}, $loss." +
                    if (capacity.skippedCycles > 0) " ${plural(capacity.skippedCycles, "drain")} left out." else "",
            )
        }
        capacity.sinceRechargeWh?.let { wh ->
            val share = rated?.let { r -> capacity.sinceRechargeMah?.let { " (${Format.percent(it / r)} % of the label)" } } ?: ""
            Note("Since the last recharge: ${Format.wh(wh)} Wh$share.")
        }
        if (capacity.sessionsUsed > 0) {
            val fulls = capacity.deliveredBankFulls?.let { " ≈ ${Format.f(it, 2)} bank-fulls" } ?: ""
            Note("Delivered so far: ${Format.wh(capacity.deliveredWh)} Wh$fulls (est., $loss).")
        }
        if (est == null) {
            Note("Tap Recharged when the bank is full and Ran empty when it dies to measure its real capacity.")
        }
    }
}

@Composable
private fun RefillMarks(lastMark: BankEvent?, onMark: (BankEventKind) -> Unit, onUndo: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onMark(BankEventKind.RECHARGED) }, modifier = Modifier.weight(1f)) { Text("Recharged") }
        OutlinedButton(onClick = { onMark(BankEventKind.EMPTY) }, modifier = Modifier.weight(1f)) { Text("Ran empty") }
    }
    if (lastMark != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val what = if (lastMark.kind == BankEventKind.RECHARGED) "Recharged" else "Ran empty"
            Note(
                "Last marked: $what, ${Format.shortDay(lastMark.tMs)} ${Format.clock(lastMark.tMs)}",
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onUndo(lastMark.id) }) { Text("Undo") }
        }
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
