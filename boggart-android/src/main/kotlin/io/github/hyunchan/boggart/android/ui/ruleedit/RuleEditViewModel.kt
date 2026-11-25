package io.github.hyunchan.boggart.android.ui.ruleedit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.OnInvalidPatch
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.SequenceEnd
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.common.BoggartStore
import io.github.hyunchan.boggart.android.ui.common.Draft
import io.github.hyunchan.boggart.android.ui.common.RuleDrafts
import io.github.hyunchan.boggart.android.ui.common.RuleOrigin
import io.github.hyunchan.boggart.android.ui.common.RuleSet
import io.github.hyunchan.boggart.android.ui.common.RuleTarget
import io.github.hyunchan.boggart.android.ui.common.UiEffect
import io.github.hyunchan.boggart.android.ui.common.UiMessage
import io.github.hyunchan.boggart.android.ui.common.ValueEdits
import io.github.hyunchan.boggart.android.ui.common.messageRes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 규칙 편집 입력. [body]는 바디 파일을 읽기 전이면 null이고, [shownBody]는 처음 보여 준 바디다(바뀌었는지 비교용). */
internal data class RuleForm(
    val method: String,
    val host: String,
    val path: String,
    val matchHeaders: String,
    val delay: String,
    val status: String,
    val responseHeaders: String,
    val body: String?,
    val shownBody: String?,
    val patchText: String,
    val opsText: String,
    val ignoreInvalid: Boolean,
    val failKind: FailKind,
    val customMessage: String,
    val then: SequenceEnd = SequenceEnd.Last,
    /** 순차 응답의 단계, 또는 확률 조작 규칙의 입력 하나. */
    val steps: List<StepForm> = emptyList(),
    /** 단계 목록으로 편집하는지. 순차 응답 규칙이거나 "순차 응답으로 바꾸기"를 눌렀으면 true. */
    val sequence: Boolean = false,
    /** 펼친 단계. 한 번에 하나만 펼친다. */
    val expanded: Int? = null,
) {
    companion object {
        fun of(rule: Rule): RuleForm {
            val override = rule.override
            val onInvalid = (override as? Override.Patch)?.onInvalid ?: (override as? Override.JsonPatch)?.onInvalid
            return RuleForm(
                method = rule.matcher.method.orEmpty(),
                host = rule.matcher.host.orEmpty(),
                path = rule.matcher.path.orEmpty(),
                matchHeaders = RuleDrafts.formatHeaders(rule.matcher.headers),
                delay = override.delayMs.toString(),
                status = (override as? Override.Replace)?.status?.toString() ?: "200",
                responseHeaders = RuleDrafts.formatHeaders((override as? Override.Replace)?.headers.orEmpty()),
                body = (override as? Override.Replace)?.body,
                shownBody = (override as? Override.Replace)?.body,
                patchText = (override as? Override.Patch)?.let { RuleDrafts.prettyPatch(it.patch) }.orEmpty(),
                opsText = (override as? Override.JsonPatch)?.let { ValueEdits.formatOps(it.ops) }.orEmpty(),
                ignoreInvalid = onInvalid != OnInvalidPatch.PassThrough,
                failKind = (override as? Override.Fail)?.kind ?: FailKind.Timeout,
                customMessage = ((override as? Override.Fail)?.kind as? FailKind.Custom)?.message ?: "Simulated by Boggart",
                then = (override as? Override.Sequence)?.then ?: SequenceEnd.Last,
                steps = when (override) {
                    is Override.Sequence -> override.steps.map(StepForm::of)
                    is Override.Chance -> listOf(StepForm.of(override))
                    else -> emptyList()
                },
                sequence = override is Override.Sequence,
            )
        }
    }
}

/**
 * 규칙 편집 화면이 그리는 것. [found]가 null이면 해제된(또는 아직 읽지 않은) 규칙이다.
 * [form]은 규칙을 처음 찾았을 때 한 번 만들고, 이후 규칙이 바뀌어도(저장으로 출처가 바뀌는 등) 입력을 덮어쓰지 않는다.
 *
 * @property nextStep 순차 응답 규칙이면 다음에 보낼 단계(0부터). 아니면 null.
 */
