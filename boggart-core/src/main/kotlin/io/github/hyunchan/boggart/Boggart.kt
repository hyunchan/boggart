package io.github.hyunchan.boggart

/**
 * Boggart 전역 진입점. 어댑터는 [engine]을, UI는 [temporaryRules]·[exchangeLog]·[mode]를 사용한다.
 */
public object Boggart {
    public const val VERSION: String = "0.1.0"

    /** 임시 규칙. 항상 [RuleSource.PRIORITY_TEMPORARY]로 최우선 매칭된다. */
    public val temporaryRules: TemporaryRuleSource = TemporaryRuleSource()

    public val exchangeLog: ExchangeLog = ExchangeLog()

    public val engine: BoggartEngine = BoggartEngine(RuleRepository(listOf(temporaryRules)), exchangeLog)

    public var mode: Mode
        get() = engine.mode
        set(value) {
            engine.mode = value
        }

    /** 현재 설정에서 시작해 [block]이 바꾼 항목만 반영한다. */
    public fun configure(block: BoggartConfig.() -> Unit) {
        val config = BoggartConfig(
            mode = engine.mode,
            validator = engine.validator,
            onInvalidPatch = engine.onInvalidPatch,
            bodyFileResolver = engine.bodyFileResolver,
            exchangeLogCapacity = exchangeLog.capacity,
            ruleSources = engine.repository.sources.value.filterNot { it === temporaryRules }.toMutableList(),
        ).apply(block)
        engine.mode = config.mode
        engine.validator = config.validator
        engine.onInvalidPatch = config.onInvalidPatch
        engine.bodyFileResolver = config.bodyFileResolver
        exchangeLog.capacity = config.exchangeLogCapacity
        engine.repository.setSources(listOf(temporaryRules) + config.ruleSources.filterNot { it === temporaryRules })
    }

    /** 모든 임시 규칙을 해제한다. 다른 출처(프리셋 등)의 규칙은 그대로다. */
    public fun riddikulus() {
        temporaryRules.clear()
    }

    /** 임시 규칙 [ruleId]를 해제한다. 없으면 false. */
    public fun riddikulus(ruleId: String): Boolean = temporaryRules.remove(ruleId)
}

/** [Boggart.configure] DSL. */
public class BoggartConfig internal constructor(
    public var mode: Mode,
    public var validator: BodyValidator,
    public var onInvalidPatch: OnInvalidPatch,
    public var bodyFileResolver: BodyFileResolver?,
    public var exchangeLogCapacity: Int,
    /** [Boggart.temporaryRules] 외의 규칙 출처. */
    public val ruleSources: MutableList<RuleSource>,
) {
    public fun ruleSource(source: RuleSource) {
        ruleSources += source
    }
}
