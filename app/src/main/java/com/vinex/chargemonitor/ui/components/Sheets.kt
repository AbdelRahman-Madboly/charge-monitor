package com.vinex.chargemonitor.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.ui.Format

/**
 * Bottom sheet listing active power banks first, then the built-ins. Used from the Live source
 * chip and from session detail, so both paths offer exactly the same choices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickerSheet(
    sources: List<PowerSource>,
    selectedId: Long?,
    onPick: (PowerSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val banks = sources.filter { it.kind == SourceKind.POWER_BANK && !it.archived }
    val builtIns = sources.filter { it.builtIn }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Space.l)) {
            Text(
                "Charging from",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
            )
            if (banks.isEmpty()) {
                Text(
                    "No power banks yet. Add one in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
                )
            }
            banks.forEach { SourceRow(it, it.id == selectedId) { onPick(it) } }
            HorizontalDivider(Modifier.padding(vertical = Space.xs))
            builtIns.forEach { SourceRow(it, it.id == selectedId) { onPick(it) } }
        }
    }
}

@Composable
private fun SourceRow(source: PowerSource, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        ColorDot(Color(source.colorArgb), size = 14.dp)
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge)
            val sub = when (source.kind) {
                SourceKind.POWER_BANK -> source.ratedMah?.let { "${Format.mah(it.toDouble())} mAh rated" } ?: "Power bank"
                SourceKind.LAPTOP -> "Not counted toward any bank"
                SourceKind.WIRELESS -> "Not counted toward any bank"
                SourceKind.CHARGER -> "Wall charger"
            }
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

/** Plain-language explanation of what is measured and what is estimated (spec section 2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccuracyInfoSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AccuracyInfoContent(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Space.l)
                .padding(bottom = Space.xl),
        )
    }
}

@Composable
fun AccuracyInfoContent(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("How accurate are these numbers?", style = MaterialTheme.typography.titleMedium)
        InfoPara(
            "The phone measures current going into its battery, not current leaving the charger " +
                "or power bank. Some numbers are therefore exact and some are estimates.",
        )
        InfoPoint(
            "Full charges: exact",
            "Every percent added is counted, then summed: 15→80 % plus 5→40 % is exactly one full charge. " +
                "Partial charges add up correctly.",
        )
        InfoPoint(
            "Energy (Wh): measured",
            "Voltage × current into the battery, integrated over time.",
        )
        InfoPoint(
            "Real bank capacity: estimate (est.)",
            "Energy into the battery ÷ loss factor ÷ the bank's nominal voltage. The loss factor " +
                "(default 0.85) covers the bank's boost converter and the phone's charging circuit; " +
                "you can change it in Settings.",
        )
        InfoPoint(
            "Using the phone while charging",
            "Power the screen and apps use while plugged in never reaches the battery, so the phone " +
                "cannot see it. Sessions with the screen on for more than 20 % of the time are marked " +
                "\"used\". They always count toward full charges, but by default are left out of " +
                "capacity estimates because they would make the bank look smaller than it is.",
        )
        InfoPoint(
            "Laptop and wireless",
            "Charging from a laptop USB port or a wireless pad is logged but never counted toward " +
                "any power bank.",
        )
    }
}

@Composable
private fun InfoPara(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun InfoPoint(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
