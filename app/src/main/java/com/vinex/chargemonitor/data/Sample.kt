package com.vinex.chargemonitor.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted reading inside a session, packed small (about 40 bytes a row) because a year of
 * daily charging at a 10 s cadence is tens of thousands of rows.
 *
 * [level10] is percent x10 and [temp10] is degrees C x10.
 */
@Entity(
    tableName = "sample",
    foreignKeys = [
        ForeignKey(
            entity = ChargeSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val tMs: Long,
    val mV: Int,
    val mA: Int,
    val level10: Int,
    val temp10: Int,
    val screenOn: Boolean,
)
