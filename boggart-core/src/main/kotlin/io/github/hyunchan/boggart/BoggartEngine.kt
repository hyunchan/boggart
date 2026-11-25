package io.github.hyunchan.boggart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicLong

/**
 * [BoggartEngine.beforeRequest]의 결정. 지연은 엔진이 아니라 어댑터가 자기 스레딩 모델에 맞게 수행한다.
 */
public sealed interface Decision {
    public val delayMs: Long

    /** 조작·캡처를 모두 건너뛴다([Mode.Off]). [BoggartEngine.afterResponse]는 응답을 그대로 돌려준다. */
    public data object Bypass : Decision {
        override val delayMs: Long get() = 0
    }

    /**
     * 실제 호출을 진행한 뒤 [BoggartEngine.afterResponse]를 호출한다.
     *
     * @property rule 실제 응답에 적용할 [Override.Patch]·[Override.JsonPatch] 규칙, 또는 bodyFile을 해석하지 못한 [Override.Replace] 규칙.
     *   없으면 null.
     * @property override 실제 응답에 적용할 조작. 보통 [rule]의 조작이고, [Override.Sequence]면 이번에 고른 단계다.
     * @property step [Override.Sequence]에서 고른 단계(0부터). 순차 응답이 아니면 null.
     */
    public data class Proceed(
        val rule: Rule? = null,
        override val delayMs: Long = 0,
        val override: Override? = rule?.override,
        val step: Int? = null,
    ) : Decision

    /** 실제 호출 없이 [response]를 전달한다. 캡처는 이미 끝났으므로 afterResponse를 부르지 않는다. */
    public data class Respond(
        val response: BoggartResponse,
        val provenance: Provenance,
        val rule: Rule?,
        override val delayMs: Long = 0,
    ) : Decision

    /** 실제 호출 없이 [kind] 에러를 유발한다. 캡처는 이미 끝났다. */
    public data class Fail(
        val kind: FailKind,
        val rule: Rule?,
        override val delayMs: Long = 0,
    ) : Decision
}

/** [Override.Sequence] 규칙의 진행 위치. [rule]과 같은 인스턴스일 때만 유효하다. */
public data class SequenceCursor(val rule: Rule, val next: Int)

/**
 * 규칙 매칭, 응답 조작, 패치 검증, 교환 캡처를 수행하는 엔진. 네트워크 라이브러리를 모른다.
 *
 * 어댑터 흐름: [beforeRequest] → (Proceed/Bypass면) 실제 호출 → 성공하면 [afterResponse], 예외면 [onFailure].
 */
