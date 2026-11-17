package io.github.hyunchan.boggart.android

import io.github.hyunchan.boggart.RuleSource
import io.github.hyunchan.boggart.RulesDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedRuleSourceTest {
    @TempDir
    lateinit var tempDir: File

    private fun file() = File(tempDir, "boggart/saved-rules.json")

    @Test
    fun `upsert remove clear and reload`() = runTest {
        val source = SavedRuleSource(file())
        source.load()
        assertTrue(source.rules.value.isEmpty())
        assertEquals(RuleSource.PRIORITY_SAVED, source.priority)

        source.add(rule("a"))
        source.add(rule("b"))
        source.add(rule("a", path = "/x"))
        assertEquals(listOf("a", "b"), source.rules.value.map { it.id })
        assertEquals("/x", source.rules.value.first().matcher.path)
        assertTrue(source.setEnabled("b", false))
        assertFalse(source.setEnabled("c", false))
        assertTrue(source.remove("a"))
        assertFalse(source.remove("a"))

        val reloaded = SavedRuleSource(file())
        reloaded.load()
        assertEquals(source.rules.value, reloaded.rules.value)
        assertEquals(listOf(rule("b", enabled = false)), RulesDocument.parse(file().readText()).rules)

        source.clear()
        assertTrue(source.rules.value.isEmpty())
        assertTrue(RulesDocument.parse(file().readText()).rules.isEmpty())
    }

    @Test
    fun `writes atomically without leftover temp file`() = runTest {
        val source = SavedRuleSource(file())
        repeat(5) { source.add(rule("r$it")) }
        assertEquals(listOf("saved-rules.json"), file().parentFile?.list()?.toList())
        assertEquals(5, RulesDocument.parse(file().readText()).rules.size)
    }

    @Test
    fun `failed write keeps previous state`() = runTest {
        val blocker = File(tempDir, "blocker").apply { writeText("not a directory") }
        val source = SavedRuleSource(File(blocker, "saved-rules.json"))
        assertFailsWith<IOException> { source.add(rule("a")) }
        assertTrue(source.rules.value.isEmpty())
    }
}
