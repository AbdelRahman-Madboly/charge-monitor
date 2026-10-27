package com.vinex.chargemonitor.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BankEventKind {
    /** The bank was charged up to full; a capacity cycle starts here. */
    RECHARGED,

    /** The bank ran out; the cycle since the last [RECHARGED] is complete. */
    EMPTY,
}

/**
 * A user-reported moment in a power bank's life. Sessions only say how much energy went into the
 * phone, so these markers are what turn a running total into a per-drain capacity.
 */
@Entity(
    tableName = "bank_event",
    foreignKeys = [
        ForeignKey(
            entity = PowerSource::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("sourceId"), Index("tMs")],
)
data class BankEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val tMs: Long,
    val kind: BankEventKind,
    /**
     * Cell voltage measured at the pack when the mark was made, V; null when not measured. It
     * tells a real full charge (about 4.2 V) from a partial one, and a real cutoff (about 3.0 V).
     */
    val cellV: Double? = null,
    /** Inline USB meter energy for the whole drain, entered on Ran empty. */
    val meterWh: Double? = null,
) {
    /** Null when no voltage was measured. */
    val confirmsFull: Boolean? get() = cellV?.let { it >= FULL_V }
    val confirmsEmpty: Boolean? get() = cellV?.let { it <= EMPTY_V }

    companion object {
        /** A resting Li-ion cell at or above this is fully charged (4.20 V less meter and rest drop). */
        const val FULL_V = 4.15

        /** At or below this a resting cell is at the module's cutoff. */
        const val EMPTY_V = 3.20
    }
}
