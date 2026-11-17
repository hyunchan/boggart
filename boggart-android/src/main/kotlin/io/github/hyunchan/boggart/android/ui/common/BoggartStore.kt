package io.github.hyunchan.boggart.android.ui.common
import androidx.annotation.StringRes
import io.github.hyunchan.boggart.Boggart
import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.android.BoggartAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 규칙이 있는 출처. */
internal enum class RuleOrigin { Temporary, Saved, Preset }

/**
 * 화면이 보는 세 출처의 규칙. [saved]·[preset]은 그 출처를 쓰지 않는 설치(또는 설치 전)면 null이다.
 */
internal data class RuleSet(
    val temporary: List<Rule> = emptyList(),
    val saved: List<Rule>? = null,
    val preset: List<Rule>? = null,
) {
    fun find(ruleId: String?): Pair<RuleOrigin, Rule>? {
        if (ruleId == null) return null
        temporary.firstOrNull { it.id == ruleId }?.let { return RuleOrigin.Temporary to it }
        saved?.firstOrNull { it.id == ruleId }?.let { return RuleOrigin.Saved to it }
        preset?.firstOrNull { it.id == ruleId }?.let { return RuleOrigin.Preset to it }
        return null
    }

    /** [ruleId]가 임시·저장 규칙이면 그 규칙을 고치고, 아니면(프리셋이거나 없음) 새 임시 규칙을 만든다. */
    fun targetFor(ruleId: String?): RuleTarget = RuleDrafts.targetFor(
        ruleId,
        temporary.mapTo(HashSet()) { it.id },
        saved?.mapTo(HashSet()) { it.id }.orEmpty(),
    )

    /** 조건이 [matcher]와 똑같은 임시 규칙(없으면 저장 규칙). 호출 화면에서 만든 규칙을 다시 찾을 때 쓴다. */
    fun editableMatching(matcher: RequestMatcher): Rule? =
        temporary.firstOrNull { it.matcher == matcher } ?: saved?.firstOrNull { it.matcher == matcher }

    /** 엔진이 보는 순서(임시 → 저장 → 프리셋, 출처 안에서는 목록 순서)로 늘어놓은 규칙. */
    val ordered: List<Rule> get() = temporary + saved.orEmpty() + preset.orEmpty()

    /**
     * 켜져 있지만 앞선 켜진 규칙에 조건이 완전히 덮여 적용될 일이 없는 규칙의 id → 그 앞선 규칙.
     * 일부만 겹치는 경우는 표시하지 않는다(요청에 따라 적용될 수 있음).
     */
    fun shadowing(): Map<String, Rule> {
        val enabled = ordered.filter { it.enabled }
        return enabled.withIndex().mapNotNull { (index, rule) ->
            enabled.take(index).firstOrNull { rule.matcher.isCoveredBy(it.matcher) }?.let { rule.id to it }
        }.toMap()
    }

    /** [request]에 맞는 켜진 규칙들. 엔진은 이 중 첫 번째만 적용한다. */
    fun matching(request: BoggartRequest): List<Rule> = ordered.filter { it.enabled && it.matcher.matches(request) }

    /** [ruleId]가 고칠 수 있는(임시·저장) 규칙으로 아직 있으면 true. */
    fun isEditable(ruleId: String?): Boolean = find(ruleId)?.first.let { it == RuleOrigin.Temporary || it == RuleOrigin.Saved }
}

/**
 * 화면(ViewModel)이 Boggart 상태를 읽고 쓰는 창구. 전역 객체 대신 이 인터페이스를 받아 테스트에서 바꿔 끼운다.
 */
internal interface BoggartStore {
    val exchanges: StateFlow<List<Exchange>>
    val mode: StateFlow<Mode>
    val rules: Flow<RuleSet>

    /** 켜져 있는 규칙 수. 앱이 `Boggart.configure`로 더한 출처처럼 화면에 나오지 않는 출처까지 엔진의 모든 출처를 센다. */
    val activeRuleCount: Flow<Int>

    /** 지금의 규칙. ViewModel이 첫 상태를 동기로 만들 때 쓴다. */
    fun currentRules(): RuleSet

    fun setMode(mode: Mode)
    fun clearExchanges()
    suspend fun upsert(target: RuleTarget, rule: Rule)
    suspend fun setEnabled(origin: RuleOrigin, rule: Rule, enabled: Boolean)

    /** 임시 규칙을 저장 규칙으로 옮긴다. */
    suspend fun promote(rule: Rule)

