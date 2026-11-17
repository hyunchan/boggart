package io.github.hyunchan.boggart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleRepositoryTest {
    private class FixedSource(override val priority: Int, vararg rules: Rule) : RuleSource {
        override val rules: StateFlow<List<Rule>> = MutableStateFlow(rules.toList())
    }

    private val request = BoggartRequest("GET", "https://api.example.com/users/1")
    private fun rule(id: String, path: String = "/users/*", enabled: Boolean = true) =
        Rule(id, matcher = RequestMatcher(path = path), override = Override.Replace(body = id), enabled = enabled)

    @Test
    fun `higher priority source wins`() {
        val preset = FixedSource(RuleSource.PRIORITY_PRESET, rule("preset"))
        val temporary = TemporaryRuleSource().apply { add(rule("temporary")) }
        val repo = RuleRepository(listOf(preset, temporary))
        assertEquals("temporary", repo.find(request)?.id)
        assertEquals(listOf("temporary", "preset"), repo.snapshot().map { it.id })
    }

    @Test
    fun `first matching enabled rule within a source`() {
        val temporary = TemporaryRuleSource().apply {
            add(rule("disabled", enabled = false))
            add(rule("other", path = "/posts/**"))
            add(rule("hit"))
            add(rule("later"))
        }
        assertEquals("hit", RuleRepository(listOf(temporary)).find(request)?.id)
    }

    @Test
    fun `falls back to lower priority when higher has no match`() {
        val temporary = TemporaryRuleSource().apply { add(rule("temporary", path = "/posts")) }
        val preset = FixedSource(RuleSource.PRIORITY_PRESET, rule("preset"))
        assertEquals("preset", RuleRepository(listOf(temporary, preset)).find(request)?.id)
        assertNull(RuleRepository(listOf(temporary)).find(request))
    }

    @Test
    fun `temporary source add update remove clear`() {
        val temporary = TemporaryRuleSource()
        temporary.add(rule("a"))
        temporary.add(rule("b"))
        temporary.add(rule("a", path = "/x"))
        assertEquals(listOf("a", "b"), temporary.rules.value.map { it.id })
        assertEquals("/x", temporary.rules.value.first().matcher.path)
        assertTrue(temporary.update(rule("b", enabled = false)))
        assertFalse(temporary.update(rule("c")))
        assertFalse(temporary.rules.value[1].enabled)
        assertTrue(temporary.remove("a"))
        assertFalse(temporary.remove("a"))
        temporary.clear()
        assertTrue(temporary.rules.value.isEmpty())
    }

    @Test
    fun `rules flow merges sources`() = runTest {
        val temporary = TemporaryRuleSource().apply { add(rule("temporary")) }
        val repo = RuleRepository(listOf(FixedSource(1, rule("preset")), temporary))
        assertEquals(listOf("temporary", "preset"), repo.rules.first().map { it.id })
        assertEquals(emptyList(), RuleRepository().rules.first())
    }
}
