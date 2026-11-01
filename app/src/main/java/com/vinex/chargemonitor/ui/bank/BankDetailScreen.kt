package com.vinex.chargemonitor.ui.bank

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vinex.chargemonitor.data.BankEvent
import com.vinex.chargemonitor.data.BankEventKind
import com.vinex.chargemonitor.stats.StatsCalculator
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.components.Panel
import com.vinex.chargemonitor.ui.components.SectionHeader
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.components.Tag
import com.vinex.chargemonitor.ui.dashboard.ChargeHistoryChart
import com.vinex.chargemonitor.ui.dashboard.DrainsBlock
import com.vinex.chargemonitor.ui.dashboard.Figure
import com.vinex.chargemonitor.ui.dashboard.FlowBar
import com.vinex.chargemonitor.ui.dashboard.MarkDialog
import com.vinex.chargemonitor.ui.dashboard.Meter
import com.vinex.chargemonitor.ui.dashboard.Note
import com.vinex.chargemonitor.ui.dashboard.ProductBlock
import com.vinex.chargemonitor.ui.dashboard.RefillMarks
import com.vinex.chargemonitor.ui.dashboard.SourceCard
import com.vinex.chargemonitor.ui.dashboard.bankStatus
import com.vinex.chargemonitor.ui.settings.BankDraft
import com.vinex.chargemonitor.ui.settings.BankEditorDialog

