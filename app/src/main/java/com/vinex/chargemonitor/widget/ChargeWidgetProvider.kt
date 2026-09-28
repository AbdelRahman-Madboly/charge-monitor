package com.vinex.chargemonitor.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.R
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A power bank's full-charge total, named, so it never reads as belonging to another source. */
data class BankTotal(val name: String, val fullCharges: Double)

/**
 * Home-screen widget built on RemoteViews (no Glance dependency). It never refreshes itself;
 * the service calls [push] at most every 30 s while charging and once on unplug.
 */
class ChargeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // A freshly placed widget shows whatever the service last published rather than blanks.
        val state = LiveBus.state.value
        val snapshot = state.snapshot
        val charging = state.isCharging && snapshot != null
        val watts = if (charging) snapshot?.powerW else null
        val sourceName = if (charging) state.source?.name else null
        manager.updateAppWidget(ids, views(context, watts, snapshot?.levelPct, sourceName, lastBank))
        if (lastBank != null) return

        // After a reboot nothing is in memory yet; the last bank's total is in the database.
        val pending = goAsync()
        scope.launch {
            try {
                loadLastBank()?.let { bank ->
                    lastBank = bank
                    manager.updateAppWidget(ids, views(context, watts, snapshot?.levelPct, sourceName, bank))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load the bank total", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ChargeWidget"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var lastBank: BankTotal? = null

        /**
         * Updates every placed widget. Null [watts] or [sourceName] shows the idle line;
         * a null [bank] keeps the last bank shown, with its name.
         */
        fun push(
            context: Context,
            watts: Double? = null,
            levelPct: Float? = null,
            sourceName: String? = null,
            bank: BankTotal? = null,
        ) {
            if (bank != null) lastBank = bank
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ChargeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            runCatching {
                manager.updateAppWidget(ids, views(context, watts, levelPct, sourceName, lastBank))
            }
        }

        private suspend fun loadLastBank(): BankTotal? {
            val id = ServiceLocator.settings.lastBankIds.value.firstOrNull() ?: return null
            val source = ServiceLocator.repository.source(id)?.takeIf { it.countsTowardBank } ?: return null
            val added = ServiceLocator.repository.observeTotals(includeUsedWhileCharging = true).first()
                .firstOrNull { it.sourceId == id }?.levelAdded ?: 0.0
            return BankTotal(source.name, added / 100.0)
        }

        private fun views(
            context: Context,
            watts: Double?,
            levelPct: Float?,
            sourceName: String?,
            bank: BankTotal?,
        ) = RemoteViews(context.packageName, R.layout.widget_charge).apply {
            setOnClickPendingIntent(R.id.widget_root, Notifications.openAppIntent(context))
            setTextViewText(
                R.id.widget_watts,
                watts?.let { context.getString(R.string.widget_watts, it) }
                    ?: context.getString(R.string.widget_placeholder_w),
            )
            setTextViewText(
                R.id.widget_level_source,
                when {
                    levelPct != null && sourceName != null ->
                        context.getString(R.string.widget_level_source, levelPct, sourceName)
                    levelPct != null -> context.getString(R.string.widget_level_idle, levelPct)
                    else -> context.getString(R.string.widget_placeholder_source)
                },
            )
            setTextViewText(
                R.id.widget_bank_total,
                bank?.let { context.getString(R.string.widget_bank_total, it.name, it.fullCharges) }
                    ?: context.getString(R.string.widget_placeholder_bank),
            )
        }
    }
}
