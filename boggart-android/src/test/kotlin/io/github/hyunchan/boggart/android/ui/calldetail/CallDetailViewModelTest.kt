package io.github.hyunchan.boggart.android.ui.calldetail
import io.github.hyunchan.boggart.JsonPatchOp
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.common.FakeBoggartStore
import io.github.hyunchan.boggart.android.ui.common.MainDispatcherExtension
import io.github.hyunchan.boggart.android.ui.common.RuleTarget
import io.github.hyunchan.boggart.android.ui.common.UiEffect
import io.github.hyunchan.boggart.android.ui.common.UiMessage
import io.github.hyunchan.boggart.android.ui.common.jsonExchange
import io.github.hyunchan.boggart.android.ui.common.rule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@ExtendWith(MainDispatcherExtension::class)
class CallDetailViewModelTest {
    private fun replace(path: String, value: String) = JsonPatchOp(JsonPatchOp.Op.Replace, path, JsonPrimitive(value))

    @Test
    fun `submitting again updates the rule this screen created`() = runTest {
        val store = FakeBoggartStore(listOf(jsonExchange()))
        val vm = CallDetailViewModel(1, store)
        assertEquals(Action.Values, vm.state.value.action)

        vm.onIntent(CallDetailIntent.SetOps(listOf(replace("/0/title", "x"))))
        assertTrue(vm.state.value.dirty)
        vm.onIntent(CallDetailIntent.Submit)
        val created = store.currentRules().temporary.single()
        assertEquals(UiEffect.ReturnToApp(UiMessage(R.string.boggart_rule_applied)), vm.effects.first())
        assertTrue(vm.state.value.updating)
        assertFalse(vm.state.value.dirty)

        vm.onIntent(CallDetailIntent.SetOps(listOf(replace("/0/title", "y"))))
        vm.onIntent(CallDetailIntent.Submit)
        val updated = store.currentRules().temporary.single()
        assertEquals(created.id, updated.id)
        assertEquals(JsonPrimitive("y"), assertIs<Override.JsonPatch>(updated.override).ops.single().value)
        assertEquals(R.string.boggart_rule_updated, (vm.effects.first() as UiEffect.ReturnToApp).message.res)
    }

    @Test
    fun `a removed rule makes the next submit create a new one`() = runTest {
        val store = FakeBoggartStore(listOf(jsonExchange()))
        val vm = CallDetailViewModel(1, store)
        vm.onIntent(CallDetailIntent.SetOps(listOf(replace("/0/title", "x"))))
        vm.onIntent(CallDetailIntent.Submit)
        store.clearTemporary()
        assertFalse(vm.state.value.updating)
        vm.onIntent(CallDetailIntent.Submit)
        assertEquals(1, store.currentRules().temporary.size)
    }

    @Test
    fun `content edits mark dirty but delay does not`() = runTest {
        val vm = CallDetailViewModel(1, FakeBoggartStore(listOf(jsonExchange())))
        vm.onIntent(CallDetailIntent.SelectAction(Action.Respond))
        val editor = vm.state.value.editor
        vm.onIntent(CallDetailIntent.Edit(editor.copy(delay = "1000")))
        assertFalse(vm.state.value.dirty)
        vm.onIntent(CallDetailIntent.Edit(editor.copy(status = "500")))
        assertTrue(vm.state.value.dirty)
    }

    @Test
    fun `an invalid draft is reported without writing`() = runTest {
        val store = FakeBoggartStore(listOf(jsonExchange()))
        val vm = CallDetailViewModel(1, store)
        vm.onIntent(CallDetailIntent.Submit)
        assertEquals(R.string.boggart_error_no_value_changes, (vm.effects.first() as UiEffect.Message).message.res)
        assertTrue(store.currentRules().temporary.isEmpty())
    }

    @Test
    fun `a call with an applied rule starts from that rule`() = runTest {
        val store = FakeBoggartStore(listOf(jsonExchange(appliedRuleId = "r")))
        store.upsert(
            RuleTarget.Temporary("r"),
            rule("r", Override.JsonPatch(listOf(replace("/1/title", "z")))),
        )
        val vm = CallDetailViewModel(1, store)
        assertEquals(listOf(replace("/1/title", "z")), vm.state.value.ops)
        assertTrue(vm.state.value.updating)
        assertEquals("GET /feed", vm.state.value.appliedRuleName)
    }

    @Test
    fun `an earlier call picks up the rule already made for the same request`() = runTest {
        val store = FakeBoggartStore(listOf(jsonExchange()))
        store.upsert(RuleTarget.Temporary("made"), rule("made", Override.JsonPatch(listOf(replace("/0/title", "x")))))
        val vm = CallDetailViewModel(1, store)
        assertTrue(vm.state.value.updating)
        assertEquals(listOf(replace("/0/title", "x")), vm.state.value.ops)

        vm.onIntent(CallDetailIntent.SetOps(listOf(replace("/0/title", "y"))))
        vm.onIntent(CallDetailIntent.Submit)
        assertEquals(listOf("made"), store.currentRules().temporary.map { it.id })
    }

    @Test
    fun `the button says switch when another action would replace the rule`() = runTest {
        val store = FakeBoggartStore(listOf(jsonExchange(appliedRuleId = "r")))
        store.upsert(RuleTarget.Temporary("r"), rule("r", Override.JsonPatch(listOf(replace("/0/title", "x")))))
        val vm = CallDetailViewModel(1, store)
        assertEquals(R.string.boggart_update_rule, vm.state.value.submitButton)
        vm.onIntent(CallDetailIntent.SelectAction(Action.Respond))
        assertEquals(R.string.boggart_switch_to_response, vm.state.value.submitButton)
        store.clearTemporary()
        assertEquals(R.string.boggart_create_response, vm.state.value.submitButton)
    }

    @Test
    fun `stub response covers both a response and a network error`() = runTest {
        val store = FakeBoggartStore(listOf(jsonExchange(appliedRuleId = "f")))
        store.upsert(RuleTarget.Temporary("f"), rule("f"))
        val vm = CallDetailViewModel(1, store)
        assertEquals(Action.Respond, vm.state.value.action)
        assertEquals(RespondKind.Error, vm.state.value.editor.respondKind)
        assertEquals(R.string.boggart_update_rule, vm.state.value.submitButton)

        vm.onIntent(CallDetailIntent.Edit(vm.state.value.editor.copy(respondKind = RespondKind.Response, status = "503")))
        assertEquals(R.string.boggart_switch_to_response, vm.state.value.submitButton)
        vm.onIntent(CallDetailIntent.Submit)
        assertEquals(503, assertIs<Override.Replace>(store.currentRules().temporary.single().override).status)
    }

    @Test
    fun `the call tells how many enabled rules match it`() = runTest {
        val store = FakeBoggartStore(listOf(jsonExchange(appliedRuleId = "a")))
        store.upsert(RuleTarget.Temporary("a"), rule("a"))
        assertEquals(1, CallDetailViewModel(1, store).state.value.matchingRuleCount)
        store.upsert(RuleTarget.Saved("b"), rule("b"))
        assertEquals(2, CallDetailViewModel(1, store).state.value.matchingRuleCount)
    }
}
