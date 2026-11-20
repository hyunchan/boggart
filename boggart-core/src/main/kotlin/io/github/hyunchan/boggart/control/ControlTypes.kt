package io.github.hyunchan.boggart.control

import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.RuleSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 제어 명령 요청. 전송 방식(HTTP 등)과 무관하다.
 *
 * @property method HTTP 메서드 이름(`GET`, `PUT` 등). 대소문자 무시.
 * @property target 퍼센트 인코딩된 경로와 쿼리(`/rules/user-404?source=saved`).
 * @property body 요청 바디 문자열. 없으면 null.
 */
public class ControlRequest(
    public val method: String,
    public val target: String,
    public val body: String? = null,
)

/** 제어 명령 응답. [body]는 항상 JSON 객체다. 실패면 `{"error":{"code","message"}}`. */
public class ControlResponse(
    public val status: Int,
    public val body: JsonObject,
) {
    public fun bodyText(): String = Json.encodeToString(JsonElement.serializer(), body)

    override fun toString(): String = "ControlResponse($status, ${bodyText()})"
}

/**
 * 제어 명령이 읽고 쓰는 규칙 출처. 플랫폼 모듈이 저장 규칙·프리셋 출처를 이 인터페이스로 감싸 넘긴다.
 *
 * [editable]이 false인 출처(프리셋)는 [setEnabled]만 지원하고 [upsert]·[remove]·[clear]는 부르지 않는다.
 */
public interface ControlRuleStore {
    public val source: RuleSource
    public val editable: Boolean

    public suspend fun upsert(rule: Rule)

    public suspend fun remove(ruleId: String): Boolean

    public suspend fun clear()

    /** 규칙이 없으면 false. */
    public suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean
}

/**
 * 명령 하나의 정의. REST 라우팅과 도움말(`GET /`)이 이 정의에서 만들어진다.
 *
 * @property name 안정된 명령 이름(`rules.put`). 나중에 MCP 도구 이름으로도 쓴다.
 * @property path 경로 패턴. `{id}`처럼 중괄호 세그먼트는 경로 파라미터다.
 * @property body 바디 설명. 바디를 받지 않으면 null.
 */
public class ControlCommand internal constructor(
    public val name: String,
    public val method: String,
    public val path: String,
    public val summary: String,
    public val params: List<ControlParam> = emptyList(),
    public val body: String? = null,
    internal val handler: suspend (ControlCall) -> JsonObject,
) {
    internal val segments: List<String> = path.split('/').filter { it.isNotEmpty() }
}

/** 명령 파라미터. [location]은 `path` 또는 `query`. */
public class ControlParam internal constructor(
    public val name: String,
    public val location: String,
    public val description: String,
    public val required: Boolean = false,
)

/** 핸들러가 받는 해석된 요청. */
internal class ControlCall(
    val pathParams: Map<String, String>,
    val query: Map<String, String>,
    val body: String?,
)

/** 핸들러가 던지는 실패. [code]는 에이전트가 분기할 수 있는 안정된 값이다. */
internal class ControlException(val status: Int, val code: String, message: String) : RuntimeException(message)
