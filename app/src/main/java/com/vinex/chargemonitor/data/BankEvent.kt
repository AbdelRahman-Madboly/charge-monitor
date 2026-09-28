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
)
