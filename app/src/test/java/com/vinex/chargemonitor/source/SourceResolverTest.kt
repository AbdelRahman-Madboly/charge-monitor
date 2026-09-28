package com.vinex.chargemonitor.source

import com.vinex.chargemonitor.battery.PlugType
import com.vinex.chargemonitor.data.BuiltInSources.CHARGER_ID
import com.vinex.chargemonitor.data.BuiltInSources.LAPTOP_ID
import com.vinex.chargemonitor.data.BuiltInSources.WIRELESS_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResolverTest {

    private val banks = listOf(7L, 5L, 9L)

    @Test
    fun usbIsLaptopWithoutPromptAndNeverABank() {
        val d = SourceResolver.decide(PlugType.USB, banks, lastNonLaptopSourceId = 7L)
        assertEquals(LAPTOP_ID, d.sourceId)
        assertFalse(d.prompt)
        assertFalse(d.countsTowardBank)
        assertTrue(d.options.isEmpty())
    }

    @Test
    fun wirelessIsWirelessWithoutPrompt() {
        val d = SourceResolver.decide(PlugType.WIRELESS, banks, 7L)
        assertEquals(WIRELESS_ID, d.sourceId)
        assertFalse(d.prompt)
        assertFalse(d.countsTowardBank)
    }

    @Test
    fun unpluggedHasNoSource() {
        val d = SourceResolver.decide(PlugType.NONE, banks, 7L)
        assertNull(d.sourceId)
        assertFalse(d.prompt)
    }

    @Test
    fun acPromptsAndKeepsTheLastUsedBank() {
        val d = SourceResolver.decide(PlugType.AC, banks, lastNonLaptopSourceId = 7L)
        assertEquals(7L, d.sourceId)
        assertTrue(d.prompt)
        assertTrue(d.countsTowardBank)
        // Last two banks, most recent first, then Charger.
        assertEquals(listOf(7L, 5L, CHARGER_ID), d.options)
    }

    @Test
    fun acWithNoHistoryDefaultsToCharger() {
        val d = SourceResolver.decide(PlugType.AC, emptyList(), null)
        assertEquals(CHARGER_ID, d.sourceId)
        assertTrue(d.prompt)
        assertFalse(d.countsTowardBank)
        assertEquals(listOf(CHARGER_ID), d.options)
    }

    @Test
    fun acAfterAChargerSessionKeepsCharger() {
        val d = SourceResolver.decide(PlugType.AC, banks, CHARGER_ID)
        assertEquals(CHARGER_ID, d.sourceId)
        assertFalse(d.countsTowardBank)
        assertEquals(listOf(7L, 5L, CHARGER_ID), d.options)
    }

    @Test
    fun acNeverFallsBackToLaptopOrWireless() {
        for (last in listOf(LAPTOP_ID, WIRELESS_ID)) {
            val d = SourceResolver.decide(PlugType.AC, banks, last)
            assertEquals(CHARGER_ID, d.sourceId)
            assertFalse(d.countsTowardBank)
        }
    }

    @Test
    fun builtInsAndDuplicatesAreNotOfferedAsBanks() {
        val d = SourceResolver.decide(PlugType.AC, listOf(CHARGER_ID, 7L, 7L, LAPTOP_ID, 5L), 5L)
        assertEquals(listOf(7L, 5L, CHARGER_ID), d.options)
        assertTrue(d.countsTowardBank)
    }

    @Test
    fun promptTimeoutIsSixtySeconds() {
        assertEquals(60_000L, SourceResolver.PROMPT_TIMEOUT_MS)
    }
}
