package com.vinex.chargemonitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts monitoring after a reboot or an app update, both of which kill the service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                // A background start can be refused (e.g. a restricted app); nothing useful to do then.
                runCatching { ChargeMonitorService.start(context) }
        }
    }
}
