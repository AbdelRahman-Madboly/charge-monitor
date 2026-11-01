package com.vinex.chargemonitor.ui.dashboard

import androidx.compose.foundation.clickable
import com.vinex.chargemonitor.stats.CellOcv
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vinex.chargemonitor.data.BankEvent
import com.vinex.chargemonitor.data.BankEventKind
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.stats.DrainGrade
import com.vinex.chargemonitor.stats.StatsCalculator
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.components.Tag
import com.vinex.chargemonitor.ui.theme.Readout
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** What a mark dialog hands back: the kind, when, and the measured cell voltage if any. */
internal data class MarkInput(val kind: BankEventKind, val tMs: Long, val cellV: Double?, val meterWh: Double? = null)

private fun kindLabel(kind: BankEventKind) = if (kind == BankEventKind.RECHARGED) "Recharged" else "Ran empty"

/**
 * Add or edit one refill mark. A new mark defaults to now; a past one (forgotten at the time)
 * gets its date and time picked. The cell voltage is optional but is what tells a full charge
 * from a partial one, so the dialog says what to expect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkDialog(
    initial: BankEvent?,
    kind: BankEventKind,
    onSave: (MarkInput) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var pickedKind by rememberSaveable { mutableStateOf(initial?.kind ?: kind) }
    var now by rememberSaveable { mutableStateOf(initial == null) }
    var tMs by rememberSaveable { mutableStateOf(initial?.tMs ?: System.currentTimeMillis()) }
    var volts by rememberSaveable { mutableStateOf(initial?.cellV?.let { Format.f(it, 2) } ?: "") }
    var meter by rememberSaveable { mutableStateOf(initial?.meterWh?.let { Format.f(it, 2) } ?: "") }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val meterValue = meter.trim().replace(',', '.').toDoubleOrNull()
    val meterError = pickedKind == BankEventKind.EMPTY && meter.isNotBlank() && (meterValue == null || meterValue !in 0.1..500.0)
    val v = volts.trim().replace(',', '.').toDoubleOrNull()
    val voltError = volts.isNotBlank() && (v == null || v !in 2.0..4.5)
    val expected = if (pickedKind == BankEventKind.RECHARGED) "about 4.20 V when full" else "about 3.0 V at cutoff"
    val verdict = v?.takeIf { !voltError }?.let {
        when (pickedKind) {
            BankEventKind.RECHARGED -> "${Format.f(it, 2)} V = ${Format.percent(CellOcv.energyFraction(it))} % full"
            BankEventKind.EMPTY -> if (it <= BankEvent.EMPTY_V) "At cutoff: the bank is really empty." else
                "Above ${Format.f(BankEvent.EMPTY_V, 2)} V: the module stopped with charge left in the cells."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Mark ${kindLabel(pickedKind)}" else "Edit mark") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (initial != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        BankEventKind.entries.forEach { k ->
                            FilterChip(selected = pickedKind == k, onClick = { pickedKind = k }, label = { Text(kindLabel(k)) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (initial == null) {
                        FilterChip(selected = now, onClick = { now = true }, label = { Text("Now") })
                        FilterChip(selected = !now, onClick = { now = false }, label = { Text("Earlier") })
                    }
                }
                if (!now) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                            Text(Format.shortDay(tMs))
                        }
                        OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) {
                            Text(Format.clock(tMs))
                        }
                    }
                }
                OutlinedTextField(
                    value = volts,
                    onValueChange = { s -> volts = s.filter { it.isDigit() || it == '.' || it == ',' }.take(5) },
                    label = { Text("Cell voltage") },
                    suffix = { Text("V") },
                    singleLine = true,
                    isError = voltError,
                    supportingText = {
                        Text(
                            when {
                                voltError -> "Must be between 2.0 and 4.5 V"
                                verdict != null -> "$verdict Measure after 30 min unplugged rest."
                                else -> "Optional. Measure at the cells after 30 min unplugged rest: $expected"
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pickedKind == BankEventKind.EMPTY) {
                    OutlinedTextField(
                        value = meter,
                        onValueChange = { meter = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) },
                        label = { Text("USB meter reading") },
                        suffix = { Text("Wh") },
                        singleLine = true,
                        isError = meterError,
                        supportingText = { Text(if (meterError) "Must be between 0.1 and 500 Wh" else "Optional. Total for this drain, meter between bank and cable.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !voltError && !meterError,
                onClick = { onSave(MarkInput(pickedKind, if (now) System.currentTimeMillis() else tMs, v, meterValue.takeIf { pickedKind == BankEventKind.EMPTY })) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(tMs).atZone(zone).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val day = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        val time = Instant.ofEpochMilli(tMs).atZone(zone).toLocalTime()
                        tMs = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
    if (pickTime) {
        val current = Instant.ofEpochMilli(tMs).atZone(zone).toLocalTime()
        val state = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val day: LocalDate = Instant.ofEpochMilli(tMs).atZone(zone).toLocalDate()
                    tMs = day.atTime(LocalTime.of(state.hour, state.minute)).atZone(zone).toInstant().toEpochMilli()
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this mark?") },
            text = { Text("The figures are worked out again without it.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Every mark of one bank, newest first; tap one to fix its time or voltage, or add one that was missed. */
