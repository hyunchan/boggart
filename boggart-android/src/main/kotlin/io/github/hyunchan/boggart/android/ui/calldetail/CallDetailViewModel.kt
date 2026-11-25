package io.github.hyunchan.boggart.android.ui.calldetail
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.JsonPatchOp
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.common.BoggartStore
import io.github.hyunchan.boggart.android.ui.common.Draft
import io.github.hyunchan.boggart.android.ui.common.RuleDrafts
import io.github.hyunchan.boggart.android.ui.common.RuleSet
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
import kotlinx.serialization.json.JsonElement

/**
 * 호출 화면에서 만드는 패치 규칙은 결과가 검증에 실패하면 원본을 전달한다. 화면이 만든 패치는 늘 올바른 JSON이라
 * 기본 검사기로는 실패하지 않고, 실패한 바디를 그대로 보내는 설정은 규칙 편집 화면의 고급 설정으로 둔다.
 */
private const val IGNORE_INVALID = true

/**
 * 호출 화면의 조작. 빠른 편집이 주력이고, 직접 편집은 같은 목적(실제 응답의 일부를 바꿈)을 텍스트로 하는 방법이다.
 * 서버 대신 응답은 서버를 부르지 않는 두 가지(응답 돌려주기·네트워크 에러)를 하나로 묶는다.
 */
internal enum class Action(@param:StringRes val label: Int) {
    Values(R.string.boggart_action_values),
    Manual(R.string.boggart_action_manual),
    Respond(R.string.boggart_action_respond),
}

/** 서버 대신 응답의 두 갈래. */
internal enum class RespondKind(@param:StringRes val label: Int) {
    Response(R.string.boggart_respond_response),
    Error(R.string.boggart_respond_error),
}

/** 만들어질 규칙의 종류(`Override`와 일대일). [button]은 새 규칙, [switchTo]는 다른 종류의 기존 규칙을 바꿀 때의 버튼 문구다. */
internal enum class RuleKind(@param:StringRes val button: Int, @param:StringRes val switchTo: Int) {
    Values(R.string.boggart_create_values, R.string.boggart_switch_to_values),
    Manual(R.string.boggart_create_manual, R.string.boggart_switch_to_manual),
    Response(R.string.boggart_create_response, R.string.boggart_switch_to_response),
    Error(R.string.boggart_create_error, R.string.boggart_switch_to_error),
}

/** 직접 편집·서버 대신 응답 입력과, 모든 조작이 함께 쓰는 지연. */
internal data class EditorFields(
    val bodyText: String = "",
    val delay: String = "0",
    val status: String = "200",
    val headersText: String = "",
    val failKind: FailKind = FailKind.Timeout,
    val customMessage: String = "Simulated by Boggart",
    val respondKind: RespondKind = RespondKind.Response,
) {
    /** 규칙 내용이 바뀌었는지. 지연은 고쳐도 버릴지 묻지 않는다. */
    fun contentDiffers(other: EditorFields): Boolean =
        bodyText != other.bodyText ||
            status != other.status ||
            headersText != other.headersText ||
            failKind::class != other.failKind::class ||
            customMessage != other.customMessage
}

/** 호출 상세 화면이 그리는 모든 것. */
internal data class CallDetailState(
    val callId: Long,
    val exchange: Exchange?,
    /** 이 호출에 적용됐던 규칙의 이름. 규칙이 없어졌으면 id. */
    val appliedRuleName: String?,
    /** 지금 켜진 규칙 중 이 호출에 맞는 규칙 수. 2개 이상이면 첫 번째만 적용된다는 것을 알린다. */
    val matchingRuleCount: Int,
    val action: Action,
    /** 서버 응답 바디가 JSON이면 그 값. 빠른 편집 트리의 원본이다. */
    val serverJson: JsonElement?,
    val ops: List<JsonPatchOp>,
    val editor: EditorFields,
    /** 만들기 버튼이 기존 규칙을 고치는지. 이 화면에서 만든 규칙도 포함한다. */
    val updating: Boolean,
    /** 고칠 기존 규칙의 종류. 고칠 규칙이 없거나 순차 응답이면 null. */
    val targetKind: RuleKind?,
    /** 규칙으로 만들지 않은 수정이 있는지. 나가기 전에 묻는다. */
    val dirty: Boolean,
    /** 고칠 기존 규칙이 순차 응답인지. 만들면 단계 목록이 한 가지 조작으로 바뀐다. */
    val targetIsSequence: Boolean = false,
    /** 이 호출에 순차 응답 규칙이 적용됐으면 그 규칙의 단계 수. 단계 번호는 [Exchange.step]에 있다. */
    val appliedSequenceSize: Int? = null,
) {
    /**
     * 버튼 문구. 새 규칙이면 "… 규칙 만들기", 같은 종류의 규칙을 고치면 "규칙 고치기",
     * 다른 종류의 규칙을 고치면 그 규칙의 조작이 바뀐다는 것이 드러나게 "…로 바꾸기".
     */
    @get:StringRes
    val submitButton: Int get() = when {
        targetIsSequence -> kind.switchTo
        else -> when (targetKind) {
            null -> kind.button
            kind -> R.string.boggart_update_rule
            else -> kind.switchTo
        }
    }

    /** 지금 고른 조작으로 만들어질 규칙의 종류. */
    val kind: RuleKind get() = when (action) {
        Action.Values -> RuleKind.Values
        Action.Manual -> RuleKind.Manual
        Action.Respond -> if (editor.respondKind == RespondKind.Error) RuleKind.Error else RuleKind.Response
    }

    val showsValueTree: Boolean get() = exchange != null && action == Action.Values && serverJson != null

    companion object {
        /** [exchange]와 [rules]로 처음 상태를 만든다. 미리보기도 이 함수를 쓴다. */
        fun initial(callId: Long, exchange: Exchange?, rules: RuleSet): CallDetailState {
            val form = Form.initial(exchange, rules)
            return form.toState(callId, exchange, serverJsonOf(exchange), rules)
        }
    }
}

