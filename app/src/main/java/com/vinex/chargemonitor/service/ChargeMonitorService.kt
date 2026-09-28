package com.vinex.chargemonitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.R
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.notify.AlertRules
import com.vinex.chargemonitor.notify.NotificationActionReceiver
import com.vinex.chargemonitor.notify.Notifications
import com.vinex.chargemonitor.session.RepositorySessionStore
import com.vinex.chargemonitor.session.SessionTracker
import com.vinex.chargemonitor.widget.ChargeWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single long-lived foreground service (spec section 6).
 *
 * Unplugged it holds only broadcast receivers: no loop, no timer, no wakelock. A power event starts
 * the sampling loop, which reads the [com.vinex.chargemonitor.battery.BatterySource] every
 * settings interval (2 s while the Live screen is visible), feeds [SessionTracker], publishes to
 * [LiveBus], raises alerts and refreshes the status notification and widget. The loop ends once
 * the phone is unplugged and the session has closed after its reconnect grace.
 *
 * Everything runs on the main thread (Room's suspend calls hop off it themselves), which keeps
 * the tracker single-threaded without locks. In mock mode the loop also runs while "unplugged",
 * because the mock's plug state lives in the source, not in any broadcast.
 */
class ChargeMonitorService : LifecycleService() {

    private val settings get() = ServiceLocator.settings
    private val tracker by lazy { SessionTracker(RepositorySessionStore(ServiceLocator.repository), settings) }

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null

    private var alertState = AlertRules.State.INITIAL
    private var statusText: String? = null
    private var statusPostedMs = 0L
    private var widgetPushedMs = 0L
    private val sourceChanges = ArrayDeque<NotificationActionReceiver.SourceChange>()

    // Last plug/status seen by the receiver; BATTERY_CHANGED fires every few seconds on some
    // phones, so it only wakes the loop when one of these actually changes.
    private var lastPlugged: Int? = null
    private var lastStatus: Int? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> kick()
                Intent.ACTION_BATTERY_CHANGED -> {
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val changed = plugged != lastPlugged || status != lastStatus
                    lastPlugged = plugged
                    lastStatus = status
                    if (changed) kick()
                }
                // Only matters mid-session, where it sharpens the screen-on attribution.
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> if (loopRunning) wake.trySend(Unit)
            }
        }
    }

    private val loopRunning: Boolean get() = loopJob?.isActive == true

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
        registerPowerReceiver()
        observeTriggers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // One read decides everything: resume an open session, start one, or stop straight away.
        kick()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        LiveBus.publish { it.copy(serviceRunning = false) }
        super.onDestroy()
    }

    /**
     * Plain [Context.registerReceiver], not ContextCompat: the manifest strips the permission
     * ContextCompat needs for RECEIVER_NOT_EXPORTED below API 33. These are all protected system
     * broadcasts, so no export flag is required there.
     */
    private fun registerPowerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun observeTriggers() {
        lifecycleScope.launch {
            // Switch to the 2 s cadence as soon as the Live screen appears.
            LiveBus.state.map { it.liveVisible }.distinctUntilChanged().drop(1)
                .collect { if (loopRunning) wake.trySend(Unit) }
        }
        lifecycleScope.launch {
            settings.mockMode.flow.drop(1).collect { kick() }
        }
        lifecycleScope.launch {
            // Queued for the loop rather than applied here: the tracker must only be touched from
            // the loop coroutine, or two suspended calls could interleave on its state.
            NotificationActionReceiver.sourceChanges.collect { change ->
                sourceChanges.addLast(change)
                if (loopRunning) wake.trySend(Unit)
            }
        }
    }

    private fun kick() {
        wake.trySend(Unit)
        if (!loopRunning) loopJob = lifecycleScope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val source = ServiceLocator.batterySource
            val snapshot = source.read()
            try {
                tick(snapshot, source.isMock)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed write must not end monitoring; the next tick retries.
                Log.w(TAG, "Tick failed", e)
            }
            if (!snapshot.isPlugged && !tracker.hasOpenSession && !source.isMock) break
            val intervalMs = if (LiveBus.state.value.liveVisible) {
                LIVE_INTERVAL_MS
            } else {
                (settings.sampleIntervalSec.value * 1000L).coerceAtLeast(LIVE_INTERVAL_MS)
            }
            withTimeoutOrNull(intervalMs) { wake.receive() }
        }
    }

    private suspend fun tick(snapshot: BatterySnapshot, isMock: Boolean) {
        while (sourceChanges.isNotEmpty()) {
            val change = sourceChanges.removeFirst()
            tracker.applySource(change.sessionId, change.sourceId)
        }
        val persistEveryMs = settings.sampleIntervalSec.value * 1000L
        val events = tracker.onSnapshot(snapshot, isMock, persistEveryMs)
        for (event in events) handle(event, snapshot)

        LiveBus.publish {
            it.copy(
                snapshot = snapshot,
                session = tracker.totals,
                sessionId = tracker.sessionId,
                source = tracker.source,
                isCharging = snapshot.isPlugged,
                serviceRunning = true,
                isMock = isMock,
            )
        }

        if (tracker.hasOpenSession && snapshot.isPlugged) {
            val (next, alerts) = AlertRules.evaluate(alertState, snapshot, alertConfig())
            alertState = next
            alerts.forEach {
                Notifications.showAlert(this, it, settings.targetLevel.value, snapshot.tempC, settings.tempThresholdC.value)
            }
        }

        updateStatus(snapshot, isMock)
        if (snapshot.isPlugged && snapshot.tMs - widgetPushedMs >= WIDGET_INTERVAL_MS) {
            widgetPushedMs = snapshot.tMs
            pushWidget(snapshot, tracker.source, charging = true)
        }
    }

    private suspend fun handle(event: SessionTracker.Event, snapshot: BatterySnapshot) {
        when (event) {
            is SessionTracker.Event.Opened -> {
                alertState = AlertRules.State.INITIAL
                widgetPushedMs = 0L
                when {
                    event.decision.prompt ->
                        Notifications.showSourcePrompt(this, event.session.id, event.promptOptions, event.source)
                    event.session.plug == "USB" ->
                        Notifications.showLaptopNotice(this, event.session.id, event.laptopBank)
                }
            }
            is SessionTracker.Event.Unplugged -> pushWidget(snapshot, tracker.source, charging = false)
            is SessionTracker.Event.Closed -> Notifications.cancelPrompt(this)
            is SessionTracker.Event.Resumed, is SessionTracker.Event.Reconnected -> Unit
        }
    }

    private fun alertConfig() = AlertRules.Config(
        targetLevel = settings.targetLevel.value,
        tempThresholdC = settings.tempThresholdC.value,
        targetEnabled = settings.alertTargetEnabled.value,
        tempEnabled = settings.alertTempEnabled.value,
        stoppedEnabled = settings.alertStoppedEnabled.value,
    )

    /** Re-posts only when the text changes, and at most every few seconds at the 2 s cadence. */
    private fun updateStatus(snapshot: BatterySnapshot, isMock: Boolean) {
        val text = if (snapshot.isPlugged) {
            val name = tracker.source?.name
            val base = if (name != null) {
                getString(R.string.notif_charging_source, snapshot.powerW, snapshot.levelPct, name)
            } else {
                getString(R.string.notif_charging, snapshot.powerW, snapshot.levelPct)
            }
            if (isMock) getString(R.string.notif_mock_suffix, base) else base
        } else {
            null
        }
        if (text == statusText) return
        val pluggedChanged = (text == null) != (statusText == null)
        if (!pluggedChanged && snapshot.tMs - statusPostedMs < STATUS_MIN_INTERVAL_MS) return
        statusText = text
        statusPostedMs = snapshot.tMs
        Notifications.updateStatus(this, text)
    }

    private suspend fun pushWidget(snapshot: BatterySnapshot, source: PowerSource?, charging: Boolean) {
        val bankTotal = source?.takeIf { it.countsTowardBank }?.let { bankFullCharges(it) }
        ChargeWidgetProvider.push(
            this,
            watts = if (charging) snapshot.powerW else null,
            levelPct = snapshot.levelPct,
            sourceName = if (charging) source?.name else null,
            bankFullCharges = bankTotal,
        )
    }

    /** Closed sessions of [bank] plus the level added so far in the open one. */
    private suspend fun bankFullCharges(bank: PowerSource): Double {
        val closed = ServiceLocator.repository.observeTotals(includeUsedWhileCharging = true).first()
            .firstOrNull { it.sourceId == bank.id }?.levelAdded ?: 0.0
        val current = tracker.totals?.levelAdded?.toDouble() ?: 0.0
        return (closed + current) / 100.0
    }

    companion object {
        private const val TAG = "ChargeMonitorService"
        private const val LIVE_INTERVAL_MS = 2_000L
        private const val WIDGET_INTERVAL_MS = 30_000L
        private const val STATUS_MIN_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ChargeMonitorService::class.java))
        }
    }
}
