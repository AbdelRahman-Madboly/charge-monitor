package com.vinex.chargemonitor.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import com.vinex.chargemonitor.ui.bank.BankReport
import com.vinex.chargemonitor.ui.components.StatTile
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.vinex.chargemonitor.ui.chart.ChartSeries
import com.vinex.chargemonitor.ui.chart.LineChart
import com.vinex.chargemonitor.ui.theme.SeriesColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vinex.chargemonitor.data.BankEvent
import com.vinex.chargemonitor.data.BankEventKind
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.stats.PhoneLoad
import com.vinex.chargemonitor.stats.StatsCalculator
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.components.ColorDot
import com.vinex.chargemonitor.ui.components.Panel
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.components.Tag
import com.vinex.chargemonitor.ui.theme.Readout

/** Compact bank summary; all test details live on the bank screen. */
@Composable
internal fun BankSummaryCard(card: SourceCard, rank: Int?, onMark: (MarkInput) -> Unit, onOpen: () -> Unit) {
    var marking by rememberSaveable(card.source.id) { mutableStateOf(false) }
    val c = card.capacity ?: StatsCalculator.CapacityEstimate.NONE
    val next = if (card.lastMark?.kind == BankEventKind.RECHARGED) BankEventKind.EMPTY else BankEventKind.RECHARGED
    Panel(Modifier.clickable(onClick = onOpen)) {
        CardHeader(card.source, rank, ratedLabel(card.source))
        Note(bankStatus(card))
        Button(onClick = { marking = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (next == BankEventKind.RECHARGED) "Mark recharged" else "Mark ran empty")
        }
        val empty = card.lastMark?.kind == BankEventKind.EMPTY
        val tile = Modifier.fillMaxWidth().fillMaxHeight()
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile(
                "Output / full bank", c.cycleOutputMah?.let { Format.mah(it) } ?: "—", "", tile.weight(1f),
                caption = "mAh @5V" + (c.outputRatio?.let { " · ${Format.percent(it)} % of rated" } ?: ""),
            )
            StatTile(
                "Phone charges", c.cyclePhoneCharges?.let { Format.f(it, 2) } ?: "—", "", tile.weight(1f),
                caption = "per full bank",
            )
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile(
                "Module efficiency", c.moduleEfficiency?.let { Format.percent(it) } ?: "—", "%", tile.weight(1f),
                caption = BankReport.efficiencyBasis(c),
            )
            StatTile(
                "Bank charge", if (empty) "Empty" else card.chargeLeft?.let { Format.percent(it) } ?: "—",
                if (empty || card.chargeLeft == null) "" else "%", tile.weight(1f),
                caption = when {
                    empty -> "marked ${Format.shortDay(card.lastMark!!.tMs)}"
                    card.chargeLeft != null -> "estimated left"
                    else -> "mark Recharged to start"
                },
            )
        }
    }
    if (marking) MarkDialog(null, next, onSave = { marking = false; onMark(it) }, onDelete = null, onDismiss = { marking = false })
}

internal fun bankStatus(card: SourceCard): String = when (card.lastMark?.kind) {
    BankEventKind.RECHARGED -> "Drain ${(card.capacity?.drains?.size ?: 0) + 1} running since ${Format.shortDay(card.lastMark.tMs)}" +
        (card.chargeLeft?.let { " · ~${Format.percent(it)} % left" } ?: "")
    BankEventKind.EMPTY -> "Empty since ${Format.shortDay(card.lastMark.tMs)}: charge it full, then mark Recharged"
    null -> "No marks yet"
}

