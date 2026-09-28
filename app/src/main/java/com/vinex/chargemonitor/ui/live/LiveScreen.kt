package com.vinex.chargemonitor.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.ui.theme.Readout
import java.util.Locale

@Composable
fun LiveScreen(modifier: Modifier = Modifier) {
    // Tells the service to sample at the fast cadence only while this screen is actually resumed.
    LifecycleResumeEffect(Unit) {
        LiveBus.setLiveVisible(true)
        onPauseOrDispose { LiveBus.setLiveVisible(false) }
    }
    val state by LiveBus.state.collectAsStateWithLifecycle()
    val snap = state.snapshot

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Live", style = MaterialTheme.typography.titleLarge)
        Text(
            text = if (snap == null) "-- W" else String.format(Locale.US, "%.1f W", snap.powerW),
            style = Readout.hero,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (state.serviceRunning) "Monitoring" else "Service not running",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
