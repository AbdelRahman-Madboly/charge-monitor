package com.vinex.chargemonitor

import android.app.Application
import com.vinex.chargemonitor.battery.DesignCapacity
import com.vinex.chargemonitor.notify.Notifications

class ChargeMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Channels must exist before the service's first startForeground call.
        Notifications.createChannels(this)
        seedDesignCapacity()
    }

    /** Spec default for the health estimate; a value the user typed is never replaced. */
    private fun seedDesignCapacity() {
        val setting = ServiceLocator.settings.designCapacityMah
        if (setting.value > 0) return
        DesignCapacity.fromPowerProfile(this)?.let { setting.set(it) }
    }
}
