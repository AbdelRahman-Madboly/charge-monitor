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

/** Draft of a power bank being added or edited; [id] 0 means new. */
data class BankDraft(
    val id: Long = 0,
    val name: String = "",
    val ratedMah: String = "",
    val nominalV: String = "3.7",
    val colorArgb: Int,
) {
    val nameError: Boolean get() = name.isBlank()
    val mahValue: Int? get() = ratedMah.trim().toIntOrNull()
    val mahError: Boolean get() = ratedMah.isNotBlank() && (mahValue == null || mahValue!! !in 100..200_000)
    val voltValue: Double? get() = nominalV.trim().replace(',', '.').toDoubleOrNull()
    val voltError: Boolean get() = voltValue == null || voltValue!! !in 1.0..24.0
    val isValid: Boolean get() = !nameError && !mahError && !voltError

    companion object {
        fun of(s: PowerSource) = BankDraft(
            id = s.id,
            name = s.name,
            ratedMah = s.ratedMah?.toString().orEmpty(),
            nominalV = s.nominalV.toString(),
            colorArgb = s.colorArgb,
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
                kind = SourceKind.POWER_BANK,
                ratedMah = draft.mahValue,
                nominalV = draft.voltValue ?: 3.7,
                colorArgb = draft.colorArgb,
                archived = existing?.archived ?: false,
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
