package io.github.yuriimurha.reels.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuriimurha.reels.data.db.CollectionCard
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.session.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Home: the collection cards and the sync button's badge. [latestRun] is the Sync controller's latest run. [session] is the
 * real library's session state, which can put a dot on the button ([SyncBadge.from]); null for Mock mode's fake library, which
 * needs no session.
 */
class HomeViewModel(
    cards: Flow<List<CollectionCard>>,
    latestRun: Flow<SyncRunEntity?>,
    session: Flow<SessionState>?,
) : ViewModel() {
    private val sharing = SharingStarted.WhileSubscribed(5_000)

    /** Null until the first emission, so the screen doesn't flash the empty state. */
    val cards: StateFlow<List<CollectionCard>?> = cards.stateIn(viewModelScope, sharing, null)

    /** The run shows at once: a session still being read counts as not read (null), not as a reason to wait. */
    private val sessionOrNull: Flow<SessionState?> = session?.onStart<SessionState?> { emit(null) } ?: flowOf(null)

    val badge: StateFlow<SyncBadge> =
        combine(latestRun, sessionOrNull) { run, state -> SyncBadge.from(run, state, requiresSession = session != null) }
            .stateIn(viewModelScope, sharing, SyncBadge.IDLE)
}
