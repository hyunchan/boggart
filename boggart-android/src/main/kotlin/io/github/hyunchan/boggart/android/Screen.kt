package io.github.hyunchan.boggart.android

/** 내부 내비게이션 대상. 상태 저장과 Intent 전달을 위해 문자열로 인코딩한다. */
internal sealed interface Screen {
    data object Calls : Screen
    data class CallDetail(val id: Long) : Screen
    data object Rules : Screen
    data class RuleEdit(val ruleId: String) : Screen

    fun encode(): String = when (this) {
        Calls -> "calls"
        Rules -> "rules"
        is CallDetail -> "call:$id"
        is RuleEdit -> "rule:$ruleId"
    }

    companion object {
        fun decode(value: String): Screen = when {
            value == "rules" -> Rules
            value.startsWith("call:") -> value.substringAfter(':').toLongOrNull()?.let(::CallDetail) ?: Calls
            value.startsWith("rule:") -> RuleEdit(value.substringAfter(':'))
            else -> Calls
        }
    }
}