internal sealed interface CallDetailIntent {
    data class SelectAction(val action: Action) : CallDetailIntent
    data class SetOps(val ops: List<JsonPatchOp>) : CallDetailIntent
    data class Edit(val editor: EditorFields) : CallDetailIntent
    data object Submit : CallDetailIntent
}

/** 화면의 입력 상태. 호출·규칙 같은 바깥 상태와 합쳐 [CallDetailState]가 된다. */
private data class Form(
    val action: Action,
    val ops: List<JsonPatchOp>,
    val editor: EditorFields,
    /** 마지막으로 규칙을 만들거나 고친 시점의 입력. 수정 여부의 기준이다. */
    val savedOps: List<JsonPatchOp>,
    val savedEditor: EditorFields,
    /** 이 화면이 쓰는 규칙. 처음에는 호출에 적용됐던 규칙이고, 규칙을 만들면 그 규칙이 된다. */
    val targetRuleId: String?,
) {
    fun toState(callId: Long, exchange: Exchange?, serverJson: JsonElement?, rules: RuleSet): CallDetailState {
        val target = rules.find(targetRuleId)?.takeIf { rules.isEditable(targetRuleId) }?.second?.override
        val applied = rules.find(exchange?.appliedRuleId)?.second?.override
        return CallDetailState(
            callId = callId,
            exchange = exchange,
            appliedRuleName = exchange?.appliedRuleId?.let { id -> rules.find(id)?.second?.name ?: id },
            matchingRuleCount = exchange?.let { rules.matching(it.request).size } ?: 0,
            action = action,
            serverJson = serverJson,
            ops = ops,
            editor = editor,
            updating = rules.isEditable(targetRuleId),
            targetKind = target?.let { kindOf(it) },
            dirty = ops != savedOps || editor.contentDiffers(savedEditor),
            targetIsSequence = target is Override.Sequence,
            appliedSequenceSize = (applied as? Override.Sequence)?.steps?.size?.takeIf { exchange?.step != null },
        )
    }

    companion object {
        fun initial(exchange: Exchange?, rules: RuleSet): Form {
            val appliedId = exchange?.appliedRuleId
            // 이 호출에 걸렸던 규칙을 고칠 수 있으면 그 규칙을, 아니면(규칙을 만들기 전의 호출이거나 프리셋) 같은 조건으로
            // 이미 만든 임시·저장 규칙을 이어서 고친다. 같은 호출에 규칙이 겹겹이 생기지 않게 한다.
            val existing = appliedId?.takeIf(rules::isEditable)?.let { rules.find(it)?.second }
                ?: exchange?.let { rules.editableMatching(RuleDrafts.matcherFor(it.request)) }
            // 순차 응답이면 이 호출에 쓰인 단계에서 시작한다.
            val applied = (existing ?: rules.find(appliedId)?.second)?.let { rule ->
                val sequence = rule.override as? Override.Sequence ?: return@let rule
                sequence.steps.getOrNull(exchange?.step ?: -1)?.let { rule.copy(override = it) }
            }
            val ops = ValueEdits.initialOps(applied)
            val appliedKind = applied?.override?.let { kindOf(it) }
            val editor = EditorFields(
                bodyText = exchange?.let(RuleDrafts::initialEditorText).orEmpty(),
                status = (exchange?.deliveredResponse ?: exchange?.originalResponse)?.status?.toString() ?: "200",
                respondKind = if (appliedKind == RuleKind.Error) RespondKind.Error else RespondKind.Response,
            )
            return Form(initialAction(exchange, appliedKind), ops, editor, ops, editor, existing?.id ?: appliedId)
        }

        /** 이미 걸린 규칙이 있으면 그 규칙의 조작으로, 서버 응답이 JSON이면 빠른 편집으로, 아니면 서버 대신 응답으로 시작한다. */
        private fun initialAction(exchange: Exchange?, applied: RuleKind?): Action = when (applied) {
            RuleKind.Values -> Action.Values
            RuleKind.Manual -> Action.Manual
            RuleKind.Response, RuleKind.Error -> Action.Respond
            null -> if (serverJsonOf(exchange) != null) Action.Values else Action.Respond
        }
    }
}