/** The energy flow as one stacked bar: battery, phone use, cable and charger, bank module. */
@Composable
internal fun FlowBar(e: StatsCalculator.EnergyTotals) {
    val parts = listOf(
        Triple("Battery", e.batteryWh, MaterialTheme.colorScheme.primary),
        Triple("Phone use", e.phoneUseWh, SeriesColors.level),
        Triple("Cable + charger", e.phoneLossWh, SeriesColors.current),
        Triple("Bank module", e.bankLossWh, SeriesColors.voltage),
    ).filter { it.second > 0.0 }
    val total = parts.sumOf { it.second }.takeIf { it > 0.0 } ?: return
    Canvas(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
        var x = 0f
        parts.forEach { (_, wh, color) ->
            val w = (wh / total * size.width).toFloat()
            drawRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height))
            x += w
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                row.forEach { (label, wh, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        ColorDot(color, size = 8.dp)
                        Text(
                            "  $label ${Format.percent(wh / total)} %",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** Charge left since the last Recharged mark, one step per session, so a drain reads as a curve. */
@Composable
internal fun ChargeHistoryChart(card: SourceCard) {
    val pts = card.chargeHistory
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text("Charge left since Recharged", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        LineChart(
            series = listOf(
                ChartSeries(
                    "Charge left", "%", MaterialTheme.colorScheme.primary,
                    LongArray(pts.size) { pts[it].first },
                    FloatArray(pts.size) { (pts[it].second * 100).toFloat() },
                    fill = true, decimals = 0,
                ),
            ),
            height = 140.dp,
            xLabel = { "${Format.shortDay(it)} ${Format.clock(it)}" },
            description = "Bank charge left after each session since it was recharged",
        )
    }
}

/** One labelled figure on one line, with how it was obtained under the label. */
@Composable
internal fun Figure(label: String, value: String, how: String) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = Space.s)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(how, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = Readout.small.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Charger and other cards: the same split on one line. */
private fun deliveredLine(d: PhoneLoad.Delivery?): String? {
    if (d == null || d.phoneUseMah < 0.5) return null
    return "~${Format.mah(d.deliveredMah)} mAh delivered (incl. ~${Format.mah(d.phoneUseMah)} phone use)"
}

@Composable
internal fun RefillMarks(
    lastMark: BankEvent?,
    marks: List<BankEvent>,
    onMark: (MarkInput) -> Unit,
    onEditMark: (BankEvent) -> Unit,
    onDeleteMark: (Long) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf<BankEventKind?>(null) }
    var listOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val compact = PaddingValues(horizontal = Space.m, vertical = Space.xs)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { adding = BankEventKind.RECHARGED },
            contentPadding = compact,
            modifier = Modifier.weight(1f),
        ) { Text("Recharged") }
        OutlinedButton(
            onClick = { adding = BankEventKind.EMPTY },
            contentPadding = compact,
            modifier = Modifier.weight(1f),
        ) { Text("Ran empty") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val last = lastMark?.let { m ->
            val what = if (m.kind == BankEventKind.RECHARGED) "Recharged" else "Ran empty"
            "Last marked: $what, ${Format.shortDay(m.tMs)} ${Format.clock(m.tMs)}" + (m.cellV?.let { ", ${Format.f(it, 2)} V" } ?: "")
        } ?: "No marks yet."
        Note(last, modifier = Modifier.weight(1f))
        TextButton(onClick = { listOpen = true }) { Text("Edit marks") }
    }

    adding?.let { kind ->
        MarkDialog(
            initial = null,
            kind = kind,
            onSave = { adding = null; onMark(it) },
            onDelete = null,
            onDismiss = { adding = null },
        )
    }
    if (listOpen) {
        MarksDialog(
            marks = marks,
            onEdit = { editingId = it.id; listOpen = false },
            onAdd = { listOpen = false; adding = BankEventKind.RECHARGED },
            onDismiss = { listOpen = false },
        )
    }
    editingId?.let { id ->
        val mark = marks.firstOrNull { it.id == id }
        if (mark == null) {
            editingId = null
        } else {
            MarkDialog(
                initial = mark,
                kind = mark.kind,
                onSave = { m -> editingId = null; onEditMark(mark.copy(kind = m.kind, tMs = m.tMs, cellV = m.cellV, meterWh = m.meterWh)) },
                onDelete = { editingId = null; onDeleteMark(mark.id) },
                onDismiss = { editingId = null },
            )
        }
    }
}

/**
 * The product as it is sold: the advertised capacity, the rated output under ideal conditions,
 * what is inside, and the measured result against each claim.
 */
@Composable
internal fun ProductBlock(source: PowerSource, capacity: StatsCalculator.CapacityEstimate) {
    val rows = buildList {
        source.ratedMah?.takeIf { it > 0 }?.let {
            add("Advertised capacity" to "${Format.mah(it.toDouble())} mAh @${Format.f(source.nominalV, 1)}V · ${Format.wh(source.ratedWh)} Wh")
        }
        source.outputMah?.takeIf { it > 0 }?.let {
            add("Rated output (ideal)" to "${Format.mah(it.toDouble())} mAh @5V · ${Format.wh(source.outputWh)} Wh")
        }
        source.cellsMah?.takeIf { it > 0 }?.let { add("Cell capacity (datasheet)" to "${Format.mah(it.toDouble())} mAh") }
        source.cells?.takeIf { it.isNotBlank() }?.let { add("Cells" to it) }
        source.module?.takeIf { it.isNotBlank() }?.let { add("Module" to it) }
        source.ratedW?.let { add("Max output" to "${Format.watts(it)} W") }
        source.cutoffV?.let { add("Cutoff" to "${Format.f(it, 1)} V") }
    }
    if (rows.isEmpty() && source.notes.isNullOrBlank()) return
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text("Product", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            rows.forEach { (label, value) ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.45f).padding(end = Space.s),
                    )
                    Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
                }
            }
            source.notes?.takeIf { it.isNotBlank() }?.let { Note(it) }
            capacity.cycleOutputMah?.let { out ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val vsOutput = source.outputMah?.takeIf { it > 0 }?.let { " = ${Format.percent(out / it)} % of the rated output" } ?: ""
                Note("Estimated full-bank output: ${Format.mah(out)} mAh @5V per full bank$vsOutput.")
            }
        }
    }
}

