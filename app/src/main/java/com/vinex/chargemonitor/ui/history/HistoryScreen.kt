package com.vinex.chargemonitor.ui.history

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.components.ColorDot
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.components.Tag
import com.vinex.chargemonitor.ui.theme.Readout
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onOpenSession: (Long) -> Unit,
    modifier: Modifier = Modifier,
    vm: HistoryViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    // Survives the document picker's activity round trip.
    var pendingKind by rememberSaveable { mutableStateOf<CsvExport.Kind?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val kind = pendingKind
        pendingKind = null
        if (uri != null && kind != null) vm.export(context, uri, kind)
    }

    LaunchedEffect(vm) {
        vm.exports.collect { r ->
            when (r) {
                is ExportResult.Failed -> snackbar.showSnackbar("Export failed: ${r.message}")
                is ExportResult.Saved -> {
                    val what = if (r.kind == CsvExport.Kind.SESSIONS) "sessions" else "samples"
                    val res = snackbar.showSnackbar("Saved ${r.rows} $what", actionLabel = "Share", withDismissAction = true)
                    if (res == SnackbarResult.ActionPerformed) {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/csv")
                            .putExtra(Intent.EXTRA_STREAM, r.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(Intent.createChooser(send, "Share CSV"))
                        } catch (_: ActivityNotFoundException) {
                            snackbar.showSnackbar("No app can share this file")
                        }
                    }
                }
            }
        }
    }

    fun startExport(kind: CsvExport.Kind) {
        menu = false
        pendingKind = kind
        createDoc.launch("${kind.fileStem}-${Format.fileStamp(System.currentTimeMillis())}.csv")
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = Space.l, end = Space.xs, top = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("History", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Export") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Export sessions (CSV)") }, onClick = { startExport(CsvExport.Kind.SESSIONS) })
                        DropdownMenuItem(text = { Text("Export samples (CSV)") }, onClick = { startExport(CsvExport.Kind.SAMPLES) })
                    }
                }
            }

            if (ui.loaded && ui.groups.isEmpty()) {
                Text(
                    "No charging sessions yet. They appear here as soon as the phone is plugged in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Space.l),
                )
            }

            val today = remember { LocalDate.now() }
            LazyColumn(contentPadding = PaddingValues(bottom = Space.xl)) {
                ui.groups.forEach { group ->
                    stickyHeader(key = "d-${group.day}") { DayHeader(group, today) }
                    items(group.sessions, key = { it.id }) { s ->
                        val source = ui.sources[s.sourceId]
                        SessionRow(
                            s,
                            source,
                            onClick = { onOpenSession(s.id) },
                            onConfirm = { source?.let { vm.confirm(context, s.id, it) } },
                        )
                        HorizontalDivider(Modifier.padding(start = 40.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun DayHeader(group: DayGroup, today: LocalDate) {
    val added = group.sessions.sumOf { it.levelAdded.toDouble() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Format.dayHeader(group.day, today),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text("+${Format.level(added.toFloat())} %", style = Readout.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionRow(s: ChargeSession, source: PowerSource?, onClick: () -> Unit, onConfirm: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.Top,
    ) {
        ColorDot(source?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.outline, Modifier.padding(top = 5.dp), size = 12.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    "${Format.levelInt(s.startLevel)} → ${if (s.isOpen) "…" else Format.levelInt(s.endLevel)} %",
                    style = Readout.tile.copy(fontSize = Readout.tile.fontSize * 0.8f),
                )
                SessionFlags(s, source)
            }
            Text(
                "${source?.name ?: "Unknown"} · ${Format.clock(s.startMs)} · ${Format.duration((s.endMs ?: s.updatedMs) - s.startMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${Format.mah(s.mahIn)} mAh", style = Readout.small)
            Text("${Format.wh(s.whIn)} Wh", style = Readout.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (needsConfirmation(s, source)) {
                TextButton(onClick = onConfirm, contentPadding = PaddingValues(horizontal = Space.s)) { Text("Confirm") }
            }
        }
    }
}

/**
 * A guessed source only matters when it is a power bank: that is the one total it would distort.
 */
fun needsConfirmation(s: ChargeSession, source: PowerSource?): Boolean =
    !s.sourceConfirmed && source?.kind == SourceKind.POWER_BANK

@Composable
fun SessionFlags(s: ChargeSession, source: PowerSource? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (s.isOpen) Tag("charging", color = MaterialTheme.colorScheme.primary)
        if (needsConfirmation(s, source)) Tag("unconfirmed", color = MaterialTheme.colorScheme.error)
        if (s.reachedFull) Tag("full", color = MaterialTheme.colorScheme.primary)
        if (s.usedWhileCharging) Tag("used", color = MaterialTheme.colorScheme.error)
        if (s.avgW >= 10.0) Tag("fast")
    }
}
