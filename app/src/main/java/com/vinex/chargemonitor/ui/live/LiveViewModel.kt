package com.vinex.chargemonitor.ui.live

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.LiveState
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Repository
import com.vinex.chargemonitor.data.SettingsStore
import com.vinex.chargemonitor.stats.StatsCalculator
import com.vinex.chargemonitor.ui.SessionActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveUi(
    val live: LiveState = LiveState(),
    val points: List<LivePoint> = emptyList(),
    val fastCharging: Boolean = false,
    val timeToFullMs: Long? = null,
    val timeToTargetMs: Long? = null,
    val targetLevel: Int = 80,
    val lastSession: ChargeSession? = null,
    val lastSource: PowerSource? = null,
    val sources: List<PowerSource> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModel(
    private val repository: Repository = ServiceLocator.repository,
    private val settings: SettingsStore = ServiceLocator.settings,
) : ViewModel() {

    /** Live ticks of the current session since this view model was created. */
    private val buffer = MutableStateFlow<List<LivePoint>>(emptyList())
    private var bufferedSession: Long? = null

    init {
        viewModelScope.launch {
            LiveBus.state.collect { s ->
                val snap = s.snapshot
                if (s.sessionId != bufferedSession) {
                    bufferedSession = s.sessionId
                    buffer.value = emptyList()
                }
                if (s.sessionId == null || snap == null) return@collect
                buffer.update { list ->
                    if (list.lastOrNull()?.tMs?.let { it >= snap.tMs } == true) list
                    // Bounded: 4 h at the 2 s live cadence.
                    else (list + LiveSeries.point(snap)).takeLast(MAX_BUFFER)
                }
            }
        }
    }

    private val persisted = LiveBus.state
        .map { it.sessionId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeSamples(id) }

    private val lastClosed = repository.observeLastClosedSession()
        .flatMapLatest { session ->
            if (session == null) flowOf(null to null)
            else repository.observeSource(session.sourceId).map { session to it }
        }

    private val points = combine(persisted, buffer) { p, b -> LiveSeries.merge(p, b) }

    val ui: StateFlow<LiveUi> = combine(
        LiveBus.state,
        points,
        lastClosed,
        repository.observeSources(),
        combine(settings.targetLevel.flow, settings.designCapacityMah.flow) { t, d -> t to d },
    ) { live, pts, (lastSession, lastSource), sources, (target, design) ->
        val snap = live.snapshot
        val currents = LiveSeries.recentCurrentsMa(pts)
        val capacity = design.takeIf { it > 0 }
        val charging = live.isCharging && snap != null
        LiveUi(
            live = live,
            points = pts,
            fastCharging = charging && LiveSeries.isFastCharging(pts),
            timeToFullMs = if (charging) StatsCalculator.timeToLevel(snap!!, 100f, currents, capacity) else null,
            timeToTargetMs = if (charging && target < 100) {
                StatsCalculator.timeToLevel(snap!!, target.toFloat(), currents, capacity)
            } else {
                null
            },
            targetLevel = target,
            lastSession = lastSession,
            lastSource = lastSource,
            sources = sources,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUi())

    fun changeSource(context: Context, source: PowerSource) {
        val id = ui.value.live.sessionId ?: return
        val app = context.applicationContext
        viewModelScope.launch { SessionActions.reassign(app, id, source) }
    }

    private companion object {
        const val MAX_BUFFER = 7_200
    }
}
