package com.vinex.chargemonitor.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vinex.chargemonitor.BuildConfig
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.battery.CurrentUnit
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.data.ThemeMode
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.components.AccuracyInfoSheet
import com.vinex.chargemonitor.ui.components.ColorDot
import com.vinex.chargemonitor.ui.components.ListDivider
import com.vinex.chargemonitor.ui.components.ListPanel
import com.vinex.chargemonitor.ui.components.ListRow
import com.vinex.chargemonitor.ui.components.SectionHeader
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.theme.BankPalette
import com.vinex.chargemonitor.ui.theme.Readout
import kotlin.math.roundToInt

/** Inset for dividers under rows that have a leading icon or dot, so they line up with the text. */
private val IconInset = Space.l + 24.dp + Space.m

/**
 * Grouped settings list: sources, measurement, alerts, appearance, data, system, about. Every
 * group is one [ListPanel]; rows open a dialog or toggle in place. [onOpenHistory] switches to the
 * History tab, where export lives.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel(), onOpenHistory: () -> Unit = {}) {
    val s = vm.settings
    val banks by vm.banks.collectAsStateWithLifecycle()
    val chargers by vm.chargers.collectAsStateWithLifecycle()
    val counts by vm.sessionCounts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BankDraft?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }

    fun newDraft(kind: SourceKind): BankDraft {
        val used = (banks + chargers).filter { !it.archived }.map { it.colorArgb }.toSet()
        return BankDraft(colorArgb = BankPalette.firstOrNull { it !in used } ?: BankPalette.first(), kind = kind)
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.l)
            .padding(top = Space.l, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = Space.xs))

        // Sources
        SectionHeader("Power banks")
        SourcesPanel(
            sources = banks,
            counts = counts,
            emptyText = "Add each bank you own so its charges are counted separately.",
            addText = "Add power bank",
            detail = { b ->
                listOfNotNull(
                    b.ratedMah?.let { "${Format.mah(it.toDouble())} mAh" },
                    "${Format.f(b.nominalV, 1)} V",
                    b.ratedWh?.let { "${Format.wh(it)} Wh" },
                ).joinToString(" · ")
            },
            onEdit = { editing = BankDraft.of(it) },
            onAdd = { editing = newDraft(SourceKind.POWER_BANK) },
            onRestore = { vm.setArchived(it.id, false) },
        )

        SectionHeader("Chargers")
        SourcesPanel(
            sources = chargers,
            counts = counts,
            emptyText = "Name a wall charger to test it on its own. Anything unnamed is logged under the built-in Charger.",
            addText = "Add charger",
            detail = { c -> c.ratedW?.let { "${Format.watts(it)} W rated" } ?: "No rating set" },
            onEdit = { editing = BankDraft.of(it) },
            onAdd = { editing = newDraft(SourceKind.CHARGER) },
            onRestore = { vm.setArchived(it.id, false) },
        )
        Footnote("Charger, Laptop USB and Wireless are built in. Laptop and wireless charges never count toward a bank.")

        // Measurement
        SectionHeader("Measurement")
        ListPanel {
            val idle by s.phoneIdleW.flow.collectAsStateWithLifecycle()
            SliderSetting(
                title = "Phone draw, screen off",
                caption = "What the phone runs on while plugged in with the screen off. The bank pays for it, " +
                    "so it counts as delivered but never reaches the battery. 0.30 W suits a Galaxy A54.",
                value = idle,
                range = 0.00f..1.00f,
                steps = 0,
                format = { "${Format.f(it.toDouble(), 2)} W" },
                onCommit = { s.phoneIdleW.set((it * 100).roundToInt() / 100f) },
            )
            ListDivider()
            val screen by s.phoneScreenW.flow.collectAsStateWithLifecycle()
            SliderSetting(
                title = "Extra draw, screen on",
                caption = "Added while the screen is on. Measured at 1.2 to 1.4 W on this phone in light use; " +
                    "raise it if you game or film while charging.",
                value = screen,
                range = 0.00f..4.00f,
                steps = 0,
                format = { "${Format.f(it.toDouble(), 1)} W" },
                onCommit = { s.phoneScreenW.set((it * 10).roundToInt() / 10f) },
            )
            ListDivider()
            val design by s.designCapacityMah.flow.collectAsStateWithLifecycle()
            DesignCapacityField(design) { s.designCapacityMah.set(it) }
            ListDivider()
            val interval by s.sampleIntervalSec.flow.collectAsStateWithLifecycle()
            ControlBlock(
                title = "Recording interval",
                caption = "How often a reading is stored while charging in the background. Live always updates every 2 s.",
            ) {
                Segmented(
                    options = listOf(5, 10, 30, 60),
                    selected = interval,
                    label = { "$it s" },
                    onSelect = { s.sampleIntervalSec.set(it) },
                )
            }
            ListDivider()
            val unit by s.currentUnit.flow.collectAsStateWithLifecycle()
            ControlBlock(
                title = "Current unit",
                caption = "Auto works on most phones. Change it only if current reads about 1000× too high or low.",
            ) {
                Segmented(
                    options = CurrentUnit.entries,
                    selected = unit,
                    label = {
                        when (it) {
                            CurrentUnit.AUTO -> "Auto"
                            CurrentUnit.MICRO -> "µA"
                            CurrentUnit.MILLI -> "mA"
                        }
                    },
                    onSelect = {
                        s.currentUnit.set(it)
                        // The normaliser is long-lived; tell it directly so the next reading uses the new scale.
                        ServiceLocator.currentNormalizer.override = it
                    },
                )
            }
            ListDivider()
            ListRow(
                title = "How accurate are these numbers?",
                subtitle = "What is measured and what is estimated",
                leading = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailing = { Chevron() },
                onClick = { info = true },
            )
        }

        // Alerts
        SectionHeader("Alerts")
        ListPanel {
            val targetOn by s.alertTargetEnabled.flow.collectAsStateWithLifecycle()
            val target by s.targetLevel.flow.collectAsStateWithLifecycle()
            SwitchRow(
                "Target level reached",
                if (targetOn) "Notify once the battery reaches $target %" else "Off",
                targetOn,
            ) { s.alertTargetEnabled.set(it) }
            AnimatedVisibility(targetOn) {
                SliderSetting(
                    title = "Target level",
                    caption = null,
                    value = target.toFloat(),
                    range = 50f..100f,
                    steps = 9,
                    format = { "${it.roundToInt()} %" },
                    onCommit = { s.targetLevel.set(it.roundToInt()) },
                )
            }
            ListDivider()
            val tempOn by s.alertTempEnabled.flow.collectAsStateWithLifecycle()
            val temp by s.tempThresholdC.flow.collectAsStateWithLifecycle()
            SwitchRow(
                "High temperature",
                if (tempOn) "Warn while charging above ${temp.roundToInt()} °C" else "Off",
                tempOn,
            ) { s.alertTempEnabled.set(it) }
            AnimatedVisibility(tempOn) {
                SliderSetting(
                    title = "Threshold",
                    caption = null,
                    value = temp,
                    range = 35f..50f,
                    steps = 14,
                    format = { "${it.roundToInt()} °C" },
                    onCommit = { s.tempThresholdC.set(it.roundToInt().toFloat()) },
                )
            }
            ListDivider()
            val stoppedOn by s.alertStoppedEnabled.flow.collectAsStateWithLifecycle()
            SwitchRow(
                "Charging stopped or full",
                "Notify when charging ends while still plugged in",
                stoppedOn,
            ) { s.alertStoppedEnabled.set(it) }
        }

        // Appearance
        SectionHeader("Appearance")
        ListPanel {
            val mode by s.themeMode.flow.collectAsStateWithLifecycle()
            ControlBlock(title = "Theme", caption = null) {
                Segmented(
                    options = ThemeMode.entries,
                    selected = mode,
                    label = {
                        when (it) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        }
                    },
                    onSelect = { s.themeMode.set(it) },
                )
            }
        }

        // Data
        SectionHeader("Data")
        ListPanel {
            ListRow(
                title = "Export sessions",
                subtitle = "CSV export is in the History tab",
                trailing = { Chevron() },
                onClick = onOpenHistory,
            )
            ListDivider()
            ListRow(
                title = "Stored on this phone only",
                subtitle = "Works offline; nothing leaves the phone. Archiving a bank or charger never deletes its sessions.",
            )
        }

        // System
        SectionHeader("System")
        SystemPanel()

        if (BuildConfig.DEBUG) {
            SectionHeader("Debug")
            ListPanel {
                val mock by s.mockMode.flow.collectAsStateWithLifecycle()
                SwitchRow(
                    "Mock battery",
                    "Replays a synthetic 15→80 % charge every minute. Mock sessions are hidden from totals.",
                    mock,
                ) { s.mockMode.set(it) }
            }
        }

        // About
        SectionHeader("About")
        ListPanel {
            ListRow(
                title = "Bank Tester",
                subtitle = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
        }
    }

    editing?.let { draft ->
        BankEditorDialog(
            draft = draft,
            onChange = { editing = it },
            onSave = {
                vm.save(draft)
                editing = null
            },
            onDismiss = { editing = null },
            onArchive = if (draft.id != 0L) {
                {
                    vm.setArchived(draft.id, true)
                    editing = null
                }
            } else {
                null
            },
            sessionCount = counts?.let { it[draft.id] ?: 0 },
        )
    }
    if (info) AccuracyInfoSheet(onDismiss = { info = false })
}

@Composable
private fun SourcesPanel(
    sources: List<PowerSource>,
    counts: Map<Long, Int>?,
    emptyText: String,
    addText: String,
    detail: (PowerSource) -> String,
    onEdit: (PowerSource) -> Unit,
    onAdd: () -> Unit,
    onRestore: (PowerSource) -> Unit,
) {
    val active = sources.filter { !it.archived }
    val archived = sources.filter { it.archived }
    var showArchived by rememberSaveable(addText) { mutableStateOf(false) }
    fun withCount(b: PowerSource, text: String): String {
        val n = counts?.get(b.id) ?: return text
        val sessions = if (n == 1) "1 session" else "$n sessions"
        return if (text.isEmpty()) sessions else "$text · $sessions"
    }
    ListPanel {
        if (active.isEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.l, vertical = Space.m),
            )
            ListDivider()
        }
        active.forEach { b ->
            ListRow(
                title = b.name,
                subtitle = withCount(b, detail(b)),
                leading = { ColorDot(Color(b.colorArgb), size = 16.dp) },
                trailing = { Chevron("Edit ${b.name}") },
                onClick = { onEdit(b) },
            )
            ListDivider(startInset = IconInset)
        }
        ListRow(
            title = addText,
            titleColor = MaterialTheme.colorScheme.primary,
            leading = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            onClick = onAdd,
        )
        if (archived.isNotEmpty()) {
            ListDivider()
            ListRow(
                title = "Archived (${archived.size})",
                subtitle = "Hidden from the picker; sessions kept",
                trailing = {
                    Icon(
                        if (showArchived) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (showArchived) "Hide archived" else "Show archived",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { showArchived = !showArchived },
            )
            AnimatedVisibility(showArchived) {
                Column {
                    archived.forEach { b ->
                        ListDivider(startInset = IconInset)
                        ListRow(
                            title = b.name,
                            subtitle = withCount(b, detail(b)),
                            titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            leading = { ColorDot(Color(b.colorArgb).copy(alpha = 0.45f), size = 16.dp) },
                            trailing = { TextButton(onClick = { onRestore(b) }) { Text("Restore") } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemPanel() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(SystemSettings.isIgnoringBatteryOptimizations(context)) }
    var notifications by remember { mutableStateOf(SystemSettings.notificationsEnabled(context)) }
    // Re-read when coming back from the system screens.
    LifecycleResumeEffect(Unit) {
        exempt = SystemSettings.isIgnoringBatteryOptimizations(context)
        notifications = SystemSettings.notificationsEnabled(context)
        onPauseOrDispose { }
    }
    ListPanel {
        ListRow(
            title = if (exempt) "Background use allowed" else "Allow background use",
            subtitle = if (exempt) {
                "Battery optimisation will not stop recording"
            } else {
                "Battery optimisation may stop recording. Tap to allow."
            },
            leading = { StatusIcon(exempt) },
            trailing = if (exempt) null else ({ Chevron() }),
            onClick = if (exempt) null else ({ SystemSettings.requestIgnoreBatteryOptimizations(context) }),
        )
        ListDivider(startInset = IconInset)
        ListRow(
            title = if (notifications) "Notifications on" else "Turn on notifications",
            subtitle = if (notifications) {
                "Source prompts and alerts can show"
            } else {
                "Source prompts and alerts will not show. Tap to open settings."
            },
            leading = { StatusIcon(notifications) },
            trailing = { Chevron() },
            onClick = { SystemSettings.openNotificationSettings(context) },
        )
        ListDivider(startInset = IconInset)
        if (SystemSettings.isSamsung) {
            ListRow(
                title = "Samsung: Never sleeping apps",
                subtitle = "Add Bank Tester under Battery → Background usage limits",
                leading = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailing = { Chevron() },
                onClick = { SystemSettings.openSamsungNeverSleeping(context) },
            )
            ListDivider(startInset = IconInset)
        }
        ListRow(
            title = "App info",
            subtitle = "Android settings for Bank Tester",
            leading = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailing = { Chevron() },
            onClick = { SystemSettings.openAppDetails(context) },
        )
    }
}

@Composable
private fun StatusIcon(ok: Boolean) {
    Icon(
        if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
        contentDescription = if (ok) "OK" else "Needs attention",
        tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Chevron(contentDescription: String? = null) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.xs),
    )
}

/** Title and caption above a control, padded like a [ListRow]. */
@Composable
private fun ControlBlock(title: String, caption: String?, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        caption?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(Space.xs))
        content()
    }
}

@Composable
private fun SwitchRow(title: String, caption: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(
        title = title,
        subtitle = caption,
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

/** Slider that only writes the setting when the drag ends, not on every frame. */
@Composable
private fun SliderSetting(
    title: String,
    caption: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.m, bottom = Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(local), style = Readout.small, color = MaterialTheme.colorScheme.primary)
        }
        caption?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
private fun DesignCapacityField(current: Int, onCommit: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(if (current > 0) current.toString() else "") }
    val parsed = text.toIntOrNull()
    val error = text.isNotEmpty() && (parsed == null || parsed !in 500..20_000)
    val changed = (if (text.isEmpty()) 0 else parsed ?: -1) != current
    ControlBlock(
        title = "Phone battery capacity",
        caption = "Design capacity from the phone's specifications, e.g. 5000. Used for battery health and time to full.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(5) },
                placeholder = { Text("Not set") },
                suffix = { Text("mAh") },
                singleLine = true,
                isError = error,
                supportingText = if (error) ({ Text("Must be between 500 and 20,000 mAh") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onCommit(if (text.isEmpty()) 0 else parsed ?: 0) },
                enabled = !error && changed,
            ) { Text("Save") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(label(o), maxLines = 1) }
        }
    }
}