    /** 임시 규칙은 해제(riddikulus), 저장 규칙은 삭제한다. 프리셋은 지울 수 없다. */
    suspend fun remove(origin: RuleOrigin, ruleId: String)
    fun clearTemporary()
    suspend fun bodyText(rule: Rule): String
}

/**
 * 전역 [Boggart]·[BoggartAndroid]에 연결된 기본 구현.
 *
 * 쓰기는 화면보다 오래 사는 스코프에서 끝까지 실행한다. 저장 직후 화면을 떠나 ViewModel이 정리돼도
 * 파일 쓰기나 `promote`(저장에 추가 → 임시에서 제거)가 중간에 끊기지 않는다. 호출한 쪽은 결과만 기다린다.
 */
internal object GlobalBoggartStore : BoggartStore {
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun <T> outliving(block: suspend () -> T): T = writes.async { block() }.await()

    override val exchanges: StateFlow<List<Exchange>> get() = Boggart.exchangeLog.exchanges
    override val mode: StateFlow<Mode> get() = Boggart.engine.modeFlow

    override val rules: Flow<RuleSet>
        get() {
            val saved = BoggartAndroid.savedRules?.rules ?: flowOf(null)
            val preset = BoggartAndroid.presetRules?.rules ?: flowOf(null)
            return combine(Boggart.temporaryRules.rules, saved, preset) { temporary, s, p -> RuleSet(temporary, s, p) }
        }

    override val activeRuleCount: Flow<Int>
        get() = Boggart.engine.repository.rules.map { rules -> rules.count { it.enabled } }

    override fun currentRules(): RuleSet = RuleSet(
        Boggart.temporaryRules.rules.value,
        BoggartAndroid.savedRules?.rules?.value,
        BoggartAndroid.presetRules?.rules?.value,
    )

    override fun setMode(mode: Mode) {
        Boggart.mode = mode
    }

    override fun clearExchanges() {
        Boggart.exchangeLog.clear()
    }

    override suspend fun upsert(target: RuleTarget, rule: Rule) = outliving {
        when (target) {
            is RuleTarget.Temporary -> Boggart.temporaryRules.add(rule)
            is RuleTarget.Saved -> checkNotNull(BoggartAndroid.savedRules) { "Saved rules are not enabled in this install" }.add(rule)
        }
    }

    override suspend fun setEnabled(origin: RuleOrigin, rule: Rule, enabled: Boolean) = outliving {
        // 출처마다 "찾았는지"를 돌려주지만 화면은 다음 규칙 흐름으로 결과를 보므로 쓰지 않는다.
        when (origin) {
            RuleOrigin.Temporary -> Boggart.temporaryRules.update(rule.copy(enabled = enabled))
            RuleOrigin.Saved -> BoggartAndroid.savedRules?.setEnabled(rule.id, enabled)
            RuleOrigin.Preset -> BoggartAndroid.presetRules?.setEnabled(rule.id, enabled)
        }.let { }
    }

    override suspend fun promote(rule: Rule) = outliving { BoggartAndroid.promote(rule) }

    override suspend fun remove(origin: RuleOrigin, ruleId: String) = outliving {
        // 없던 규칙이어도 결과는 같으므로(목록에 없음) 지웠는지는 쓰지 않는다.
        when (origin) {
            RuleOrigin.Temporary -> Boggart.riddikulus(ruleId)
            RuleOrigin.Saved -> BoggartAndroid.demote(ruleId)
            RuleOrigin.Preset -> false
        }.let { }
    }

    override fun clearTemporary() {
        Boggart.riddikulus()
    }

    override suspend fun bodyText(rule: Rule): String {
        val replace = rule.override as? Override.Replace ?: return ""
        replace.body?.let { return it }
        val file = replace.bodyFile ?: return ""
        return Boggart.engine.bodyFileResolver?.resolve(file)?.toString(Charsets.UTF_8).orEmpty()
    }
}

/** ViewModel이 화면에 보낼 문구. 리소스를 모르는 ViewModel 대신 화면이 문자열로 바꾼다. */
internal data class UiMessage(@param:StringRes val res: Int, val args: List<Any> = emptyList())

/** 한 번만 처리하는 화면 효과. */
internal sealed interface UiEffect {
    data class Message(val message: UiMessage) : UiEffect

    /** 화면을 닫는다(규칙을 지운 뒤 등). */
    data object Close : UiEffect

    /** Boggart 화면을 모두 닫고 앱으로 돌아간다. 앱 위에는 스낵바를 띄울 수 없어 [message]는 토스트로 알린다. */
    data class ReturnToApp(val message: UiMessage) : UiEffect
}
