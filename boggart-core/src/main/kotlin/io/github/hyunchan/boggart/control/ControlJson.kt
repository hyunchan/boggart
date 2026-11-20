package io.github.hyunchan.boggart.control

import io.github.hyunchan.boggart.BoggartHeaders
import io.github.hyunchan.boggart.BoggartResponse
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.StrictJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.io.encoding.Base64

/** 제어 응답의 JSON 표현. 에이전트가 읽기 쉽도록 바디는 JSON·텍스트·base64 중 하나로 풀어 싣는다. */
internal object ControlJson {
    /** 규칙 입력은 엄격하게 읽는다. 오타 난 필드를 조용히 무시하면 에이전트가 실패를 알아채지 못한다. */
    val strict = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    private val compact = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    fun rule(rule: Rule, origin: String): JsonObject {
        val fields = compact.encodeToJsonElement(Rule.serializer(), rule).jsonObject
        return buildJsonObject {
            put("source", origin)
            put("id", rule.id)
            put("name", rule.name)
            put("enabled", rule.enabled)
            fields.forEach { (key, value) -> if (key !in RULE_FIXED_KEYS) put(key, value) }
        }
    }

    fun exchangeSummary(exchange: Exchange): JsonObject = buildJsonObject { putSummary(exchange) }

    fun exchangeDetail(exchange: Exchange): JsonObject = buildJsonObject {
        putSummary(exchange)
        put(
            "request",
            buildJsonObject {
                put("headers", headers(exchange.request.headers))
                put("body", body(exchange.request.body, exchange.request.headers["Content-Type"]))
            },
        )
        put("originalResponse", exchange.originalResponse?.let(::response) ?: JsonNull)
        put("deliveredResponse", exchange.deliveredResponse?.let(::response) ?: JsonNull)
    }

    private fun JsonObjectBuilder.putSummary(exchange: Exchange) {
        put("id", exchange.id)
        put("timestampMs", exchange.timestampMs)
        put("method", exchange.request.method)
        put("url", exchange.request.url.toString())
        put("status", exchange.deliveredResponse?.status)
        put("provenance", exchange.provenance.name.replaceFirstChar { it.lowercaseChar() })
        put("ruleId", exchange.appliedRuleId)
        put("error", exchange.error)
    }

    private fun response(response: BoggartResponse): JsonObject = buildJsonObject {
        put("status", response.status)
        put("headers", headers(response.headers))
        put("body", body(response.body, response.mediaType))
    }

    /** 같은 이름이 하나면 문자열, 여럿이면 배열. */
    private fun headers(headers: BoggartHeaders): JsonObject = buildJsonObject {
        for (name in headers.names) {
            val values = headers.values(name)
            put(name, if (values.size == 1) JsonPrimitive(values[0]) else JsonArray(values.map(::JsonPrimitive)))
        }
    }

    /** `{"size", "mediaType", "json" | "text" | "base64"}`. 바디가 없으면 JSON null. */
    private fun body(bytes: ByteArray?, mediaType: String?): JsonElement = if (bytes == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("size", bytes.size)
            put("mediaType", mediaType)
            val json = if (bytes.isEmpty()) null else StrictJson.parse(bytes)
            val text = if (json == null) utf8OrNull(bytes) else null
            when {
                json != null -> put("json", json)
                text != null -> put("text", text)
                else -> put("base64", Base64.encode(bytes))
            }
        }
    }

    private fun utf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private val RULE_FIXED_KEYS = setOf("id", "name", "enabled")
}
