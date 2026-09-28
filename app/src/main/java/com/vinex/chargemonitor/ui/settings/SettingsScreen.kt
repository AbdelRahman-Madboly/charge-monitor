package com.vinex.chargemonitor.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vinex.chargemonitor.BuildConfig
import com.vinex.chargemonitor.battery.CurrentUnit
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.components.AccuracyInfoSheet
import com.vinex.chargemonitor.ui.components.ColorDot
import com.vinex.chargemonitor.ui.components.Panel
import com.vinex.chargemonitor.ui.components.SectionHeader
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.theme.BankPalette
import com.vinex.chargemonitor.ui.theme.Readout
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
    val s = vm.settings
    val banks by vm.banks.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BankDraft?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        SectionHeader("Power banks")
        BanksPanel(
            banks = banks,
            onEdit = { editing = BankDraft.of(it) },
            onAdd = {
                val used = banks.map { it.colorArgb }.toSet()
                editing = BankDraft(colorArgb = BankPalette.firstOrNull { it !in used } ?: BankPalette.first())
            },
            onRestore = { vm.setArchived(it.id, false) },
        )

        SectionHeader("Estimates")
        Panel {
            val loss by s.lossFactor.flow.collectAsStateWithLifecycle()
            SliderSetting(
                title = "Loss factor",
                caption = "Share of the bank's energy that reaches the battery. 0.85 suits most banks.",
                value = loss,
                range = 0.60f..1.00f,
                steps = 0,
                format = { Format.f(it.toDouble(), 2) },
                onCommit = { s.lossFactor.set((it * 100).roundToInt() / 100f) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            val design by s.designCapacityMah.flow.collectAsStateWithLifecycle()
            DesignCapacityField(design) { s.designCapacityMah.set(it) }
        }

        SectionHeader("Alerts")
        Panel {
            val targetOn by s.alertTargetEnabled.flow.collectAsStateWithLifecycle()
            val target by s.targetLevel.flow.collectAsStateWithLifecycle()
            SwitchSetting("Target level reached", "Notify once the battery reaches the target.", targetOn) {
                s.alertTargetEnabled.set(it)
            }
            SliderSetting(
                title = "Target level",
                caption = null,
                value = target.toFloat(),
                range = 50f..100f,
                steps = 9,
                format = { "${it.roundToInt()} %" },
                enabled = targetOn,
                onCommit = { s.targetLevel.set(it.roundToInt()) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            val tempOn by s.alertTempEnabled.flow.collectAsStateWithLifecycle()
            val temp by s.tempThresholdC.flow.collectAsStateWithLifecycle()
            SwitchSetting("High temperature", "Warn while charging above the threshold.", tempOn) { s.alertTempEnabled.set(it) }
            SliderSetting(
                title = "Threshold",
                caption = null,
                value = temp,
                range = 35f..50f,
                steps = 14,
                format = { "${it.roundToInt()} °C" },
                enabled = tempOn,
                onCommit = { s.tempThresholdC.set(it.roundToInt().toFloat()) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            val stoppedOn by s.alertStoppedEnabled.flow.collectAsStateWithLifecycle()
            SwitchSetting("Charging stopped or full", "Notify when charging ends while still plugged in.", stoppedOn) {
                s.alertStoppedEnabled.set(it)
            }
        }

        SectionHeader("Recording")
        Panel {
            val interval by s.sampleIntervalSec.flow.collectAsStateWithLifecycle()
            Text("Sample interval", style = MaterialTheme.typography.bodyLarge)
            Text(
                "How often a reading is stored while charging in the background. The Live screen always samples every 2 s.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Segmented(
                options = listOf(5, 10, 30, 60),
                selected = interval,
                label = { "$it s" },
                onSelect = { s.sampleIntervalSec.set(it) },
            )
        }

        SectionHeader("Background")
        BackgroundPanel()

        SectionHeader("Calibration")
        Panel {
            val unit by s.currentUnit.flow.collectAsStateWithLifecycle()
            Text("Current unit", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Auto works on most phones. Change it only if the current reads about 1000× too high or low. " +
                    "Takes effect after the app restarts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                onSelect = { s.currentUnit.set(it) },
            )
        }

        if (BuildConfig.DEBUG) {
            SectionHeader("Debug")
            Panel {
                val mock by s.mockMode.flow.collectAsStateWithLifecycle()
                SwitchSetting("Mock battery", "Replays a synthetic 15→80 % charge every minute. Mock sessions are hidden from totals.", mock) {
                    s.mockMode.set(it)
                }
            }
        }

        SectionHeader("About")
        Panel {
            Row(
                Modifier.fillMaxWidth().clickable { info = true }.padding(vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("How accurate are these numbers?", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
            Text(
                "Charge Monitor ${BuildConfig.VERSION_NAME}. Works offline; nothing leaves the phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        )
    }
    if (info) AccuracyInfoSheet(onDismiss = { info = false })
}

@Composable
private fun BanksPanel(
    banks: List<PowerSource>,
    onEdit: (PowerSource) -> Unit,
    onAdd: () -> Unit,
    onRestore: (PowerSource) -> Unit,
) {
    val active = banks.filter { !it.archived }
    val archived = banks.filter { it.archived }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    Panel {
        if (active.isEmpty()) {
            Text(
                "No power banks yet. Add each bank you own so its charges are counted separately.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        active.forEach { b ->
            Row(
                Modifier.fillMaxWidth().clickable { onEdit(b) }.padding(vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                ColorDot(Color(b.colorArgb), size = 14.dp)
                Column(Modifier.weight(1f)) {
                    Text(b.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(b.ratedMah?.let { "${Format.mah(it.toDouble())} mAh" }, "${Format.f(b.nominalV, 1)} V").joinToString(" · "),
                        style = Readout.small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Edit ${b.name}")
            }
        }
        FilledTonalButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(" Add power bank")
        }
        if (archived.isNotEmpty()) {
            TextButton(onClick = { showArchived = !showArchived }) {
                Text(if (showArchived) "Hide archived (${archived.size})" else "Show archived (${archived.size})")
            }
            if (showArchived) {
                archived.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        ColorDot(Color(b.colorArgb).copy(alpha = 0.5f), size = 14.dp)
                        Text(b.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onRestore(b) }) { Text("Restore") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundPanel() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(SystemSettings.isIgnoringBatteryOptimizations(context)) }
    var notifications by remember { mutableStateOf(SystemSettings.notificationsEnabled(context)) }
    // Re-read when coming back from the system screens.
    LifecycleResumeEffect(Unit) {
        exempt = SystemSettings.isIgnoringBatteryOptimizations(context)
        notifications = SystemSettings.notificationsEnabled(context)
        onPauseOrDispose { }
    }
    Panel {
        StatusLine(
            ok = exempt,
            okText = "Battery optimisation: not restricted",
            badText = "Battery optimisation may stop recording",
        )
        if (!exempt) {
            OutlinedButton(onClick = { SystemSettings.requestIgnoreBatteryOptimizations(context) }) { Text("Allow background use") }
        }
        if (SystemSettings.isSamsung) {
            Text(
                "On Samsung phones also add Charge Monitor to \"Never sleeping apps\" (Battery → Background usage limits).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { SystemSettings.openSamsungNeverSleeping(context) }) { Text("Open battery settings") }
        } else {
            TextButton(onClick = { SystemSettings.openAppDetails(context) }) { Text("App details") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        StatusLine(ok = notifications, okText = "Notifications: on", badText = "Notifications are off; prompts and alerts will not show")
        if (!notifications) {
            OutlinedButton(onClick = { SystemSettings.openNotificationSettings(context) }) { Text("Turn on notifications") }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, okText: String, badText: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(if (ok) okText else badText, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchSetting(title: String, caption: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Space.s)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            caption?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
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
    enabled: Boolean = true,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(local), style = Readout.small, color = MaterialTheme.colorScheme.onSurface)
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
    Text("Phone design capacity", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Printed in the phone's specifications, e.g. 5000 mAh. Used for battery health and time-to-full.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter { it.isDigit() }.take(5) },
            label = { Text("mAh") },
            singleLine = true,
            isError = error,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onCommit(if (text.isEmpty()) 0 else parsed ?: 0) },
            enabled = !error && (parsed ?: 0) != current,
        ) { Text("Save") }
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
            ) { Text(label(o)) }
        }
    }
}
