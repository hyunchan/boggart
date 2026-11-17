package io.github.hyunchan.boggart.android.ui.rulelist
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.common.FakeBoggartStore
import io.github.hyunchan.boggart.android.ui.common.MainDispatcherExtension
import io.github.hyunchan.boggart.android.ui.common.RuleOrigin
import io.github.hyunchan.boggart.android.ui.common.RuleTarget
import io.github.hyunchan.boggart.android.ui.common.UiEffect
import io.github.hyunchan.boggart.android.ui.common.rule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@ExtendWith(MainDispatcherExtension::class)
class RuleListViewModelTest {
    @Test
    fun `state follows the store and intents write to it`() = runTest {
        val store = FakeBoggartStore()
        store.upsert(RuleTarget.Temporary("t"), rule("t"))
        val vm = RuleListViewModel(store)
        assertEquals(listOf("t"), vm.state.value.rules.temporary.map { it.id })

        vm.onIntent(RuleListIntent.Toggle(RuleOrigin.Temporary, rule("t"), false))
        assertFalse(vm.state.value.rules.temporary.single().enabled)

        vm.onIntent(RuleListIntent.Save(vm.state.value.rules.temporary.single()))
        assertEquals(listOf("t"), vm.state.value.rules.saved?.map { it.id })

        vm.onIntent(RuleListIntent.Remove(RuleOrigin.Saved, rule("t")))
        assertEquals(R.string.boggart_removed_done, (vm.effects.first() as UiEffect.Message).message.res)
        assertEquals(emptyList(), vm.state.value.rules.saved)
    }

    @Test
    fun `a rule covered by an earlier one is marked shadowed`() = runTest {
        val store = FakeBoggartStore()
        val broad = rule("broad").copy(name = "모든 피드", matcher = RequestMatcher(path = "/feed/**"))
        store.upsert(RuleTarget.Temporary("broad"), broad)
        store.upsert(RuleTarget.Saved("exact"), rule("exact"))
        val vm = RuleListViewModel(store)
        assertEquals(mapOf("exact" to "모든 피드"), vm.state.value.shadowedBy)

        // 앞선 규칙을 끄면 가려지지 않는다.
        vm.onIntent(RuleListIntent.Toggle(RuleOrigin.Temporary, broad, false))
        assertEquals(emptyMap(), vm.state.value.shadowedBy)
    }
}
