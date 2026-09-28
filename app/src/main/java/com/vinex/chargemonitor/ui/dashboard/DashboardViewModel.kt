package com.vinex.chargemonitor.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Repository
import com.vinex.chargemonitor.data.SettingsStore
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.stats.StatsCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Everything one source's card shows. */
data class SourceCard(
    val source: PowerSource,
    /** Exact; includes used-while-charging sessions by design (spec section 2). */
    val fullCharges: Double,
    val whIn: Double,
    val sessionCount: Int,
    /** Mean of per-session averages over the sessions the toggle admits; null without any. */
    val avgW: Double?,
    /** Only for power banks. */
    val capacity: StatsCalculator.CapacityEstimate?,
    /** Sessions left out of the estimate because the phone was in use. */
    val usedExcluded: Int,
)

data class DashboardUi(
    val sources: List<PowerSource> = emptyList(),
    /** Null means "All". */
    val filter: Long? = null,
    val cards: List<SourceCard> = emptyList(),
    /** Closed sessions matching the filter, oldest first, for the charts. */
    val chartSessions: List<ChargeSession> = emptyList(),
    val includeUsed: Boolean = false,
    val lossFactor: Float = SettingsStore.DEFAULT_LOSS_FACTOR,
    val health: StatsCalculator.HealthEstimate? = null,
    val loaded: Boolean = false,
)

class DashboardViewModel(
    repository: Repository = ServiceLocator.repository,
    private val settings: SettingsStore = ServiceLocator.settings,
) : ViewModel() {

    private val filter = MutableStateFlow<Long?>(null)

    val ui: StateFlow<DashboardUi> = combine(
        repository.observeSources(),
        repository.observeSessions(),
        filter,
        settings.includeUsedWhileCharging.flow,
        combine(settings.lossFactor.flow, settings.designCapacityMah.flow) { l, d -> l to d },
    ) { sources, sessions, selected, includeUsed, (loss, design) ->
        build(sources, sessions, selected, includeUsed, loss, design)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi())

    fun setFilter(sourceId: Long?) {
        filter.value = sourceId
    }

    fun setIncludeUsed(include: Boolean) = settings.includeUsedWhileCharging.set(include)

    private fun build(
        sources: List<PowerSource>,
        sessions: List<ChargeSession>,
        selected: Long?,
        includeUsed: Boolean,
        loss: Float,
        design: Int,
    ): DashboardUi {
        val closed = sessions.filter { !it.isOpen && !it.mock }
        val bySource = closed.groupBy { it.sourceId }
        // A selected source that has since been archived stays selectable until the filter changes.
        val filter = selected?.takeIf { id -> sources.any { it.id == id } }

        val shown = sources.filter { s ->
            if (filter != null) s.id == filter
            else bySource.containsKey(s.id) || (s.kind == SourceKind.POWER_BANK && !s.archived)
        }.sortedWith(compareBy<PowerSource> { it.kind != SourceKind.POWER_BANK }.thenBy { it.builtIn }.thenBy { it.name.lowercase() })

        val cards = shown.map { s ->
            val own = bySource[s.id].orEmpty()
            val admitted = own.filter { includeUsed || !it.usedWhileCharging }
            SourceCard(
                source = s,
                fullCharges = StatsCalculator.fullChargeEquivalents(own),
                whIn = own.sumOf { it.whIn },
                sessionCount = own.size,
                avgW = admitted.takeIf { it.isNotEmpty() }?.map { it.avgW }?.average(),
                capacity = if (s.kind == SourceKind.POWER_BANK) {
                    StatsCalculator.bankCapacityEstimate(own, s, loss.toDouble(), includeUsed)
                } else {
                    null
                },
                usedExcluded = if (includeUsed) 0 else own.count { it.usedWhileCharging },
            )
        }

        val chart = (if (filter == null) closed else bySource[filter].orEmpty()).sortedBy { it.startMs }

        return DashboardUi(
            sources = sources,
            filter = filter,
            cards = cards,
            chartSessions = chart,
            includeUsed = includeUsed,
            lossFactor = loss,
            health = StatsCalculator.batteryHealthEstimate(closed, design.takeIf { it > 0 }),
            loaded = true,
        )
    }
}
