package io.github.hyunchan.boggart.android.ui.ruleedit
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.SequenceEnd
import io.github.hyunchan.boggart.SequenceStep
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.common.FakeBoggartStore
import io.github.hyunchan.boggart.android.ui.common.MainDispatcherExtension
import io.github.hyunchan.boggart.android.ui.common.RuleOrigin
import io.github.hyunchan.boggart.android.ui.common.RuleTarget
import io.github.hyunchan.boggart.android.ui.common.UiEffect
import io.github.hyunchan.boggart.android.ui.common.UiMessage
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
    fun `removing closes the screen with the same message as the list`() = runTest {
        val store = FakeBoggartStore()
        store.upsert(RuleTarget.Temporary("t"), rule("t"))
        store.upsert(RuleTarget.Saved("s"), rule("s"))
        val temporary = RuleEditViewModel("t", store)
        temporary.onIntent(RuleEditIntent.Remove)
        assertEquals(UiEffect.CloseWith(UiMessage(R.string.boggart_riddikulus_done)), temporary.effects.first())
        assertTrue(store.currentRules().temporary.isEmpty())

        val saved = RuleEditViewModel("s", store)
        saved.onIntent(RuleEditIntent.Remove)
        assertEquals(UiEffect.CloseWith(UiMessage(R.string.boggart_removed_done)), saved.effects.first())
        assertTrue(store.currentRules().saved.orEmpty().isEmpty())
    }

    @Test
    fun `a sequence rule keeps its steps and changes only what happens after the last`() = runTest {
        val store = FakeBoggartStore()
        val steps = listOf(SequenceStep(Override.Replace(status = 500), times = 2), SequenceStep(Override.Replace(status = 200)))
        store.upsert(RuleTarget.Temporary("t"), rule("t", Override.Sequence(steps)))
        store.progressState.value = mapOf("t" to 1)
        val vm = RuleEditViewModel("t", store)
        assertEquals(1, vm.state.value.nextStep)
        vm.onIntent(RuleEditIntent.Edit(vm.state.value.form!!.copy(path = "/feed/*", then = SequenceEnd.Loop)))
        vm.onIntent(RuleEditIntent.Apply)
        vm.effects.first()
        val applied = store.currentRules().temporary.single()
        assertEquals("/feed/*", applied.matcher.path)
        assertEquals(Override.Sequence(steps, SequenceEnd.Loop), applied.override)
    }

    @Test
    fun `restarting a sequence starts it from the first step`() = runTest {
        val store = FakeBoggartStore()
        store.upsert(RuleTarget.Temporary("t"), rule("t", Override.Sequence(listOf(SequenceStep(Override.Replace(status = 500))))))
        store.progressState.value = mapOf("t" to 1)
        val vm = RuleEditViewModel("t", store)
        vm.onIntent(RuleEditIntent.RestartSequence)
        assertEquals(UiEffect.Message(UiMessage(R.string.boggart_sequence_restarted)), vm.effects.first())
        assertEquals(0, vm.state.value.nextStep)
        assertNull(RuleEditViewModel("other", store).state.value.nextStep)
    }

    @Test
    fun `steps can be added, edited, moved and removed before applying`() = runTest {
        val store = FakeBoggartStore()
        store.upsert(RuleTarget.Temporary("t"), rule("t", Override.Sequence(listOf(SequenceStep(Override.Replace(status = 500))))))
        val vm = RuleEditViewModel("t", store)
        vm.onIntent(RuleEditIntent.AddStep(StepKind.Error))
        assertEquals(1, vm.state.value.form?.expanded)
        val added = vm.state.value.form!!.steps[1]
        vm.onIntent(RuleEditIntent.EditStep(1, added.copy(times = "3", chance = true, percent = "40")))
        vm.onIntent(RuleEditIntent.MoveStep(1, -1))
        vm.onIntent(RuleEditIntent.DuplicateStep(1))
        vm.onIntent(RuleEditIntent.RemoveStep(2))
        vm.onIntent(RuleEditIntent.Apply)
        vm.effects.first()
        val applied = assertIs<Override.Sequence>(store.currentRules().temporary.single().override)
        assertEquals(
            listOf(SequenceStep(Override.Chance(40, Override.Fail(FailKind.Timeout)), times = 3), SequenceStep(Override.Replace(status = 500))),
            applied.steps,
        )
    }

    @Test
    fun `a bad step is opened and reported instead of applied`() = runTest {
        val store = FakeBoggartStore()
        store.upsert(RuleTarget.Temporary("t"), rule("t", Override.Sequence(listOf(SequenceStep(Override.Replace(status = 500)), SequenceStep(Override.Replace(status = 200))))))
        val vm = RuleEditViewModel("t", store)
        vm.onIntent(RuleEditIntent.EditStep(1, vm.state.value.form!!.steps[1].copy(times = "0")))
        vm.onIntent(RuleEditIntent.Apply)
        assertEquals(UiEffect.Message(UiMessage(R.string.boggart_error_invalid_times)), vm.effects.first())
        assertEquals(1, vm.state.value.form?.expanded)
    }

    @Test
    fun `a plain rule can be turned into a sequence`() = runTest {
        val store = FakeBoggartStore()
        store.upsert(RuleTarget.Temporary("t"), rule("t", Override.Replace(status = 404, body = "{}")))
        val vm = RuleEditViewModel("t", store)
        vm.onIntent(RuleEditIntent.ConvertToSequence)
        vm.onIntent(RuleEditIntent.AddStep(StepKind.Response))
        vm.onIntent(RuleEditIntent.Apply)
        vm.effects.first()
        val applied = assertIs<Override.Sequence>(store.currentRules().temporary.single().override)
        assertEquals(listOf(SequenceStep(Override.Replace(status = 404, body = "{}")), SequenceStep(Override.Replace(status = 200))), applied.steps)
    }

    @Test
    fun `a chance rule edits its percent and inner override`() = runTest {
        val store = FakeBoggartStore()
        store.upsert(RuleTarget.Temporary("t"), rule("t", Override.Chance(30, Override.Fail(FailKind.Timeout))))
        val vm = RuleEditViewModel("t", store)
        vm.onIntent(RuleEditIntent.EditStep(0, vm.state.value.form!!.steps.single().copy(percent = "70", failKind = FailKind.ConnectionReset)))
        vm.onIntent(RuleEditIntent.Apply)
        vm.effects.first()
        assertEquals(Override.Chance(70, Override.Fail(FailKind.ConnectionReset)), store.currentRules().temporary.single().override)
    }
}