@Composable
internal fun MarksDialog(
    marks: List<BankEvent>,
    onEdit: (BankEvent) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Refill marks") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Note("A drain runs from a Recharged mark to the next Ran empty mark. Fix a mark tapped late, or add one you missed.")
                if (marks.isEmpty()) Note("No marks yet.")
                marks.sortedByDescending { it.tMs }.forEach { m ->
                    TextButton(onClick = { onEdit(m) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text(kindLabel(m.kind), style = MaterialTheme.typography.bodyMedium)
                            Text("${Format.shortDay(m.tMs)} ${Format.clock(m.tMs)}", style = Readout.small)
                            Note(listOfNotNull(
                                m.cellV?.let { "${Format.f(it, 2)} V" },
                                m.meterWh?.let { "${Format.wh(it)} Wh meter" },
                            ).joinToString(" · "))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Add mark") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Every completed drain, with its evidence and full-bank equivalent. */
@Composable
internal fun DrainsBlock(bank: PowerSource, drains: List<StatsCalculator.Drain>, onEdit: (StatsCalculator.Drain) -> Unit) {
    if (drains.isEmpty()) {
        Note("No completed drains yet.")
        return
    }
    drains.forEachIndexed { i, d ->
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().clickable { onEdit(d) },
        ) {
            Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(
                        "Drain ${i + 1} · ${Format.shortDay(d.fromMs)} → ${Format.shortDay(d.toMs)}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Tag(
                        "GRADE ${d.grade.name}",
                        color = if (d.grade == DrainGrade.C) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
                if (!d.counted) Note(
                    if (!d.confirmed) "Left out: confirm the source in History." else "Left out: less than 30 % of the cells used.",
                    color = MaterialTheme.colorScheme.error,
                )
                val claim = bank.outputMah?.takeIf { it > 0 }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${Format.mah(d.outputMah)} mAh @5V",
                        style = Readout.small.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${Format.wh(d.portWh)} Wh ${if (d.metered) "metered" else "est."}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (d.counted && d.window < 0.995) {
                    Note(
                        "Full-bank equivalent ${Format.mah(d.outputMahFull)} mAh" +
                            (claim?.let { " · ${Format.percent(d.outputMahFull / it)} % of rated" } ?: ""),
                    )
                } else if (claim != null) {
                    Note("${Format.percent(d.outputMahFull / claim)} % of the rated output")
                }
                Text(
                    listOfNotNull(
                        "${Format.fullCharges(d.phoneCharges)} phone charges",
                        d.moduleEfficiency?.let { "module ${Format.percent(it)} %" },
                        d.moduleLossWh?.let { l -> "heat ${Format.wh(l)} Wh" + (d.moduleHeatW?.let { " (≈${Format.watts(it)} W)" } ?: "") },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Cells ${d.startV?.let { "${Format.f(it, 2)} V" } ?: "assumed full"} → ${d.endV?.let { "${Format.f(it, 2)} V" } ?: "assumed empty"}" +
                        (if (d.partial) " · started ${Format.percent(d.window)} % of a full bank" else "") +
                        (d.meterWh?.let { " · meter ${Format.wh(it)} Wh" } ?: "") + "  ·  tap to edit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
