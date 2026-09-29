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
    /** Label capacity; null for built-ins. */
    val ratedMah: Int? = null,
    /** Cell voltage the label's mAh refers to; 3.7 V for nearly every bank. */
    val nominalV: Double = 3.7,
    val colorArgb: Int,
    val archived: Boolean = false,
    val builtIn: Boolean = false,
    /** A wall charger's rated output, W, from its label; null for banks and built-ins. */
    val ratedW: Double? = null,
) {
    /** Label energy, Wh: the one figure comparable across banks of different cell voltages. */
    val ratedWh: Double?
        get() = ratedMah?.takeIf { it > 0 }?.let { mah ->
            nominalV.takeIf { it.isFinite() && it > 0.0 }?.let { mah * it / 1000.0 }
        }

    /** Only power banks accumulate toward bank totals; laptop and wireless sessions never do. */
    val countsTowardBank: Boolean get() = kind == SourceKind.POWER_BANK
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
