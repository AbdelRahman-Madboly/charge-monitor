package com.vinex.chargemonitor.ui.history

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Repository
import com.vinex.chargemonitor.ui.SessionActions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayGroup(val day: LocalDate, val sessions: List<ChargeSession>)

data class HistoryUi(
    val groups: List<DayGroup> = emptyList(),
    val sources: Map<Long, PowerSource> = emptyMap(),
    val loaded: Boolean = false,
)

sealed interface ExportResult {
    data class Saved(val uri: Uri, val rows: Int, val kind: CsvExport.Kind) : ExportResult
    data class Failed(val message: String) : ExportResult
}

class HistoryViewModel(
    private val repository: Repository = ServiceLocator.repository,
) : ViewModel() {

    val ui: StateFlow<HistoryUi> = combine(repository.observeSessions(), repository.observeSources()) { sessions, sources ->
        HistoryUi(groupByDay(sessions, ZoneId.systemDefault()), sources.associateBy { it.id }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUi())

    private val _exports = MutableSharedFlow<ExportResult>(extraBufferCapacity = 1)
    val exports: SharedFlow<ExportResult> = _exports.asSharedFlow()

    fun export(context: Context, uri: Uri, kind: CsvExport.Kind) {
        val app = context.applicationContext
        viewModelScope.launch {
            val result = runCatching { CsvExport.write(app, uri, kind, repository) }
            _exports.emit(
                result.fold(
                    onSuccess = { ExportResult.Saved(uri, it, kind) },
                    onFailure = { ExportResult.Failed(it.message ?: "Export failed") },
                ),
            )
        }
    }

    /** Keeps the guessed source and marks it as the user's choice. */
    fun confirm(context: Context, sessionId: Long, source: PowerSource) {
        val app = context.applicationContext
        viewModelScope.launch { SessionActions.reassign(app, sessionId, source) }
    }

    companion object {
        /** Newest day first, newest session first within a day; a session belongs to its start day. */
        fun groupByDay(sessions: List<ChargeSession>, zone: ZoneId): List<DayGroup> =
            sessions.sortedByDescending { it.startMs }
                .groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
                .map { (day, list) -> DayGroup(day, list) }
    }
}
