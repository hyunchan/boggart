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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/**
 * 빌드에 실린 에셋 `<dir>/rules.json`([RulesDocument])의 규칙. priority는 [RuleSource.PRIORITY_PRESET].
 *
 * 에셋은 읽기 전용이므로 사용자가 바꾼 enabled 상태는 [overridesFile]에 `ruleId → enabled`로 저장하고 로드할 때 덮어쓴다.
 * 로드할 때 에셋에 없는 id의 토글은 버리고, 버린 것이 있으면 파일을 다시 쓴다.
 *
 * @param openAsset 에셋 경로를 열어 스트림을 돌려준다. 없으면 null.
 * @param overridesFile enabled 토글 저장 파일. null이면 토글을 메모리에만 둔다.
 */
public class PresetRuleSource(
    private val openAsset: (path: String) -> InputStream?,
    private val overridesFile: File?,
    public val dir: String = BoggartAndroid.DEFAULT_ASSETS_DIR,
) : RuleSource {
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<Rule>>(emptyList())
    private var baseRules: List<Rule> = emptyList()
    private var overrides: Map<String, Boolean> = emptyMap()

    override val rules: StateFlow<List<Rule>> = state.asStateFlow()
    override val priority: Int = RuleSource.PRIORITY_PRESET
    private val loaded = CompletableDeferred<Unit>()

    /** [load]가 끝날 때까지 기다린다. 읽기에 실패해도 끝난 것으로 본다. */
    override suspend fun awaitReady(): Unit = loaded.await()

    override val isReady: Boolean get() = loaded.isCompleted

    /** 에셋과 토글 파일을 IO 디스패처에서 읽어 [rules]를 갱신한다. 에셋이 없으면 빈 목록이다. */
    public suspend fun load(): Unit = try {
        withContext(Dispatchers.IO) {
            val document = openAsset("$dir/$RULES_FILE")?.use { RulesDocument.parse(it.readBytes().toString(Charsets.UTF_8)) }
            val savedOverrides = overridesFile?.let(AtomicFile::readText)?.let { json.decodeFromString(overridesSerializer, it) }
            mutex.withLock {
                baseRules = document?.rules.orEmpty()
                val ids = baseRules.mapTo(HashSet()) { it.id }
                val loaded = savedOverrides ?: overrides
                val kept = loaded.filterKeys { it in ids }
                if (overridesFile != null && savedOverrides != null && kept.size != savedOverrides.size) {
                    AtomicFile.writeText(overridesFile, json.encodeToString(overridesSerializer, kept))
                }
                overrides = kept
                publish()
            }
        }
    } finally {
        loaded.complete(Unit)
    }

    /** 프리셋 규칙 [ruleId]의 enabled를 바꾸고 토글 파일에 저장한다. 규칙이 없으면 false. */
    public suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (baseRules.none { it.id == ruleId }) return@withLock false
            val next = overrides + (ruleId to enabled)
            overridesFile?.let { AtomicFile.writeText(it, json.encodeToString(overridesSerializer, next)) }
            overrides = next
            publish()
            true
        }
    }

    private fun publish() {
        state.value = baseRules.map { rule -> overrides[rule.id]?.let { rule.copy(enabled = it) } ?: rule }
    }

    private companion object {
        const val RULES_FILE = "rules.json"
        val json = Json { prettyPrint = true }
        val overridesSerializer = MapSerializer(String.serializer(), Boolean.serializer())
    }
}
