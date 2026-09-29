package com.vinex.chargemonitor

import android.app.Application
import android.util.Log
import com.vinex.chargemonitor.battery.DesignCapacity
import com.vinex.chargemonitor.data.SourcePresets
import com.vinex.chargemonitor.notify.Notifications
import com.vinex.chargemonitor.ui.theme.BankPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ChargeMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Channels must exist before the service's first startForeground call.
        Notifications.createChannels(this)
        seedDesignCapacity()
        addSourcePresets()
    }

    /** Spec default for the health estimate; a value the user typed is never replaced. */
    private fun seedDesignCapacity() {
        val setting = ServiceLocator.settings.designCapacityMah
        if (setting.value > 0) return
        DesignCapacity.fromPowerProfile(this)?.let { setting.set(it) }
    }

    /**
     * Adds any preset bank or charger this install has not seen yet, and makes the main charger
     * the prompt's charger unless one was already chosen. The version is recorded only after
     * the inserts succeed, so a failure is retried on the next launch rather than lost.
     */
    private fun addSourcePresets() {
        val settings = ServiceLocator.settings
        if (settings.sourcePresetsVersion.value >= SourcePresets.VERSION) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val repository = ServiceLocator.repository
                SourcePresets.toAdd(repository.allSources(), BankPalette).forEach { source ->
                    val id = repository.addSource(source)
                    if (source.name == SourcePresets.MAIN_CHARGER.name && settings.lastChargerId.value == null) {
                        settings.lastChargerId.set(id)
                    }
                }
                settings.sourcePresetsVersion.set(SourcePresets.VERSION)
            }.onFailure { Log.w("SourcePresets", "could not add preset sources", it) }
        }
    }
}
