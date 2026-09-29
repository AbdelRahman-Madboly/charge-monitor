package com.vinex.chargemonitor.ui.dashboard

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
import com.vinex.chargemonitor.stats.StatsCalculator
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.components.ColorDot
import com.vinex.chargemonitor.ui.components.Panel
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.components.Tag
import com.vinex.chargemonitor.ui.theme.Readout

/**
 * A power bank at a glance: phone charges so far, what one full bank gives against the label,
 * real capacity once a drain is marked, and the two marks that measure it. The working behind
 * each figure sits under Details so the card stays short.
 */
@Composable
internal fun BankCard(
    card: SourceCard,
    rank: Int?,
    lossFactor: Float,
    onMark: (BankEventKind) -> Unit,
    onUndo: (Long) -> Unit,
) {
    var details by rememberSaveable(card.source.id) { mutableStateOf(false) }
    val capacity = card.capacity ?: StatsCalculator.CapacityEstimate.NONE
    Panel {
        CardHeader(card.source, rank, joinDot(ratedLabel(card.source), lastUsed(card.lastUsedMs)))
        Hero(Format.fullCharges(card.fullCharges), "phone charges so far")

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
        if (details) BankDetails(card, capacity, lossFactor)
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
            capacity.sinceRechargeWh?.takeIf { it > 0.0 }?.let { wh ->
                val share = capacity.ratedMah?.takeIf { it > 0 }?.let { r -> capacity.sinceRechargeMah?.let { it / r } }
                Meter(
                    label = "Since Recharged",
                    value = "${Format.wh(wh)} Wh${share?.let { " · ${Format.percent(it)} % of label" } ?: ""}",
                    ratio = share,
                )
            }
        }
    }
}

@Composable
private fun BankDetails(card: SourceCard, capacity: StatsCalculator.CapacityEstimate, lossFactor: Float) {
    val loss = "loss factor ${Format.f(lossFactor.toDouble(), 2)}"
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        card.labelPhoneCharges?.let { Note("Label promise: ≈${Format.f(it, 1)} phone charges per full bank ($loss).") }
        card.source.ratedWh?.let { Note("Rated energy: ${Format.wh(it)} Wh at ${Format.f(card.source.nominalV, 2)} V.") }
        if (capacity.cycleMah != null) {
            Note(
                "Real capacity is the median of ${plural(capacity.completedCycles, "full drain")}, $loss." +
                    if (capacity.skippedCycles > 0) " ${plural(capacity.skippedCycles, "drain")} left out." else "",
            )
        }
        capacity.sinceRechargeWh?.let { wh ->
            if (capacity.cycleMah != null) Note("Since the last recharge: ${Format.wh(wh)} Wh.")
        }
        if (capacity.sessionsUsed > 0 && capacity.deliveredWh > 0.0) {
            val fulls = capacity.deliveredBankFulls?.let { " ≈ ${Format.f(it, 2)} bank-fulls" } ?: ""
            Note("Delivered so far: ${Format.wh(capacity.deliveredWh)} Wh$fulls (est.).")
        }
        if (card.usedExcluded > 0) {
            Note("${plural(card.usedExcluded, "session")} used while charging left out of capacity and power.")
        }
    }
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
