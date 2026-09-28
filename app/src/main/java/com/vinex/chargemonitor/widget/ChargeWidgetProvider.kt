package com.vinex.chargemonitor.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.R
import com.vinex.chargemonitor.notify.Notifications

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
        manager.updateAppWidget(
            ids,
            views(
                context,
                watts = if (charging) snapshot?.powerW else null,
                levelPct = snapshot?.levelPct,
                sourceName = if (charging) state.source?.name else null,
                bankFullCharges = lastBankFullCharges,
            ),
        )
    }

    companion object {
        @Volatile
        private var lastBankFullCharges: Double? = null

        /**
         * Updates every placed widget. Null [watts] or [sourceName] shows the idle line;
         * a null [bankFullCharges] keeps the last known bank total.
         */
        fun push(
            context: Context,
            watts: Double? = null,
            levelPct: Float? = null,
            sourceName: String? = null,
            bankFullCharges: Double? = null,
        ) {
            if (bankFullCharges != null) lastBankFullCharges = bankFullCharges
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ChargeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            runCatching {
                manager.updateAppWidget(ids, views(context, watts, levelPct, sourceName, lastBankFullCharges))
            }
        }

        private fun views(
            context: Context,
            watts: Double?,
            levelPct: Float?,
            sourceName: String?,
            bankFullCharges: Double?,
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
                bankFullCharges?.let { context.getString(R.string.widget_bank_total, it) }
                    ?: context.getString(R.string.widget_placeholder_bank),
            )
        }
    }
}
