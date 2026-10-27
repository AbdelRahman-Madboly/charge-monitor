package com.vinex.chargemonitor.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Repository
import com.vinex.chargemonitor.data.SettingsStore
import com.vinex.chargemonitor.data.SourceKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Draft of a power bank or a named wall charger being added or edited; [id] 0 means new. A bank
 * is rated in mAh at a nominal voltage, a charger in watts; the other kind's fields are ignored.
 */
data class BankDraft(
    val id: Long = 0,
    val name: String = "",
    val ratedMah: String = "",
    val nominalV: String = "3.7",
    val colorArgb: Int,
    val kind: SourceKind = SourceKind.POWER_BANK,
    val ratedW: String = "",
    val outputMah: String = "",
    val cellsMah: String = "",
    val cells: String = "",
    val module: String = "",
    val cutoffV: String = "",
    val notes: String = "",
) {
    val isCharger: Boolean get() = kind == SourceKind.CHARGER
    val nameError: Boolean get() = name.isBlank()
    val mahValue: Int? get() = ratedMah.trim().toIntOrNull()
    val mahError: Boolean get() = !isCharger && ratedMah.isNotBlank() && (mahValue == null || mahValue!! !in 100..200_000)
    val voltValue: Double? get() = nominalV.trim().replace(',', '.').toDoubleOrNull()
    val voltError: Boolean get() = !isCharger && (voltValue == null || voltValue!! !in 1.0..24.0)
    val wattValue: Double? get() = ratedW.trim().replace(',', '.').toDoubleOrNull()
    val wattError: Boolean get() = isCharger && ratedW.isNotBlank() && (wattValue == null || wattValue!! !in 1.0..300.0)
    val outputValue: Int? get() = outputMah.trim().toIntOrNull()
    val outputError: Boolean get() = !isCharger && outputMah.isNotBlank() && (outputValue == null || outputValue!! !in 100..200_000)
    val cellsMahValue: Int? get() = cellsMah.trim().toIntOrNull()
    val cellsMahError: Boolean get() = !isCharger && cellsMah.isNotBlank() && (cellsMahValue == null || cellsMahValue!! !in 100..200_000)
    val cutoffValue: Double? get() = cutoffV.trim().replace(',', '.').toDoubleOrNull()
    val cutoffError: Boolean get() = !isCharger && cutoffV.isNotBlank() && (cutoffValue == null || cutoffValue!! !in 2.0..4.0)

    /** A bank's highest port output reuses the charger's rated W field. */
    val bankWattError: Boolean get() = !isCharger && ratedW.isNotBlank() && (wattValue == null || wattValue!! !in 1.0..300.0)
    val isValid: Boolean get() =
        !nameError && !mahError && !voltError && !wattError && !outputError && !cellsMahError && !cutoffError && !bankWattError

    companion object {
        fun of(s: PowerSource) = BankDraft(
            id = s.id,
            name = s.name,
            ratedMah = s.ratedMah?.toString().orEmpty(),
            nominalV = s.nominalV.toString(),
            colorArgb = s.colorArgb,
            kind = s.kind,
            ratedW = s.ratedW?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty(),
            outputMah = s.outputMah?.toString().orEmpty(),
            cellsMah = s.cellsMah?.toString().orEmpty(),
            cells = s.cells.orEmpty(),
            module = s.module.orEmpty(),
            cutoffV = s.cutoffV?.toString().orEmpty(),
            notes = s.notes.orEmpty(),
        )
    }
}

class SettingsViewModel(
    private val repository: Repository = ServiceLocator.repository,
    val settings: SettingsStore = ServiceLocator.settings,
) : ViewModel() {

    val banks: StateFlow<List<PowerSource>> = repository.observeSources()
        .map { all -> all.filter { it.kind == SourceKind.POWER_BANK } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Named wall chargers; the built-in Charger stays the catch-all and is not listed. */
    val chargers: StateFlow<List<PowerSource>> = repository.observeSources()
        .map { all -> all.filter { it.kind == SourceKind.CHARGER && !it.builtIn } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Recorded (non-mock) sessions per source id, for row subtitles and the archive confirmation; null until loaded. */
    val sessionCounts: StateFlow<Map<Long, Int>?> = repository.observeSessions()
        .map { all -> all.filter { !it.mock }.groupingBy { it.sourceId }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(draft: BankDraft) {
        if (!draft.isValid) return
        viewModelScope.launch { saveBank(repository, draft) }
    }

    fun setArchived(id: Long, archived: Boolean) {
        viewModelScope.launch { repository.setArchived(id, archived) }
    }

    companion object {
        /** Shared with onboarding so both create banks identically. */
        suspend fun saveBank(repository: Repository, draft: BankDraft): Long {
            val existing = if (draft.id != 0L) repository.source(draft.id) else null
            val source = PowerSource(
                id = draft.id,
                name = draft.name.trim(),
                kind = draft.kind,
                ratedMah = if (draft.isCharger) null else draft.mahValue,
                nominalV = if (draft.isCharger) 3.7 else draft.voltValue ?: 3.7,
                colorArgb = draft.colorArgb,
                archived = existing?.archived ?: false,
                ratedW = draft.wattValue,
                outputMah = if (draft.isCharger) null else draft.outputValue,
                cellsMah = if (draft.isCharger) null else draft.cellsMahValue,
                cells = draft.cells.trim().takeIf { !draft.isCharger && it.isNotEmpty() },
                module = draft.module.trim().takeIf { !draft.isCharger && it.isNotEmpty() },
                cutoffV = if (draft.isCharger) null else draft.cutoffValue,
                notes = draft.notes.trim().takeIf { !draft.isCharger && it.isNotEmpty() },
            )
            return if (existing == null) {
                repository.addSource(source)
            } else {
                repository.updateSource(source)
                source.id
            }
        }
    }
}
