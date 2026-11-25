package io.github.hyunchan.boggart.android.ui.common
import io.github.hyunchan.boggart.BoggartHeaders
import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.BoggartResponse
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.Provenance
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** 메모리에서 동작하는 [BoggartStore]. 저장 규칙과 프리셋 출처를 쓰는 설치처럼 동작한다. */
internal class FakeBoggartStore(exchanges: List<Exchange> = emptyList()) : BoggartStore {
    val ruleState = MutableStateFlow(RuleSet(saved = emptyList(), preset = emptyList()))
    override val exchanges = MutableStateFlow(exchanges)
    override val mode = MutableStateFlow(Mode.Active)
    override val rules: Flow<RuleSet> = ruleState
    override val activeRuleCount: Flow<Int> = ruleState.map { set -> (set.temporary + set.saved.orEmpty()).count { it.enabled } }

    override fun currentRules(): RuleSet = ruleState.value

    override fun setMode(mode: Mode) {
        this.mode.value = mode
    }

    override fun clearExchanges() {
        exchanges.value = emptyList()
    }

    override suspend fun upsert(target: RuleTarget, rule: Rule) {
        ruleState.update { set ->
            when (target) {
                is RuleTarget.Temporary -> set.copy(temporary = set.temporary.upsert(rule))
                is RuleTarget.Saved -> set.copy(saved = set.saved.orEmpty().upsert(rule))
            }
        }
    }

    override suspend fun setEnabled(origin: RuleOrigin, rule: Rule, enabled: Boolean) {
        val changed = rule.copy(enabled = enabled)
        ruleState.update { set ->
            when (origin) {
                RuleOrigin.Temporary -> set.copy(temporary = set.temporary.upsert(changed))
                RuleOrigin.Saved -> set.copy(saved = set.saved.orEmpty().upsert(changed))
                RuleOrigin.Preset -> set.copy(preset = set.preset.orEmpty().upsert(changed))
            }
        }
    }

    override suspend fun promote(rule: Rule) {
        ruleState.update { set -> set.copy(temporary = set.temporary.filterNot { it.id == rule.id }, saved = set.saved.orEmpty().upsert(rule)) }
    }

    override suspend fun remove(origin: RuleOrigin, ruleId: String) {
        ruleState.update { set ->
            when (origin) {
                RuleOrigin.Temporary -> set.copy(temporary = set.temporary.filterNot { it.id == ruleId })
                RuleOrigin.Saved -> set.copy(saved = set.saved?.filterNot { it.id == ruleId })
                RuleOrigin.Preset -> set
            }
        }
    }

    override fun clearTemporary() {
        ruleState.update { it.copy(temporary = emptyList()) }
    }

    override suspend fun bodyText(rule: Rule): String = ""

    /** 순차 응답 규칙 id → 다음 단계. 테스트가 직접 바꾼다. */
    val progressState = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val sequenceProgress: Flow<Map<String, Int>> = progressState

    override fun currentSequenceProgress(): Map<String, Int> = progressState.value

    override fun resetSequence(ruleId: String) {
        progressState.update { it + (ruleId to 0) }
    }

    private fun List<Rule>.upsert(rule: Rule): List<Rule> {
        val index = indexOfFirst { it.id == rule.id }
        return if (index < 0) this + rule else toMutableList().also { it[index] = rule }
    }
}

internal fun jsonExchange(id: Long = 1, body: String = """[{"id":1,"title":"a"},{"id":2,"title":"b"}]""", appliedRuleId: String? = null) = Exchange(
    id = id,
    timestampMs = 1,
    request = BoggartRequest("GET", "https://api.example.com/feed"),
    originalResponse = BoggartResponse(200, BoggartHeaders.EMPTY, body.toByteArray(), "application/json"),
    deliveredResponse = null,
    provenance = Provenance.Original,
    appliedRuleId = appliedRuleId,
)

internal fun rule(id: String, override: Override = Override.Fail(FailKind.Timeout)) =
    Rule(id, "GET /feed", RequestMatcher("GET", "api.example.com", "/feed"), override)
