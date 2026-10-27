package com.vinex.chargemonitor.ui.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.data.BankEvent
import com.vinex.chargemonitor.data.Repository
import com.vinex.chargemonitor.data.SettingsStore
import com.vinex.chargemonitor.ui.dashboard.SourceCard
import com.vinex.chargemonitor.ui.settings.BankDraft
import com.vinex.chargemonitor.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BankDetailUi(val card: SourceCard? = null, val loaded: Boolean = false)

class BankDetailViewModel(
    private val bankId: Long,
    private val repository: Repository = ServiceLocator.repository,
    settings: SettingsStore = ServiceLocator.settings,
) : ViewModel() {
    val ui = combine(
        repository.observeSources(),
        repository.observeSessions(),
        repository.observeBankEvents(),
        combine(settings.phoneIdleW.flow, settings.phoneScreenW.flow) { _, _ -> settings.energyModel() },
        settings.designCapacityMah.flow,
    ) { sources, sessions, events, model, design ->
        BankDetailUi(
            sources.firstOrNull { it.id == bankId && it.countsTowardBank }?.let {
                BankReport.build(it, sessions, events, model, design.takeIf { n -> n > 0 })
            },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BankDetailUi())

    fun addMark(mark: BankEvent) {
        viewModelScope.launch { repository.addBankEvent(mark.copy(sourceId = bankId)) }
    }

    fun updateMark(mark: BankEvent) {
        viewModelScope.launch { repository.updateBankEvent(mark) }
    }

    fun deleteMark(id: Long) {
        viewModelScope.launch { repository.deleteBankEvent(id) }
    }

    fun saveBank(draft: BankDraft) {
        if (draft.isValid) viewModelScope.launch { SettingsViewModel.saveBank(repository, draft) }
    }
}
