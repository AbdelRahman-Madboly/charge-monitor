package com.vinex.chargemonitor.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vinex.chargemonitor.data.Sample
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
import com.vinex.chargemonitor.ui.theme.SeriesColors

/** The four quantities a session chart can show; at most two at once, one per axis. */
private enum class Quantity(val label: String, val unit: String, val decimals: Int, val includeZero: Boolean) {
    POWER("W", "W", 1, true),
    CURRENT("A", "A", 2, true),
    VOLTAGE("V", "V", 2, false),
    LEVEL("%", "%", 1, true),
}

@Composable
fun SessionDetailScreen(sessionId: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SessionDetailViewModel = viewModel(key = "session-$sessionId") { SessionDetailViewModel(sessionId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Oldest selection is dropped when a third is picked, so the chart never has more than two axes.
    val shown = remember { mutableStateListOf(Quantity.POWER, Quantity.LEVEL) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = Space.l), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            val s = ui.session
            Text(
                if (s == null) "Session" else "${Format.shortDay(s.startMs)}, ${Format.clock(s.startMs)}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }

        val s = ui.session
        if (s == null) {
            if (ui.loaded) {
                Text("This session no longer exists.", Modifier.padding(Space.l), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                ColorDot(ui.source?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.outline)
                Text(ui.source?.name ?: "Unknown source", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SessionFlags(s, ui.source)
            }
            val guessed = ui.source
            if (guessed != null && needsConfirmation(s, guessed)) {
                Panel {
                    Text(
                        "The \"Connected to?\" prompt was not answered, so ${guessed.name} is a guess and this " +
                            "session is not counted toward it yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Button(onClick = { vm.changeSource(context, guessed) }, modifier = Modifier.weight(1f)) {
                            Text("Confirm ${guessed.name}")
                        }
                        OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) { Text("Pick another") }
                    }
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Quantity.entries.forEach { q ->
                    FilterChip(
                        selected = q in shown,
                        onClick = {
                            if (q in shown) {
                                if (shown.size > 1) shown.remove(q)
                            } else {
                                if (shown.size >= 2) shown.removeAt(0)
                                shown.add(q)
                            }
                        },
                        label = { Text(q.label) },
                        leadingIcon = { ColorDot(colorOf(q)) },
                    )
                }
            }
            Panel {
                val samples = ui.samples
                val xs = remember(samples) { LongArray(samples.size) { samples[it].tMs } }
                val t0 = xs.firstOrNull() ?: s.startMs
                val picked = shown.toList()
                val arrays = remember(samples, picked) { picked.map { values(samples, it) } }
                val series = picked.mapIndexed { i, q ->
                    ChartSeries(
                        label = q.label,
                        unit = q.unit,
                        color = colorOf(q),
                        xs = xs,
                        ys = arrays[i],
                        axis = if (i == 0) Axis.LEFT else Axis.RIGHT,
                        decimals = q.decimals,
                        fill = i == 0,
                        includeZero = q.includeZero,
                    )
                }
                LineChart(
                    series = series,
                    height = 240.dp,
                    xLabel = { Format.elapsed(it - t0) },
                    emptyText = "No samples stored for this session",
                    description = "Session chart of ${shown.joinToString { it.label }}",
                )
                Text(
                    "${shown.first().label} on the left axis" + (shown.getOrNull(1)?.let { ", ${it.label} on the right" } ?: "") +
                        ". Drag across the chart to read values.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Stats")
            Panel {
                StatRow("Start → end", "${Format.level(s.startLevel)} → ${s.endLevel?.let { Format.level(it) } ?: "…"} %")
                StatRow("Added", "${Format.level(s.levelAdded)} %  (${Format.f(s.levelAdded / 100.0, 2)} full charges)")
                StatRow("Duration", Format.duration((s.endMs ?: s.updatedMs) - s.startMs))
                StatRow("Charge in", "${Format.mah(s.mahIn)} mAh")
                s.counterMahIn?.let { StatRow("Charge counter", "${Format.mah(it)} mAh") }
                StatRow("Energy in", "${Format.wh(s.whIn)} Wh")
                StatRow("Peak / average", "${Format.watts(s.peakW)} / ${Format.watts(s.avgW)} W")
                s.maxTempC?.let { StatRow("Max temperature", "${Format.tempC(it)} °C") }
                StatRow("Screen on", "${Format.percent(s.screenOnFraction.toDouble())} %")
                StatRow("Plug", s.plug)
                StatRow("Samples", s.sampleCount.toString())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) { Text("Change source") }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !s.isOpen,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text(" Delete")
                }
            }
            if (s.isOpen) {
                Text(
                    "This session is still recording; it can be deleted after unplugging.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (picking) {
        SourcePickerSheet(
            sources = ui.sources,
            selectedId = ui.session?.sourceId,
            onPick = {
                vm.changeSource(context, it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this session?") },
            text = { Text("Its samples are deleted too, and it no longer counts toward any totals. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onBack)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun colorOf(q: Quantity): Color = when (q) {
    Quantity.POWER -> SeriesColors.power
    Quantity.CURRENT -> SeriesColors.current
    Quantity.VOLTAGE -> SeriesColors.voltage
    Quantity.LEVEL -> SeriesColors.level
}

private fun values(samples: List<Sample>, q: Quantity): FloatArray = FloatArray(samples.size) { i ->
    val x = samples[i]
    when (q) {
        Quantity.POWER -> (x.mV.toDouble() * x.mA / 1_000_000.0).toFloat()
        Quantity.CURRENT -> x.mA / 1000f
        Quantity.VOLTAGE -> x.mV / 1000f
        Quantity.LEVEL -> x.level10 / 10f
    }
}
