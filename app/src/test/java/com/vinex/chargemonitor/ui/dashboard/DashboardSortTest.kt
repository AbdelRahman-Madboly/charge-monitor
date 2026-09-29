package com.vinex.chargemonitor.ui.dashboard

import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.stats.PhoneLoad
import com.vinex.chargemonitor.stats.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSortTest {
    private fun card(
        name: String,
        fullCharges: Double = 0.0,
        whIn: Double = 0.0,
        ratio: Double? = null,
        cycle: Double? = null,
        label: Double? = null,
        lastUsed: Long? = null,
        peakW: Double? = null,
        avgW: Double? = null,
        kind: SourceKind = SourceKind.POWER_BANK,
        into: Double? = null,
        use: Double = 0.0,
    ) = SourceCard(
        source = PowerSource(name = name, kind = kind, colorArgb = 0),
        fullCharges = fullCharges,
        whIn = whIn,
        sessionCount = 1,
        avgW = avgW,
        capacity = StatsCalculator.CapacityEstimate.NONE.copy(ratioOfRated = ratio, cyclePhoneCharges = cycle),
        usedExcluded = 0,
        labelPhoneCharges = label,
        charger = peakW?.let { StatsCalculator.ChargerStats(it, null, null, 0) },
        lastUsedMs = lastUsed,
        delivery = into?.let { delivery(it, use) },
    )

    private fun delivery(into: Double, use: Double) = PhoneLoad.Delivery(
        intoBatteryMah = into,
        phoneUseMah = use,
        measuredSessions = 1,
        typicalSessions = 0,
        unknownSessions = 0,
        screenOnMs = 0,
        coveredMs = 0,
        typicalLoadMa = null,
    )

    private fun names(cards: List<SourceCard>) = cards.map { it.source.name }

    @Test
    fun phoneChargesBiggestFirstWithNameTieBreak() {
        val sorted = DashboardViewModel.sortCards(
            listOf(card("b", 1.0), card("C", 3.0), card("a", 1.0)),
            DashboardSort.PHONE_CHARGES,
        )
        assertEquals(listOf("C", "a", "b"), names(sorted))
    }

    @Test
    fun unknownRealCapacityGoesLast() {
        val sorted = DashboardViewModel.sortCards(
            listOf(card("none"), card("half", ratio = 0.5), card("most", ratio = 0.9)),
            DashboardSort.REAL_CAPACITY,
        )
        assertEquals(listOf("most", "half", "none"), names(sorted))
    }

    @Test
    fun perFullBankPrefersMeasuredOverLabel() {
        val sorted = DashboardViewModel.sortCards(
            listOf(card("promise", label = 4.0), card("measured", cycle = 2.0, label = 5.0), card("neither")),
            DashboardSort.PER_FULL_BANK,
        )
        assertEquals(listOf("promise", "measured", "neither"), names(sorted))
    }

    @Test
    fun lastUsedNewestFirstAndNeverUsedLast() {
        val sorted = DashboardViewModel.sortCards(
            listOf(card("old", lastUsed = 1_000), card("never"), card("new", lastUsed = 5_000)),
            DashboardSort.LAST_USED,
        )
        assertEquals(listOf("new", "old", "never"), names(sorted))
    }

    @Test
    fun nameIsCaseInsensitiveAToZ() {
        val sorted = DashboardViewModel.sortCards(
            listOf(card("zolo", 9.0), card("Anker"), card("adata")),
            DashboardSort.NAME,
        )
        assertEquals(listOf("adata", "Anker", "zolo"), names(sorted))
    }

    @Test
    fun zeroAveragePowerCountsAsUnknown() {
        val sorted = DashboardViewModel.sortCards(
            listOf(card("zero", avgW = 0.0), card("fast", avgW = 12.0), card("slow", avgW = 5.0)),
            DashboardSort.AVG_POWER,
        )
        assertEquals(listOf("fast", "slow", "zero"), names(sorted))
    }

    @Test
    fun banksDefaultToDeliveredMah() {
        assertEquals(DashboardSort.DELIVERED_MAH, DashboardTab.BANKS.defaultSort)
        assertEquals(DashboardSort.DELIVERED_MAH, DashboardUi().sort)
    }

    @Test
    fun deliveredMahCountsPhoneUseAndUnloadedGoesLast() {
        // "idle" put more into the battery, but "used" delivered more once phone use is added.
        val sorted = DashboardViewModel.sortCards(
            listOf(card("idle", into = 5_000.0), card("loading"), card("used", into = 4_500.0, use = 1_000.0)),
            DashboardSort.DELIVERED_MAH,
        )
        assertEquals(listOf("used", "idle", "loading"), names(sorted))
    }

    @Test
    fun fairChargesAddPhoneUseInDesignBatteries() {
        assertEquals(1.6, DashboardViewModel.fairCharges(1.4, delivery(5_000.0, 1_000.0), 5_000)!!, 1e-9)
        assertNull(DashboardViewModel.fairCharges(1.4, delivery(5_000.0, 1_000.0), null))
        assertNull(DashboardViewModel.fairCharges(1.4, delivery(5_000.0, 1_000.0), 0))
        assertNull(DashboardViewModel.fairCharges(1.4, delivery(5_000.0, 0.0), 5_000))
        assertNull(DashboardViewModel.fairCharges(1.4, null, 5_000))
    }

    @Test
    fun everyTabOffersItsDefaultSortAndTabsPartitionKinds() {
        DashboardTab.entries.forEach { assertTrue(it.defaultSort in it.sorts) }
        SourceKind.entries.forEach { kind ->
            assertEquals(1, DashboardTab.entries.count { it.admits(kind) })
        }
    }
}
