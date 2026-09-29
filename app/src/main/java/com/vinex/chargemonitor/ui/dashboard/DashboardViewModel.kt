package com.vinex.chargemonitor.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.data.BankEvent
import com.vinex.chargemonitor.data.BankEventKind
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
import kotlinx.coroutines.launch

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
    /** Bank sessions whose source was guessed (prompt unanswered); not in any figure above. */
    val unconfirmedCount: Int = 0,
    val unconfirmedFullCharges: Double = 0.0,
    /** The bank's most recent refill mark, for the "last marked" line and undo. */
    val lastMark: BankEvent? = null,
    /** Full phone charges the label promises per full bank; null without rating or phone size. */
    val labelPhoneCharges: Double? = null,
    /** Only for wall chargers. */
    val charger: StatsCalculator.ChargerStats? = null,
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
    private val repository: Repository = ServiceLocator.repository,
    private val settings: SettingsStore = ServiceLocator.settings,
) : ViewModel() {

    private val filter = MutableStateFlow<Long?>(null)

    val ui: StateFlow<DashboardUi> = combine(
        repository.observeSources(),
        repository.observeSessions(),
        filter,
        settings.includeUsedWhileCharging.flow,
        combine(settings.lossFactor.flow, settings.designCapacityMah.flow, repository.observeBankEvents()) { l, d, e ->
            Triple(l, d, e)
        },
    ) { sources, sessions, selected, includeUsed, (loss, design, events) ->
        build(sources, sessions, selected, includeUsed, loss, design, events)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi())

    fun setFilter(sourceId: Long?) {
        filter.value = sourceId
    }

    fun setIncludeUsed(include: Boolean) = settings.includeUsedWhileCharging.set(include)

    fun markBank(sourceId: Long, kind: BankEventKind) {
        viewModelScope.launch {
            repository.addBankEvent(BankEvent(sourceId = sourceId, tMs = System.currentTimeMillis(), kind = kind))
        }
    }

    fun undoMark(eventId: Long) {
        viewModelScope.launch { repository.deleteBankEvent(eventId) }
    }

    private fun build(
        sources: List<PowerSource>,
        sessions: List<ChargeSession>,
        selected: Long?,
        includeUsed: Boolean,
        loss: Float,
        design: Int,
        events: List<BankEvent>,
    ): DashboardUi {
        val closed = sessions.filter { !it.isOpen && !it.mock }
        val bySource = closed.groupBy { it.sourceId }
        // A selected source that has since been archived stays selectable until the filter changes.
        val filter = selected?.takeIf { id -> sources.any { it.id == id } }

        val shown = sources.filter { s ->
            if (filter != null) s.id == filter
            else bySource.containsKey(s.id) || (!s.builtIn && !s.archived)
        }.sortedWith(compareBy<PowerSource> { it.kind.ordinal }.thenBy { it.builtIn }.thenBy { it.name.lowercase() })

        val cards = shown.map { s ->
            val isBank = s.kind == SourceKind.POWER_BANK
            val all = bySource[s.id].orEmpty()
            // A guessed source must not feed a bank; built-ins keep every session.
            val own = if (isBank) all.filter { it.sourceConfirmed } else all
            val unconfirmed = if (isBank) all.filterNot { it.sourceConfirmed } else emptyList()
            val admitted = own.filter { includeUsed || !it.usedWhileCharging }
            SourceCard(
                source = s,
                fullCharges = StatsCalculator.fullChargeEquivalents(own),
                whIn = own.sumOf { it.whIn },
                sessionCount = own.size,
                avgW = admitted.takeIf { it.isNotEmpty() }?.map { it.avgW }?.average(),
                capacity = if (isBank) {
                    StatsCalculator.bankCapacityEstimate(all, s, loss.toDouble(), includeUsed, events)
                } else {
                    null
                },
                usedExcluded = if (includeUsed) 0 else own.count { it.usedWhileCharging },
                unconfirmedCount = unconfirmed.size,
                unconfirmedFullCharges = StatsCalculator.fullChargeEquivalents(unconfirmed),
                lastMark = if (isBank) events.lastOrNull { it.sourceId == s.id } else null,
                charger = if (s.kind == SourceKind.CHARGER) {
                    StatsCalculator.chargerStats(own, s.ratedW, includeUsed)
                } else {
                    null
                },
                labelPhoneCharges = if (isBank) {
                    StatsCalculator.labelPhoneCharges(s, design.takeIf { it > 0 }, loss.toDouble())
                } else {
                    null
                },
            )
        }

        val kindOf = sources.associate { it.id to it.kind }
        val chart = closed
            .filter { filter == null || it.sourceId == filter }
            .filter { it.sourceConfirmed || kindOf[it.sourceId] != SourceKind.POWER_BANK }
            .sortedBy { it.startMs }

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
