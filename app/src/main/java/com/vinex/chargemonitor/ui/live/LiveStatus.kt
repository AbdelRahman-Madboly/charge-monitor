package com.vinex.chargemonitor.ui.live

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.battery.PlugType
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.ui.Format
import java.util.Locale

/** What the phone is doing right now, as the one line the Live headline leads with. */
enum class Headline {
    /** No reading has arrived yet. */
    WAITING,

    /** Power is going into the battery. */
    CHARGING,

    /** Plugged in and the battery reports full. */
    FULL,

    /** Plugged in but nothing goes in (laptop port asleep, charge limit, dead bank). */
    PLUGGED_IDLE,

    /** Plugged in but the phone draws more than the source gives. */
    PLUGGED_DRAINING,

    /** Nothing plugged in. */
    ON_BATTERY,
}

/** Pure decisions behind the Live screen, split out so they can be unit-tested. */
object LiveStatus {
    /** Below this the reading is noise, not charging or draining. */
    const val NOISE_W = 0.05

    fun headline(snap: BatterySnapshot?, isCharging: Boolean): Headline = when {
        snap == null || snap.tMs == 0L -> Headline.WAITING
        !snap.isPlugged -> Headline.ON_BATTERY
        snap.status == ChargeStatus.FULL -> Headline.FULL
        snap.powerW > NOISE_W && (isCharging || snap.status == ChargeStatus.CHARGING) -> Headline.CHARGING
        snap.powerW < -NOISE_W -> Headline.PLUGGED_DRAINING
        else -> Headline.PLUGGED_IDLE
    }

    fun plugLabel(plug: PlugType): String? = when (plug) {
        PlugType.AC -> "AC"
        PlugType.USB -> "USB"
        PlugType.WIRELESS -> "Wireless"
        PlugType.NONE -> null
    }

    fun kindLabel(kind: SourceKind): String = when (kind) {
        SourceKind.POWER_BANK -> "Power bank"
        SourceKind.CHARGER -> "Charger"
        SourceKind.LAPTOP -> "Laptop USB"
        SourceKind.WIRELESS -> "Wireless"
    }

    /** "Power bank · 10,000 mAh", "Charger · 25 W rated", or just the kind. */
    fun sourceDetail(source: PowerSource): String {
        val rating = source.ratedMah?.takeIf { it > 0 }?.let { "%,d mAh".format(Locale.US, it) }
            ?: source.ratedW?.takeIf { it > 0 }?.let { "${Format.watts(it)} W rated" }
        return listOfNotNull(kindLabel(source.kind), rating).joinToString(" · ")
    }

    /**
     * Time-to-level readouts worth showing, label to duration. Empty unless charging, and a
     * target already passed is left out rather than shown as "reached".
     */
    fun etas(
        headline: Headline,
        levelPct: Float,
        targetLevel: Int,
        timeToTargetMs: Long?,
        timeToFullMs: Long?,
    ): List<Pair<String, Long>> {
        if (headline != Headline.CHARGING) return emptyList()
        return buildList {
            if (timeToTargetMs != null && levelPct < targetLevel && targetLevel < 100) {
                add("$targetLevel % in" to timeToTargetMs)
            }
            if (timeToFullMs != null && levelPct < 100f) add("Full in" to timeToFullMs)
        }
    }

    /**
     * Chart copy of the session: power and current into the battery, with negative readings
     * (the phone drawing more than the source gives) drawn at zero so one drain spike does not
     * stretch the axis below the line that matters.
     */
    fun chartPoints(points: List<LivePoint>): List<LivePoint> =
        points.map {
            if (it.watts >= 0f && it.amps >= 0f) it
            else it.copy(watts = it.watts.coerceAtLeast(0f), amps = it.amps.coerceAtLeast(0f))
        }

    fun hasNegative(points: List<LivePoint>): Boolean = points.any { it.watts < 0f || it.amps < 0f }
}
