package com.vinex.chargemonitor.ui.bank

import com.vinex.chargemonitor.data.BankEvent
import com.vinex.chargemonitor.data.BankEventKind
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.stats.CellOcv
import com.vinex.chargemonitor.stats.EnergyModel
import com.vinex.chargemonitor.stats.PhoneLoad
import com.vinex.chargemonitor.stats.StatsCalculator
import com.vinex.chargemonitor.ui.dashboard.SourceCard
import java.util.Locale

/** Pure bank calculations shared by the dashboard, detail screen and shared report. */
object BankReport {
    fun build(
        bank: PowerSource,
        sessions: List<ChargeSession>,
        events: List<BankEvent>,
        model: EnergyModel.Params = EnergyModel.DEFAULT,
        designMah: Int? = null,
        delivery: PhoneLoad.Delivery? = null,
    ): SourceCard {
        val all = sessions.filter { it.sourceId == bank.id && !it.isOpen && !it.mock }
        val own = all.filter { it.sourceConfirmed }
        val unconfirmed = all.filterNot { it.sourceConfirmed }
        val marks = events.filter { it.sourceId == bank.id }.sortedBy { it.tMs }
        val capacity = StatsCalculator.bankCapacityEstimate(all, bank, marks, model)
        val measured = capacity.efficiency?.takeIf { capacity.drawnWh >= 1.0 }
        val efficiency = measured ?: EnergyModel.efficiencyAt(EnergyModel.TYPICAL_BATTERY_W, model)
        val left = remaining(bank, capacity, marks.lastOrNull())
        val fullCharges = StatsCalculator.fullChargeEquivalents(own)
        return SourceCard(
            source = bank,
            fullCharges = fullCharges,
            whIn = own.sumOf { it.whIn },
            sessionCount = own.size,
            avgW = own.takeIf { it.isNotEmpty() }?.map { it.avgW }?.average(),
            capacity = capacity,
            usedExcluded = 0,
            unconfirmedCount = unconfirmed.size,
            unconfirmedFullCharges = StatsCalculator.fullChargeEquivalents(unconfirmed),
            lastMark = marks.lastOrNull(),
            labelPhoneCharges = StatsCalculator.labelPhoneCharges(bank, designMah, efficiency),
            lastUsedMs = all.maxOfOrNull { it.endMs ?: it.startMs },
            delivery = delivery,
            fairPhoneCharges = delivery?.phoneUseMah?.takeIf { it > 0.0 }?.let { use ->
                designMah?.takeIf { it > 0 }?.let { fullCharges + use / it }
            },
            energy = StatsCalculator.energy(own, model, capacity.moduleEfficiency, capacity.portScale),
            efficiency = efficiency,
            efficiencyFromSessions = measured != null,
            chargeLeft = left?.let { wh -> bank.usableCellWh?.let { wh / it } },
            phoneChargesLeft = left?.let { wh ->
                designMah?.takeIf { it > 0 }?.let { wh * efficiency / (it * StatsCalculator.PHONE_NOMINAL_V / 1000.0) }
            },
            chargeHistory = chargeHistory(bank, own, marks.lastOrNull(), model, capacity.moduleEfficiency, capacity.portScale),
            marks = marks,
        )
    }

    fun remaining(bank: PowerSource, capacity: StatsCalculator.CapacityEstimate?, lastMark: BankEvent?): Double? {
        if (lastMark?.kind != BankEventKind.RECHARGED) return null
        val rated = bank.usableCellWh ?: return null
        return (rated * CellOcv.energyFraction(lastMark.cellV ?: 4.20) - (capacity?.sinceRechargeCellsWh ?: 0.0)).coerceAtLeast(0.0)
    }

    fun chargeHistory(
        bank: PowerSource,
        sessions: List<ChargeSession>,
        lastMark: BankEvent?,
        model: EnergyModel.Params,
        moduleEff: Double? = null,
        portScale: Double = 1.0,
    ): List<Pair<Long, Double>> {
        if (lastMark?.kind != BankEventKind.RECHARGED) return emptyList()
        val rated = bank.usableCellWh ?: return emptyList()
        val start = CellOcv.energyFraction(lastMark.cellV ?: 4.20)
        var used = 0.0
        val after = sessions.filter { it.sourceId == bank.id && it.sourceConfirmed && !it.isOpen && !it.mock && (it.endMs ?: it.startMs) > lastMark.tMs }
            .sortedBy { it.endMs }.map {
                used += StatsCalculator.energy(it, model, moduleEff, portScale).cellsWh
                (it.endMs ?: it.startMs) to (start - used / rated).coerceIn(0.0, 1.0)
            }
        return listOf(lastMark.tMs to start) + after
    }

    fun efficiencyBasis(capacity: StatsCalculator.CapacityEstimate): String {
        val counted = capacity.drains.filter { it.counted }
        val basis = counted.filter { it.verified }.ifEmpty { counted }
        return basis.groupingBy { it.grade }.eachCount().entries.sortedBy { it.key }
            .joinToString(", ") { (grade, count) -> "$count $grade drain${if (count == 1) "" else "s"}" }
            .let { if (it.isEmpty()) "awaiting a drain" else "from $it" }
    }

