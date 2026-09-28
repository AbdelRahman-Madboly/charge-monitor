package com.vinex.chargemonitor.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Real [BatterySource] backed by [BatteryManager] and the sticky `ACTION_BATTERY_CHANGED`
 * broadcast. Registering a null receiver only reads the sticky intent, so this holds no
 * receiver and costs nothing between reads.
 *
 * Still to do by the battery team: decimal level from charge counter / full capacity, and
 * persisting [normalizer]'s verdict.
 */
class BatteryReader(
    context: Context,
    private val normalizer: CurrentNormalizer,
    private val clock: () -> Long = System::currentTimeMillis,
) : BatterySource {

    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    override val isMock: Boolean = false

    override fun read(): BatterySnapshot {
        val sticky: Intent? = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val plug = when (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
            // Docks report as AC for source-selection purposes.
            BatteryManager.BATTERY_PLUGGED_DOCK -> PlugType.AC
            else -> PlugType.NONE
        }
        val status = when (sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ChargeStatus.CHARGING
            BatteryManager.BATTERY_STATUS_FULL -> ChargeStatus.FULL
            BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeStatus.DISCHARGING
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeStatus.NOT_CHARGING
            else -> ChargeStatus.UNKNOWN
        }
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val levelPct = if (level >= 0 && scale > 0) level * 100f / scale else 0f

        val raw = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Long.MIN_VALUE } ?: 0L
        val counter = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 && it != Long.MIN_VALUE }

        return BatterySnapshot(
            tMs = clock(),
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            currentUaRaw = raw,
            currentMa = normalizer.normalize(raw, status, plug),
            levelPct = levelPct,
            chargeCounterUah = counter,
            tempC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            plug = plug,
            status = status,
            screenOn = powerManager?.isInteractive ?: false,
        )
    }
}
