package io.github.hyunchan.boggart.android.ui.common
import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.BoggartResponse
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.JsonMergePatch
import io.github.hyunchan.boggart.JsonSyntaxValidator
import io.github.hyunchan.boggart.OnInvalidPatch
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** 규칙을 저장할 위치. */
internal sealed interface RuleTarget {
    val id: String

    /** 기존 임시 규칙을 제자리 갱신하거나 새 임시 규칙을 만든다. */
    data class Temporary(override val id: String) : RuleTarget

    /** 기존 저장 규칙을 제자리 갱신한다. */
    data class Saved(override val id: String) : RuleTarget
}

internal sealed interface Draft {
    data class Ok(val rule: Rule) : Draft
    data class Error(val reason: DraftError) : Draft
}

/** 규칙을 만들 수 없는 이유. 화면이 언어별 문구로 바꿔 보여 준다. */
internal enum class DraftError {
    NoOriginalBody,
    OriginalNotJson,
    EditedNotJson,
    NoChanges,
    PatchNotJson,
    OpsNotJsonPatch,
    NoOps,
    NoValueChanges,
    ValuesNotJson,
    CannotApply,
}

/** 편집 화면 입력을 규칙으로 바꾸는 순수 함수 모음. Compose와 무관하게 JVM에서 검증한다. */
internal object RuleDrafts {
    private val pretty = Json { prettyPrint = true }

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * 교환에 이미 적용된 규칙이 임시·저장 규칙이면 그 규칙을 갱신하고, 아니면(프리셋 규칙이거나 없음) 새 임시 규칙을 만든다.
     */
    fun targetFor(appliedRuleId: String?, temporaryIds: Set<String>, savedIds: Set<String>): RuleTarget = when (appliedRuleId) {
        null -> RuleTarget.Temporary(newId())
        in temporaryIds -> RuleTarget.Temporary(appliedRuleId)
        in savedIds -> RuleTarget.Saved(appliedRuleId)
        else -> RuleTarget.Temporary(newId())
    }

    /**
     * 교환에서 만든 규칙의 이름. 조작 종류는 목록에 따로 표시되므로 이름에 넣지 않는다.
     * 이름은 저장되므로 언어에 따라 달라지는 말을 넣지 않는다.
     */
    fun ruleName(request: BoggartRequest): String = nameFor(matcherFor(request))

    /** 조건으로 만든 기본 이름(`GET /path`). 조건을 고칠 때 이름을 따라 바꿀지 판단하는 기준이기도 하다. */
    fun nameFor(matcher: RequestMatcher): String = listOfNotNull(matcher.method?.uppercase(), matcher.path).joinToString(" ")

    /** method + host + 정확한 path. */
    fun matcherFor(request: BoggartRequest): RequestMatcher =
        RequestMatcher(method = request.method.uppercase(), host = request.url.host, path = request.url.path)

    /** JSON이면 들여쓰기한 문자열, 아니면 UTF-8 원문. 바디가 없으면 빈 문자열. */
    fun prettyBody(body: ByteArray?): String {
        if (body == null) return ""
        val element = parseJson(body) ?: return body.toString(Charsets.UTF_8)
        return pretty.encodeToString(JsonElement.serializer(), element)
    }

    /** 편집기의 시작 텍스트. 전달된 응답이 있으면 그것을, 없으면 원본을 쓴다. */
    fun initialEditorText(exchange: Exchange): String =
        prettyBody((exchange.deliveredResponse ?: exchange.originalResponse)?.body)

    /**
     * 편집한 바디와 원본 응답의 차이로 Patch 규칙을 만든다.
     *
     * @param ignoreInvalid 검증에 실패하면 원본을 전달할지. true면 [OnInvalidPatch.Ignore], false면 [OnInvalidPatch.PassThrough].
     */
    fun patchRule(
        exchange: Exchange,
        editedText: String,
        ignoreInvalid: Boolean,
        delayMs: Long,
        target: RuleTarget,
    ): Draft {
        val originalBody = exchange.originalResponse?.body
            ?: return Draft.Error(DraftError.NoOriginalBody)
        val original = parseJson(originalBody)
            ?: return Draft.Error(DraftError.OriginalNotJson)
        val edited = parseJson(editedText.toByteArray())
            ?: return Draft.Error(DraftError.EditedNotJson)
        val patch = JsonMergePatch.diff(original, edited)
        if (patch == JsonObject(emptyMap())) return Draft.Error(DraftError.NoChanges)
        val request = exchange.request
        return Draft.Ok(
            Rule(
                id = target.id,
                name = ruleName(request),
                matcher = matcherFor(request),
                override = Override.Patch(patch, delayMs, invalidPolicy(ignoreInvalid)),
            ),
        )
    }

