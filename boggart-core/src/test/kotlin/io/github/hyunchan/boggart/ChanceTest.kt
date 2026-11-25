package io.github.hyunchan.boggart

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ChanceTest {
    /** 정해 둔 값을 차례로 돌려주는 난수. */
    private class Rolls(vararg values: Int) : Random() {
        private val queue = ArrayDeque(values.toList())

        override fun nextBits(bitCount: Int): Int = error("not used")

        override fun nextInt(until: Int): Int = queue.removeFirst()
    }

    private val temporary = TemporaryRuleSource()
    private val log = ExchangeLog()
    private val request = BoggartRequest("GET", "https://api.example.com/users/1")
    private val original = BoggartResponse(200, body = "{}".toByteArray(), mediaType = "application/json")
    private val timeout = Override.Fail(FailKind.Timeout, delayMs = 500)

    private fun engine(vararg rolls: Int) = BoggartEngine(RuleRepository(listOf(temporary)), log, random = Rolls(*rolls))

    private fun add(override: Override) = temporary.add(Rule("c", matcher = RequestMatcher(path = "/users/*"), override = override))

    @Test
    fun `a roll under the percent applies the override`() = runTest {
        add(Override.Chance(30, timeout))
        val engine = engine(29, 30)
        val hit = assertIs<Decision.Fail>(engine.beforeRequest(request))
        assertEquals(500, hit.delayMs)
        engine.onDelivered(request, hit)
        val miss = assertIs<Decision.Proceed>(engine.beforeRequest(request))
        assertNull(miss.override)
        assertEquals(0, miss.delayMs)
        engine.afterResponse(request, original, miss)
        assertEquals(listOf(Provenance.Failed, Provenance.Original), log.exchanges.value.map { it.provenance })
        // 걸리지 않은 호출도 어느 규칙이 판정했는지 남는다.
        assertEquals(listOf("c", "c"), log.exchanges.value.map { it.appliedRuleId })
    }

    @Test
    fun `a chance can be a sequence step`() = runTest {
        add(Override.Sequence(listOf(SequenceStep(Override.Chance(50, timeout), times = 2), SequenceStep(Override.Replace(status = 200)))))
        val engine = engine(10, 90)
        assertIs<Decision.Fail>(engine.beforeRequest(request))
        val miss = assertIs<Decision.Proceed>(engine.beforeRequest(request))
        assertEquals(0, miss.step)
        assertIs<Decision.Respond>(engine.beforeRequest(request))
    }

    @Test
    fun `percent and nesting are checked`() {
        assertFailsWith<IllegalArgumentException> { Override.Chance(0, timeout) }
        assertFailsWith<IllegalArgumentException> { Override.Chance(100, timeout) }
        assertFailsWith<IllegalArgumentException> { Override.Chance(50, Override.Chance(50, timeout)) }
        assertFailsWith<IllegalArgumentException> { Override.Chance(50, Override.Sequence(listOf(SequenceStep(timeout)))) }
    }

    @Test
    fun `chance round-trips`() {
        val text = """{"rules":[{"id":"c","override":{"type":"chance","percent":30,"override":{"type":"fail","kind":{"type":"timeout"}}}}]}"""
        val rule = RulesDocument.parse(text).rules.single()
        assertEquals(Override.Chance(30, Override.Fail(FailKind.Timeout)), rule.override)
        assertEquals(rule, RulesDocument.parse(RulesDocument(rules = listOf(rule)).encode()).rules.single())
    }
}
