package com.vinex.chargemonitor.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class FormatTest {
    @Test
    fun nullAndNonFiniteValuesShowADash() {
        assertEquals("--", Format.watts(null))
        assertEquals("--", Format.volts(null))
        assertEquals("--", Format.volts(0))
        assertEquals("--", Format.wh(Double.NaN))
        assertEquals("--", Format.f(Double.POSITIVE_INFINITY, 2))
        assertEquals("--", Format.duration(-1))
    }

    @Test
    fun unitsConvertAndRound() {
        assertEquals("4.12", Format.volts(4123))
        assertEquals("1.50", Format.amps(1500.0))
        assertEquals("12.3", Format.watts(12.34))
        assertEquals("120", Format.watts(120.4))
        assertEquals("1,234", Format.mah(1234.4))
        assertEquals("87", Format.percent(0.8712))
    }

    @Test
    fun whDecimalsShrinkAsValuesGrow() {
        assertEquals("9.87", Format.wh(9.871))
        assertEquals("37.0", Format.wh(37.04))
        assertEquals("148", Format.wh(148.2))
    }

    @Test
    fun durationAndElapsed() {
        assertEquals("40s", Format.duration(40_000))
        assertEquals("12m", Format.duration(12 * 60_000L))
        assertEquals("1h 05m", Format.duration(65 * 60_000L))
        assertEquals("0:00", Format.elapsed(-5))
        assertEquals("12:30", Format.elapsed(750_000))
        assertEquals("1:05:00", Format.elapsed(3_900_000))
    }

    @Test
    fun datesUseTheGivenZone() {
        val t = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC
        assertEquals("22:13", Format.clock(t, ZoneOffset.UTC))
        assertEquals("14 Nov", Format.shortDay(t, ZoneOffset.UTC))
        assertEquals("20231114-2213", Format.fileStamp(t, ZoneOffset.UTC))
        val today = LocalDate.of(2026, 10, 27)
        assertEquals("Today", Format.dayHeader(today, today))
        assertEquals("Yesterday", Format.dayHeader(today.minusDays(1), today))
        assertEquals("Sat 24 Oct 2026", Format.dayHeader(today.minusDays(3), today))
    }
}