/**
 * A wall charger's result: peak power into the battery against its rating, then average power
 * and speed. Measured at the battery, so it describes the charger and the phone together.
 */
@Composable
internal fun ChargerCard(card: SourceCard, rank: Int?) {
    val stats = card.charger ?: StatsCalculator.ChargerStats(null, null, null, 0)
    val rated = card.source.ratedW?.let { "${Format.watts(it)} W rated" } ?: if (card.source.builtIn) "any charger" else null
    Panel {
        CardHeader(card.source, rank, joinDot(rated, lastUsed(card.lastUsedMs)))
        if (card.sessionCount == 0) {
            Note("No sessions yet. Plug in, pick this charger on the \"Connected to?\" prompt, and charge with the screen off.")
            return@Panel
        }
        stats.peakW?.let { Hero(Format.watts(it), "W peak") }
        stats.peakOfRated?.let { ratio ->
            Meter(label = "Of rated", value = "${Format.percent(ratio)} %", ratio = ratio)
        }
        StatLine(
            avgPower(card.avgW),
            stats.pctPerHour?.takeIf { it > 0.0 }?.let { "${Format.f(it, 0)} %/h" },
            plural(card.sessionCount, "session"),
        )
        StatLine(
            energy(card.whIn),
            card.fullCharges.takeIf { it > 0.0 }?.let { "${Format.fullCharges(it)} phone charges" },
            stats.fullCount.takeIf { it > 0 }?.let { "$it× to 100 %" },
        )
        deliveredLine(card.delivery)?.let { Note(it) }
    }
}

/** Laptop and wireless: logged for reference, never counted toward a bank. */
@Composable
internal fun OtherCard(card: SourceCard, rank: Int?) {
    Panel {
        CardHeader(card.source, rank, joinDot("not counted toward banks", lastUsed(card.lastUsedMs)))
        Hero(Format.fullCharges(card.fullCharges), "phone charges")
        StatLine(
            energy(card.whIn),
            card.sessionCount.takeIf { it > 0 }?.let { plural(it, "session") },
            avgPower(card.avgW),
        )
        deliveredLine(card.delivery)?.let { Note(it) }
    }
}

@Composable
private fun CardHeader(source: PowerSource, rank: Int?, subtitle: String?) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        ColorDot(Color(source.colorArgb), size = 12.dp, modifier = Modifier.padding(top = 5.dp))
        Column(Modifier.weight(1f)) {
            Text(
                source.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        rank?.let { Tag("#$it", color = if (it == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Hero(value: String, caption: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = Readout.tile.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        Text(
            "  $caption",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

/** Keep each readout on its own line whenever the pair exceeds the available width. */
@Composable
internal fun Meter(label: String, value: String, ratio: Double?) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.bodyMedium
    val valueStyle = Readout.small.copy(fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val required = measurer.measure(label, labelStyle, softWrap = false).size.width +
                measurer.measure(value, valueStyle, softWrap = false).size.width + with(density) { Space.m.toPx() }
            if (required <= with(density) { maxWidth.toPx() }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = valueStyle)
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(label, style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = valueStyle, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                }
            }
        }
        if (ratio != null && ratio.isFinite()) {
            LinearProgressIndicator(
                progress = { ratio.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

/** Secondary figures on one wrapping line; a missing or zero figure is left out rather than shown as "--". */
@Composable
private fun StatLine(vararg parts: String?) {
    val text = parts.filterNotNull().joinToString("  ·  ")
    if (text.isNotEmpty()) {
        Text(text, style = Readout.small, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun Note(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier)
}

internal fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

private fun ratedLabel(source: PowerSource): String? {
    val mah = source.ratedMah?.takeIf { it > 0 } ?: return null
    val output = source.outputMah?.takeIf { it > 0 }?.let { " · ${Format.mah(it.toDouble())} mAh @5V" }
        ?: source.ratedWh?.let { " · ${Format.wh(it)} Wh" } ?: ""
    return "${Format.mah(mah.toDouble())} mAh$output"
}

private fun lastUsed(tMs: Long?): String? = tMs?.let { "used ${Format.shortDay(it)}" }

private fun joinDot(vararg parts: String?): String? =
    parts.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" · ")

private fun energy(wh: Double): String? = wh.takeIf { it >= 0.005 }?.let { "${Format.wh(it)} Wh in" }

private fun avgPower(w: Double?): String? = w?.takeIf { it >= 0.05 }?.let { "avg ${Format.watts(it)} W" }
