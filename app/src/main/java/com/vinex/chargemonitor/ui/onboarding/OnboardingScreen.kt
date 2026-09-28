package com.vinex.chargemonitor.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.ui.components.Space
import com.vinex.chargemonitor.ui.settings.BankDraft
import com.vinex.chargemonitor.ui.settings.BankFields
import com.vinex.chargemonitor.ui.settings.SettingsViewModel
import com.vinex.chargemonitor.ui.settings.SystemSettings
import com.vinex.chargemonitor.ui.theme.BankPalette
import kotlinx.coroutines.launch

/**
 * First-run flow (spec section 4): notifications, battery optimisation, first power bank. Every
 * step can be skipped; Settings offers the same controls later.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = 3

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = Space.xl, vertical = Space.l),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            repeat(steps) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.height(Space.xl))
        AnimatedContent(targetState = step, label = "onboarding", modifier = Modifier.weight(1f)) { s ->
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                when (s) {
                    0 -> NotificationStep()
                    1 -> BatteryStep()
                    else -> BankStep(onFinished = onDone)
                }
            }
        }
        if (step < steps - 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone) { Text("Skip setup") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { step++ }) { Text("Next") }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Done(text: String) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun NotificationStep() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SystemSettings.notificationsEnabled(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = SystemSettings.notificationsEnabled(context)
    }
    LifecycleResumeEffect(Unit) {
        enabled = SystemSettings.notificationsEnabled(context)
        onPauseOrDispose { }
    }
    StepTitle(
        "Notifications",
        "Charge Monitor keeps one quiet notification while it runs, asks which power bank you plugged in, " +
            "and can alert you at a target level or high temperature.",
    )
    if (enabled) {
        Done("Notifications are on")
    } else {
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                SystemSettings.openNotificationSettings(context)
            }
        }) { Text("Allow notifications") }
    }
}

@Composable
private fun BatteryStep() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(SystemSettings.isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        exempt = SystemSettings.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }
    StepTitle(
        "Keep recording in the background",
        "Android may stop background apps to save battery, which would cut sessions short. " +
            "Charge Monitor uses no timers while unplugged, so allowing it costs almost nothing.",
    )
    if (exempt) {
        Done("Background use allowed")
    } else {
        Button(onClick = { SystemSettings.requestIgnoreBatteryOptimizations(context) }) { Text("Allow background use") }
    }
    if (SystemSettings.isSamsung) {
        Text(
            "Samsung phones: also add Charge Monitor to \"Never sleeping apps\" under Battery → Background usage limits.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { SystemSettings.openSamsungNeverSleeping(context) }) { Text("Open battery settings") }
    }
}

@Composable
private fun BankStep(onFinished: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(BankDraft(colorArgb = BankPalette.first())) }
    StepTitle(
        "Your first power bank",
        "Each bank's charges are counted separately. Add more later in Settings. " +
            "Wall chargers, laptops and wireless pads are already built in.",
    )
    BankFields(draft, onChange = { draft = it })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onFinished) { Text("Skip") }
        Spacer(Modifier.weight(1f))
        Button(
            enabled = draft.isValid,
            onClick = {
                scope.launch {
                    SettingsViewModel.saveBank(ServiceLocator.repository, draft)
                    onFinished()
                }
            },
        ) { Text("Save and finish") }
    }
}
