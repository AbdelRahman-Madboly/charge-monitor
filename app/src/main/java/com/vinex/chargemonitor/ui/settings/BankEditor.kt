package com.vinex.chargemonitor.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.theme.BankPalette

/**
 * Add or edit a power bank or named charger. Save stays tappable: an invalid draft shows what is
 * wrong under each field instead of a silently greyed-out button. [onArchive] (existing sources
 * only) asks for confirmation first; [sessionCount] is how many recorded sessions the source has,
 * shown in that confirmation, or null while unknown.
 */
@Composable
fun BankEditorDialog(
    draft: BankDraft,
    onChange: (BankDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onArchive: (() -> Unit)? = null,
    sessionCount: Int? = null,
) {
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    val what = if (draft.isCharger) "charger" else "power bank"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == 0L) "Add $what" else "Edit $what") },
        text = {
            BankFields(
                draft,
                onChange,
                showErrors = showErrors,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (draft.isValid) onSave() else showErrors = true }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onArchive != null) {
                    TextButton(onClick = { confirmArchive = true }) { Text("Archive", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (confirmArchive && onArchive != null) {
        ArchiveConfirmDialog(
            name = draft.name.ifBlank { what },
            what = what,
            sessionCount = sessionCount,
            onConfirm = {
                confirmArchive = false
                onArchive()
            },
            onDismiss = { confirmArchive = false },
        )
    }
}

/**
 * Archiving deletes nothing: sessions keep their source (the foreign key forbids deleting a
 * source that has sessions), totals still include them, and the source can be restored.
 */
@Composable
private fun ArchiveConfirmDialog(name: String, what: String, sessionCount: Int?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val kept = when (sessionCount) {
        null -> "Its recorded sessions stay in History and still count in Dashboard totals."
        0 -> "It has no recorded sessions yet."
        1 -> "Its 1 recorded session stays in History and still counts in Dashboard totals."
        else -> "All $sessionCount recorded sessions stay in History and still count in Dashboard totals."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Archive $name?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    "The $what is removed from the source picker and the \"Connected to?\" prompt, and is no " +
                        "longer picked automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Nothing is deleted. $kept", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "You can restore it any time from Settings, under Archived.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Archive") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

/**
 * The editable fields, shared by the dialog and the onboarding step. Range errors show as soon as
 * a value is typed; a missing name shows only once [showErrors] is set (after a Save attempt), so
 * an empty new form does not open in red.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BankFields(draft: BankDraft, onChange: (BankDraft) -> Unit, modifier: Modifier = Modifier, showErrors: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        val nameError = draft.nameError && showErrors
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it.take(40))) },
            label = { Text("Name") },
            placeholder = { Text(if (draft.isCharger) "e.g. Anker 30W" else "e.g. Anker 20k") },
            singleLine = true,
            isError = nameError,
            supportingText = { Text(if (nameError) "Enter a name" else "Shown in the picker and on charts") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.isCharger) {
            OutlinedTextField(
                value = draft.ratedW,
                onValueChange = { v -> onChange(draft.copy(ratedW = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5))) },
                label = { Text("Rated output") },
                suffix = { Text("W") },
                singleLine = true,
                isError = draft.wattError,
                supportingText = {
                    Text(if (draft.wattError) "Must be between 1 and 300 W" else "Optional. From the label, e.g. 25")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val wh = draft.mahValue?.let { mah -> draft.voltValue?.let { v -> mah * v / 1000.0 } }
            OutlinedTextField(
                value = draft.ratedMah,
                onValueChange = { v -> onChange(draft.copy(ratedMah = v.filter { it.isDigit() }.take(6))) },
                label = { Text("Advertised capacity") },
                suffix = { Text("mAh") },
                singleLine = true,
                isError = draft.mahError,
                supportingText = {
                    Text(
                        when {
                            draft.mahError -> "Must be between 100 and 200,000 mAh"
                            wh != null && !draft.voltError -> "= ${Format.wh(wh)} Wh at the voltage below"
                            else -> "Optional. The headline mAh on the label, e.g. 34000"
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.nominalV,
                onValueChange = { v -> onChange(draft.copy(nominalV = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5))) },
                label = { Text("Nominal cell voltage") },
                suffix = { Text("V") },
                singleLine = true,
                isError = draft.voltError,
                supportingText = {
                    Text(if (draft.voltError) "Must be between 1 and 24 V" else "The voltage the mAh rating refers to; usually 3.7")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            ProductFields(draft, onChange)
        }
        Text(
            "Colour on charts",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = Space.xs),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            BankPalette.forEachIndexed { i, argb ->
                val selected = argb == draft.colorArgb
                val c = Color(argb)
                Surface(
                    shape = CircleShape,
                    color = c,
                    border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics {
                            contentDescription = "Colour ${i + 1}"
                            this.selected = selected
                        }
                        .clickable { onChange(draft.copy(colorArgb = argb)) },
                ) {
                    if (selected) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = if (c.luminance() > 0.5f) Color.Black else Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A bank's product details: the output rating it is sold with, the cells' datasheet capacity
 * (what a full drain really takes out, so the module's loss can be measured), and free text.
 */
@Composable
private fun ProductFields(draft: BankDraft, onChange: (BankDraft) -> Unit) {
    fun digits(v: String, max: Int) = v.filter { it.isDigit() }.take(max)
    fun decimal(v: String) = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5)
    Text(
        "Product",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = Space.s),
    )
    OutlinedTextField(
        value = draft.outputMah,
        onValueChange = { onChange(draft.copy(outputMah = digits(it, 6))) },
        label = { Text("Rated output") },
        suffix = { Text("mAh @5V") },
        singleLine = true,
        isError = draft.outputError,
        supportingText = {
            Text(if (draft.outputError) "Must be between 100 and 200,000 mAh" else "Optional. What it can charge under ideal conditions, e.g. 20000")
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.cellsMah,
        onValueChange = { onChange(draft.copy(cellsMah = digits(it, 6))) },
        label = { Text("Cell capacity (datasheet)") },
        suffix = { Text("mAh") },
        singleLine = true,
        isError = draft.cellsMahError,
        supportingText = {
            Text(
                if (draft.cellsMahError) "Must be between 100 and 200,000 mAh" else
                    "Optional. All cells together, e.g. 10 × 3350 = 33500. Used to measure the module's loss",
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.cells,
        onValueChange = { onChange(draft.copy(cells = it.take(80))) },
        label = { Text("Cells") },
        placeholder = { Text("e.g. 10 × Panasonic NCR18650B, 1S10P") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.module,
        onValueChange = { onChange(draft.copy(module = it.take(80))) },
        label = { Text("Module") },
        placeholder = { Text("e.g. 22.5 W QC4+/PD3.0, 5 ports") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(
            value = draft.ratedW,
            onValueChange = { onChange(draft.copy(ratedW = decimal(it))) },
            label = { Text("Max output") },
            suffix = { Text("W") },
            singleLine = true,
            isError = draft.bankWattError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = draft.cutoffV,
            onValueChange = { onChange(draft.copy(cutoffV = decimal(it))) },
            label = { Text("Cutoff") },
            suffix = { Text("V") },
            singleLine = true,
            isError = draft.cutoffError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
    OutlinedTextField(
        value = draft.notes,
        onValueChange = { onChange(draft.copy(notes = it.take(300))) },
        label = { Text("Notes") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}
