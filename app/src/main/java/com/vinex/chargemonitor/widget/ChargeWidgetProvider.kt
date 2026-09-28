package com.vinex.chargemonitor.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.vinex.chargemonitor.R
import com.vinex.chargemonitor.notify.Notifications

/**
 * Home-screen widget built on RemoteViews (no Glance dependency). It never refreshes itself;
 * the service calls [push] at most every 30 s while charging.
 */
class ChargeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, baseViews(context))
    }

    companion object {
        /**
         * Updates every placed widget. Null arguments show the idle placeholders.
         *
         * Stub: renders placeholders only; the widget team fills in the values.
         */
        fun push(
            context: Context,
            watts: Double? = null,
            levelPct: Float? = null,
            sourceName: String? = null,
            bankFullCharges: Double? = null,
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ChargeWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, baseViews(context))
        }

        private fun baseViews(context: Context) =
            RemoteViews(context.packageName, R.layout.widget_charge).apply {
                setOnClickPendingIntent(R.id.widget_root, Notifications.openAppIntent(context))
            }
    }
}
