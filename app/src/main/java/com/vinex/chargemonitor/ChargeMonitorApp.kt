package com.vinex.chargemonitor

import android.app.Application
import com.vinex.chargemonitor.notify.Notifications

class ChargeMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Channels must exist before the service's first startForeground call.
        Notifications.createChannels(this)
    }
}
