package com.vinex.chargemonitor.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Real [BatterySource] backed by [BatteryManager] and the sticky `ACTION_BATTERY_CHANGED`
 * broadcast. Registering a null receiver only reads the sticky intent, so this holds no
 * receiver and costs nothing between reads.
 *
 * The normaliser's verdict is persisted by the [VerdictSink] it was built with, so the reader
 * itself stays free of settings.
 */
class BatteryReader(
    context: Context,
    private val normalizer: CurrentNormalizer,
    private val clock: () -> Long = System::currentTimeMillis,
) : BatterySource {

    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val levelInterpolator = LevelInterpolator()

    override val isMock: Boolean = false

    override fun read(): BatterySnapshot = runCatching { readUnsafe() }
        // The contract says never throw; a binder hiccup yields a neutral reading instead.
        .getOrElse { BatterySnapshot.EMPTY.copy(tMs = clock()) }

    private fun readUnsafe(): BatterySnapshot {
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
        }.let { if (it == PlugType.AC && usbHostConnected()) PlugType.USB else it }
        val status = when (sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ChargeStatus.CHARGING
            BatteryManager.BATTERY_STATUS_FULL -> ChargeStatus.FULL
            BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeStatus.DISCHARGING
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeStatus.NOT_CHARGING
            else -> ChargeStatus.UNKNOWN
        }
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val integerPct = if (level >= 0 && scale > 0) level * 100f / scale else 0f

        // Both properties return Int.MIN_VALUE when the device does not support them.
        val raw = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Int.MIN_VALUE }?.toLong() ?: 0L
        val counter = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 }?.toLong()

        val now = clock()
        return BatterySnapshot(
            tMs = now,
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            currentUaRaw = raw,
            currentMa = normalizer.normalize(raw, status, plug, integerPct, counter, now),
            levelPct = levelInterpolator.levelPct(integerPct, counter, now, plug != PlugType.NONE),
            chargeCounterUah = counter,
            tempC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            plug = plug,
            status = status,
            screenOn = powerManager?.isInteractive ?: false,
        )
    }

    /**
     * True when the port is a USB data connection to a computer. A laptop's USB-C port can
     * negotiate fast charging, and Samsung then reports it as AC (seen on a Galaxy A54), so the
     * plug type alone cannot tell it from a charger. Chargers and power banks never enumerate,
     * so a data link settles it. The link comes up a moment after power does; the tracker
     * reclassifies an unconfirmed AC session when that happens.
     */
    private fun usbHostConnected(): Boolean = runCatching {
        val filter = IntentFilter(ACTION_USB_STATE)
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(null, filter)
        }
        state?.getBooleanExtra(USB_CONNECTED, false) == true
    }.getOrDefault(false)

    private companion object {
        // Hidden in UsbManager, but a sticky system broadcast any app can read.
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        const val USB_CONNECTED = "connected"
    }
}

/**
 * Adds a decimal to the platform's integer battery level using the charge counter. Pure.
 *
 * The obvious `counter / fullCapacity` does not match what the phone shows: Samsung scales its
 * displayed level, and the counter's idea of "full" drifts with age. Instead this anchors on the
 * moment the integer level steps by one (the true level is then at the step boundary) and
 * interpolates from there with the counter, so the decimal always agrees with the integer the
 * system reports. Until a step has been seen, or whenever the result would disagree with the
 * integer level, it returns the integer level unchanged.
 *
 * A step is only trusted when the previous read was recent and on the same side of the plug:
 * the service stops reading while unplugged, so "50 at unplug, 49 at replug hours later" is not
 * a crossing that just happened.
 */
class LevelInterpolator {
    private var lastLevel: Int? = null
    private var anchorLevel: Int? = null
    private var anchorCounterUah: Long? = null
    private var lastReadMs: Long? = null
    private var lastPlugged: Boolean? = null

    fun levelPct(integerPct: Float, counterUah: Long?, tMs: Long? = null, plugged: Boolean? = null): Float {
        val level = integerPct.toInt()
        val stale = (tMs != null && lastReadMs?.let { tMs - it !in 0..STALE_READ_MS } == true) ||
            (plugged != null && lastPlugged != null && plugged != lastPlugged)
        if (tMs != null) lastReadMs = tMs
        if (plugged != null) lastPlugged = plugged
        // Non-integral input means an unusual EXTRA_SCALE; nothing sensible to add.
        if (stale || integerPct != level.toFloat() || counterUah == null || counterUah <= 0 || level < MIN_LEVEL) {
            reset(level)
            return integerPct
        }

        val previous = lastLevel
        lastLevel = level
        if (previous != null && previous != level) {
            if (kotlin.math.abs(previous - level) == 1) {
                // Charging 41->42 crosses 42.0; discharging 43->42 crosses 43.0 going down.
                anchorLevel = maxOf(previous, level)
                anchorCounterUah = counterUah
            } else {
                // A jump means reads were missed; the crossing moment is unknown.
                anchorLevel = null
                anchorCounterUah = null
            }
        }

        val anchor = anchorLevel ?: return integerPct
        val anchorCounter = anchorCounterUah ?: return integerPct
        if (level != anchor && level != anchor - 1) return integerPct

        // Rough full capacity from the current reading; a few percent error here moves the
        // decimal by hundredths, since it only scales the distance from the anchor.
        val fullUah = counterUah * 100.0 / level
        val estimate = anchor + (counterUah - anchorCounter) * 100.0 / fullUah
        return when {
            estimate < level - PLAUSIBLE_SLACK || estimate > level + 1 + PLAUSIBLE_SLACK -> integerPct
            else -> (Math.round(estimate.coerceIn(level.toDouble(), level + MAX_FRACTION) * 10) / 10.0).toFloat()
        }
    }

    private fun reset(level: Int) {
        lastLevel = level
        anchorLevel = null
        anchorCounterUah = null
    }

    private companion object {
        /** Below this the counter/level ratio is too coarse to estimate full capacity. */
        const val MIN_LEVEL = 5

        /** How far past the integer bounds the estimate may stray before it is distrusted. */
        const val PLAUSIBLE_SLACK = 0.5

        const val MAX_FRACTION = 0.9

        /**
         * A gap longer than this between reads makes a one-step change useless as an anchor.
         * Generous because the service also reads on every level change while plugged.
         */
        const val STALE_READ_MS = 10 * 60_000L
    }
}
