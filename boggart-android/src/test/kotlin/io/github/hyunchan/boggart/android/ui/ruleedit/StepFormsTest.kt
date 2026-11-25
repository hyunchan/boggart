package io.github.hyunchan.boggart.android.ui.ruleedit

import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.SequenceStep
import io.github.hyunchan.boggart.android.ui.common.DraftError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StepFormsTest {
    private fun built(step: StepForm) = assertIs<StepForm.Built.Ok>(step.build()).step

    @Test
    fun `every step kind survives a round trip through the form`() {
        val steps = listOf(
            SequenceStep(Override.Replace(503, mapOf("Retry-After" to "1"), "{}", delayMs = 100), times = 2),
            SequenceStep(Override.Chance(30, Override.Fail(FailKind.Custom("boom"), 500))),
            SequenceStep(Override.Replace(200, bodyFile = "user.json")),
        )
        steps.forEach { assertEquals(it, built(StepForm.of(it))) }
    }

    @Test
    fun `editing a preset body drops the body file`() {
        val form = StepForm.of(SequenceStep(Override.Replace(200, bodyFile = "user.json")))
        assertEquals(Override.Replace(200, body = "[]"), built(form.copy(body = "[]")).override)
    }

    @Test
    fun `out of range numbers are reported`() {
        val form = StepForm.new(StepKind.Error)
        assertEquals(StepForm.Built.Error(DraftError.InvalidTimes), form.copy(times = "0").build())
        assertEquals(StepForm.Built.Error(DraftError.InvalidTimes), form.copy(times = "1001").build())
        assertEquals(StepForm.Built.Error(DraftError.InvalidPercent), form.copy(chance = true, percent = "100").build())
        assertEquals(StepForm.Built.Error(DraftError.PatchNotJson), StepForm.new(StepKind.Manual).copy(patchText = "{").build())
    }

    @Test
    fun `list edits keep at least one step`() {
        val a = StepForm.new(StepKind.Response)
        val b = StepForm.new(StepKind.Error)
        assertEquals(listOf(b, a), StepEdits.move(listOf(a, b), 0, 1))
        assertEquals(listOf(a, b), StepEdits.move(listOf(a, b), 1, 1))
        assertEquals(listOf(a, a, b), StepEdits.duplicate(listOf(a, b), 0))
        assertEquals(listOf(b), StepEdits.remove(listOf(a, b), 0))
        assertEquals(listOf(b), StepEdits.remove(listOf(b), 0))
        assertEquals(1 to DraftError.InvalidTimes, StepEdits.buildAll(listOf(a, b.copy(times = ""))).second)
    }
}
