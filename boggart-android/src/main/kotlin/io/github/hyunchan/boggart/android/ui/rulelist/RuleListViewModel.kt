package io.github.hyunchan.boggart.android.ui.rulelist
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.common.BoggartStore
import io.github.hyunchan.boggart.android.ui.common.RuleOrigin
import io.github.hyunchan.boggart.android.ui.common.RuleSet
import io.github.hyunchan.boggart.android.ui.common.UiEffect
import io.github.hyunchan.boggart.android.ui.common.UiMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * @property shadowedBy 다른 규칙에 가려 적용될 일이 없는 규칙의 id → 먼저 적용되는 규칙의 이름.
 * @property sequenceProgress 순차 응답 규칙 id → 다음 단계(0부터).
 */
internal data class RuleListState(
    val rules: RuleSet,
    val mode: Mode,
    val shadowedBy: Map<String, String> = emptyMap(),
    val sequenceProgress: Map<String, Int> = emptyMap(),
) {
    companion object {
        fun of(rules: RuleSet, mode: Mode, progress: Map<String, Int>): RuleListState =
            RuleListState(rules, mode, rules.shadowing().mapValues { it.value.name }, progress)
    }
}

internal sealed interface RuleListIntent {
    data class Toggle(val origin: RuleOrigin, val rule: Rule, val enabled: Boolean) : RuleListIntent
    data class Save(val rule: Rule) : RuleListIntent
    data class Remove(val origin: RuleOrigin, val rule: Rule) : RuleListIntent
    data object ClearTemporary : RuleListIntent
}

/** 규칙 목록의 상태와 동작. 쓰기는 [BoggartStore]가 화면보다 오래 사는 스코프에서 끝낸다. */
internal class RuleListViewModel(private val store: BoggartStore) : ViewModel() {
    private val effectChannel = Channel<UiEffect>(Channel.BUFFERED)
    val effects: Flow<UiEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<RuleListState> = combine(store.rules, store.mode, store.sequenceProgress, RuleListState::of)
        .stateIn(viewModelScope, SharingStarted.Eagerly, RuleListState.of(store.currentRules(), store.mode.value, store.currentSequenceProgress()))

    fun onIntent(intent: RuleListIntent) {
        when (intent) {
            is RuleListIntent.Toggle -> viewModelScope.launch { store.setEnabled(intent.origin, intent.rule, intent.enabled) }
            is RuleListIntent.Save -> viewModelScope.launch {
                runCatching { store.promote(intent.rule) }
                    .onFailure { message(R.string.boggart_save_failed, it.message.orEmpty()) }
            }
            is RuleListIntent.Remove -> viewModelScope.launch {
                store.remove(intent.origin, intent.rule.id)
                when (intent.origin) {
                    RuleOrigin.Temporary -> message(R.string.boggart_riddikulus_done)
                    RuleOrigin.Saved -> message(R.string.boggart_removed_done)
                    RuleOrigin.Preset -> Unit
                }
            }
            RuleListIntent.ClearTemporary -> {
                store.clearTemporary()
                viewModelScope.launch { message(R.string.boggart_riddikulus_done) }
            }
        }
    }

    private suspend fun message(res: Int, vararg args: Any) {
        effectChannel.send(UiEffect.Message(UiMessage(res, args.toList())))
    }
}
