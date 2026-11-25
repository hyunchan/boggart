package io.github.hyunchan.boggart

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoggartEngineTest {
    private val temporary = TemporaryRuleSource()
    private val log = ExchangeLog()
    private var now = 1000L
    private val engine = BoggartEngine(RuleRepository(listOf(temporary)), log, clock = { now })

    private val request = BoggartRequest("GET", "https://api.example.com/users/1")
    private val original = BoggartResponse(
        status = 200,
        headers = BoggartHeaders.of("Content-Length" to "25"),
        body = """{"id":1,"name":"Harry"}""".toByteArray(),
        mediaType = "application/json",
    )

    private fun addRule(override: Override, id: String = "r1") =
        temporary.add(Rule(id, matcher = RequestMatcher(path = "/users/*"), override = override))

    private fun patch(json: String, delayMs: Long = 0) = Override.Patch(Json.parseToJsonElement(json), delayMs)

    @Test
    fun `no rule proceeds and records original`() = runTest {
        val decision = engine.beforeRequest(request)
        assertNull(assertIs<Decision.Proceed>(decision).rule)
        assertSame(original, engine.afterResponse(request, original, decision))
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Original, exchange.provenance)
        assertEquals(original, exchange.originalResponse)
        assertEquals(1000L, exchange.timestampMs)
        assertNull(exchange.appliedRuleId)
    }

    @Test
    fun `replace responds without network`() = runTest {
        addRule(Override.Replace(status = 404, headers = mapOf("X-A" to "1"), body = """{"e":1}""", delayMs = 300))
        val decision = assertIs<Decision.Respond>(engine.beforeRequest(request))
        assertEquals(404, decision.response.status)
        assertEquals("1", decision.response.headers["x-a"])
        assertEquals("""{"e":1}""", decision.response.bodyAsString())
        assertEquals("application/json", decision.response.mediaType)
        assertEquals(Provenance.Replaced, decision.provenance)
        assertEquals(300, decision.delayMs)
        // 어댑터가 지연을 마치고 앱에 넘길 때 기록한다. 그 전에는 /calls/wait가 돌아오면 안 된다.
        assertTrue(log.exchanges.value.isEmpty())
        now = 1300L
        engine.onDelivered(request, decision)
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Replaced, exchange.provenance)
        assertNull(exchange.originalResponse)
        assertEquals("r1", exchange.appliedRuleId)
        assertEquals(1000L, exchange.startedAtMs)
        assertEquals(1300L, exchange.timestampMs)
    }

    @Test
    fun `records are ordered by when the app got them`() = runTest {
        addRule(Override.Replace(status = 200, delayMs = 500))
        val slow = engine.beforeRequest(request)
        val other = BoggartRequest("GET", "https://api.example.com/posts")
        val fast = engine.beforeRequest(other)
        now = 1100L
        engine.afterResponse(other, original, fast)
        now = 1500L
        engine.onDelivered(request, slow)
        assertEquals(listOf("/posts", "/users/1"), log.exchanges.value.map { it.request.url.path })
        assertEquals(listOf(1L, 2L), log.exchanges.value.map { it.id })
        assertEquals(listOf(1000L, 1000L), log.exchanges.value.map { it.startedAtMs })
    }

    @Test
    fun `replace with body file uses resolver`() = runTest {
        addRule(Override.Replace(bodyFile = "boggart/bodies/user.json"))
        engine.bodyFileResolver = BodyFileResolver { path -> if (path == "boggart/bodies/user.json") "{}".toByteArray() else null }
        val decision = assertIs<Decision.Respond>(engine.beforeRequest(request))
        assertEquals("{}", decision.response.bodyAsString())
    }

    @Test
    fun `replace with unresolved body file proceeds and records invalid`() = runTest {
        addRule(Override.Replace(bodyFile = "missing.json", delayMs = 50))
        val decision = assertIs<Decision.Proceed>(engine.beforeRequest(request))
        assertEquals("r1", decision.rule?.id)
        assertEquals(50, decision.delayMs)
        assertTrue(log.exchanges.value.isEmpty())
        assertSame(original, engine.afterResponse(request, original, decision))
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.InvalidIgnored, exchange.provenance)
        assertEquals("r1", exchange.appliedRuleId)
    }

    @Test
    fun `network failure after proceed is recorded`() = runTest {
        addRule(patch("""{"a":1}"""))
        val decision = engine.beforeRequest(request)
        engine.onFailure(request, decision, java.io.IOException("refused"))
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Original, exchange.provenance)
        assertEquals("java.io.IOException: refused", exchange.error)
        assertNull(exchange.originalResponse)
        assertNull(exchange.deliveredResponse)
        assertNull(exchange.appliedRuleId)
    }

    @Test
    fun `network failure is not recorded for bypass`() = runTest {
        engine.mode = Mode.Off
        engine.onFailure(request, engine.beforeRequest(request), java.io.IOException("refused"))
        assertTrue(log.exchanges.value.isEmpty())
    }

    @Test
    fun `fail decision`() = runTest {
        addRule(Override.Fail(FailKind.Timeout, delayMs = 5000))
        val decision = assertIs<Decision.Fail>(engine.beforeRequest(request))
        assertEquals(FailKind.Timeout, decision.kind)
        assertEquals(5000, decision.delayMs)
        // 지연을 마치고 앱에 넘길 때 기록한다.
        assertTrue(log.exchanges.value.isEmpty())
        engine.onDelivered(request, decision)
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Failed, exchange.provenance)
        assertNull(exchange.deliveredResponse)
    }

    @Test
    fun `patch applies merge patch after response`() = runTest {
        addRule(patch("""{"name":"Boggart","id":null}""", delayMs = 10))
        val decision = assertIs<Decision.Proceed>(engine.beforeRequest(request))
        assertEquals("r1", decision.rule?.id)
        assertEquals(10, decision.delayMs)
        val delivered = engine.afterResponse(request, original, decision)
        assertEquals("""{"name":"Boggart"}""", delivered.bodyAsString())
        assertEquals("18", delivered.headers["Content-Length"])
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Patched, exchange.provenance)
        assertEquals(original, exchange.originalResponse)
        assertEquals(delivered, exchange.deliveredResponse)
    }

    @Test
    fun `invalid patch is ignored by default`() = runTest {
        addRule(patch("""{"name":42}"""))
        engine.validator = BodyValidator { body, _ -> !body.toString(Charsets.UTF_8).contains("42") }
        val delivered = engine.afterResponse(request, original, engine.beforeRequest(request))
        assertSame(original, delivered)
        assertEquals(Provenance.InvalidIgnored, log.exchanges.value.single().provenance)
    }

    @Test
    fun `invalid patch passes through when configured`() = runTest {
        addRule(patch("""{"name":42}"""))
        engine.validator = BodyValidator { _, _ -> false }
        engine.onInvalidPatch = OnInvalidPatch.PassThrough
        val delivered = engine.afterResponse(request, original, engine.beforeRequest(request))
        assertEquals("""{"id":1,"name":42}""", delivered.bodyAsString())
        assertEquals(Provenance.PatchedInvalid, log.exchanges.value.single().provenance)
    }

    @Test
    fun `rule level invalid policy overrides engine policy`() = runTest {
        engine.validator = BodyValidator { _, _ -> false }
        temporary.add(Rule("r1", matcher = RequestMatcher(path = "/users/*"), override = Override.Patch(Json.parseToJsonElement("""{"name":42}"""), onInvalid = OnInvalidPatch.PassThrough)))
        assertEquals("""{"id":1,"name":42}""", engine.afterResponse(request, original, engine.beforeRequest(request)).bodyAsString())

        engine.onInvalidPatch = OnInvalidPatch.PassThrough
        temporary.add(Rule("r1", matcher = RequestMatcher(path = "/users/*"), override = Override.Patch(Json.parseToJsonElement("""{"name":42}"""), onInvalid = OnInvalidPatch.Ignore)))
        assertSame(original, engine.afterResponse(request, original, engine.beforeRequest(request)))
        assertEquals(listOf(Provenance.PatchedInvalid, Provenance.InvalidIgnored), log.exchanges.value.map { it.provenance })
    }

    @Test
    fun `patch on non json body is ignored`() = runTest {
        addRule(patch("""{"a":1}"""))
        engine.onInvalidPatch = OnInvalidPatch.PassThrough
        val html = original.copy(body = "<html>".toByteArray(), mediaType = "text/html")
        assertSame(html, engine.afterResponse(request, html, engine.beforeRequest(request)))
        assertEquals(Provenance.InvalidIgnored, log.exchanges.value.single().provenance)
    }

    @Test
    fun `json patch replaces only the given path`() = runTest {
        addRule(Override.JsonPatch(listOf(JsonPatchOp(JsonPatchOp.Op.Replace, "/name", JsonPrimitive("Boggart"))), delayMs = 7))
        val decision = assertIs<Decision.Proceed>(engine.beforeRequest(request))
        assertEquals(7, decision.delayMs)
        val delivered = engine.afterResponse(request, original, decision)
        assertEquals("""{"id":1,"name":"Boggart"}""", delivered.bodyAsString())
        assertEquals("25", delivered.headers["Content-Length"])
        assertEquals(Provenance.Patched, log.exchanges.value.single().provenance)
    }

    @Test
    fun `json patch with missing path delivers original even when pass through`() = runTest {
        addRule(Override.JsonPatch(listOf(JsonPatchOp(JsonPatchOp.Op.Replace, "/missing", JsonPrimitive(1)))))
        engine.onInvalidPatch = OnInvalidPatch.PassThrough
        assertSame(original, engine.afterResponse(request, original, engine.beforeRequest(request)))
        assertEquals(Provenance.InvalidIgnored, log.exchanges.value.single().provenance)
    }

    @Test
    fun `json patch follows invalid policy`() = runTest {
        engine.validator = BodyValidator { _, _ -> false }
        val op = JsonPatchOp(JsonPatchOp.Op.Replace, "/name", JsonPrimitive(42))
        addRule(Override.JsonPatch(listOf(op), onInvalid = OnInvalidPatch.PassThrough))
        assertEquals("""{"id":1,"name":42}""", engine.afterResponse(request, original, engine.beforeRequest(request)).bodyAsString())
        addRule(Override.JsonPatch(listOf(op)))
        assertSame(original, engine.afterResponse(request, original, engine.beforeRequest(request)))
        assertEquals(listOf(Provenance.PatchedInvalid, Provenance.InvalidIgnored), log.exchanges.value.map { it.provenance })
    }

    @Test
    fun `json syntax validator`() {
        assertTrue(JsonSyntaxValidator.validate("""{"a":[1]}""".toByteArray(), null))
        assertTrue(JsonSyntaxValidator.validate("""[-1.5e3,true,null,"x"]""".toByteArray(), null))
        listOf("""{"a":""", "<html>", """{"a":foo}""", "01", "").forEach {
            assertEquals(false, JsonSyntaxValidator.validate(it.toByteArray(), null), it)
        }
    }

    @Test
    fun `mode off bypasses everything`() = runTest {
        addRule(Override.Fail(FailKind.Timeout))
        engine.mode = Mode.Off
        val decision = engine.beforeRequest(request)
        assertEquals(Decision.Bypass, decision)
        assertSame(original, engine.afterResponse(request, original, decision))
        assertTrue(log.exchanges.value.isEmpty())
    }

    @Test
    fun `paused mode captures without overriding`() = runTest {
        addRule(Override.Fail(FailKind.Timeout))
        engine.mode = Mode.Paused
        val decision = engine.beforeRequest(request)
        assertNull(assertIs<Decision.Proceed>(decision).rule)
        engine.afterResponse(request, original, decision)
        assertEquals(Provenance.Original, log.exchanges.value.single().provenance)
    }

    @Test
    fun `offline mode fails unmatched requests as unknown host`() = runTest {
        engine.mode = Mode.Offline
        val decision = assertIs<Decision.Fail>(engine.beforeRequest(request))
        assertEquals(FailKind.UnknownHost, decision.kind)
        assertNull(decision.rule)
        engine.onDelivered(request, decision)
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Failed, exchange.provenance)
        assertEquals("Offline mode: no rule for this call", exchange.error)

        addRule(Override.Replace(body = "{}"))
        assertIs<Decision.Respond>(engine.beforeRequest(request))
    }

    @Test
    fun `active mode passes unmatched and overrides matched`() = runTest {
        addRule(Override.Replace(body = "{}"))
        assertIs<Decision.Respond>(engine.beforeRequest(request))
        assertNull(assertIs<Decision.Proceed>(engine.beforeRequest(BoggartRequest("GET", "https://api.example.com/posts"))).rule)
    }

    @Test
    fun `exchange ids increase`() = runTest {
        repeat(3) { engine.afterResponse(request, original, engine.beforeRequest(request)) }
        assertEquals(listOf(1L, 2L, 3L), log.exchanges.value.map { it.id })
    }
}
