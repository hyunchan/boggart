package io.github.hyunchan.boggart.android.control

import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.RuleSource
import io.github.hyunchan.boggart.android.PresetRuleSource
import io.github.hyunchan.boggart.android.SavedRuleSource
import io.github.hyunchan.boggart.control.ControlRuleStore

/** 저장 규칙을 원격 제어에 연결한다. */
internal class SavedControlStore(private val saved: SavedRuleSource) : ControlRuleStore {
    override val source: RuleSource get() = saved
    override val editable: Boolean = true

    override suspend fun upsert(rule: Rule) = saved.add(rule)

    override suspend fun remove(ruleId: String): Boolean = saved.remove(ruleId)

    override suspend fun clear() = saved.clear()

    override suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean = saved.setEnabled(ruleId, enabled)
}

/** 프리셋을 원격 제어에 연결한다. 에셋은 읽기 전용이라 켜고 끄기만 한다. */
internal class PresetControlStore(private val preset: PresetRuleSource) : ControlRuleStore {
    override val source: RuleSource get() = preset
    override val editable: Boolean = false

    override suspend fun upsert(rule: Rule): Unit = throw UnsupportedOperationException("Preset rules are read-only")

    override suspend fun remove(ruleId: String): Boolean = throw UnsupportedOperationException("Preset rules are read-only")

    override suspend fun clear(): Unit = throw UnsupportedOperationException("Preset rules are read-only")

    override suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean = preset.setEnabled(ruleId, enabled)
}