@Composable
fun BankDetailScreen(bankId: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: BankDetailViewModel = viewModel(key = "bank-$bankId") { BankDetailViewModel(bankId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingId by rememberSaveable(bankId) { mutableStateOf<Long?>(null) }
    var draft by remember(bankId) { mutableStateOf<BankDraft?>(null) }
    var methodOpen by rememberSaveable(bankId) { mutableStateOf(false) }
    val card = ui.card
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = Space.l), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(card?.source?.name ?: "Power bank", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        if (card == null) {
            if (ui.loaded) Text("This bank no longer exists.", Modifier.padding(Space.l))
            return@Column
        }
        val c = card.capacity ?: StatsCalculator.CapacityEstimate.NONE
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.l, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            SectionHeader("Test guide")
            Panel {
                Note(bankStatus(card))
                val running = card.lastMark?.kind == BankEventKind.RECHARGED
                val step = if (running) 2 else 1
                val steps = listOf(
                    "1 Charge to full, unplug, rest 30 min, measure the cells (about 4.20 V), mark Recharged",
                    "2 Use the bank normally (screen-on use is fine)",
                    "3 When it stops, rest 30 min, measure (about 3.0 V), mark Ran empty; add the USB meter Wh if one was inline",
                )
                steps.forEachIndexed { i, text ->
                    if (i + 1 == step) Tag("CURRENT STEP", color = MaterialTheme.colorScheme.primary)
                    Note(text, color = if (i + 1 == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (i == 1 && running) Note(
                        "${Format.wh(c.sinceRechargeCellsWh ?: 0.0)} Wh taken from cells (estimated)" +
                            (card.chargeLeft?.let { " · ~${Format.percent(it)} % left" } ?: ""),
                    )
                }
                Note("Retest progress: ${c.verifiedCycles} of 3 verified drains")
                RefillMarks(
                    card.lastMark, card.marks,
                    onMark = { vm.addMark(BankEvent(sourceId = bankId, tMs = it.tMs, kind = it.kind, cellV = it.cellV, meterWh = it.meterWh)) },
                    onEditMark = vm::updateMark,
                    onDeleteMark = vm::deleteMark,
                )
            }
            SectionHeader("Results vs the label")
            Panel { Results(card, c) }
            SectionHeader("Drains")
            DrainsBlock(card.source, c.drains) { drain ->
                editingId = card.marks.firstOrNull { it.kind == BankEventKind.EMPTY && it.tMs == drain.toMs }?.id
            }
            SectionHeader("Energy flow per full bank")
            Panel {
                val flow = BankReport.fullBankEnergy(c)
                if (flow == null) Note("Complete a counted drain with cell capacity entered to see the flow.") else {
                    Note("Median drain by full-bank output (central pair averaged for even counts); scaled to a full bank, estimated.")
                    Figure("Cells", "${Format.wh(flow.cellsWh)} Wh", "datasheet or label capacity × resting voltage window")
                    Figure("Module heat", "${Format.wh(flow.bankLossWh)} Wh", "estimated: cells − port")
                    Figure("Port", "${Format.wh(flow.portWh)} Wh", "meter reading or calibrated model, scaled to full bank")
                    Figure("Cable + charger", "${Format.wh(flow.phoneLossWh)} Wh", "estimated")
                    Figure("Phone use", "${Format.wh(flow.phoneUseWh)} Wh", "modelled per reading")
                    Figure("Battery", "${Format.wh(flow.batteryWh)} Wh", "measured battery energy, scaled to full bank")
                    FlowBar(flow)
                }
                Note("Lifetime: ${card.sessionCount} confirmed sessions · ${Format.wh(card.whIn)} Wh into battery · ${Format.fullCharges(card.fullCharges)} phone charges")
                card.energy?.let { Note("Lifetime estimated: ${Format.wh(it.cellsWh)} Wh from cells · ${Format.wh(it.portWh)} Wh at port") }
                if (card.unconfirmedCount > 0) Note("${card.unconfirmedCount} unconfirmed sessions excluded; confirm their source in History.")
            }
            if (card.lastMark?.kind == BankEventKind.RECHARGED && card.chargeHistory.isNotEmpty()) {
                Panel { ChargeHistoryChart(card) }
            }
            SectionHeader("Product sheet") {
                TextButton(onClick = { draft = BankDraft.of(card.source) }) { Text("Edit") }
            }
            ProductBlock(card.source, c)
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "${card.source.name} capacity report")
                        putExtra(Intent.EXTRA_TEXT, BankReport.text(card))
                    }
                    context.startActivity(Intent.createChooser(intent, "Share report"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share report") }
            Panel {
                TextButton(onClick = { methodOpen = !methodOpen }) {
                    Text(if (methodOpen) "How this is measured · Hide" else "How this is measured · Show")
                }
                if (methodOpen) {
                    Note("The phone measures power into its battery. Phone use and cable + charger loss are modelled per reading, or calibrated by an inline USB meter.")
                    Note("Cell energy uses datasheet capacity (label if missing), nominal voltage × 0.96, and an approximate resting voltage curve. Rest unplugged for at least 30 min; missing voltages assume full / empty.")
                    Note("Module loss = cells − port. Partial drains scale to a full bank; windows below 30 % and unconfirmed drains are excluded, including from calibration. Efficiency is bounded to 40–98 %.")
                    Note("A: both voltages + meter. B: both voltages. C: assumed endpoint(s). Module efficiency uses A/B drains when available.")
                }
            }
        }
    }
    card?.marks?.firstOrNull { it.id == editingId }?.let { mark ->
        MarkDialog(
            mark, mark.kind,
            onSave = {
                vm.updateMark(mark.copy(kind = it.kind, tMs = it.tMs, cellV = it.cellV, meterWh = it.meterWh))
                editingId = null
            },
            onDelete = { vm.deleteMark(mark.id); editingId = null },
            onDismiss = { editingId = null },
        )
    }
    draft?.let { current ->
        BankEditorDialog(current, onChange = { draft = it }, onSave = { vm.saveBank(current); draft = null }, onDismiss = { draft = null })
    }
}

@Composable
private fun Results(card: SourceCard, c: StatsCalculator.CapacityEstimate) {
    val output = c.cycleOutputMah
    if (output == null) {
        Note("Complete a drain with a window of at least 30 % to estimate capacity.")
        return
    }
    Meter("Output per full bank", "${Format.mah(output)} mAh @5V", c.outputRatio)
    Note("Estimated full-bank equivalent" + (c.outputRatio?.let { " · ${Format.percent(it)} % of rated output" } ?: ""))
    card.source.outputMah?.let { Note("Rated output: ${Format.mah(it.toDouble())} mAh @5V") }
    val portWh = output * 0.005
    Figure("Output vs advertised energy", "${Format.wh(portWh)} Wh", "estimated output" +
        (card.source.ratedWh?.let { " vs ${Format.wh(it)} Wh advertised (${Format.percent(portWh / it)} %)" } ?: ""))
    Figure("Phone charges / full bank", c.cyclePhoneCharges?.let { Format.f(it, 2) } ?: "—", "estimated from measured phone percentage added")
    c.moduleEfficiency?.let {
        Figure("Module efficiency", "${Format.percent(it)} %", "estimated; ${BankReport.efficiencyBasis(c)}")
    }
    BankReport.fullBankEnergy(c)?.let {
        Figure("Module heat / full bank", "${Format.wh(it.bankLossWh)} Wh",
            "estimated from median drain" + (BankReport.moduleHeatW(c)?.let { w -> " · average ${Format.watts(w)} W while plugged" } ?: ""))
    }
    if (c.drains.any { it.metered }) {
        Figure("Phone-side scale", "×${Format.f(c.portScale, 2)}", "calibrated from counted USB meter drains; bounded to 0.80–1.25")
        Note("USB meter port energy is measured. Full-bank output and module efficiency remain estimates using the cell voltage curve.")
    } else {
        Note("Port energy is modelled from battery energy, phone use and cable + charger loss.")
    }
}
