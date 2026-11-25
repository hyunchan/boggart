package io.github.hyunchan.boggart

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoggartTest {
    private val request = BoggartRequest("GET", "https://api.example.com/users/1")
    private fun rule(id: String, path: String) =
        Rule(id, matcher = RequestMatcher(path = path), override = Override.Replace(body = id))

    @AfterTest
    fun reset() {
        Boggart.riddikulus()
        Boggart.exchangeLog.clear()
        Boggart.configure {
            mode = Mode.Active
            onInvalidPatch = OnInvalidPatch.Ignore
            ruleSources.clear()
        }
    }

    @Test
    fun `version comes from the build`() {
        assertTrue(Regex("""\d+\.\d+\.\d+(-SNAPSHOT)?""").matches(Boggart.VERSION), Boggart.VERSION)
    }

    @Test
    fun `riddikulus removes all temporary rules`() = runTest {
        Boggart.temporaryRules.add(rule("a", "/users/*"))
        Boggart.temporaryRules.add(rule("b", "/posts"))
        assertIs<Decision.Respond>(Boggart.engine.beforeRequest(request))
        Boggart.riddikulus()
        assertTrue(Boggart.temporaryRules.rules.value.isEmpty())
        assertNull(assertIs<Decision.Proceed>(Boggart.engine.beforeRequest(request)).rule)
    }

    @Test
    fun `riddikulus by id removes one temporary rule`() = runTest {
        Boggart.temporaryRules.add(rule("a", "/users/*"))
        Boggart.temporaryRules.add(rule("b", "/users/**"))
        assertTrue(Boggart.riddikulus("a"))
        assertFalse(Boggart.riddikulus("a"))
        val decision = assertIs<Decision.Respond>(Boggart.engine.beforeRequest(request))
        assertEquals("b", decision.rule?.id)
    }

    @Test
    fun `riddikulus keeps other sources`() = runTest {
        val preset = TemporaryRuleSource(RuleSource.PRIORITY_PRESET).apply { add(rule("preset", "/users/*")) }
        Boggart.configure { ruleSource(preset) }
        Boggart.temporaryRules.add(rule("temporary", "/users/*"))
        Boggart.riddikulus()
        assertEquals("preset", assertIs<Decision.Respond>(Boggart.engine.beforeRequest(request)).rule?.id)
    }

    @Test
    fun `configure keeps unspecified settings`() {
        Boggart.configure {
            mode = Mode.Paused
            exchangeLogCapacity = 10
        }
        Boggart.configure { onInvalidPatch = OnInvalidPatch.PassThrough }
        assertEquals(Mode.Paused, Boggart.mode)
        assertEquals(10, Boggart.exchangeLog.capacity)
        assertEquals(OnInvalidPatch.PassThrough, Boggart.engine.onInvalidPatch)
        assertEquals(listOf<RuleSource>(Boggart.temporaryRules), Boggart.engine.repository.sources.value)
        Boggart.configure { exchangeLogCapacity = ExchangeLog.DEFAULT_CAPACITY }
    }
}
