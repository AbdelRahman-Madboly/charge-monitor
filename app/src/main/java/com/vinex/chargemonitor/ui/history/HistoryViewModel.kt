package com.vinex.chargemonitor.ui.history

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Repository
import com.vinex.chargemonitor.ui.SessionActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayGroup(val day: LocalDate, val sessions: List<ChargeSession>)

data class HistoryUi(
    val view: HistoryView = HistoryView(emptyList(), HistoryTotals(), 0),
    val filter: HistoryFilter = HistoryFilter(),
    val sources: Map<Long, PowerSource> = emptyMap(),
    /** Sources offered as filter chips, most used first. */
    val chipSources: List<PowerSource> = emptyList(),
    /** Every stored session before any filter; zero means there is no history at all. */
    val totalSessions: Int = 0,
    /** Level of the session recording right now, fresher than its last checkpoint. */
    val live: LiveLevel? = null,
    val loaded: Boolean = false,
)

data class LiveLevel(val sessionId: Long, val levelPct: Float)

sealed interface ExportResult {
    data class Saved(val uri: Uri, val rows: Int, val kind: CsvExport.Kind) : ExportResult
    data class Failed(val message: String) : ExportResult
}

class HistoryViewModel(
    private val repository: Repository = ServiceLocator.repository,
) : ViewModel() {

    // Held here rather than in the composable: the History tab's back stack entry, and with it
    // this view model, is kept while the user visits other tabs.
    private val filter = MutableStateFlow(HistoryFilter())

    private val liveLevel = LiveBus.state
        .map { st ->
            val id = st.sessionId
            val level = st.snapshot?.levelPct
            if (id != null && level != null) LiveLevel(id, level) else null
        }
        .distinctUntilChanged()

    val ui: StateFlow<HistoryUi> = combine(
        repository.observeSessions(),
        repository.observeSources(),
        filter,
        liveLevel,
    ) { sessions, sources, f, live ->
        val byId = sources.associateBy { it.id }
        val view = HistoryQuery.apply(sessions, f, ZoneId.systemDefault(), System.currentTimeMillis()) { id ->
            byId[id]?.name.orEmpty()
        }
        HistoryUi(
            view = view,
            filter = f,
            sources = byId,
            chipSources = HistoryQuery.chipSourceIds(sessions, f.sourceId).mapNotNull { byId[it] },
            totalSessions = sessions.size,
            live = live,
            loaded = true,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUi())

    fun setSort(sort: HistorySort) = filter.update { it.copy(sort = sort) }

    fun setSource(sourceId: Long?) = filter.update { it.copy(sourceId = sourceId) }

    fun setRange(range: HistoryRange) = filter.update { it.copy(range = range) }

    fun setHideShort(hide: Boolean) = filter.update { it.copy(hideShort = hide) }

    /** Shows everything again. The order is a preference rather than a filter, so it stays. */
    fun clearFilters() = filter.update { HistoryFilter(sort = it.sort, hideShort = false) }

    private val _exports = MutableSharedFlow<ExportResult>(extraBufferCapacity = 1)
    val exports: SharedFlow<ExportResult> = _exports.asSharedFlow()

    /** Always exports every stored session or sample; the list filters do not apply. */
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
