package io.github.hyunchan.boggart.android

import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.RuleSource
import io.github.hyunchan.boggart.RulesDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 사용자가 "저장"으로 승격한 규칙. [file]에 [RulesDocument]로 영속한다. priority는 [RuleSource.PRIORITY_SAVED].
 *
 * 모든 변경은 [Mutex]로 직렬화하고, 파일을 원자적으로 교체한 뒤에만 [rules]를 갱신한다. 쓰기에 실패하면 상태는 그대로다.
 */
public class SavedRuleSource(private val file: File) : RuleSource {
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<Rule>>(emptyList())

    override val rules: StateFlow<List<Rule>> = state.asStateFlow()
    override val priority: Int = RuleSource.PRIORITY_SAVED
    private val loaded = CompletableDeferred<Unit>()

    /** [load]가 끝날 때까지 기다린다. 읽기에 실패해도 끝난 것으로 본다. */
    override suspend fun awaitReady(): Unit = loaded.await()

    override val isReady: Boolean get() = loaded.isCompleted

    /** 파일을 IO 디스패처에서 읽어 [rules]를 갱신한다. 파일이 없으면 빈 목록이다. */
    public suspend fun load(): Unit = try {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                state.value = AtomicFile.readText(file)?.let { RulesDocument.parse(it).rules }.orEmpty()
            }
        }
    } finally {
        loaded.complete(Unit)
    }

    /** 같은 id가 있으면 교체하고, 없으면 끝에 추가한다. */
    public suspend fun add(rule: Rule): Unit = mutate { current ->
        val index = current.indexOfFirst { it.id == rule.id }
        if (index < 0) current + rule else current.toMutableList().also { it[index] = rule }
    }

    public suspend fun remove(ruleId: String): Boolean {
        var removed = false
        mutate { current -> current.filterNot { it.id == ruleId }.also { removed = it.size != current.size } }
        return removed
    }

    public suspend fun clear(): Unit = mutate { emptyList() }

    /** 규칙이 없으면 false. */
    public suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean {
        var found = false
        mutate { current ->
            current.map { if (it.id == ruleId) it.copy(enabled = enabled).also { found = true } else it }
        }
        return found
    }

    private suspend fun mutate(transform: (List<Rule>) -> List<Rule>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = state.value
            val next = transform(current)
            if (next != current) {
                AtomicFile.writeText(file, RulesDocument(rules = next).encode())
                state.value = next
            }
        }
    }
}
