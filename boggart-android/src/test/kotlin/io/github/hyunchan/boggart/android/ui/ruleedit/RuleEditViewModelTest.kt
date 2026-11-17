package io.github.hyunchan.boggart.android.ui.ruleedit
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.common.FakeBoggartStore
import io.github.hyunchan.boggart.android.ui.common.MainDispatcherExtension
import io.github.hyunchan.boggart.android.ui.common.RuleOrigin
import io.github.hyunchan.boggart.android.ui.common.RuleTarget
import io.github.hyunchan.boggart.android.ui.common.UiEffect
import io.github.hyunchan.boggart.android.ui.common.rule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MainDispatcherExtension::class)
class RuleEditViewModelTest {
    @Test
    fun `the form appears when a saved rule finishes loading`() = runTest {
        val store = FakeBoggartStore()
        val vm = RuleEditViewModel("s", store)
        assertNull(vm.state.value.form)
        store.ruleState.update { it.copy(saved = listOf(rule("s"))) }
        assertEquals(RuleOrigin.Saved, vm.state.value.found?.first)
        assertEquals("/feed", vm.state.value.form?.path)
    }

    @Test
    fun `saving a temporary rule promotes it and keeps the edits`() = runTest {
        val store = FakeBoggartStore()
        store.upsert(RuleTarget.Temporary("t"), rule("t"))
        val vm = RuleEditViewModel("t", store)
        val form = vm.state.value.form!!
        vm.onIntent(RuleEditIntent.Edit(form.copy(path = "/feed/*", failKind = FailKind.ConnectionReset)))
        vm.onIntent(RuleEditIntent.Save)
        assertEquals(R.string.boggart_saved_done, (vm.effects.first() as UiEffect.Message).message.res)
        assertTrue(store.currentRules().temporary.isEmpty())
        val saved = store.currentRules().saved!!.single()
        assertEquals("/feed/*", saved.matcher.path)
        assertEquals(FailKind.ConnectionReset, assertIs<Override.Fail>(saved.override).kind)
        // 출처가 바뀌어도 입력은 그대로다.
        assertEquals(RuleOrigin.Saved, vm.state.value.found?.first)
        assertEquals("/feed/*", vm.state.value.form?.path)
    }

    @Test
    fun `removing closes the screen`() = runTest {
        val store = FakeBoggartStore()
        store.upsert(RuleTarget.Temporary("t"), rule("t"))
        val vm = RuleEditViewModel("t", store)
        vm.onIntent(RuleEditIntent.Remove)
        assertEquals(UiEffect.Close, vm.effects.first())
        assertTrue(store.currentRules().temporary.isEmpty())
    }
}
