package io.github.hyunchan.boggart.control

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /**
     * 규칙 스키마가 `$ref`로 가리키는 정의. 도구 입력 스키마 루트의 `$defs`에 싣는다.
     * sequence를 뺀 조작은 바깥 조작과 sequence의 단계 양쪽에서 쓰이므로 한 번만 싣고 참조한다.
     */
    val defs: Map<String, JsonObject> get() = singleVariants + (CHANCE to chance)

    /** sequence를 뺀 조작(구분자 → 스키마). sequence의 단계로도 쓴다. */
    private val singleVariants: Map<String, JsonObject> = listOf(
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
        variant(
            "fail",
            "Fail the call with a network error without calling the server.",
            mapOf("kind" to failKind, "delayMs" to delayMs),
            listOf("kind"),
        ),
    ).associateBy { it.getValue("properties").jsonObject.getValue("type").jsonObject.getValue("const").jsonPrimitive.content }

    private val singleRefs: List<JsonObject> = singleVariants.keys.map(::ref)

    private val chance = variant(
        CHANCE,
        "On each call, apply 'override' with the given chance; otherwise let the call reach the server unchanged. " +
            "Calls it lets through are captured as original with this rule's id.",
        mapOf(
            "percent" to buildJsonObject {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 99)
                put("description", "Chance of applying 'override', in percent.")
            },
            "override" to buildJsonObject {
                put("description", "What to do when the chance hits: any override except sequence and chance.")
                putJsonArray("oneOf") { singleRefs.forEach(::add) }
            },
        ),
        listOf("percent", "override"),
    )

    /** 단계에 쓸 수 있는 조작: 단일 조작과 chance. */
    private fun stepOverrides(description: String) = buildJsonObject {
        put("description", description)
        putJsonArray("oneOf") {
            singleRefs.forEach(::add)
            add(ref(CHANCE))
        }
    }

    private val repeatedStep = obj(
        "A step used several calls in a row.",
        mapOf(
            "times" to buildJsonObject {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 1000)
                put("description", "How many calls in a row get this step.")
            },
            "override" to stepOverrides("The override for those calls: any except sequence."),
        ),
        required = listOf("times", "override"),
    )

    private val override = buildJsonObject {
        put("description", "How to change the response. Pick one variant by 'type'.")
        putJsonArray("oneOf") {
            singleRefs.forEach(::add)
            add(ref(CHANCE))
            add(
                variant(
                    "sequence",
                    "Answer successive matching calls with 'steps' in turn: the first call gets the first step, the next call the second. " +
                        "Each step has its own delayMs. Changing, re-enabling or putting the rule again starts over from the first step.",
                    mapOf(
                        "steps" to buildJsonObject {
                            put("type", "array")
                            put("minItems", 1)
                            put(
                                "description",
                                "Steps in order. A step is an override (used once) or {times, override} (used several calls in a row). " +
                                    "A step can't be another sequence.",
                            )
                            putJsonObject("items") {
                                put("description", "One step.")
                                putJsonArray("oneOf") {
                                    singleRefs.forEach(::add)
                                    add(ref(CHANCE))
                                    add(repeatedStep)
                                }
                            }
                        },
                        "then" to enumOf(
                            listOf("last", "loop", "original"),
                            "After the last step: last keeps using it, loop starts again from the first step, " +
                                "original lets calls reach the server unchanged. Defaults to last.",
                        ),
                    ),
                    listOf("steps"),
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

    private fun ref(name: String) = buildJsonObject { put("\$ref", "#/\$defs/$name") }

    private const val CHANCE = "chance"

    private fun string(description: String) = typed("string", description)

    private fun typed(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun describe(schema: JsonObject, description: String) = JsonObject(schema + ("description" to JsonPrimitive(description)))
}
