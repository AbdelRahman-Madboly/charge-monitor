package com.vinex.chargemonitor.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.notify.Notifications

/**
 * The single long-lived foreground service. Skeleton only: it goes foreground with the
 * min-importance status notification and reports itself running.
 *
 * Intended shape (spec section 6): dynamic receivers for power connect/disconnect, battery
 * changed and screen on/off; a sampling coroutine in lifecycleScope that runs only while plugged
 * (10 s, or 2 s while [com.vinex.chargemonitor.LiveState.liveVisible]); no timers or wakelocks
 * while unplugged.
 */
class ChargeMonitorService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        // Called here, not only in the app, so a restart by the system after a crash is covered.
        Notifications.createChannels(this)
        ServiceCompat.startForeground(
            this,
            Notifications.ID_STATUS,
            Notifications.status(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        LiveBus.publish { it.copy(serviceRunning = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        LiveBus.publish { it.copy(serviceRunning = false) }
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ChargeMonitorService::class.java))
        }
    }
}