    /** Middle drain(s) by normalized output; even counts use the average of the central pair. */
    private fun middleDrains(capacity: StatsCalculator.CapacityEstimate): List<StatsCalculator.Drain> {
        val sorted = capacity.drains.filter { it.counted }.sortedBy { it.outputMahFull }
        if (sorted.isEmpty()) return emptyList()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) listOf(sorted[mid]) else listOf(sorted[mid - 1], sorted[mid])
    }

    fun fullBankEnergy(capacity: StatsCalculator.CapacityEstimate): StatsCalculator.EnergyTotals? {
        val middle = middleDrains(capacity)
        if (middle.isEmpty() || middle.any { it.cellWh == null }) return null
        return StatsCalculator.EnergyTotals(
            middle.map { it.batteryWh / it.window }.average(),
            middle.map { it.phoneUseWh / it.window }.average(),
            middle.map { it.portWh / it.window }.average(),
            middle.map { it.cellWh!! / it.window }.average(),
        )
    }

    fun moduleHeatW(capacity: StatsCalculator.CapacityEstimate): Double? =
        middleDrains(capacity).mapNotNull { it.moduleHeatW }.takeIf { it.isNotEmpty() }?.average()

    fun text(card: SourceCard): String = buildString {
        val bank = card.source
        val c = card.capacity ?: StatsCalculator.CapacityEstimate.NONE
        fun number(value: Double?, decimals: Int = 2) = value?.let { String.format(Locale.ROOT, "%.${decimals}f", it) } ?: "—"
        appendLine("Charge Monitor — ${bank.name}")
        appendLine("Product: ${bank.ratedMah ?: "—"} mAh at ${number(bank.nominalV)} V; advertised ${number(bank.ratedWh)} Wh")
        appendLine("Rated output: ${bank.outputMah ?: "—"} mAh @5V")
        appendLine("Cells: ${bank.cells ?: "not entered"}; datasheet ${bank.cellsMah ?: "—"} mAh")
        appendLine("Module: ${bank.module ?: "not entered"}; ${number(bank.ratedW)} W; cutoff ${number(bank.cutoffV)} V")
        bank.notes?.let { appendLine("Notes: $it") }
        appendLine()
        appendLine("Drain | grade | window % | start/end V | meter Wh | port Wh | output mAh @5V | full-bank mAh @5V | phone charges/full | module % | heat Wh")
        c.drains.forEachIndexed { i, d ->
            appendLine("${i + 1} | ${d.grade} | ${number(d.window * 100)} | ${number(d.startV)}/${number(d.endV)} | ${number(d.meterWh)} | ${number(d.portWh)} | ${number(d.outputMah)} | ${if (d.counted) number(d.outputMahFull) else "excluded"} | ${if (d.counted) number(d.phoneChargesFull) else "excluded"} | ${number(d.moduleEfficiency?.times(100))} | ${number(d.moduleLossWh)}")
        }
        appendLine()
        appendLine("Medians over ${c.completedCycles} counted drains (estimated full-bank equivalents):")
        appendLine("Output / full bank: ${number(c.cycleOutputMah)} mAh @5V; ${number(c.outputRatio?.times(100))} % of rated output")
        appendLine("Output energy: ${number(c.cycleOutputMah?.times(0.005))} Wh vs advertised ${number(bank.ratedWh)} Wh")
        appendLine("Phone charges / full bank: ${number(c.cyclePhoneCharges)}")
        appendLine("Battery / full bank: ${number(c.cycleBatteryWh)} Wh")
        appendLine("Module efficiency: ${number(c.moduleEfficiency?.times(100))} % (estimated; ${efficiencyBasis(c)})")
        fullBankEnergy(c)?.let { flow ->
            appendLine("Module heat / full bank: ${number(flow.bankLossWh)} Wh; average ${number(moduleHeatW(c))} W while plugged (estimated, median drain)")
        }
        appendLine("Phone-side scale: ${number(c.portScale)}")
        appendLine("Retest progress: ${c.verifiedCycles} of 3 verified drains")
        appendLine("Method: battery measured; phone use and cable + charger modelled per reading, calibrated by a USB meter when available.")
        appendLine("Metered port Wh is measured; other port Wh and full-bank equivalents are estimated.")
        appendLine("Cells use datasheet capacity (label fallback), nominal voltage × 0.96 and approximate resting OCV energy windows.")
        appendLine("Rest unplugged for at least 30 min before voltage readings. Missing voltages assume 4.20 V / 3.00 V.")
        appendLine("Module loss = cells - port. Windows below 30 % and unconfirmed drains are excluded, including from calibration.")
        appendLine("Grades: A = both voltages + USB meter; B = both voltages; C = assumed endpoint(s).")
    }
}
