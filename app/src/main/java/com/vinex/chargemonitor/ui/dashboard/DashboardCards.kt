package com.vinex.chargemonitor.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.vinex.chargemonitor.ui.chart.ChartSeries
import com.vinex.chargemonitor.ui.chart.LineChart
import com.vinex.chargemonitor.ui.theme.SeriesColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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

/**
 * A power bank at a glance: phone charges so far, how much charge it has left since it was
 * recharged, what one full bank gives against the label, real capacity once a drain is marked,
 * and where the energy went. The working behind each figure sits under Details.
 */
@Composable
internal fun BankCard(
    card: SourceCard,
    rank: Int?,
    onMark: (BankEventKind) -> Unit,
    onUndo: (Long) -> Unit,
) {
    var details by rememberSaveable(card.source.id) { mutableStateOf(false) }
    val capacity = card.capacity ?: StatsCalculator.CapacityEstimate.NONE
    Panel {
        CardHeader(card.source, rank, joinDot(ratedLabel(card.source), lastUsed(card.lastUsedMs)))
        Hero(Format.fullCharges(card.fullCharges), "phone charges so far")

        BankGauge(card)

        val measured = capacity.cyclePhoneCharges
        val promised = card.labelPhoneCharges
        if (measured != null) {
            Meter(
                label = "Per full bank",
                value = "${Format.f(measured, 2)}${promised?.let { " of ≈${Format.f(it, 1)}" } ?: ""}",
                ratio = promised?.takeIf { it > 0.0 }?.let { measured / it },
            )
        }
        val ratio = capacity.ratioOfRated
        val est = capacity.cycleMah
        if (est != null) {
            val rated = capacity.ratedMah?.let { " / ${Format.mah(it.toDouble())}" } ?: ""
            Meter(
                label = "Real capacity",
                value = "${ratio?.let { "${Format.percent(it)} % · " } ?: ""}${Format.mah(est)}$rated mAh",
                ratio = ratio,
            )
        } else {
            MeasureCallout(promised, capacity)
        }

        EnergyFlowBlock(card)

        if (card.chargeHistory.size >= 2) ChargeHistoryChart(card)

        StatLine(
            energy(card.whIn),
            card.sessionCount.takeIf { it > 0 }?.let { plural(it, "session") },
            avgPower(card.avgW),
        )

        if (card.unconfirmedCount > 0) {
            Note(
                "${plural(card.unconfirmedCount, "session")} (+${Format.fullCharges(card.unconfirmedFullCharges)}) " +
                    "not counted until you confirm the source in History.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        RefillMarks(card.lastMark, onMark, onUndo)

        DetailsToggle(details) { details = !details }
        if (details) BankDetails(card, capacity)
    }
}

/** Before the first marked drain: say how to measure, and how far into the current charge the bank is. */
@Composable
private fun MeasureCallout(promised: Double?, capacity: StatsCalculator.CapacityEstimate) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                "Mark a full drain to measure real capacity",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Tap Recharged when the bank is full, Ran empty when it dies." +
                    (promised?.let { " The label promises ≈${Format.f(it, 1)} phone charges per full bank." } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BankDetails(card: SourceCard, capacity: StatsCalculator.CapacityEstimate) {
    val eff = card.efficiency?.let {
        "${Format.percent(it)} % cells to battery, " +
            if (card.efficiencyFromSessions) "from this bank's sessions" else "the model at a typical charge"
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        card.labelPhoneCharges?.let { p ->
            Note("Label promise: ≈${Format.f(p, 1)} phone charges per full bank${eff?.let { " ($it)" } ?: ""}.")
        }
        card.source.ratedWh?.let { Note("Rated energy: ${Format.wh(it)} Wh at ${Format.f(card.source.nominalV, 2)} V.") }
        if (capacity.cycleMah != null) {
            Note(
                "Real capacity is the median cell energy of ${plural(capacity.completedCycles, "full drain")}." +
                    (capacity.cycleBatteryWh?.let { " One full bank put ${Format.wh(it)} Wh into the phone battery." } ?: "") +
                    if (capacity.skippedCycles > 0) " ${plural(capacity.skippedCycles, "drain")} left out." else "",
            )
        }
        if (capacity.sessionsUsed > 0 && capacity.drawnWh > 0.0) {
            val fulls = capacity.deliveredBankFulls?.let { " ≈ ${Format.f(it, 2)} bank-fulls" } ?: ""
            Note("Taken from the cells so far: ${Format.wh(capacity.drawnWh)} Wh$fulls (est.).")
        }
        Note(
            "Losses are worked out for every reading at the power it happened at: the bank's boost module is about " +
                "89 to 91 % from 3 to 18 W (pack resistance included) and falls under 80 % below 0.5 W; the cable and phone charger about 91 %; " +
                "and the phone's own draw while plugged in (set in Settings) is energy the bank gave that never " +
                "reached the battery. That is why the slow end of a charge costs the bank far more per percent.",
        )
        if (card.usedExcluded > 0) {
            Note("${plural(card.usedExcluded, "session")} used while charging left out of capacity and power.")
        }
    }
}

/**
 * Where the bank's energy went, in the order it flows: out of the cells (modelled), lost in the
 * bank's boost module, lost in the cable and the phone's charger, used by the phone while it
 * charged, and into the battery (measured). Efficiency is the last over the first.
 */
@Composable
private fun EnergyFlowBlock(card: SourceCard) {
    val e = card.energy ?: return
    if (e.cellsWh < 0.005) return
    val volts = card.source.nominalV.takeIf { it > 0.0 } ?: 3.7
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text("Energy flow", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Figure(
                "Taken from the cells",
                "${Format.wh(e.cellsWh)} Wh",
                "estimated · ${Format.mah(e.cellsWh / volts * 1000.0)} mAh at ${Format.f(volts, 1)} V",
            )
            Figure("Bank module loss", "−${Format.wh(e.bankLossWh)} Wh", "estimated, heat in the boost converter")
            Figure("Cable and phone charger loss", "−${Format.wh(e.phoneLossWh)} Wh", "estimated")
            if (e.phoneUseWh >= 0.005) {
                Figure("Phone ran on while plugged in", "−${Format.wh(e.phoneUseWh)} Wh", "estimated from the screen state")
            }
            FlowBar(e)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val battery = card.delivery?.intoBatteryMah?.let { "measured · ${Format.mah(it)} mAh" } ?: "measured"
            Figure("Into the phone battery", "${Format.wh(e.batteryWh)} Wh", battery)
            e.efficiency?.let { Meter(label = "Efficiency, cells to battery", value = "${Format.percent(it)} %", ratio = it) }
        }
    }
}

/**
 * The bank's charge as a big percentage over a bar split into four, like the module's four level
 * lights, so the app's estimate can be checked against the lights at a glance. Before the first
 * Recharged mark there is nothing to count from, so it says so.
 */
@Composable
private fun BankGauge(card: SourceCard) {
    val left = card.chargeLeft
    val primary = MaterialTheme.colorScheme.primary
    val low = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "Bank charge",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                )
                Text(
                    left?.let { Format.percent(it) } ?: "--",
                    style = Readout.tile.copy(fontWeight = FontWeight.Bold),
                    color = if (left != null && left < 0.15) low else MaterialTheme.colorScheme.onSurface,
                )
                Text(" %", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val gap = 4.dp.toPx()
                val seg = (size.width - gap * 3) / 4
                val r = CornerRadius(3.dp.toPx())
                for (i in 0 until 4) {
                    val x = i * (seg + gap)
                    drawRoundRect(track, topLeft = Offset(x, 0f), size = Size(seg, size.height), cornerRadius = r)
                    val fill = ((left ?: 0.0) * 4 - i).coerceIn(0.0, 1.0).toFloat()
                    if (fill > 0f) {
                        drawRoundRect(
                            if ((left ?: 0.0) < 0.15) low else primary,
                            topLeft = Offset(x, 0f),
                            size = Size(seg * fill, size.height),
                            cornerRadius = r,
                        )
                    }
                }
            }
            if (left != null) {
                val lights = kotlin.math.ceil(left * 4).toInt().coerceIn(0, 4)
                Text(
                    listOfNotNull(
                        card.phoneChargesLeft?.let { "≈${Format.f(it, 1)} phone charges left" },
                        "module should show $lights of 4 lights",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Charge the bank to full, then tap Recharged below to start counting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The energy flow as one stacked bar: battery, phone use, cable and charger, bank module. */
@Composable
private fun FlowBar(e: StatsCalculator.EnergyTotals) {
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
private fun ChargeHistoryChart(card: SourceCard) {
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

/** One labelled mAh figure with how it was obtained underneath. */
@Composable
private fun Figure(label: String, value: String, how: String) {
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
private fun RefillMarks(lastMark: BankEvent?, onMark: (BankEventKind) -> Unit, onUndo: (Long) -> Unit) {
    var confirmUndo by rememberSaveable { mutableStateOf(false) }
    val compact = PaddingValues(horizontal = Space.m, vertical = Space.xs)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onMark(BankEventKind.RECHARGED) },
            contentPadding = compact,
            modifier = Modifier.weight(1f),
        ) { Text("Recharged") }
        OutlinedButton(
            onClick = { onMark(BankEventKind.EMPTY) },
            contentPadding = compact,
            modifier = Modifier.weight(1f),
        ) { Text("Ran empty") }
    }
    if (lastMark != null) {
        val what = if (lastMark.kind == BankEventKind.RECHARGED) "Recharged" else "Ran empty"
        val whenText = "${Format.shortDay(lastMark.tMs)} ${Format.clock(lastMark.tMs)}"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Note("Last marked: $what, $whenText", modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmUndo = true }) { Text("Undo") }
        }
        if (confirmUndo) {
            AlertDialog(
                onDismissRequest = { confirmUndo = false },
                title = { Text("Remove this mark?") },
                text = { Text("\"$what\" from $whenText will be removed and capacity recalculated without it.") },
                confirmButton = {
                    TextButton(onClick = { confirmUndo = false; onUndo(lastMark.id) }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Cancel") } },
            )
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

/** Label and value on one line, with a bar under them when there is something to measure against. */
@Composable
private fun Meter(label: String, value: String, ratio: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(value, style = Readout.small.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
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
private fun DetailsToggle(open: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
        Text(if (open) "Hide details" else "Details", style = MaterialTheme.typography.labelLarge)
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
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
    val wh = source.ratedWh?.let { " · ${Format.wh(it)} Wh" } ?: ""
    return "${Format.mah(mah.toDouble())} mAh$wh"
}

private fun lastUsed(tMs: Long?): String? = tMs?.let { "used ${Format.shortDay(it)}" }

private fun joinDot(vararg parts: String?): String? =
    parts.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" · ")

private fun energy(wh: Double): String? = wh.takeIf { it >= 0.005 }?.let { "${Format.wh(it)} Wh in" }

private fun avgPower(w: Double?): String? = w?.takeIf { it >= 0.05 }?.let { "avg ${Format.watts(it)} W" }
