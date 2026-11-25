package io.github.hyunchan.boggart.android.ui.ruleedit

import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.OnInvalidPatch
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.SequenceStep
import io.github.hyunchan.boggart.android.ui.common.DraftError
import io.github.hyunchan.boggart.android.ui.common.RuleDrafts
import io.github.hyunchan.boggart.android.ui.common.ValueEdits

/** 단계(또는 확률 조작 안쪽)의 조작 종류. 편집 폼이 이 종류에 맞는 입력칸을 그린다. */
internal enum class StepKind { Response, Error, Manual, Values }

/**
 * 순차 응답 단계 하나, 또는 확률 조작 규칙의 입력. 숫자 칸은 입력 중인 문자열 그대로 두고 [build]에서 검사한다.
 *
 * @property originalBody 처음 바디(없으면 null). 바디를 고치지 않으면 이 값과 [bodyFile]을 그대로 둔다.
 */
internal data class StepForm(
    val kind: StepKind,
    val status: String = "200",
    val headers: String = "",
    val body: String = "",
    val originalBody: String? = null,
    val bodyFile: String? = null,
    val mediaType: String? = "application/json",
    val patchText: String = "",
    val opsText: String = "",
    val ignoreInvalid: Boolean = true,
    val failKind: FailKind = FailKind.Timeout,
    val customMessage: String = DEFAULT_FAIL_MESSAGE,
    val delay: String = "0",
    val times: String = "1",
    val chance: Boolean = false,
    val percent: String = "50",
) {
    /** 입력으로 만든 단계. 입력이 잘못됐으면 그 이유. */
    sealed interface Built {
        data class Ok(val step: SequenceStep) : Built
        data class Error(val reason: DraftError) : Built
    }

    fun build(): Built {
        val times = times.toIntOrNull()?.takeIf { it in 1..SequenceStep.MAX_TIMES } ?: return Built.Error(DraftError.InvalidTimes)
        val inner = when (val result = buildInner()) {
            is Built.Ok -> result.step.override
            is Built.Error -> return result
        }
        val override = if (chance) {
            val percent = percent.toIntOrNull()?.takeIf { it in 1..99 } ?: return Built.Error(DraftError.InvalidPercent)
            Override.Chance(percent, inner)
        } else {
            inner
        }
        return Built.Ok(SequenceStep(override, times))
    }

    private fun buildInner(): Built {
        val delayMs = delay.toLongOrNull()?.coerceAtLeast(0) ?: 0
        val policy = if (ignoreInvalid) OnInvalidPatch.Ignore else OnInvalidPatch.PassThrough
        val override = when (kind) {
            StepKind.Response -> {
                val bodyChanged = body != originalBody.orEmpty()
                Override.Replace(
                    status = status.toIntOrNull() ?: DEFAULT_STATUS,
                    headers = RuleDrafts.parseHeaders(headers),
                    body = if (bodyChanged) body else originalBody,
                    bodyFile = if (bodyChanged) null else bodyFile,
                    mediaType = mediaType,
                    delayMs = delayMs,
                )
            }
            StepKind.Error -> Override.Fail(if (failKind is FailKind.Custom) FailKind.Custom(customMessage) else failKind, delayMs)
            StepKind.Manual -> {
                val patch = RuleDrafts.parseJson(patchText.toByteArray()) ?: return Built.Error(DraftError.PatchNotJson)
                Override.Patch(patch, delayMs, policy)
            }
            StepKind.Values -> {
                val ops = ValueEdits.parseOps(opsText) ?: return Built.Error(DraftError.OpsNotJsonPatch)
                if (ops.isEmpty()) return Built.Error(DraftError.NoOps)
                Override.JsonPatch(ops, delayMs, policy)
            }
        }
        return Built.Ok(SequenceStep(override))
    }

    companion object {
        const val DEFAULT_STATUS = 200
        const val DEFAULT_FAIL_MESSAGE = "Simulated by Boggart"

        fun of(step: SequenceStep): StepForm = of(step.override).copy(times = step.times.toString())

        /** 조작으로 입력을 만든다. 확률 조작이면 안쪽 조작의 입력에 확률을 켠다. 순차 응답은 단계가 될 수 없다. */
        fun of(override: Override): StepForm = when (override) {
            is Override.Chance -> of(override.override).copy(chance = true, percent = override.percent.toString())
            is Override.Replace -> StepForm(
                kind = StepKind.Response,
                status = override.status.toString(),
                headers = RuleDrafts.formatHeaders(override.headers),
                body = override.body.orEmpty(),
                originalBody = override.body,
                bodyFile = override.bodyFile,
                mediaType = override.mediaType,
                delay = override.delayMs.toString(),
            )
            is Override.Fail -> StepForm(
                kind = StepKind.Error,
                failKind = override.kind,
                customMessage = (override.kind as? FailKind.Custom)?.message ?: DEFAULT_FAIL_MESSAGE,
                delay = override.delayMs.toString(),
            )
            is Override.Patch -> StepForm(
                kind = StepKind.Manual,
                patchText = RuleDrafts.prettyPatch(override.patch),
                ignoreInvalid = override.onInvalid != OnInvalidPatch.PassThrough,
                delay = override.delayMs.toString(),
            )
            is Override.JsonPatch -> StepForm(
                kind = StepKind.Values,
                opsText = ValueEdits.formatOps(override.ops),
                ignoreInvalid = override.onInvalid != OnInvalidPatch.PassThrough,
                delay = override.delayMs.toString(),
            )
            is Override.Sequence -> error("A sequence can't be a step")
        }

        /** "단계 추가"로 만드는 새 단계. */
        fun new(kind: StepKind): StepForm = StepForm(kind)
    }
}

/** 단계 목록 편집. 화면 밖에서 검증할 수 있게 순수 함수로 둔다. */
internal object StepEdits {
    fun move(steps: List<StepForm>, index: Int, delta: Int): List<StepForm> {
        val to = index + delta
        if (index !in steps.indices || to !in steps.indices) return steps
        return steps.toMutableList().apply { add(to, removeAt(index)) }
    }

    fun duplicate(steps: List<StepForm>, index: Int): List<StepForm> =
        if (index in steps.indices) steps.toMutableList().apply { add(index + 1, steps[index]) } else steps

    /** 마지막 남은 단계는 지우지 않는다. */
    fun remove(steps: List<StepForm>, index: Int): List<StepForm> =
        if (steps.size > 1 && index in steps.indices) steps.toMutableList().apply { removeAt(index) } else steps

    /** 모든 단계를 만든다. 하나라도 잘못됐으면 첫 오류와 그 단계 번호. */
    fun buildAll(steps: List<StepForm>): Pair<List<SequenceStep>?, Pair<Int, DraftError>?> {
        val built = steps.mapIndexed { index, step ->
            when (val result = step.build()) {
                is StepForm.Built.Ok -> result.step
                is StepForm.Built.Error -> return null to (index to result.reason)
            }
        }
        return built to null
    }
}
