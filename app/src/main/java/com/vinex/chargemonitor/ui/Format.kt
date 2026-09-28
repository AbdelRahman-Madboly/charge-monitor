package com.vinex.chargemonitor.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Number and time formatting shared by every screen. Locale.US on purpose: readouts must always
 * use a dot decimal separator so values line up with exported CSV and with the bench tools.
 */
object Format {
    private const val DASH = "--"

    fun watts(w: Double?): String = w?.let { f(it, if (abs(it) >= 100) 0 else 1) } ?: DASH

    fun volts(mv: Int?): String = mv?.takeIf { it > 0 }?.let { f(it / 1000.0, 2) } ?: DASH

    fun amps(ma: Double?): String = ma?.let { f(it / 1000.0, 2) } ?: DASH

    fun tempC(c: Float?): String = c?.let { f(it.toDouble(), 1) } ?: DASH

    fun level(pct: Float?): String = pct?.let { f(it.toDouble(), 1) } ?: DASH

    fun levelInt(pct: Float?): String = pct?.let { "%d".format(Locale.US, it.toInt()) } ?: DASH

    fun mah(mah: Double?): String = mah?.let { "%,d".format(Locale.US, it.roundToLong()) } ?: DASH

    fun wh(wh: Double?): String = wh?.let { f(it, if (abs(it) >= 100) 0 else if (abs(it) >= 10) 1 else 2) } ?: DASH

    fun fullCharges(x: Double?): String = x?.let { f(it, if (it >= 100) 0 else 2) } ?: DASH

    fun percent(ratio: Double?): String = ratio?.let { "%d".format(Locale.US, (it * 100).roundToLong()) } ?: DASH

    fun f(value: Double, decimals: Int): String =
        if (value.isFinite()) "%.${decimals}f".format(Locale.US, value) else DASH

    /** "1h 05m", "12m", "40s"; tight because readouts use a monospace face. */
    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return DASH
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return when {
            h > 0 -> "%dh %02dm".format(Locale.US, h, m)
            m > 0 -> "%dm".format(Locale.US, m)
            else -> "%ds".format(Locale.US, totalSec)
        }
    }

    /** Chart axis flavour: "0:00", "12:30", "1:05:00" relative to the series start. */
    fun elapsed(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
    }

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)
    private val shortDayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val fileStampFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US)

    fun clock(tMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFmt.format(Instant.ofEpochMilli(tMs).atZone(zone))

    fun shortDay(tMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        shortDayFmt.format(Instant.ofEpochMilli(tMs).atZone(zone))

    fun fileStamp(tMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        fileStampFmt.format(Instant.ofEpochMilli(tMs).atZone(zone))

    /** "Today", "Yesterday", or a full date for list section headers. */
    fun dayHeader(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dayFmt.format(day)
    }
}