/** 순차 응답은 한 가지 종류로 나타낼 수 없으므로 null. */
private fun kindOf(override: Override): RuleKind? = when (override) {
    is Override.JsonPatch -> RuleKind.Values
    is Override.Patch -> RuleKind.Manual
    is Override.Replace -> RuleKind.Response
    is Override.Fail -> RuleKind.Error
    is Override.Sequence -> null
}

private fun serverJsonOf(exchange: Exchange?): JsonElement? = exchange?.originalResponse?.body?.let(RuleDrafts::parseJson)

/**
 * 호출 상세의 상태와 동작. 화면은 [state]를 그리고 [onIntent]로 사용자 동작을 보낸다.
 * 규칙을 만들면 그 규칙을 기억해, 같은 화면에서 다시 만들기를 누르면 새 규칙 대신 그 규칙을 고친다.
 */
internal class CallDetailViewModel(
    private val callId: Long,
    private val store: BoggartStore,
) : ViewModel() {
    private val form: MutableStateFlow<Form>
    private var parsed: Pair<ByteArray?, JsonElement?>

    init {
        val exchange = store.exchanges.value.firstOrNull { it.id == callId }
        form = MutableStateFlow(Form.initial(exchange, store.currentRules()))
        parsed = exchange?.originalResponse?.body to serverJsonOf(exchange)
    }

    private val effectChannel = Channel<UiEffect>(Channel.BUFFERED)
    val effects: Flow<UiEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<CallDetailState> =
        combine(form, store.exchanges, store.rules) { f, exchanges, rules -> derive(f, exchanges, rules) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, derive(form.value, store.exchanges.value, store.currentRules()))

    private fun derive(form: Form, exchanges: List<Exchange>, rules: RuleSet): CallDetailState {
        val exchange = exchanges.firstOrNull { it.id == callId }
        return form.toState(callId, exchange, serverJson(exchange), rules)
    }

    /** 응답 바디가 같으면 다시 파싱하지 않는다. */
    private fun serverJson(exchange: Exchange?): JsonElement? {
        val body = exchange?.originalResponse?.body
        if (body !== parsed.first) parsed = body to serverJsonOf(exchange)
        return parsed.second
    }

    fun onIntent(intent: CallDetailIntent) {
        when (intent) {
            is CallDetailIntent.SelectAction -> form.update { it.copy(action = intent.action) }
            is CallDetailIntent.SetOps -> form.update { it.copy(ops = intent.ops) }
            is CallDetailIntent.Edit -> form.update { it.copy(editor = intent.editor) }
            CallDetailIntent.Submit -> submit()
        }
    }

    private fun submit() {
        val current = form.value
        val exchange = state.value.exchange ?: return
        val rules = store.currentRules()
        val target = rules.targetFor(current.targetRuleId)
        val updating = rules.isEditable(current.targetRuleId)
        val editor = current.editor
        val draft = when (state.value.kind) {
            RuleKind.Values -> ValueEdits.jsonPatchRule(exchange, current.ops, IGNORE_INVALID, editor.delay.toLongOrNull() ?: 0, target)
            RuleKind.Manual -> RuleDrafts.patchRule(exchange, editor.bodyText, IGNORE_INVALID, editor.delay.toLongOrNull() ?: 0, target)
            RuleKind.Response -> Draft.Ok(
                RuleDrafts.replaceRule(
                    exchange,
                    editor.status.toIntOrNull() ?: 200,
                    editor.headersText,
                    editor.bodyText,
                    editor.delay.toLongOrNull() ?: 0,
                    target,
                ),
            )
            RuleKind.Error -> {
                val kind = (editor.failKind as? FailKind.Custom)?.let { FailKind.Custom(editor.customMessage) } ?: editor.failKind
                Draft.Ok(RuleDrafts.failRule(exchange, kind, editor.delay.toLongOrNull() ?: 0, target))
            }
        }
        val rule = when (draft) {
            is Draft.Ok -> draft.rule
            is Draft.Error -> {
                effectChannel.trySend(UiEffect.Message(UiMessage(draft.reason.messageRes())))
                return
            }
        }
        viewModelScope.launch {
            runCatching { store.upsert(target, rule) }
                .onSuccess {
                    // 만든 시점의 입력이 새 기준이 된다(닫히기 전 뒤로 가기 확인이 뜨지 않게).
                    form.update { it.copy(savedOps = current.ops, savedEditor = current.editor, targetRuleId = rule.id) }
                    // 규칙은 다음 요청부터 적용되어 결과는 앱에서 다시 불러와야 보인다. 이 화면의 응답은 만들기 전 것이라 머물지 않고 앱으로 돌아간다.
                    val done = if (updating) R.string.boggart_rule_updated else R.string.boggart_rule_applied
                    // 토스트는 두 줄까지만 보여 규칙 이름은 넣지 않는다.
                    effectChannel.send(UiEffect.ReturnToApp(UiMessage(done)))
                }
                .onFailure { effectChannel.send(UiEffect.Message(UiMessage(R.string.boggart_apply_failed, listOf(it.message.orEmpty())))) }
        }
    }
}
