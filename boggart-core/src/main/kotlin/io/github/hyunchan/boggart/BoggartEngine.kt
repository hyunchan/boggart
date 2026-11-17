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
     */
    public data class Proceed(
        val rule: Rule? = null,
        override val delayMs: Long = 0,
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
        return when (val override = rule.override) {
            is Override.Replace -> {
                val response = buildReplacement(override) ?: return Decision.Proceed(rule, override.delayMs)
                record(request, null, response, Provenance.Replaced, rule.id)
                Decision.Respond(response, Provenance.Replaced, rule, override.delayMs)
            }
            is Override.Patch, is Override.JsonPatch -> Decision.Proceed(rule, override.delayMs)
            is Override.Fail -> {
                record(request, null, null, Provenance.Failed, rule.id)
                Decision.Fail(override.kind, rule, override.delayMs)
            }
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
        val (patchedBody, ruleOnInvalid) = when (val override = rule?.override) {
            null -> {
                record(request, response, response, Provenance.Original, null)
                return response
            }
            is Override.Patch -> applyPatch(response.body) { JsonMergePatch.apply(it, override.patch) } to override.onInvalid
            is Override.JsonPatch -> applyPatch(response.body) { JsonPatch.apply(it, override.ops) } to override.onInvalid
            is Override.Replace, is Override.Fail -> {
                record(request, response, response, Provenance.InvalidIgnored, rule.id)
                return response
            }
        }
        if (patchedBody == null) {
            record(request, response, response, Provenance.InvalidIgnored, rule.id)
            return response
        }
        val valid = validator.validate(patchedBody, response.mediaType)
        if (!valid && (ruleOnInvalid ?: onInvalidPatch) == OnInvalidPatch.Ignore) {
            record(request, response, response, Provenance.InvalidIgnored, rule.id)
            return response
        }
        val headers = if (CONTENT_LENGTH in response.headers) {
            response.headers.with(CONTENT_LENGTH, patchedBody.size.toString())
        } else {
            response.headers
        }
        val delivered = response.copy(headers = headers, body = patchedBody)
        record(request, response, delivered, if (valid) Provenance.Patched else Provenance.PatchedInvalid, rule.id)
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
    ) {
        exchangeLog.add(
            Exchange(nextId.getAndIncrement(), clock(), request, original, delivered, provenance, ruleId, error),
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
