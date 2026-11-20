package io.github.hyunchan.boggart.control

import io.github.hyunchan.boggart.BoggartEngine
import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.BoggartResponse
import io.github.hyunchan.boggart.Decision
import io.github.hyunchan.boggart.ExchangeLog
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.RuleRepository
import io.github.hyunchan.boggart.RuleSource
import io.github.hyunchan.boggart.TemporaryRuleSource
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoggartControlTest {
    private class FakeStore(override val priority: Int, override val editable: Boolean, initial: List<Rule> = emptyList()) :
        ControlRuleStore,
        RuleSource {
        val state = MutableStateFlow(initial)
        override val rules: StateFlow<List<Rule>> get() = state
        override val source: RuleSource get() = this

        override suspend fun upsert(rule: Rule) {
            state.value = state.value.filterNot { it.id == rule.id } + rule
        }

        override suspend fun remove(ruleId: String): Boolean = state.value.any { it.id == ruleId }.also { state.value = state.value.filterNot { r -> r.id == ruleId } }

        override suspend fun clear() {
            state.value = emptyList()
        }

        override suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean {
            val found = state.value.any { it.id == ruleId }
            state.value = state.value.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }
            return found
        }
    }

    private val temporary = TemporaryRuleSource()
    private val saved = FakeStore(RuleSource.PRIORITY_SAVED, editable = true)
    private val preset = FakeStore(
        RuleSource.PRIORITY_PRESET,
        editable = false,
        listOf(Rule("preset-500", matcher = RequestMatcher(path = "/users"), override = Override.Replace(status = 500), enabled = false)),
    )
    private val log = ExchangeLog()
    private val engine = BoggartEngine(RuleRepository(listOf(temporary, saved, preset)), log)
    private val control = BoggartControl(engine, temporary, { saved }, { preset }, { mapOf("packageName" to "com.example") })

    private suspend fun call(method: String, target: String, body: String? = null) = control.handle(ControlRequest(method, target, body))

    private suspend fun ok(method: String, target: String, body: String? = null): JsonObject {
        val response = call(method, target, body)
        assertEquals(200, response.status, response.bodyText())
        return response.body
    }

    private fun JsonObject.errorCode() = getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private suspend fun capture(path: String, status: Int = 200, body: String = """{"ok":true}""") {
        val request = BoggartRequest("GET", "https://api.example.com$path")
        val decision = engine.beforeRequest(request)
        if (decision !is Decision.Respond) {
            engine.afterResponse(request, BoggartResponse(status, body = body.toByteArray(), mediaType = "application/json"), decision)
        }
    }

    @Test
    fun `help lists every command`() = runTest {
        val names = ok("GET", "/").getValue("commands").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(control.commands.map { it.name }, names)
        assertTrue("rules.put" in names && "calls.wait" in names)
    }

    @Test
    fun `status reports counts and app info`() = runTest {
        val status = ok("GET", "/status")
        assertEquals("active", status.getValue("mode").jsonPrimitive.content)
        val rules = status.getValue("rules").jsonObject
        assertEquals(0, rules.getValue("temporary").jsonPrimitive.int)
        assertEquals(1, rules.getValue("preset").jsonPrimitive.int)
        assertEquals(0, rules.getValue("enabled").jsonPrimitive.int)
        assertEquals("com.example", status.getValue("app").jsonObject.getValue("packageName").jsonPrimitive.content)
    }

    @Test
    fun `put creates a temporary rule that the engine applies`() = runTest {
        val body = """{"matcher":{"path":"/users/*"},"override":{"type":"replace","status":404,"body":"{}"}}"""
        val result = ok("PUT", "/rules/user-404", body)
        assertTrue(result.getValue("created").jsonPrimitive.content.toBoolean())
        assertEquals("temporary", result.getValue("rule").jsonObject.getValue("source").jsonPrimitive.content)
        val decision = engine.beforeRequest(BoggartRequest("GET", "https://api.example.com/users/1"))
        assertIs<Decision.Respond>(decision)
        assertEquals(404, decision.response.status)
    }

    @Test
    fun `put without target keeps a saved rule saved`() = runTest {
        saved.upsert(Rule("s", override = Override.Replace(status = 200)))
        val result = ok("PUT", "/rules/s", """{"override":{"type":"replace","status":503}}""")
        assertFalse(result.getValue("created").jsonPrimitive.content.toBoolean())
        assertEquals(503, (saved.state.value.single().override as Override.Replace).status)
        assertTrue(temporary.rules.value.isEmpty())
    }

    @Test
    fun `put accepts a rule read back from get`() = runTest {
        ok("PUT", "/rules/a", """{"override":{"type":"fail","kind":{"type":"timeout"}}}""")
        val rule = ok("GET", "/rules/a").getValue("rule").jsonObject
        ok("PUT", "/rules/a?target=saved", rule.toString())
        assertEquals("a", saved.state.value.single().id)
    }

    @Test
    fun `put rejects unknown fields and mismatched ids`() = runTest {
        assertEquals("INVALID_RULE", call("PUT", "/rules/a", """{"overide":{"type":"replace"}}""").body.errorCode())
        assertEquals("BAD_REQUEST", call("PUT", "/rules/a", """{"id":"b","override":{"type":"replace"}}""").body.errorCode())
        assertEquals("INVALID_JSON", call("PUT", "/rules/a", "{").body.errorCode())
        assertEquals("BAD_REQUEST", call("PUT", "/rules/a?taget=saved", """{"override":{"type":"replace"}}""").body.errorCode())
    }

    @Test
    fun `patch toggles a preset rule but delete refuses it`() = runTest {
        val rule = ok("PATCH", "/rules/preset-500", """{"enabled":true}""").getValue("rule").jsonObject
        assertEquals("true", rule.getValue("enabled").jsonPrimitive.content)
        assertTrue(preset.state.value.single().enabled)
        val delete = call("DELETE", "/rules/preset-500")
        assertEquals(409, delete.status)
        assertEquals("READ_ONLY", delete.body.errorCode())
    }

    @Test
    fun `delete removes temporary and saved rules`() = runTest {
        ok("PUT", "/rules/t", """{"override":{"type":"replace"}}""")
        ok("PUT", "/rules/s?target=saved", """{"override":{"type":"replace"}}""")
        ok("DELETE", "/rules/t")
        ok("DELETE", "/rules/s")
        assertTrue(temporary.rules.value.isEmpty() && saved.state.value.isEmpty())
        assertEquals("RULE_NOT_FOUND", call("DELETE", "/rules/t").body.errorCode())
    }

    @Test
    fun `list follows engine order and filters by source`() = runTest {
        ok("PUT", "/rules/s?target=saved", """{"override":{"type":"replace"}}""")
        ok("PUT", "/rules/t", """{"override":{"type":"replace"}}""")
        val all = ok("GET", "/rules").getValue("rules").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf("t", "s", "preset-500"), all)
        val savedOnly = ok("GET", "/rules?source=saved").getValue("rules").jsonArray
        assertEquals(1, savedOnly.size)
    }

    @Test
    fun `import applies a rules document and replace clears first`() = runTest {
        ok("PUT", "/rules/old", """{"override":{"type":"replace"}}""")
        val doc = """{"version":1,"rules":[{"id":"a","override":{"type":"replace"}},{"id":"b","override":{"type":"replace"}}]}"""
        val result = ok("POST", "/rules/import?replace=true", doc)
        assertEquals(2, result.getValue("imported").jsonPrimitive.int)
        assertEquals(listOf("a", "b"), temporary.rules.value.map { it.id })
        assertEquals("INVALID_RULE", call("POST", "/rules/import", """{"version":2,"rules":[]}""").body.errorCode())
    }

    @Test
    fun `calls list and get expose bodies`() = runTest {
        capture("/users/1")
        capture("/posts/1", body = "plain text")
        val list = ok("GET", "/calls?path=/users/*")
        assertEquals(2, list.getValue("lastId").jsonPrimitive.long)
        val calls = list.getValue("calls").jsonArray
        assertEquals(1, calls.size)
        val id = calls.single().jsonObject.getValue("id").jsonPrimitive.long
        val detail = ok("GET", "/calls/$id").getValue("call").jsonObject
        val body = detail.getValue("deliveredResponse").jsonObject.getValue("body").jsonObject
        assertEquals(JsonPrimitive(true), body.getValue("json").jsonObject.getValue("ok"))
        val text = ok("GET", "/calls/2").getValue("call").jsonObject.getValue("deliveredResponse").jsonObject.getValue("body").jsonObject
        assertEquals("plain text", text.getValue("text").jsonPrimitive.content)
        assertEquals("CALL_NOT_FOUND", call("GET", "/calls/99").body.errorCode())
    }

    @Test
    fun `calls since and limit page through the log`() = runTest {
        repeat(5) { capture("/items/$it") }
        val newer = ok("GET", "/calls?since=3").getValue("calls").jsonArray
        assertEquals(listOf(4L, 5L), newer.map { it.jsonObject.getValue("id").jsonPrimitive.long })
        val latest = ok("GET", "/calls?limit=1").getValue("calls").jsonArray
        assertEquals(5L, latest.single().jsonObject.getValue("id").jsonPrimitive.long)
    }

    @Test
    fun `wait returns the next matching call`() = runTest {
        capture("/users/1")
        val waiting = async { call("GET", "/calls/wait?path=/users/*&timeoutMs=5000") }
        yield()
        capture("/posts/1")
        capture("/users/2")
        val response = waiting.await()
        assertEquals(200, response.status, response.bodyText())
        assertTrue(response.body.getValue("call").jsonObject.getValue("url").jsonPrimitive.content.endsWith("/users/2"))
    }

    @Test
    fun `wait with since finds a call that already arrived`() = runTest {
        capture("/users/1")
        val found = ok("GET", "/calls/wait?since=0&path=/users/*&timeoutMs=0")
        assertEquals(1L, found.getValue("call").jsonObject.getValue("id").jsonPrimitive.long)
    }

    @Test
    fun `wait times out with 408`() = runTest {
        val response = call("GET", "/calls/wait?timeoutMs=100")
        assertEquals(408, response.status)
        assertEquals("TIMEOUT", response.body.errorCode())
    }

    @Test
    fun `mode set and reset`() = runTest {
        ok("PUT", "/mode", """{"mode":"offline"}""")
        assertEquals(Mode.Offline, engine.mode)
        assertEquals("BAD_REQUEST", call("PUT", "/mode", """{"mode":"mixed"}""").body.errorCode())
        ok("PUT", "/rules/t", """{"override":{"type":"replace"}}""")
        capture("/x")
        ok("POST", "/reset")
        assertEquals(Mode.Active, engine.mode)
        assertTrue(temporary.rules.value.isEmpty())
        assertTrue(log.exchanges.value.isEmpty())
    }

    @Test
    fun `clear rules empties one editable source`() = runTest {
        ok("PUT", "/rules/s?target=saved", """{"override":{"type":"replace"}}""")
        assertEquals(1, ok("DELETE", "/rules?source=saved").getValue("removed").jsonPrimitive.int)
        assertEquals("BAD_REQUEST", call("DELETE", "/rules").body.errorCode())
        assertEquals("BAD_REQUEST", call("DELETE", "/rules?source=preset").body.errorCode())
    }

    @Test
    fun `unknown paths and methods are explained`() = runTest {
        assertEquals(404, call("GET", "/nope").status)
        val wrongMethod = call("POST", "/status")
        assertEquals(405, wrongMethod.status)
        assertEquals("METHOD_NOT_ALLOWED", wrongMethod.body.errorCode())
    }

    @Test
    fun `saved target fails when persistence is off`() = runTest {
        val noSaved = BoggartControl(engine, temporary, { null }, { preset })
        val response = noSaved.handle(ControlRequest("PUT", "/rules/a?target=saved", """{"override":{"type":"replace"}}"""))
        assertEquals(409, response.status)
        assertEquals("SAVED_RULES_DISABLED", response.body.errorCode())
    }

    @Test
    fun `path segments and query are percent-decoded`() = runTest {
        ok("PUT", "/rules/user%20404", """{"override":{"type":"replace"}}""")
        assertEquals("user 404", temporary.rules.value.single().id)
        capture("/a%20b")
        assertEquals(1, (ok("GET", "/calls?path=%2Fa%2520b").getValue("calls") as JsonArray).size)
    }
}
