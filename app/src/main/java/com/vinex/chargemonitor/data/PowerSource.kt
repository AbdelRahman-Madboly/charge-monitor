package com.vinex.chargemonitor.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceKind { POWER_BANK, CHARGER, LAPTOP, WIRELESS }

/**
 * Something the phone charges from. Power banks and named wall chargers are user-defined;
 * Charger, Laptop (USB) and Wireless are seeded on database creation with the fixed ids in
 * [BuiltInSources] and are never deleted.
 */
@Entity(tableName = "power_source", indices = [Index("kind")])
data class PowerSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: SourceKind,
    /** Advertised capacity from the label, at [nominalV]; null for built-ins. */
    val ratedMah: Int? = null,
    /** Cell voltage the label's mAh refers to; 3.7 V for nearly every bank. */
    val nominalV: Double = 3.7,
    val colorArgb: Int,
    val archived: Boolean = false,
    val builtIn: Boolean = false,
    /** Rated output, W, from the label: a charger's output, or a bank's highest port output. */
    val ratedW: Double? = null,
    /** A bank's rated output capacity at 5 V under ideal conditions, mAh, as the label states it. */
    val outputMah: Int? = null,
    /**
     * Total cell capacity from the cell datasheet at [nominalV], mAh: what a full drain really
     * takes out of the cells. Null means the advertised [ratedMah] stands in for it.
     */
    val cellsMah: Int? = null,
    /** Free-text product details shown on the bank's card. */
    val cells: String? = null,
    val module: String? = null,
    /** Cell voltage at which the module cuts off under load, V. */
    val cutoffV: Double? = null,
    val notes: String? = null,
) {
    /** Label energy, Wh: the one figure comparable across banks of different cell voltages. */
    val ratedWh: Double?
        get() = ratedMah?.takeIf { it > 0 }?.let { mah ->
            nominalV.takeIf { it.isFinite() && it > 0.0 }?.let { mah * it / 1000.0 }
        }

    /**
     * Cell energy one full drain takes out, Wh: the datasheet capacity (or the label) down to the
     * module's cutoff, which leaves [USABLE_TO_CUTOFF] of a Li-ion cell's rated energy.
     */
    val usableCellWh: Double?
        get() = (cellsMah ?: ratedMah)?.takeIf { it > 0 }?.let { mah ->
            nominalV.takeIf { it.isFinite() && it > 0.0 }?.let { mah * it / 1000.0 * USABLE_TO_CUTOFF }
        }

    /** Rated output capacity as energy at the port, Wh (the label's mAh at 5 V). */
    val outputWh: Double? get() = outputMah?.takeIf { it > 0 }?.let { it * USB_V / 1000.0 }

    /** Only power banks accumulate toward bank totals; laptop and wireless sessions never do. */
    val countsTowardBank: Boolean get() = kind == SourceKind.POWER_BANK

    companion object {
        /** Voltage a bank's output mAh rating refers to. */
        const val USB_V = 5.0

        /**
         * Share of a Li-ion cell's rated energy (rated down to 2.5 V) left above a bank module's
         * cutoff: the SW6206 stops at 2.9 V under load, about 3.0 V at rest, and an NCR18650B
         * holds only 3 to 4 % of its energy below that.
         */
        const val USABLE_TO_CUTOFF = 0.96
    }
}

/** Stable ids of the seeded rows, so pure code can refer to them without a database lookup. */
object BuiltInSources {
    const val CHARGER_ID = 1L
    const val LAPTOP_ID = 2L
    const val WIRELESS_ID = 3L

    const val CHARGER_COLOR = 0xFF5FBF3F.toInt()
    const val LAPTOP_COLOR = 0xFF4F8FD6.toInt()
    const val WIRELESS_COLOR = 0xFF9A7BD1.toInt()
}
