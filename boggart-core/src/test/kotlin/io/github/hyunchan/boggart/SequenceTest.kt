package io.github.hyunchan.boggart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SequenceTest {
    private val temporary = TemporaryRuleSource()
    private val log = ExchangeLog()
    private val engine = BoggartEngine(RuleRepository(listOf(temporary)), log)

    private val request = BoggartRequest("GET", "https://api.example.com/users/1")
    private val original = BoggartResponse(200, body = """{"name":"Harry"}""".toByteArray(), mediaType = "application/json")

    private fun status(code: Int) = Override.Replace(status = code)

    private fun steps(vararg overrides: Override) = overrides.map(::SequenceStep)

    private fun addSequence(vararg steps: Override, then: SequenceEnd = SequenceEnd.Last, id: String = "seq"): Rule =
        addSteps(steps(*steps), then, id)

    private fun addSteps(steps: List<SequenceStep>, then: SequenceEnd = SequenceEnd.Last, id: String = "seq"): Rule =
        Rule(id, matcher = RequestMatcher(path = "/users/*"), override = Override.Sequence(steps, then)).also(temporary::add)

    /** 한 번 호출하고 앱이 받은 상태 코드를 돌려준다. 실패면 -1, 실제 서버면 0. */
    private suspend fun callOnce(): Int = when (val decision = engine.beforeRequest(request)) {
        is Decision.Respond -> decision.response.status.also { engine.onDelivered(request, decision) }
        is Decision.Fail -> -1.also { engine.onDelivered(request, decision) }
        is Decision.Proceed -> engine.afterResponse(request, original, decision).let { if (decision.rule == null) 0 else it.status }
        Decision.Bypass -> 0
    }

    private suspend fun calls(count: Int) = List(count) { callOnce() }

    @Test
    fun `steps answer successive calls and the last one stays`() = runTest {
        addSequence(status(500), status(503), status(200))
        assertEquals(listOf(500, 503, 200, 200, 200), calls(5))
        assertEquals(listOf(0, 1, 2, 2, 2), log.exchanges.value.map { it.step })
        assertEquals(listOf("seq"), log.exchanges.value.map { it.appliedRuleId }.distinct())
    }

    @Test
    fun `loop starts again from the first step`() = runTest {
        addSequence(status(500), status(200), then = SequenceEnd.Loop)
        assertEquals(listOf(500, 200, 500, 200, 500), calls(5))
    }

    @Test
    fun `original lets calls through once the steps are used`() = runTest {
        addSequence(status(500), then = SequenceEnd.Original)
        assertEquals(listOf(500, 0, 0), calls(3))
        val passed = log.exchanges.value.last()
        assertEquals(Provenance.Original, passed.provenance)
        assertNull(passed.appliedRuleId)
        assertNull(passed.step)
    }

    @Test
    fun `original fails like an unmatched call in offline mode`() = runTest {
        engine.mode = Mode.Offline
        addSequence(status(500), then = SequenceEnd.Original)
        assertEquals(listOf(500, -1), calls(2))
        assertEquals("Offline mode: no rule for this call", log.exchanges.value.last().error)
    }

    @Test
    fun `patch and fail steps work with their own delay`() = runTest {
        addSequence(
            Override.Fail(FailKind.Timeout, delayMs = 2000),
            Override.Patch(Json.parseToJsonElement("""{"name":"Ron"}"""), delayMs = 300),
        )
        val failed = engine.beforeRequest(request)
        assertEquals(2000, failed.delayMs)
        engine.onDelivered(request, failed)
        val decision = assertIs<Decision.Proceed>(engine.beforeRequest(request))
        assertEquals(300, decision.delayMs)
        assertEquals(1, decision.step)
        val delivered = engine.afterResponse(request, original, decision)
        assertEquals("""{"name":"Ron"}""", delivered.bodyAsString())
        assertEquals(listOf(Provenance.Failed, Provenance.Patched), log.exchanges.value.map { it.provenance })
        assertEquals(listOf(0, 1), log.exchanges.value.map { it.step })
    }

    @Test
    fun `a step is used up even when its patch can't be applied`() = runTest {
        addSequence(Override.JsonPatch(listOf(JsonPatchOp(JsonPatchOp.Op.Replace, "/missing", JsonPrimitive(1)))), status(200))
        assertEquals(listOf(200, 200), calls(2))
        assertEquals(Provenance.InvalidIgnored, log.exchanges.value.first().provenance)
        assertEquals(0, log.exchanges.value.first().step)
    }

    @Test
    fun `paused and off don't move the cursor`() = runTest {
        val rule = addSequence(status(500), status(200))
        engine.mode = Mode.Paused
        calls(2)
        engine.mode = Mode.Off
        calls(2)
        assertEquals(0, engine.nextStep(rule))
        engine.mode = Mode.Active
        assertEquals(listOf(500, 200), calls(2))
    }

    @Test
    fun `changing the rule starts it over`() = runTest {
        val rule = addSequence(status(500), status(200))
        calls(1)
        assertEquals(1, engine.nextStep(rule))
        temporary.update(rule.copy(enabled = false))
        temporary.update(rule.copy(enabled = true))
        assertEquals(0, engine.nextStep(temporary.rules.value.single()))
        assertEquals(listOf(500), calls(1))
    }

    @Test
    fun `another rule changing keeps the cursor`() = runTest {
        addSequence(status(500), status(200))
        calls(1)
        temporary.add(Rule("other", matcher = RequestMatcher(path = "/posts"), override = status(404)))
        assertEquals(listOf(200), calls(1))
    }

    @Test
    fun `reset starts sequences over`() = runTest {
        val rule = addSequence(status(500), status(200))
        calls(1)
        engine.resetSequence(rule.id)
        assertEquals(listOf(500), calls(1))
        engine.resetSequences()
        assertEquals(listOf(500), calls(1))
    }

    @Test
    fun `concurrent calls each get their own step`() = runTest {
        val steps = (1..50).map { status(100 + it) }.toTypedArray()
        addSequence(*steps)
        val statuses = withContext(Dispatchers.Default) { List(50) { async { callOnce() } }.awaitAll() }
        assertEquals((101..150).toSet(), statuses.toSet())
    }

    @Test
    fun `sequences need steps and can't nest`() {
        assertFailsWith<IllegalArgumentException> { Override.Sequence(emptyList()) }
        assertFailsWith<IllegalArgumentException> { SequenceStep(Override.Sequence(steps(status(200)))) }
        assertFailsWith<IllegalArgumentException> { SequenceStep(status(200), times = 0) }
        assertFailsWith<IllegalArgumentException> { SequenceStep(status(200), times = SequenceStep.MAX_TIMES + 1) }
    }

    @Test
    fun `sequence round-trips through the rules document`() {
        val text = """
            {"rules":[{"id":"retry","override":{"type":"sequence","then":"loop","steps":[
              {"type":"replace","status":500},{"type":"fail","kind":{"type":"timeout"},"delayMs":100}
            ]}}]}
        """.trimIndent()
        val rule = RulesDocument.parse(text).rules.single()
        assertEquals(Override.Sequence(steps(status(500), Override.Fail(FailKind.Timeout, 100)), SequenceEnd.Loop), rule.override)
        assertEquals(rule, RulesDocument.parse(RulesDocument(rules = listOf(rule)).encode()).rules.single())
        assertFailsWith<IllegalArgumentException> {
            RulesDocument.parse("""{"rules":[{"id":"x","override":{"type":"sequence","steps":[]}}]}""")
        }
    }

    @Test
    fun `a step with times is used that many calls in a row`() = runTest {
        val rule = addSteps(listOf(SequenceStep(status(500), times = 3), SequenceStep(status(200))))
        assertEquals(listOf(500, 500), calls(2))
        assertEquals(0, engine.nextStep(rule))
        assertEquals(listOf(500, 200, 200), calls(3))
        assertEquals(listOf(0, 0, 0, 1, 1), log.exchanges.value.map { it.step })
        assertEquals(2, engine.nextStep(rule))
    }

    @Test
    fun `times works with loop and original`() = runTest {
        addSteps(listOf(SequenceStep(status(500), times = 2), SequenceStep(status(200))), SequenceEnd.Loop)
        assertEquals(listOf(500, 500, 200, 500, 500, 200), calls(6))
        temporary.clear()
        addSteps(listOf(SequenceStep(Override.Fail(FailKind.Timeout), times = 2)), SequenceEnd.Original, id = "limited")
        assertEquals(listOf(-1, -1, 0, 0), calls(4))
    }

    @Test
    fun `steps with times round-trip and single steps stay plain`() {
        val text = """{"rules":[{"id":"r","override":{"type":"sequence","steps":[
            {"times":3,"override":{"type":"replace","status":500}},
            {"type":"replace","status":200}
        ]}}]}"""
        val rule = RulesDocument.parse(text).rules.single()
        assertEquals(Override.Sequence(listOf(SequenceStep(status(500), 3), SequenceStep(status(200)))), rule.override)
        val encoded = RulesDocument(rules = listOf(rule)).encode()
        assertEquals(rule, RulesDocument.parse(encoded).rules.single())
        assertTrue("\"times\": 3" in encoded, encoded)
        assertEquals(1, Regex("\"times\"").findAll(encoded).count(), encoded)
    }
}