internal data class RuleEditState(
    val found: Pair<RuleOrigin, Rule>?,
    val form: RuleForm?,
    val canSave: Boolean,
    val nextStep: Int? = null,
) {
    companion object {
        fun of(rules: RuleSet, ruleId: String, form: RuleForm?, progress: Map<String, Int> = emptyMap()): RuleEditState {
            val found = rules.find(ruleId)
            val nextStep = if (found?.second?.override is Override.Sequence) progress[ruleId] ?: 0 else null
            return RuleEditState(found, form ?: found?.second?.let(RuleForm::of), canSave = rules.saved != null, nextStep)
        }
    }
}

internal sealed interface RuleEditIntent {
    data class Edit(val form: RuleForm) : RuleEditIntent

    /** 임시 규칙에 바로 적용한다. */
    data object Apply : RuleEditIntent

    /** 임시 규칙이면 저장 규칙으로 옮기고, 저장 규칙이면 고쳐 저장한다. */
    data object Save : RuleEditIntent
    data object Remove : RuleEditIntent

    /** 순차 응답을 처음부터 다시 시작한다. */
    data object RestartSequence : RuleEditIntent

    /** 지금 조작을 첫 단계로 하는 순차 응답으로 바꾼다. 적용·저장을 눌러야 반영된다. */
    data object ConvertToSequence : RuleEditIntent
    data class EditStep(val index: Int, val step: StepForm) : RuleEditIntent
    data class ToggleStep(val index: Int) : RuleEditIntent
    data class MoveStep(val index: Int, val delta: Int) : RuleEditIntent
    data class DuplicateStep(val index: Int) : RuleEditIntent
    data class RemoveStep(val index: Int) : RuleEditIntent
    data class AddStep(val kind: StepKind) : RuleEditIntent
    data class SetPresetEnabled(val enabled: Boolean) : RuleEditIntent
}

internal class RuleEditViewModel(private val ruleId: String, private val store: BoggartStore) : ViewModel() {
    private val form = MutableStateFlow<RuleForm?>(null)

    // 지우는 중에는 마지막 모습을 그대로 둔다. 그러지 않으면 화면이 닫히는 동안 "해제된 규칙"으로 다시 그려진다.
    private val closing = MutableStateFlow<RuleEditState?>(null)
    private val effectChannel = Channel<UiEffect>(Channel.BUFFERED)
    val effects: Flow<UiEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<RuleEditState> =
        combine(store.rules, form, closing, store.sequenceProgress) { rules, f, last, progress -> last ?: RuleEditState.of(rules, ruleId, f, progress) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RuleEditState.of(store.currentRules(), ruleId, null, store.currentSequenceProgress()))

    init {
        // 저장 규칙은 설치 뒤 비동기로 읽히므로 규칙이 나타나는 시점에 입력을 만든다.
        viewModelScope.launch {
            state.collect { current ->
                val rule = current.found?.second ?: return@collect
                if (form.value == null) {
                    form.value = current.form
                    loadBody(rule)
                }
            }
        }
    }

    /** 바디 파일을 참조하는 교체 규칙은 파일 내용을 읽어 편집 칸에 채운다. */
    private fun loadBody(rule: Rule) {
        if (rule.override !is Override.Replace || form.value?.body != null) return
        viewModelScope.launch {
            val text = store.bodyText(rule)
            form.update { it?.copy(body = it.body ?: text, shownBody = text) }
        }
    }

    fun onIntent(intent: RuleEditIntent) {
        val (origin, rule) = state.value.found ?: return
        when (intent) {
            is RuleEditIntent.Edit -> form.value = intent.form
            RuleEditIntent.Apply -> commit(R.string.boggart_applied_done) { store.upsert(RuleTarget.Temporary(it.id), it) }
            RuleEditIntent.Save -> commit(R.string.boggart_saved_done) {
                if (origin == RuleOrigin.Temporary) store.promote(it) else store.upsert(RuleTarget.Saved(it.id), it)
            }
            RuleEditIntent.Remove -> viewModelScope.launch {
                closing.value = state.value
                store.remove(origin, rule.id)
                // 목록에서 지울 때와 같은 문구로 알린다.
                val done = if (origin == RuleOrigin.Temporary) R.string.boggart_riddikulus_done else R.string.boggart_removed_done
                effectChannel.send(UiEffect.CloseWith(UiMessage(done)))
            }
            RuleEditIntent.RestartSequence -> viewModelScope.launch {
                store.resetSequence(rule.id)
                effectChannel.send(UiEffect.Message(UiMessage(R.string.boggart_sequence_restarted)))
            }
            is RuleEditIntent.SetPresetEnabled -> viewModelScope.launch { store.setEnabled(origin, rule, intent.enabled) }
            RuleEditIntent.ConvertToSequence -> convertToSequence()
            is RuleEditIntent.EditStep -> editSteps { it.toMutableList().apply { set(intent.index, intent.step) } }
            is RuleEditIntent.ToggleStep -> form.update { f -> f?.copy(expanded = intent.index.takeIf { it != f.expanded }) }
            is RuleEditIntent.MoveStep -> form.update { f ->
                f?.copy(steps = StepEdits.move(f.steps, intent.index, intent.delta), expanded = (intent.index + intent.delta).takeIf { f.expanded == intent.index } ?: f.expanded)
            }
            is RuleEditIntent.DuplicateStep -> form.update { f -> f?.copy(steps = StepEdits.duplicate(f.steps, intent.index), expanded = intent.index + 1) }
            is RuleEditIntent.RemoveStep -> form.update { f -> f?.copy(steps = StepEdits.remove(f.steps, intent.index), expanded = null) }
            is RuleEditIntent.AddStep -> form.update { f -> f?.copy(steps = f.steps + StepForm.new(intent.kind), expanded = f.steps.size) }
        }
    }

