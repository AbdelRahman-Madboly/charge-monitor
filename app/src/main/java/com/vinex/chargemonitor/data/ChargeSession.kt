package com.vinex.chargemonitor.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One plug-in to unplug. While open, [endMs] and [endLevel] are null and the running totals are
 * rewritten periodically by the session tracker, so a process death loses at most one interval.
 *
 * Levels are percent with one decimal. [plug] is the [com.vinex.chargemonitor.battery.PlugType]
 * name at session start, kept as text so the data layer does not depend on the battery package.
 */
@Entity(
    tableName = "charge_session",
    foreignKeys = [
        ForeignKey(
            entity = PowerSource::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            // Sources are archived, never deleted, so a session can never lose its source.
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("sourceId"), Index("startMs"), Index("endMs")],
)
data class ChargeSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val plug: String,
    val startMs: Long,
    val endMs: Long? = null,
    /** Last time the running totals were written; lets a restart judge the reconnect grace. */
    val updatedMs: Long = startMs,
    val startLevel: Float,
    val endLevel: Float? = null,
    /** Integral of current into the battery. */
    val mahIn: Double = 0.0,
    /** Charge-counter delta over the session, for cross-checking [mahIn]; null if unsupported. */
    val counterMahIn: Double? = null,
    val whIn: Double = 0.0,
    val peakW: Double = 0.0,
    val avgW: Double = 0.0,
    val maxTempC: Float? = null,
    val reachedFull: Boolean = false,
    /** Screen on for more than 20 % of the session; excluded from capacity estimates by default. */
    val usedWhileCharging: Boolean = false,
    val screenOnFraction: Float = 0f,
    val sampleCount: Int = 0,
    /** Session recorded from the mock source; hidden from totals. */
    val mock: Boolean = false,
) {
    val isOpen: Boolean get() = endMs == null

    /** Percentage points added; clamped so a draining session never subtracts from totals. */
    val levelAdded: Float get() = ((endLevel ?: startLevel) - startLevel).coerceAtLeast(0f)
}
