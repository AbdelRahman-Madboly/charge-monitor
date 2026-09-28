package com.vinex.chargemonitor.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Repository
import com.vinex.chargemonitor.data.Sample
import com.vinex.chargemonitor.ui.SessionActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionDetailUi(
    val session: ChargeSession? = null,
    val source: PowerSource? = null,
    val samples: List<Sample> = emptyList(),
    val sources: List<PowerSource> = emptyList(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModel(
    private val sessionId: Long,
    private val repository: Repository = ServiceLocator.repository,
) : ViewModel() {

    private val session = repository.observeSession(sessionId)
    private val source = session.flatMapLatest { s -> s?.let { repository.observeSource(it.sourceId) } ?: flowOf(null) }

    val ui: StateFlow<SessionDetailUi> = combine(
        session,
        source,
        repository.observeSamples(sessionId),
        repository.observeSources(),
    ) { s, src, samples, sources ->
        SessionDetailUi(s, src, samples, sources, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionDetailUi())

    fun changeSource(context: Context, source: PowerSource) {
        val app = context.applicationContext
        viewModelScope.launch { SessionActions.reassign(app, sessionId, source) }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            onDone()
        }
    }
}