    private fun editSteps(transform: (List<StepForm>) -> List<StepForm>) {
        form.update { it?.copy(steps = transform(it.steps)) }
    }

    private fun convertToSequence() {
        when (val draft = edited() ?: return) {
            is Draft.Error -> effectChannel.trySend(UiEffect.Message(UiMessage(draft.reason.messageRes())))
            is Draft.Ok -> form.update { it?.copy(sequence = true, steps = listOf(StepForm.of(draft.rule.override)), expanded = 0) }
        }
    }

    private fun commit(done: Int, action: suspend (Rule) -> Unit) {
        val draft = edited() ?: return
        viewModelScope.launch {
            when (draft) {
                is Draft.Error -> effectChannel.send(UiEffect.Message(UiMessage(draft.reason.messageRes())))
                is Draft.Ok -> runCatching { action(draft.rule) }
                    .onSuccess { effectChannel.send(UiEffect.Message(UiMessage(done))) }
                    .onFailure { effectChannel.send(UiEffect.Message(UiMessage(R.string.boggart_failed, listOf(it.message.orEmpty())))) }
            }
        }
    }

    private fun edited(): Draft? {
        val rule = state.value.found?.second ?: return null
        val f = form.value ?: return null
        val delayMs = f.delay.toLongOrNull() ?: 0
        val base = RuleDrafts.editMatcher(rule, f.method, f.host, f.path, f.matchHeaders)
        if (f.sequence) {
            val (steps, error) = StepEdits.buildAll(f.steps)
            if (steps == null) {
                // 잘못된 단계를 펼쳐 어디를 고칠지 보이게 한다.
                val (index, reason) = checkNotNull(error)
                form.update { it?.copy(expanded = index) }
                return Draft.Error(reason)
            }
            return Draft.Ok(base.copy(override = Override.Sequence(steps, f.then)))
        }
        return when (val override = rule.override) {
            is Override.Replace -> Draft.Ok(
                RuleDrafts.editReplace(base, f.status.toIntOrNull() ?: 200, f.responseHeaders, f.body.orEmpty(), f.shownBody.orEmpty(), delayMs),
            )
            is Override.Patch -> RuleDrafts.editPatch(base, f.patchText, f.ignoreInvalid, delayMs)
            is Override.JsonPatch -> RuleDrafts.editJsonPatch(base, f.opsText, f.ignoreInvalid, delayMs)
            is Override.Fail -> {
                val kind = if (f.failKind is FailKind.Custom) FailKind.Custom(f.customMessage) else f.failKind
                Draft.Ok(RuleDrafts.editFail(base, kind, delayMs))
            }
            // 단계는 화면에서 고치지 않는다(rules.json·원격 제어). 조건과 마지막 뒤 동작만 바꾼다.
            is Override.Sequence -> Draft.Ok(base.copy(override = override.copy(then = f.then)))
            // 확률 조작 규칙은 입력 하나로 고친다. 확률을 끄면 안쪽 조작만 남는다.
            is Override.Chance -> when (val built = f.steps.single().copy(times = "1").build()) {
                is StepForm.Built.Ok -> Draft.Ok(base.copy(override = built.step.override))
                is StepForm.Built.Error -> Draft.Error(built.reason)
            }
        }
    }
}
