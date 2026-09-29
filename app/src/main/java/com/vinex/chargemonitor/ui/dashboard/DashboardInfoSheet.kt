package com.vinex.chargemonitor.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vinex.chargemonitor.ui.components.AccuracyInfoContent
import com.vinex.chargemonitor.ui.components.Space

/** The explanations the cards leave out: what each figure means, then how accurate it is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardInfoSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Space.l)
                .padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text("Reading the dashboard", style = MaterialTheme.typography.titleMedium)
            Point(
                "Phone charges so far",
                "Every percent a source has added, summed and divided by 100. Partial charges add up.",
            )
            Point(
                "Per full bank",
                "How many full phone charges one full bank gives: the median, over marked drains, of the " +
                    "percent added between Recharged and the next Ran empty. The bar compares it with what " +
                    "the label promises (rated Wh × loss factor ÷ the phone battery's Wh).",
            )
            Point(
                "Real capacity",
                "Energy delivered between Recharged and Ran empty, divided by the loss factor, against the " +
                    "label. It appears after the first marked drain.",
            )
            Point(
                "Comparing banks",
                "Sort by Per full bank or Real capacity to rank banks. These use Wh, not mAh: a mAh label only " +
                    "compares with another rated at the same cell voltage.",
            )
            Point(
                "Chargers",
                "Peak and average power into the battery, and speed in %/h including the slow top-up above " +
                    "80 %. A 30 W charger on a phone that accepts 25 W never reads 30.",
            )
            HorizontalDivider()
            AccuracyInfoContent()
        }
    }
}

@Composable
private fun Point(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
