package io.github.hyunchan.boggart

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 파일에서 비동기로 읽는 출처를 흉내 낸다. [finishLoading]을 부를 때까지 준비되지 않는다. */
private class SlowSource(private val pending: List<Rule>) : RuleSource {
    private val state = MutableStateFlow<List<Rule>>(emptyList())
    private val loaded = CompletableDeferred<Unit>()
    override val rules: StateFlow<List<Rule>> = state
    override val priority: Int = RuleSource.PRIORITY_PRESET

    override suspend fun awaitReady(): Unit = loaded.await()

    override val isReady: Boolean get() = loaded.isCompleted

    fun finishLoading() {
        state.value = pending
        loaded.complete(Unit)
    }
}

class RulesReadyTest {
    private val request = BoggartRequest("GET", "https://api.example.com/users/1")
    private val rule = Rule("p", matcher = RequestMatcher(path = "/users/*"), override = Override.Fail(FailKind.Timeout))

    @Test
    fun `a request made while rules are loading waits and then matches`() = runTest {
        val source = SlowSource(listOf(rule))
        val engine = BoggartEngine(RuleRepository(listOf(source)))
        launch {
            delay(100)
            source.finishLoading()
        }
        val decision = engine.beforeRequest(request)
        assertIs<Decision.Fail>(decision)
        assertEquals(100, currentTime)
    }

    @Test
    fun `a request goes ahead without rules after the timeout`() = runTest {
        val source = SlowSource(listOf(rule))
        val engine = BoggartEngine(RuleRepository(listOf(source)))
        var reported: Boolean? = null
        engine.onRulesWait = { _, ready -> reported = ready }
        assertEquals(Decision.Proceed(), engine.beforeRequest(request))
        assertEquals(500, currentTime)
        assertEquals(false, reported)
    }

    @Test
    fun `sources that are always ready don't wait`() = runTest {
        val repository = RuleRepository(listOf(TemporaryRuleSource()))
        assertTrue(repository.isReady)
        assertTrue(repository.awaitReady(500))
        assertEquals(0, currentTime)
    }

    @Test
    fun `a request after loading neither waits nor reports`() = runTest {
        val source = SlowSource(listOf(rule)).apply { finishLoading() }
        val engine = BoggartEngine(RuleRepository(listOf(source)))
        var reported = false
        engine.onRulesWait = { _, _ -> reported = true }
        assertIs<Decision.Fail>(engine.beforeRequest(request))
        assertEquals(false, reported)
    }
}
