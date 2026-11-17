package io.github.hyunchan.boggart.android.ui.calllist
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.android.ui.common.BoggartStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal data class CallListState(val exchanges: List<Exchange>, val mode: Mode, val activeRules: Int)

internal sealed interface CallListIntent {
    data class SetMode(val mode: Mode) : CallListIntent
    data object Clear : CallListIntent
}

/** 호출 목록의 상태와 동작. */
internal class CallListViewModel(private val store: BoggartStore) : ViewModel() {
    val state: StateFlow<CallListState> = combine(store.exchanges, store.mode, store.activeRuleCount, ::CallListState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CallListState(store.exchanges.value, store.mode.value, 0))

    fun onIntent(intent: CallListIntent) {
        when (intent) {
            is CallListIntent.SetMode -> store.setMode(intent.mode)
            CallListIntent.Clear -> store.clearExchanges()
        }
    }
}
