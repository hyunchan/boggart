package io.github.hyunchan.boggart.android

import io.github.hyunchan.boggart.RuleSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetRuleSourceTest {
    @TempDir
    lateinit var tempDir: File

    private val assets = FakeAssets(
        mapOf(
            "boggart/rules.json" to rulesJson(rule("preset-404", enabled = false), rule("on")),
            "boggart/bodies/user.json" to """{"id":1}""",
            "custom/rules.json" to rulesJson(rule("custom")),
        ),
    )

    private fun overridesFile() = File(tempDir, "boggart/preset-overrides.json")

    @Test
    fun `loads rules json with preset priority`() = runTest {
        val source = PresetRuleSource(assets, overridesFile())
        assertTrue(source.rules.value.isEmpty())
        source.load()
        assertEquals(listOf("preset-404", "on"), source.rules.value.map { it.id })
        assertEquals(listOf(false, true), source.rules.value.map { it.enabled })
        assertEquals(RuleSource.PRIORITY_PRESET, source.priority)
    }

    @Test
    fun `missing rules json yields empty list and custom dir is used`() = runTest {
        val missing = PresetRuleSource(FakeAssets(emptyMap()), null)
        missing.load()
        assertTrue(missing.rules.value.isEmpty())

        val custom = PresetRuleSource(assets, null, dir = "custom")
        custom.load()
        assertEquals(listOf("custom"), custom.rules.value.map { it.id })
    }

    @Test
    fun `enabled toggle is persisted and restored`() = runTest {
        val source = PresetRuleSource(assets, overridesFile())
        source.load()
        assertTrue(source.setEnabled("preset-404", true))
        assertFalse(source.setEnabled("unknown", true))
        assertTrue(source.rules.value.first().enabled)
        assertTrue(overridesFile().exists())

        val reloaded = PresetRuleSource(assets, overridesFile())
        reloaded.load()
        assertEquals(listOf(true, true), reloaded.rules.value.map { it.enabled })
    }

    @Test
    fun `orphan toggles are dropped on load`() = runTest {
        overridesFile().apply { parentFile?.mkdirs() }.writeText("""{"on":false,"gone":true}""")
        val source = PresetRuleSource(assets, overridesFile())
        source.load()
        assertFalse(source.rules.value[1].enabled)
        assertEquals("""{"on":false}""", overridesFile().readText().replace(Regex("\\s"), ""))

        val modified = overridesFile().lastModified()
        PresetRuleSource(assets, overridesFile()).load()
        assertEquals(modified, overridesFile().lastModified())
    }

    @Test
    fun `toggle without overrides file stays in memory`() = runTest {
        val source = PresetRuleSource(assets, null)
        source.load()
        assertTrue(source.setEnabled("on", false))
        assertFalse(source.rules.value[1].enabled)
        assertTrue(tempDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `unsupported version fails load`() = runTest {
        val source = PresetRuleSource(FakeAssets(mapOf("boggart/rules.json" to """{"version":9,"rules":[]}""")), null)
        assertFailsWith<IllegalArgumentException> { source.load() }
    }

    @Test
    fun `body file resolver reads bodies dir`() = runTest {
        val resolver = PresetBodyFileResolver(assets)
        assertContentEquals("""{"id":1}""".toByteArray(), resolver.resolve("user.json"))
        assertNull(resolver.resolve("missing.json"))
        assertEquals(listOf("boggart/bodies/user.json", "boggart/bodies/missing.json"), assets.opened)
    }
}