    fun replaceRule(
        exchange: Exchange,
        status: Int,
        headersText: String,
        bodyText: String,
        delayMs: Long,
        target: RuleTarget,
    ): Rule {
        val request = exchange.request
        val mediaType = exchange.deliveredResponse?.mediaType ?: exchange.originalResponse?.mediaType ?: "application/json"
        return Rule(
            id = target.id,
            name = ruleName(request),
            matcher = matcherFor(request),
            override = Override.Replace(status, parseHeaders(headersText), bodyText, null, mediaType, delayMs),
        )
    }

    fun failRule(exchange: Exchange, kind: FailKind, delayMs: Long, target: RuleTarget): Rule {
        val request = exchange.request
        return Rule(
            id = target.id,
            name = ruleName(request),
            matcher = matcherFor(request),
            override = Override.Fail(kind, delayMs),
        )
    }

    /**
     * Replace 규칙 편집. 바디를 고쳤으면 [Override.Replace.bodyFile]을 버리고 인라인 바디로 바꾼다.
     * 고치지 않았으면 기존 body/bodyFile을 유지한다.
     */
    fun editReplace(
        rule: Rule,
        status: Int,
        headersText: String,
        bodyText: String,
        shownBodyText: String,
        delayMs: Long,
    ): Rule {
        val replace = rule.override as Override.Replace
        val bodyChanged = bodyText != shownBodyText
        return rule.copy(
            override = replace.copy(
                status = status,
                headers = parseHeaders(headersText),
                body = if (bodyChanged) bodyText else replace.body,
                bodyFile = if (bodyChanged) null else replace.bodyFile,
                delayMs = delayMs,
            ),
        )
    }

    fun editPatch(rule: Rule, patchText: String, ignoreInvalid: Boolean, delayMs: Long): Draft {
        val patch = parseJson(patchText.toByteArray()) ?: return Draft.Error(DraftError.PatchNotJson)
        return Draft.Ok(rule.copy(override = Override.Patch(patch, delayMs, invalidPolicy(ignoreInvalid))))
    }

    fun editJsonPatch(rule: Rule, opsText: String, ignoreInvalid: Boolean, delayMs: Long): Draft {
        val ops = ValueEdits.parseOps(opsText) ?: return Draft.Error(DraftError.OpsNotJsonPatch)
        if (ops.isEmpty()) return Draft.Error(DraftError.NoOps)
        return Draft.Ok(rule.copy(override = Override.JsonPatch(ops, delayMs, invalidPolicy(ignoreInvalid))))
    }

    fun editFail(rule: Rule, kind: FailKind, delayMs: Long): Rule = rule.copy(override = Override.Fail(kind, delayMs))

    /**
     * 조건을 고친다. 빈 칸은 조건 없음(null)이다. 이름이 조건으로 만든 기본 이름 그대로면 새 조건에 맞춰 이름도 바꾼다.
     * 프리셋처럼 사람이 붙인 이름은 건드리지 않는다.
     */
    fun editMatcher(rule: Rule, method: String, host: String, path: String, headersText: String): Rule {
        val matcher = rule.matcher.copy(
            method = method.trim().uppercase().ifEmpty { null },
            host = host.trim().ifEmpty { null },
            path = path.trim().ifEmpty { null },
            headers = parseHeaders(headersText),
        )
        val autoNamed = rule.name == nameFor(rule.matcher)
        val name = if (autoNamed) nameFor(matcher).ifEmpty { rule.name } else rule.name
        return rule.copy(name = name, matcher = matcher)
    }

    /** `Name: value` 줄 목록. 빈 줄과 콜론 없는 줄은 무시하고, 같은 이름은 마지막 값을 쓴다. */
    fun parseHeaders(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { ':' in it }
            .map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            .filter { it.first.isNotEmpty() }
            .toMap()

    fun formatHeaders(headers: Map<String, String>): String = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    fun formatResponseHeaders(response: BoggartResponse?): String =
        response?.headers?.toList()?.joinToString("\n") { "${it.first}: ${it.second}" }.orEmpty()

    fun prettyPatch(patch: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), patch)

    private fun invalidPolicy(ignoreInvalid: Boolean) =
        if (ignoreInvalid) OnInvalidPatch.Ignore else OnInvalidPatch.PassThrough

    /** JSON이면 파싱한 트리, 아니면 null. */
    fun parseJson(bytes: ByteArray): JsonElement? =
        if (JsonSyntaxValidator.validate(bytes, null)) Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) else null

    /** 이 요청을 다시 보내는 cURL 명령. 값은 작은따옴표로 감싸고 그 안의 작은따옴표는 '\\''로 끊어 잇는다. */
    fun curlOf(request: BoggartRequest): String {
        fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val parts = mutableListOf("curl", "-X", request.method.uppercase(), q(request.url.toString()))
        request.headers.toList().forEach { (name, value) -> parts += listOf("-H", q("$name: $value")) }
        request.body?.takeIf { it.isNotEmpty() }?.let { parts += listOf("--data-raw", q(it.toString(Charsets.UTF_8))) }
        return parts.joinToString(" ")
    }
}
