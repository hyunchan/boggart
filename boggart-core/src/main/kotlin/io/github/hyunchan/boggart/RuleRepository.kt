package io.github.hyunchan.boggart

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/** 여러 [RuleSource]를 priority 내림차순으로 병합한다. 같은 priority는 등록 순서를 따른다. */
public class RuleRepository(sources: List<RuleSource> = emptyList()) {
    private val sourcesState = MutableStateFlow(sortByPriority(sources))

    public val sources: StateFlow<List<RuleSource>> = sourcesState.asStateFlow()

    /** 병합된 전체 규칙(비활성 포함)의 흐름. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val rules: Flow<List<Rule>> = sourcesState.flatMapLatest { current ->
        if (current.isEmpty()) flowOf(emptyList()) else combine(current.map { it.rules }) { it.toList().flatten() }
    }

    public fun setSources(sources: List<RuleSource>) {
        sourcesState.value = sortByPriority(sources)
    }

    /** 현재 시점의 병합된 전체 규칙(비활성 포함). */
    public fun snapshot(): List<Rule> = sourcesState.value.flatMap { it.rules.value }

    /**
     * 모든 출처가 규칙을 다 읽을 때까지 최대 [timeoutMs] 기다린다. 다 읽었으면 바로 돌아온다.
     *
     * @return 시간 안에 모두 준비되었으면 true, 기다리다 포기했으면 false
     */
    public suspend fun awaitReady(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { sourcesState.value.forEach { it.awaitReady() } } != null

    /** 모든 출처가 규칙을 다 읽었는지. 기다리지 않는다. */
    public val isReady: Boolean get() = sourcesState.value.all { it.isReady }

    /** [request]에 매칭되는 첫 번째 활성 규칙. */
    public fun find(request: BoggartRequest): Rule? =
        sourcesState.value.firstNotNullOfOrNull { source ->
            source.rules.value.firstOrNull { it.enabled && it.matcher.matches(request) }
        }

    private fun sortByPriority(sources: List<RuleSource>): List<RuleSource> =
        sources.sortedByDescending { it.priority }
}
