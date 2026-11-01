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
                "Delivered to phone",
                "The app measures charge going into the battery. With the screen on, the bank also runs the " +
                    "phone, so that share is added as an estimate. Below 80 % the charge current is steady, " +
                    "so the phone's load is the median current with the screen off minus the median with it " +
                    "on, in the same session. A session with too little of either (for example screen on the " +
                    "whole time) uses the median load from your other sessions. The load is multiplied by the " +
                    "time the screen was on. Use with the screen off is not counted, so the total is a lower " +
                    "bound. Phone charges marked incl. phone use add this share, counted against the phone's " +
                    "design capacity.",
            )
            Point(
                "Per full bank",
                "How many full phone charges one full bank gives: the median, over marked drains, of the " +
                    "percent added between Recharged and the next Ran empty. The bar compares it with what " +
                    "one full bank should give (cell Wh × efficiency ÷ the phone battery's Wh).",
            )
            Point(
                "Output per full bank",
                "Energy out of the bank's port between Recharged and Ran empty, as mAh at 5 V, against the " +
                    "label's output rating. It appears after the first marked drain.",
            )
            Point(
                "Module efficiency",
                "A bank charged full and run to its cutoff gives all of its cells' energy (datasheet capacity " +
                    "down to the cutoff). What did not come out of the port was lost in the module, nearly all " +
                    "as heat. Enter the cell voltage on each mark: about 4.2 V when full, 3.0 V at cutoff. A " +
                    "drain that did not start full is left out, so a partial recharge cannot read as loss.",
            )
            Point(
                "Comparing banks",
                "Banks are ranked by mAh delivered to the phone, including use while charging, so a bank " +
                    "used with the screen on is not ranked below one charged idle. Sort by Per full bank or " +
                    "Output vs label to rank banks by capacity. These use Wh, not mAh: a mAh label only " +
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
