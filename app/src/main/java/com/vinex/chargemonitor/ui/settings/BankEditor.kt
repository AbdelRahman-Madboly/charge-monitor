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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vinex.chargemonitor.ui.Format
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.theme.BankPalette

@Composable
fun BankEditorDialog(
    draft: BankDraft,
    onChange: (BankDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onArchive: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val what = if (draft.isCharger) "charger" else "power bank"
            Text(if (draft.id == 0L) "Add $what" else "Edit $what")
        },
        text = { BankFields(draft, onChange) },
        confirmButton = { TextButton(onClick = onSave, enabled = draft.isValid) { Text("Save") } },
        dismissButton = {
            Row {
                if (onArchive != null) {
                    TextButton(onClick = onArchive) { Text("Archive", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** The editable fields, shared by the dialog and the onboarding step. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BankFields(draft: BankDraft, onChange: (BankDraft) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it.take(40))) },
            label = { Text("Name") },
            placeholder = { Text(if (draft.isCharger) "e.g. Anker 30W" else "e.g. Anker 20k") },
            singleLine = true,
            isError = draft.nameError && draft.name.isNotEmpty(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.isCharger) {
            OutlinedTextField(
                value = draft.ratedW,
                onValueChange = { v -> onChange(draft.copy(ratedW = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5))) },
                label = { Text("Rated output W") },
                singleLine = true,
                isError = draft.wattError,
                supportingText = { Text(if (draft.wattError) "1 to 300" else "From the label, e.g. 30") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        } else Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedTextField(
                value = draft.ratedMah,
                onValueChange = { v -> onChange(draft.copy(ratedMah = v.filter { it.isDigit() }.take(6))) },
                label = { Text("Rated mAh") },
                singleLine = true,
                isError = draft.mahError,
                supportingText = {
                    val wh = draft.mahValue?.let { mah -> draft.voltValue?.let { v -> mah * v / 1000.0 } }
                    Text(
                        when {
                            draft.mahError -> "100 to 200,000"
                            wh != null && !draft.voltError -> "= ${Format.wh(wh)} Wh"
                            else -> "From the label"
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1.4f),
            )
            OutlinedTextField(
                value = draft.nominalV,
                onValueChange = { v -> onChange(draft.copy(nominalV = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5))) },
                label = { Text("Nominal V") },
                singleLine = true,
                isError = draft.voltError,
                supportingText = { Text("Usually 3.7") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        Text("Colour", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            BankPalette.forEachIndexed { i, argb ->
                val selected = argb == draft.colorArgb
                val c = Color(argb)
                Surface(
                    shape = CircleShape,
                    color = c,
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null,
                    modifier = Modifier
                        .size(36.dp)
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
