package io.github.hyunchan.boggart.control

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [io.github.hyunchan.boggart.Rule]의 JSON Schema. MCP 도구 입력에 실어 에이전트가 첫 시도에 올바른 규칙을 만들게 한다.
 * 직렬화 형식과 어긋나지 않는지는 테스트가 직렬화 기술자(descriptor)와 대조한다.
 */
internal object RuleSchema {
    fun rule(withId: Boolean): JsonObject = obj(
        "A rule: when a call matches 'matcher', 'override' changes what the app receives.",
        buildMap {
            if (withId) put("id", string("Unique rule id."))
            put("name", string("Label shown in the Boggart screens. Defaults to the id."))
            put("matcher", matcher)
            put("override", override)
            put("enabled", boolean("Defaults to true."))
        },
        required = listOfNotNull("id".takeIf { withId }, "override"),
    )

    fun rules(): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", "Rules to apply. Ids must be unique.")
        put("items", rule(withId = true))
    }

    fun enumOf(values: List<String>, description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }

    fun boolean(description: String): JsonObject = typed("boolean", description)

    private val delayMs = buildJsonObject {
        put("type", "integer")
        put("minimum", 0)
        put("description", "Delay before the response reaches the app, in ms. Defaults to 0.")
    }

    private val onInvalid = enumOf(
        listOf("Ignore", "PassThrough"),
        "When the patched body fails validation: Ignore delivers the original body, PassThrough delivers the invalid one. " +
            "Defaults to the app's setting (Ignore).",
    )

    private val stringMap = buildJsonObject {
        put("type", "object")
        putJsonObject("additionalProperties") { put("type", "string") }
    }

    private val matcher = obj(
        "Which calls the rule applies to. Leave a field out to match anything.",
        mapOf(
            "method" to string("HTTP method, e.g. GET. Case-insensitive."),
            "host" to string("Exact host, case-insensitive."),
            "path" to string("Path glob. * matches one segment (or part of one, as in *.json), ** any number of segments."),
            "query" to describe(stringMap, "Query parameters that must be present. A value of * only checks presence."),
            "headers" to describe(stringMap, "Request headers that must be present. A value of * only checks presence."),
        ),
    )

    private val failKind = buildJsonObject {
        put("description", "The network error to raise.")
        putJsonArray("oneOf") {
            add(variant("timeout", "Socket timeout.", emptyMap()))
            add(variant("connectionReset", "Connection reset.", emptyMap()))
            add(variant("unknownHost", "Unknown host, as when the device is offline.", emptyMap()))
            add(variant("custom", "IOException with a message.", mapOf("message" to string("Exception message.")), listOf("message")))
        }
    }

    private val jsonPatchOp = obj(
        "One RFC 6902 operation.",
        mapOf(
            "op" to enumOf(listOf("add", "remove", "replace"), "Operation."),
            "path" to string("JSON Pointer into the response body, e.g. /0/title or /items/2. Array items are addressed by index."),
            "value" to describe(JsonObject(emptyMap()), "New value for add and replace. Defaults to null."),
        ),
        required = listOf("op", "path"),
    )

    private val override = buildJsonObject {
        put("description", "How to change the response. Pick one variant by 'type'.")
        putJsonArray("oneOf") {
            add(
                variant(
                    "replace",
                    "Answer without calling the server.",
                    mapOf(
                        "status" to typed("integer", "HTTP status. Defaults to 200."),
                        "headers" to describe(stringMap, "Response headers."),
                        "body" to string("Response body as text. Use either body or bodyFile."),
                        "bodyFile" to string("File name under assets/boggart/bodies/ to use as the body."),
                        "mediaType" to string("Content-Type. Defaults to application/json."),
                        "delayMs" to delayMs,
                    ),
                ),
            )
            add(
                variant(
                    "patch",
                    "Call the server, then apply a JSON Merge Patch (RFC 7386) to its JSON body. Arrays are replaced whole.",
                    mapOf(
                        "patch" to describe(JsonObject(emptyMap()), "Merge patch document. null removes a key."),
                        "delayMs" to delayMs,
                        "onInvalid" to onInvalid,
                    ),
                    listOf("patch"),
                ),
            )
            add(
                variant(
                    "jsonPatch",
                    "Call the server, then change values at given paths (RFC 6902) and keep the rest of the real body. " +
                        "If any operation can't be applied, the original body is delivered.",
                    mapOf(
                        "ops" to buildJsonObject {
                            put("type", "array")
                            put("items", jsonPatchOp)
                        },
                        "delayMs" to delayMs,
                        "onInvalid" to onInvalid,
                    ),
                    listOf("ops"),
                ),
            )
            add(
                variant(
                    "fail",
                    "Fail the call with a network error without calling the server.",
                    mapOf("kind" to failKind, "delayMs" to delayMs),
                    listOf("kind"),
                ),
            )
        }
    }

    /** `type` 상수로 구분되는 변형. 직렬화 형식과 같은 구분자 이름을 쓴다. */
    private fun variant(type: String, description: String, properties: Map<String, JsonObject>, required: List<String> = emptyList()) = obj(
        description,
        mapOf("type" to buildJsonObject { put("const", type) }) + properties,
        required = listOf("type") + required,
    )

    private fun obj(description: String, properties: Map<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object")
        put("description", description)
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }

    private fun string(description: String) = typed("string", description)

    private fun typed(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun describe(schema: JsonObject, description: String) = JsonObject(schema + ("description" to JsonPrimitive(description)))
}
