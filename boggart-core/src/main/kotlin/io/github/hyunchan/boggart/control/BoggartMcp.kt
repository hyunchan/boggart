package io.github.hyunchan.boggart.control

import io.github.hyunchan.boggart.Boggart
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [BoggartControl]의 명령 중 도구 정의([ControlCommand.tool])가 있는 것을 MCP 도구로 노출한다.
 *
 * JSON-RPC 메시지 하나를 받아 응답 하나를 돌려주는 상태 없는 서버다. 세션 ID를 쓰지 않으므로
 * 테스트 중에 앱이 재시작돼도 클라이언트가 다시 초기화할 필요가 없다. 지원하는 메서드는
 * `initialize`, `ping`, `tools/list`, `tools/call`뿐이고 서버 알림·스트리밍은 없다.
 */
public class BoggartMcp(private val control: BoggartControl) {
    private val tools: List<Pair<ControlTool, ControlCommand>> = control.commands.mapNotNull { command -> command.tool?.let { it to command } }

    /** 노출하는 도구 이름. */
    public val toolNames: List<String> get() = tools.map { it.first.name }

    /** JSON-RPC 메시지 [message]를 처리한다. 알림·응답처럼 답할 필요가 없으면 null(HTTP 202)이다. */
    public suspend fun handle(message: String): String? {
        val element = try {
            Json.parseToJsonElement(message)
        } catch (e: SerializationException) {
            return encode(failure(JsonNull, PARSE_ERROR, "Parse error: ${e.message}"))
        }
        if (element is JsonArray) return encode(failure(JsonNull, INVALID_REQUEST, "Batched messages aren't supported."))
        val request = element as? JsonObject ?: return encode(failure(JsonNull, INVALID_REQUEST, "A message must be a JSON object."))
        val id = request["id"]
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (id == null || method == null) return null
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        val reply = when (method) {
            "initialize" -> success(id, initialize(params))
            "ping" -> success(id, JsonObject(emptyMap()))
            "tools/list" -> success(id, buildJsonObject { put("tools", JsonArray(tools.map { (tool, command) -> describe(tool, command) })) })
            "tools/call" -> callTool(id, params)
            else -> failure(id, METHOD_NOT_FOUND, "Method '$method' isn't supported.")
        }
        return encode(reply)
    }

    private fun initialize(params: JsonObject) = buildJsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
        put("protocolVersion", requested?.takeIf { it in PROTOCOL_VERSIONS } ?: PROTOCOL_VERSIONS.first())
        putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
        putJsonObject("serverInfo") {
            put("name", "boggart")
            put("version", Boggart.VERSION)
        }
        put("instructions", INSTRUCTIONS)
    }

    private fun describe(tool: ControlTool, command: ControlCommand) = buildJsonObject {
        put("name", tool.name)
        put("description", command.summary)
        put("inputSchema", inputSchema(tool, command))
    }

    private fun inputSchema(tool: ControlTool, command: ControlCommand) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (param in command.params) {
                putJsonObject(param.name) {
                    put("type", param.type)
                    put("description", param.description)
                    param.enum?.let { values -> put("enum", JsonArray(values.map(::JsonPrimitive))) }
                }
            }
            tool.bodyProperties.forEach { (name, schema) -> put(name, schema) }
        }
        val required = command.params.filter { it.required }.map { it.name } + tool.requiredBody
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
        if (tool.defs.isNotEmpty()) put("\$defs", JsonObject(tool.defs))
    }

    private suspend fun callTool(id: JsonElement, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.content ?: return failure(id, INVALID_PARAMS, "tools/call needs a 'name'.")
        val (tool, command) = tools.firstOrNull { it.first.name == name }
            ?: return failure(id, INVALID_PARAMS, "Unknown tool '$name'. Known: ${toolNames.joinToString()}.")
        val args = when (val raw = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> return failure(id, INVALID_PARAMS, "'arguments' must be an object.")
        }
        val request = try {
            toRequest(tool, command, args)
        } catch (e: ToolInputException) {
            return success(id, toolResult(e.message.orEmpty(), structured = null, isError = true))
        }
        val response = control.handle(request)
        return success(id, toolResult(response.bodyText(), response.body, isError = response.status !in 200..299))
    }

    /** 도구 입력을 명령 요청으로 바꾼다. 경로·쿼리 파라미터는 문자열로, 나머지는 바디로 보낸다. */
    private fun toRequest(tool: ControlTool, command: ControlCommand, args: JsonObject): ControlRequest {
        val known = command.params.map { it.name }.toSet() + tool.bodyProperties.keys
        val unknown = args.keys - known
        if (unknown.isNotEmpty()) {
            throw ToolInputException("Unknown argument ${unknown.joinToString { "'$it'" }} for ${tool.name}. Known: ${known.sorted().joinToString()}.")
        }
        val missing = (command.params.filter { it.required }.map { it.name } + tool.requiredBody).filter { args[it] == null || args[it] == JsonNull }
        if (missing.isNotEmpty()) throw ToolInputException("Missing argument ${missing.joinToString { "'$it'" }} for ${tool.name}.")

        fun text(name: String): String? = when (val value = args[name]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.content
            else -> throw ToolInputException("'$name' must be a ${command.params.first { it.name == name }.type}, not an object or array.")
        }
        val path = command.segments.joinToString("/", prefix = "/") { segment ->
            if (segment.startsWith('{')) ControlTarget.encode(text(segment.trim('{', '}')).orEmpty()) else segment
        }
        val query = command.params.filter { it.location == "query" }
            .mapNotNull { param -> text(param.name)?.let { "${param.name}=${ControlTarget.encode(it)}" } }
        val target = if (query.isEmpty()) path else "$path?${query.joinToString("&")}"
        val body = when {
            tool.bodyProperties.isEmpty() -> null
            else -> (tool.toBody?.invoke(args) ?: JsonObject(args.filterKeys { it in tool.bodyProperties })).toString()
        }
        return ControlRequest(command.method, target, body)
    }

    private fun toolResult(text: String, structured: JsonObject?, isError: Boolean) = buildJsonObject {
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
        structured?.let { put("structuredContent", it) }
        put("isError", isError)
    }

    private fun success(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun failure(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private fun encode(message: JsonObject): String = Json.encodeToString(JsonObject.serializer(), message)

    private class ToolInputException(message: String) : RuntimeException(message)

    private companion object {
        /** 응답할 수 있는 프로토콜 버전. 클라이언트가 이 중 하나를 요청하면 그대로, 아니면 첫 번째로 답한다. */
        val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        val INSTRUCTIONS = """
            Boggart changes the API responses of the Android app under test.
            Typical test step: boggart_status to read calls.lastId, put or enable a rule, trigger the screen in the app,
            then boggart_wait_for_call with since = that lastId to see what the app sent and received.
            Rules apply from the next call, so reload the screen after changing a rule.
            A sequence override answers successive matching calls with its steps in turn (for example 500, then 200, to test a retry);
            each captured call shows which step answered it.
            Call boggart_reset between tests. Temporary rules disappear on reset or app restart; use target = saved to keep one.
        """.trimIndent()
    }
}
