package com.vinex.chargemonitor.ui.history

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.components.ColorDot
import com.vinex.chargemonitor.ui.components.Space
import kotlinx.coroutines.launch
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
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

    // A new order or filter starts reading from the top rather than somewhere in the middle.
    fun change(action: () -> Unit) {
        action()
        scope.launch { listState.scrollToItem(0) }
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
                        DropdownMenuItem(text = { Text("Export all sessions (CSV)") }, onClick = { startExport(CsvExport.Kind.SESSIONS) })
                        DropdownMenuItem(text = { Text("Export all samples (CSV)") }, onClick = { startExport(CsvExport.Kind.SAMPLES) })
                    }
                }
            }

            if (ui.loaded && ui.totalSessions == 0) {
                Text(
                    "No charging sessions yet. They appear here as soon as the phone is plugged in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Space.l),
                )
                return@Column
            }

            val today = remember { LocalDate.now() }
            val filter = ui.filter
            val emphasis = emphasisFor(filter.sort)
            val flat = !filter.sort.groupsByDay
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Space.xl)) {
                item(key = "controls") {
                    Controls(
                        ui = ui,
                        onSort = { change { vm.setSort(it) } },
                        onSource = { change { vm.setSource(it) } },
                        onRange = { change { vm.setRange(it) } },
                        onHideShort = { change { vm.setHideShort(it) } },
                    )
                }

                if (ui.loaded && ui.view.sections.isEmpty()) {
                    item(key = "empty") {
                        EmptyFiltered(
                            hiddenShort = ui.view.hiddenShort,
                            canClear = filter.narrows,
                            onShowShort = { change { vm.setHideShort(false) } },
                            onClear = { change { vm.clearFilters() } },
                        )
                    }
                }

                ui.view.sections.forEachIndexed { index, section ->
                    val day = section.day
                    val sourceId = section.sourceId
                    when {
                        day != null -> stickyHeader(key = "d-$day") { DayHeader(day, section.totals, today) }
                        sourceId != null -> stickyHeader(key = "s-$sourceId") { SourceHeader(ui.sources[sourceId], section.totals) }
                    }
                    items(section.sessions, key = { it.id }) { s ->
                        val source = ui.sources[s.sourceId]
                        SessionRow(
                            s = s,
                            source = source,
                            showDate = flat,
                            emphasis = emphasis,
                            liveLevel = ui.live?.takeIf { it.sessionId == s.id && s.isOpen }?.levelPct,
                            onClick = { onOpenSession(s.id) },
                            onConfirm = { source?.let { vm.confirm(context, s.id, it) } },
                        )
                        HorizontalDivider(
                            Modifier.padding(start = Space.xl),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    if (index == ui.view.sections.lastIndex && ui.view.hiddenShort > 0) {
                        item(key = "hidden-footer") {
                            TextButton(
                                onClick = { change { vm.setHideShort(false) } },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
                            ) { Text("Show ${ui.view.hiddenShort} short sessions") }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** Source chips, the sort and date menus, the short-session toggle and the summary line. */
@Composable
private fun Controls(
    ui: HistoryUi,
    onSort: (HistorySort) -> Unit,
    onSource: (Long?) -> Unit,
    onRange: (HistoryRange) -> Unit,
    onHideShort: (Boolean) -> Unit,
) {
    val filter = ui.filter
    Column(Modifier.fillMaxWidth().padding(bottom = Space.xs)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.l),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            FilterChip(selected = filter.sourceId == null, onClick = { onSource(null) }, label = { Text("All sources") })
            ui.chipSources.forEach { src -> SourceChip(src, selected = filter.sourceId == src.id, onClick = { onSource(src.id) }) }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.l),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuButton(
                label = filter.sort.label,
                options = HistorySort.entries,
                selected = filter.sort,
                optionLabel = { it.label },
                onSelect = onSort,
                description = "Sort order",
            )
            MenuButton(
                label = filter.range.label,
                options = HistoryRange.entries,
                selected = filter.range,
                optionLabel = { it.label },
                onSelect = onRange,
                description = "Date range",
            )
            FilterChip(
                selected = filter.hideShort,
                onClick = { onHideShort(!filter.hideShort) },
                label = { Text("Hide short") },
                leadingIcon = if (filter.hideShort) {
                    { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                } else {
                    null
                },
            )
        }
        if (ui.view.sections.isNotEmpty()) {
            val hidden = ui.view.hiddenShort
            Text(
                HistoryText.summary(ui.view.totals) + if (hidden > 0) " · $hidden short hidden" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.l, vertical = Space.xs),
            )
        }
    }
}

@Composable
private fun SourceChip(source: PowerSource, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(source.name, maxLines = 1) },
        leadingIcon = { ColorDot(sourceColor(source)) },
    )
}

@Composable
private fun <T> MenuButton(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    description: String,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            contentPadding = PaddingValues(start = Space.m, end = Space.xs),
        ) {
            Text(label, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = description)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    leadingIcon = {
                        if (option == selected) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected")
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyFiltered(hiddenShort: Int, canClear: Boolean, onShowShort: () -> Unit, onClear: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text("No sessions match these filters", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            if (hiddenShort > 0) "$hiddenShort short sessions are hidden." else "Try another source or a wider date range.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (hiddenShort > 0) OutlinedButton(onClick = onShowShort) { Text("Show short") }
            if (canClear) OutlinedButton(onClick = onClear) { Text("Clear filters") }
        }
    }
}
