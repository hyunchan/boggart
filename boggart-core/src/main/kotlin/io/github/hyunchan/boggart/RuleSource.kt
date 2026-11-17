package io.github.hyunchan.boggart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 규칙 공급 출처. [priority]가 높은 출처의 규칙이 먼저 매칭된다. */
public interface RuleSource {
    public val rules: StateFlow<List<Rule>>
    public val priority: Int

    /**
     * 규칙을 다 읽을 때까지 기다린다. 파일에서 비동기로 읽는 출처가 구현하고, 메모리 출처는 바로 돌아온다.
     * 엔진은 앱 시작 직후의 요청이 아직 읽지 못한 규칙을 놓치지 않도록 매칭 전에 잠깐 기다린다.
     */
    public suspend fun awaitReady() {}

    /** 규칙을 다 읽었는지. 기다리지 않고 확인할 때 쓴다. */
    public val isReady: Boolean get() = true

    public companion object {
        public const val PRIORITY_TEMPORARY: Int = 300
        public const val PRIORITY_SAVED: Int = 250
        public const val PRIORITY_REMOTE: Int = 200
        public const val PRIORITY_PRESET: Int = 100
    }
}

/** 앱 프로세스 메모리에만 존재하는 임시 규칙 출처. */
public class TemporaryRuleSource(
    override val priority: Int = RuleSource.PRIORITY_TEMPORARY,
) : RuleSource {
    private val state = MutableStateFlow<List<Rule>>(emptyList())

    override val rules: StateFlow<List<Rule>> = state.asStateFlow()

    /** 같은 id가 있으면 교체하고, 없으면 끝에 추가한다. */
    public fun add(rule: Rule) {
        state.update { current ->
            val index = current.indexOfFirst { it.id == rule.id }
            if (index < 0) current + rule else current.toMutableList().also { it[index] = rule }
        }
    }

    /** 같은 id의 규칙을 교체한다. 없으면 false. */
    public fun update(rule: Rule): Boolean {
        var found = false
        state.update { current ->
            val index = current.indexOfFirst { it.id == rule.id }
            found = index >= 0
            if (found) current.toMutableList().also { it[index] = rule } else current
        }
        return found
    }

    public fun remove(ruleId: String): Boolean {
        var removed = false
        state.update { current ->
            val next = current.filterNot { it.id == ruleId }
            removed = next.size != current.size
            next
        }
        return removed
    }

    public fun clear() {
        state.value = emptyList()
    }
}