public class BoggartEngine(
    public val repository: RuleRepository,
    public val exchangeLog: ExchangeLog = ExchangeLog(),
    mode: Mode = Mode.Active,
    @Volatile public var validator: BodyValidator = JsonSyntaxValidator,
    @Volatile public var onInvalidPatch: OnInvalidPatch = OnInvalidPatch.Ignore,
    @Volatile public var bodyFileResolver: BodyFileResolver? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * 요청이 규칙 로딩을 기다렸을 때 불린다(기다린 ms, 시간 안에 준비되었는지). 이미 준비된 뒤의 요청에는 불리지 않는다.
     * 안드로이드 설치가 로그로 남긴다.
     */
    @Volatile public var onRulesWait: ((waitedMs: Long, ready: Boolean) -> Unit)? = null

    private val modeState = MutableStateFlow(mode)
    private val nextId = AtomicLong(1)
    private val cursorLock = Any()
    private val cursors = MutableStateFlow<Map<String, SequenceCursor>>(emptyMap())

    /**
     * [Override.Sequence] 규칙의 진행 위치. 규칙 id로 찾되, 규칙이 바뀌었으면(다른 인스턴스) 무시해야 하므로 [nextStep]으로 읽는다.
     * 화면이 진행 위치 변화를 구독할 때 쓴다.
     */
    public val sequenceCursors: StateFlow<Map<String, SequenceCursor>> = cursors.asStateFlow()

    public val modeFlow: StateFlow<Mode> = modeState.asStateFlow()

    public var mode: Mode
        get() = modeState.value
        set(value) {
            modeState.value = value
        }

    public suspend fun beforeRequest(request: BoggartRequest): Decision {
        when (mode) {
            Mode.Off -> return Decision.Bypass
            Mode.Paused -> return Decision.Proceed()
            Mode.Active, Mode.Offline -> Unit
        }
        awaitRules()
        val rule = repository.find(request)
        if (rule == null) {
            if (mode != Mode.Offline) return Decision.Proceed()
            // 네트워크 없음으로 실패시켜 앱의 오프라인 처리(UnknownHostException 등)가 동작하게 한다.
            record(request, null, null, Provenance.Failed, null, OFFLINE_ERROR)
            return Decision.Fail(FailKind.UnknownHost, null)
        }
        val sequence = rule.override as? Override.Sequence
        val step = sequence?.let { advance(rule, it) }
        val override = when {
            sequence == null -> rule.override
            step == null -> {
                // 단계를 다 쓰고 then = original. 규칙이 없는 호출처럼 다룬다.
                if (mode != Mode.Offline) return Decision.Proceed()
                record(request, null, null, Provenance.Failed, null, OFFLINE_ERROR)
                return Decision.Fail(FailKind.UnknownHost, null)
            }
            else -> sequence.steps[step]
        }
        return when (override) {
            is Override.Replace -> {
                val response = buildReplacement(override) ?: return Decision.Proceed(rule, override.delayMs, override, step)
                record(request, null, response, Provenance.Replaced, rule.id, step = step)
                Decision.Respond(response, Provenance.Replaced, rule, override.delayMs)
            }
            is Override.Patch, is Override.JsonPatch -> Decision.Proceed(rule, override.delayMs, override, step)
            is Override.Fail -> {
                record(request, null, null, Provenance.Failed, rule.id, step = step)
                Decision.Fail(override.kind, rule, override.delayMs)
            }
            is Override.Sequence -> error("A sequence step can't be another sequence")
        }
    }

    /**
     * [rule]의 다음 단계 번호. 아직 매칭된 적이 없거나 규칙이 바뀌었으면 0, 단계를 다 썼으면(then이 last·original) 단계 수.
     * 순차 응답 규칙이 아니면 0.
     *
     * 규칙이 바뀌었는지는 인스턴스로 판단한다. 출처의 StateFlow는 같은 내용으로 바꾸면 기존 인스턴스를 유지하므로,
     * 내용 그대로 다시 넣고 처음부터 시작하려면 [resetSequence]를 부른다.
     */
    public fun nextStep(rule: Rule): Int {
        val cursor = cursors.value[rule.id] ?: return 0
        return if (cursor.rule === rule) cursor.next else 0
    }

    /** 순차 응답 규칙 [ruleId]를 처음부터 다시 시작하게 한다. */
    public fun resetSequence(ruleId: String) {
        synchronized(cursorLock) { cursors.value -= ruleId }
    }

    /** 모든 순차 응답 규칙을 처음부터 다시 시작하게 한다. */
    public fun resetSequences() {
        synchronized(cursorLock) { cursors.value = emptyMap() }
    }

    /** 이번 호출에 쓸 단계를 고르고 커서를 한 칸 옮긴다. then = original로 다 썼으면 null. */
    private fun advance(rule: Rule, sequence: Override.Sequence): Int? = synchronized(cursorLock) {
        val size = sequence.steps.size
        val current = nextStep(rule)
        val next = when (sequence.then) {
            SequenceEnd.Loop -> (current + 1) % size
            SequenceEnd.Last, SequenceEnd.Original -> minOf(current + 1, size)
        }
        cursors.value += rule.id to SequenceCursor(rule, next)
        when {
            current < size -> current
            sequence.then == SequenceEnd.Last -> size - 1
            else -> null
        }
    }

    /** 앱 시작 직후 아직 읽지 못한 규칙(프리셋·저장 규칙)을 놓치지 않도록 최대 [RULES_READY_TIMEOUT_MS] 기다린다. */
    private suspend fun awaitRules() {
        if (repository.isReady) return
        val start = System.nanoTime()
        val ready = repository.awaitReady(RULES_READY_TIMEOUT_MS)
        val waitedMs = (System.nanoTime() - start) / 1_000_000
        onRulesWait?.invoke(waitedMs, ready)
    }

    /**
     * 실제 응답을 받은 뒤 호출한다. Patch 적용·검증·정책 처리 후 캡처하고 앱에 전달할 응답을 돌려준다.
     * [Decision.Respond]·[Decision.Fail]에 대해서는 아무것도 하지 않는다.
     */
    public suspend fun afterResponse(
        request: BoggartRequest,
        response: BoggartResponse,
        decision: Decision,
    ): BoggartResponse {
        if (decision !is Decision.Proceed) return response
        val rule = decision.rule
        val override = decision.override
        val step = decision.step
        if (rule == null || override == null) {
            record(request, response, response, Provenance.Original, null)
            return response
        }
        val (patchedBody, ruleOnInvalid) = when (override) {
            is Override.Patch -> applyPatch(response.body) { JsonMergePatch.apply(it, override.patch) } to override.onInvalid
            is Override.JsonPatch -> applyPatch(response.body) { JsonPatch.apply(it, override.ops) } to override.onInvalid
            is Override.Replace, is Override.Fail, is Override.Sequence -> {
                record(request, response, response, Provenance.InvalidIgnored, rule.id, step = step)
                return response
            }
        }
        if (patchedBody == null) {
            record(request, response, response, Provenance.InvalidIgnored, rule.id, step = step)
            return response
        }
        val valid = validator.validate(patchedBody, response.mediaType)
        if (!valid && (ruleOnInvalid ?: onInvalidPatch) == OnInvalidPatch.Ignore) {
            record(request, response, response, Provenance.InvalidIgnored, rule.id, step = step)
            return response
        }
        val headers = if (CONTENT_LENGTH in response.headers) {
            response.headers.with(CONTENT_LENGTH, patchedBody.size.toString())
        } else {
            response.headers
        }
        val delivered = response.copy(headers = headers, body = patchedBody)
        record(request, response, delivered, if (valid) Provenance.Patched else Provenance.PatchedInvalid, rule.id, step = step)
        return delivered
    }

    /**
     * [Decision.Proceed] 이후 실제 호출이 예외를 던졌을 때 호출한다. [Provenance.Original]과 [error]를 캡처한다.
     * 그 외 결정에 대해서는 아무것도 하지 않는다.
     */
    public fun onFailure(request: BoggartRequest, decision: Decision, error: Throwable) {
        if (decision !is Decision.Proceed) return
        record(request, null, null, Provenance.Original, null, error.toString())
    }

    /** 원래 바디가 JSON이 아니거나 JSON Patch 경로를 찾지 못해 패치할 수 없으면 null. */
    private inline fun applyPatch(body: ByteArray?, patch: (JsonElement) -> JsonElement): ByteArray? {
        val original = body?.let(StrictJson::parse) ?: return null
        val patched = try {
            patch(original)
        } catch (_: JsonPatchException) {
            return null
        }
        return patched.toString().toByteArray(Charsets.UTF_8)
    }

    /** bodyFile을 해석하지 못하면 null. */
    private suspend fun buildReplacement(override: Override.Replace): BoggartResponse? {
        val body = when {
            override.body != null -> override.body.toByteArray(Charsets.UTF_8)
            override.bodyFile != null -> bodyFileResolver?.resolve(override.bodyFile) ?: return null
            else -> null
        }
        return BoggartResponse(
            status = override.status,
            headers = BoggartHeaders.of(override.headers.toList()),
            body = body,
            mediaType = override.mediaType,
        )
    }

    private fun record(
        request: BoggartRequest,
        original: BoggartResponse?,
        delivered: BoggartResponse?,
        provenance: Provenance,
        ruleId: String?,
        error: String? = null,
        step: Int? = null,
    ) {
        exchangeLog.add(
            Exchange(nextId.getAndIncrement(), clock(), request, original, delivered, provenance, ruleId, error, step),
        )
    }

    private companion object {
        const val CONTENT_LENGTH = "Content-Length"

        /** 오프라인 모드에서 규칙이 없어 막은 호출의 교환 기록 문구. */
        const val OFFLINE_ERROR = "Offline mode: no rule for this call"

        /** 규칙 로딩을 기다리는 최대 시간. 보통 몇 ms면 끝나고, 디스크가 느릴 때 요청을 오래 붙잡지 않도록 상한을 둔다. */
        const val RULES_READY_TIMEOUT_MS = 500L
    }
}
