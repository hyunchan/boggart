package io.github.hyunchan.boggart.android

import io.github.hyunchan.boggart.Boggart
import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.Decision
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.Provenance
import io.github.hyunchan.boggart.RuleSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoggartAndroidTest {
    @TempDir
    lateinit var tempDir: File

    private val errors = mutableListOf<Throwable>()
    private val request = BoggartRequest("GET", "https://api.example.com/users/1")

    private fun install(assets: Map<String, String> = emptyMap(), block: BoggartAndroid.Config.() -> Unit = {}) =
        BoggartAndroid.install(FakeAssets(assets), tempDir, { _, e -> errors += e }, BoggartAndroid.Config().apply(block))

    @AfterTest
    fun reset() {
        Boggart.riddikulus()
        Boggart.exchangeLog.clear()
        Boggart.configure {
            ruleSources.clear()
            bodyFileResolver = null
            mode = Mode.Active
        }
    }

    @Test
    fun `install registers sources and resolver`() = runTest {
        install(
            mapOf(
                "boggart/rules.json" to rulesJson(rule("preset").copy(override = Override.Replace(bodyFile = "user.json"))),
                "boggart/bodies/user.json" to """{"from":"preset"}""",
            ),
        ) { mode = Mode.Offline }
        BoggartAndroid.awaitLoaded()
        assertEquals(Mode.Offline, Boggart.mode)
        assertEquals(
            listOf(RuleSource.PRIORITY_TEMPORARY, RuleSource.PRIORITY_SAVED, RuleSource.PRIORITY_PRESET),
            Boggart.engine.repository.sources.value.map { it.priority },
        )
        val decision = assertIs<Decision.Respond>(Boggart.engine.beforeRequest(request))
        assertEquals("""{"from":"preset"}""", decision.response.bodyAsString())
    }

    @Test
    fun `missing body file is recorded as invalid ignored`() = runTest {
        install(mapOf("boggart/rules.json" to rulesJson(rule("preset").copy(override = Override.Replace(bodyFile = "nope.json")))))
        BoggartAndroid.awaitLoaded()
        val decision = Boggart.engine.beforeRequest(request)
        assertIs<Decision.Proceed>(decision)
        Boggart.engine.afterResponse(request, io.github.hyunchan.boggart.BoggartResponse(200), decision)
        assertEquals(Provenance.InvalidIgnored, Boggart.exchangeLog.exchanges.value.single().provenance)
    }

    @Test
    fun `second install replaces previous sources`() = runTest {
        install()
        val firstSaved = BoggartAndroid.savedRules
        install()
        BoggartAndroid.awaitLoaded()
        val sources = Boggart.engine.repository.sources.value
        assertEquals(3, sources.size)
        assertTrue(sources.none { it === firstSaved })
        assertSame<RuleSource?>(BoggartAndroid.savedRules, sources[1])
    }

    @Test
    fun `install without assets and persistence`() = runTest {
        install {
            loadAssets = false
            persistence = false
        }
        BoggartAndroid.awaitLoaded()
        assertNull(BoggartAndroid.presetRules)
        assertNull(BoggartAndroid.savedRules)
        assertEquals(listOf<RuleSource>(Boggart.temporaryRules), Boggart.engine.repository.sources.value)
        assertFailsWith<IllegalStateException> { BoggartAndroid.promote(rule("a")) }
        assertTrue(tempDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `promote moves temporary rule to saved and demote removes it`() = runTest {
        install()
        BoggartAndroid.awaitLoaded()
        val temporary = rule("temporary")
        Boggart.temporaryRules.add(temporary)
        BoggartAndroid.promote(temporary)
        assertTrue(Boggart.temporaryRules.rules.value.isEmpty())
        assertEquals(listOf(temporary), BoggartAndroid.savedRules?.rules?.value)
        assertTrue(File(tempDir, "boggart/saved-rules.json").exists())

        Boggart.riddikulus()
        assertEquals("temporary", assertIs<Decision.Respond>(Boggart.engine.beforeRequest(request)).rule?.id)

        assertTrue(BoggartAndroid.demote("temporary"))
        assertNull(assertIs<Decision.Proceed>(Boggart.engine.beforeRequest(request)).rule)
    }

    @Test
    fun `saved rules survive reinstall`() = runTest {
        install()
        BoggartAndroid.awaitLoaded()
        BoggartAndroid.savedRules?.add(rule("kept"))
        install()
        BoggartAndroid.awaitLoaded()
        assertEquals(listOf("kept"), BoggartAndroid.savedRules?.rules?.value?.map { it.id })
    }

    @Test
    fun `load failure is logged and rethrown by awaitLoaded`() = runTest {
        install(mapOf("boggart/rules.json" to "{broken"))
        assertFailsWith<Exception> { BoggartAndroid.awaitLoaded() }
        assertEquals(1, errors.size)
    }
}
