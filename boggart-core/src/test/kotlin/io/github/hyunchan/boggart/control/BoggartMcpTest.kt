package io.github.hyunchan.boggart.control

import io.github.hyunchan.boggart.BoggartEngine
import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.BoggartResponse
import io.github.hyunchan.boggart.Decision
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.JsonPatchOp
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.RuleRepository
import io.github.hyunchan.boggart.SequenceEnd
import io.github.hyunchan.boggart.TemporaryRuleSource
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoggartMcpTest {
    private val temporary = TemporaryRuleSource()
    private val engine = BoggartEngine(RuleRepository(listOf(temporary)))
    private val mcp = BoggartMcp(BoggartControl(engine, temporary))
    private var nextId = 1

    private suspend fun rpc(method: String, params: String = "{}"): JsonObject {
        val reply = mcp.handle("""{"jsonrpc":"2.0","id":${nextId++},"method":"$method","params":$params}""")
        return Json.parseToJsonElement(checkNotNull(reply)).jsonObject
    }

    private suspend fun call(tool: String, arguments: String = "{}"): JsonObject =
        rpc("tools/call", """{"name":"$tool","arguments":$arguments}""").getValue("result").jsonObject

    private fun JsonObject.isError() = getValue("isError").jsonPrimitive.boolean

    private fun JsonObject.structured() = getValue("structuredContent").jsonObject

    private suspend fun schemaOf(tool: String): JsonObject = rpc("tools/list").getValue("result").jsonObject.getValue("tools").jsonArray
        .map { it.jsonObject }
        .first { it.getValue("name").jsonPrimitive.content == tool }
        .getValue("inputSchema").jsonObject

    private suspend fun capture(path: String) {
        val request = BoggartRequest("GET", "https://api.example.com$path")
        val decision = engine.beforeRequest(request)
        when (decision) {
            is Decision.Respond, is Decision.Fail -> engine.onDelivered(request, decision)
            else -> engine.afterResponse(request, BoggartResponse(200, body = "{}".toByteArray()), decision)
        }
    }

    @Test
    fun `initialize echoes a supported protocol version`() = runTest {
        val result = rpc("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")
            .getValue("result").jsonObject
        assertEquals("2025-06-18", result.getValue("protocolVersion").jsonPrimitive.content)
        assertTrue("tools" in result.getValue("capabilities").jsonObject)
        assertEquals("boggart", result.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
        val unknown = rpc("initialize", """{"protocolVersion":"2099-01-01"}""").getValue("result").jsonObject
        assertEquals("2025-11-25", unknown.getValue("protocolVersion").jsonPrimitive.content)
    }

    @Test
    fun `notifications get no reply`() = runTest {
        assertNull(mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `tools list exposes the eleven tools with closed schemas`() = runTest {
        val tools = rpc("tools/list").getValue("result").jsonObject.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(
                "boggart_status", "boggart_set_mode", "boggart_reset", "boggart_list_calls", "boggart_wait_for_call", "boggart_get_call",
                "boggart_list_rules", "boggart_put_rule", "boggart_set_rule_enabled", "boggart_delete_rule", "boggart_import_rules",
            ),
            tools.map { it.getValue("name").jsonPrimitive.content },
        )
        tools.forEach { tool ->
            val schema = tool.getValue("inputSchema").jsonObject
            assertEquals("object", schema.getValue("type").jsonPrimitive.content)
            assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
        }
        val required = schemaOf("boggart_put_rule").getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("id", "rule"), required)
    }

    @Test
    fun `put rule creates a temporary rule that the engine applies`() = runTest {
        val result = call("boggart_put_rule", """{"id":"user 404","rule":{"matcher":{"path":"/users/*"},"override":{"type":"replace","status":404}}}""")
        assertFalse(result.isError(), result.toString())
        assertEquals("user 404", temporary.rules.value.single().id)
        val decision = engine.beforeRequest(BoggartRequest("GET", "https://api.example.com/users/1"))
        assertIs<Decision.Respond>(decision)
        assertEquals(404, decision.response.status)
    }

    @Test
    fun `import, toggle and delete rules`() = runTest {
        val rules = """[{"id":"a","override":{"type":"fail","kind":{"type":"timeout"}}},{"id":"b","override":{"type":"replace"}}]"""
        assertEquals(2, call("boggart_import_rules", """{"rules":$rules}""").structured().getValue("imported").jsonPrimitive.int)
        call("boggart_set_rule_enabled", """{"id":"a","enabled":false}""")
        assertFalse(temporary.rules.value.first { it.id == "a" }.enabled)
        call("boggart_delete_rule", """{"id":"b"}""")
        assertEquals(listOf("a"), temporary.rules.value.map { it.id })
    }

    @Test
    fun `mode and reset`() = runTest {
        call("boggart_set_mode", """{"mode":"offline"}""")
        assertEquals(Mode.Offline, engine.mode)
        call("boggart_reset")
        assertEquals(Mode.Active, engine.mode)
    }

    @Test
    fun `wait for call returns the call with integer arguments`() = runTest {
        val since = call("boggart_status").structured().getValue("calls").jsonObject.getValue("lastId").jsonPrimitive.long
        val waiting = async { call("boggart_wait_for_call", """{"since":$since,"path":"/users/*","timeoutMs":5000}""") }
        yield()
        capture("/users/7")
        val result = waiting.await()
        assertFalse(result.isError(), result.toString())
        assertTrue(result.structured().getValue("call").jsonObject.getValue("url").jsonPrimitive.content.endsWith("/users/7"))
        val listed = call("boggart_list_calls", """{"limit":1}""").structured().getValue("calls").jsonArray
        assertEquals(1, listed.size)
    }

    @Test
    fun `command failures are tool errors with the error code`() = runTest {
        val missing = call("boggart_delete_rule", """{"id":"nope"}""")
        assertTrue(missing.isError())
        assertEquals("RULE_NOT_FOUND", missing.structured().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        val invalid = call("boggart_put_rule", """{"id":"x","rule":{"override":{"type":"replace","stauts":500}}}""")
        assertTrue(invalid.isError())
        assertEquals("INVALID_RULE", invalid.structured().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `bad tool input is reported without running`() = runTest {
        assertTrue(call("boggart_list_calls", """{"sinceId":3}""").isError())
        assertTrue(call("boggart_put_rule", """{"id":"x"}""").isError())
        assertTrue(call("boggart_get_call", """{"id":{"n":1}}""").isError())
        assertTrue(temporary.rules.value.isEmpty())
    }

    @Test
    fun `protocol errors use json-rpc codes`() = runTest {
        assertEquals(-32601, rpc("resources/list").getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        assertEquals(-32602, rpc("tools/call", """{"name":"nope"}""").getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        val parse = Json.parseToJsonElement(checkNotNull(mcp.handle("{"))).jsonObject
        assertEquals(-32700, parse.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        val batch = Json.parseToJsonElement(checkNotNull(mcp.handle("[]"))).jsonObject
        assertEquals(-32600, batch.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
    }

    // ---- 스키마가 직렬화 형식과 같은지 ----

    @OptIn(ExperimentalSerializationApi::class)
    private fun SerialDescriptor.fields() = elementNames.toSet()

    private fun JsonObject.properties() = getValue("properties").jsonObject

    /** 도구 입력 스키마 루트의 `$defs`. 규칙 스키마의 `$ref`를 풀 때 쓴다. */
    private var defs: JsonObject = JsonObject(emptyMap())

    private fun JsonObject.resolve(): JsonObject {
        val ref = this["\$ref"]?.jsonPrimitive?.content ?: return this
        return defs.getValue(ref.removePrefix("#/\$defs/")).jsonObject
    }

    private fun JsonObject.variants() = getValue("oneOf").jsonArray.associate {
        val variant = it.jsonObject.resolve()
        variant.properties().getValue("type").jsonObject.getValue("const").jsonPrimitive.content to variant
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun subclasses(descriptor: SerialDescriptor) = descriptor.getElementDescriptor(1).let { value ->
        (0 until value.elementsCount).associate { value.getElementDescriptor(it).let { sub -> sub.serialName to sub } }
    }

    @Test
    fun `rule schema matches the serialized form`() = runTest {
        val tool = schemaOf("boggart_import_rules")
        defs = tool.getValue("\$defs").jsonObject
        val rule = tool.properties().getValue("rules").jsonObject.getValue("items").jsonObject
        assertEquals(Rule.serializer().descriptor.fields(), rule.properties().keys)
        assertEquals(serializer<RequestMatcher>().descriptor.fields(), rule.properties().getValue("matcher").jsonObject.properties().keys)

        val overrides = rule.properties().getValue("override").jsonObject.variants()
        val overrideClasses = subclasses(Override.serializer().descriptor)
        assertEquals(overrideClasses.keys, overrides.keys)
        overrideClasses.forEach { (name, descriptor) -> assertEquals(descriptor.fields() + "type", overrides.getValue(name).properties().keys, name) }

        val failKinds = overrides.getValue("fail").properties().getValue("kind").jsonObject.variants()
        val failClasses = subclasses(FailKind.serializer().descriptor)
        assertEquals(failClasses.keys, failKinds.keys)
        failClasses.forEach { (name, descriptor) -> assertEquals(descriptor.fields() + "type", failKinds.getValue(name).properties().keys, name) }

        val op = overrides.getValue("jsonPatch").properties().getValue("ops").jsonObject.getValue("items").jsonObject
        assertEquals(JsonPatchOp.serializer().descriptor.fields(), op.properties().keys)

        val sequence = overrides.getValue("sequence").properties()
        val steps = sequence.getValue("steps").jsonObject.getValue("items").jsonObject.variants()
        assertEquals(overrideClasses.keys - "sequence", steps.keys)
        val ends = SequenceEnd.serializer().descriptor.elementNames.toSet()
        assertEquals(ends, sequence.getValue("then").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }
}
