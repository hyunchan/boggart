package io.github.hyunchan.boggart

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * RFC 8259 문법을 지키는 JSON만 해석한다. 실패하면 null.
 *
 * `Json.parseToJsonElement`는 `<html>` 같은 따옴표 없는 리터럴도 받아들이므로 비문자열 원시값을 다시 검사한다.
 */
internal object StrictJson {
    private val NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

    fun parse(bytes: ByteArray): JsonElement? {
        val element = try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return null
        }
        return element.takeIf { isStrict(it) }
    }

    private fun isStrict(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.all(::isStrict)
        is JsonArray -> element.all(::isStrict)
        is JsonNull -> true
        is JsonPrimitive ->
            element.isString ||
                element.content == "true" ||
                element.content == "false" ||
                NUMBER.matches(element.content)
    }
}
