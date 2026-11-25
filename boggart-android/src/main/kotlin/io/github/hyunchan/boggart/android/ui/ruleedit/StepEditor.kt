package io.github.hyunchan.boggart.android.ui.ruleedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.calldetail.FailKindPicker
import io.github.hyunchan.boggart.android.ui.common.CodeField
import io.github.hyunchan.boggart.android.ui.common.DelayPicker
import io.github.hyunchan.boggart.android.ui.common.NumberField
import io.github.hyunchan.boggart.android.ui.common.stepSentence

/**
 * 순차 응답 단계 목록 편집. 단계를 누르면 그 자리에서 펼쳐 입력칸을 보여 준다(한 번에 하나).
 * 고친 내용은 적용·저장을 눌러야 반영된다.
 *
 * @param nextStep 지금 규칙에서 다음에 보낼 단계. 아직 적용하지 않은 편집과 어긋날 수 있어 표시에만 쓴다.
 */
@Composable
internal fun ColumnScope.SequenceEditor(form: RuleForm, nextStep: Int?, onIntent: (RuleEditIntent) -> Unit) {
    val steps = form.steps
    val calls = steps.sumOf { it.times.toIntOrNull() ?: 1 }
    Text(
        pluralStringResource(R.plurals.boggart_desc_sequence_steps, steps.size, steps.size) +
            if (calls != steps.size) " · " + pluralStringResource(R.plurals.boggart_desc_sequence_calls, calls, calls) else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    steps.forEachIndexed { index, step ->
        key(index) {
            StepRow(
                index = index,
                step = step,
                isNext = index == nextStep,
                expanded = form.expanded == index,
                canMoveUp = index > 0,
                canMoveDown = index < steps.lastIndex,
                canRemove = steps.size > 1,
                onIntent = onIntent,
            )
        }
    }
    if (nextStep != null && nextStep >= steps.size) {
        Text(stringResource(R.string.boggart_sequence_used_up_full), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    AddStepButton { onIntent(RuleEditIntent.AddStep(it)) }
}

@Composable
private fun ColumnScope.StepRow(
    index: Int,
    step: StepForm,
    isNext: Boolean,
    expanded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onIntent: (RuleEditIntent) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onIntent(RuleEditIntent.ToggleStep(index)) }.padding(vertical = 2.dp),
    ) {
        Text(
            stringResource(R.string.boggart_sequence_step, index + 1, stepSummary(step)) +
                if (isNext) " · " + stringResource(R.string.boggart_sequence_next_marker) else "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (expanded || isNext) FontWeight.Bold else null,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.boggart_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                StepMenuItem(R.string.boggart_step_move_up, canMoveUp) {
                    menuOpen = false
                    onIntent(RuleEditIntent.MoveStep(index, -1))
                }
                StepMenuItem(R.string.boggart_step_move_down, canMoveDown) {
                    menuOpen = false
                    onIntent(RuleEditIntent.MoveStep(index, 1))
                }
                StepMenuItem(R.string.boggart_step_duplicate, true) {
                    menuOpen = false
                    onIntent(RuleEditIntent.DuplicateStep(index))
                }
                StepMenuItem(R.string.boggart_remove, canRemove) {
                    menuOpen = false
                    onIntent(RuleEditIntent.RemoveStep(index))
                }
            }
        }
    }
    if (expanded) {
        Column(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
            StepFields(step, showTimes = true) { onIntent(RuleEditIntent.EditStep(index, it)) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun StepMenuItem(label: Int, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick, enabled = enabled)
}

/** 단계 요약. 입력이 아직 완성되지 않았으면 종류 이름만 보인다. */
@Composable
private fun stepSummary(step: StepForm): String = when (val built = step.build()) {
    is StepForm.Built.Ok -> stepSentence(built.step)
    is StepForm.Built.Error -> stringResource(step.kind.labelRes())
}

private fun StepKind.labelRes(): Int = when (this) {
    StepKind.Response -> R.string.boggart_respond_response
    StepKind.Error -> R.string.boggart_respond_error
    StepKind.Manual -> R.string.boggart_action_manual
    StepKind.Values -> R.string.boggart_action_values
}

/** 새 단계는 순차 응답에서 주로 쓰는 응답 돌려주기·네트워크 에러로 만든다. 패치는 실제 응답이 있어야 해 호출 화면에서 만든다. */
@Composable
private fun AddStepButton(onAdd: (StepKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = { open = true }) { Text(stringResource(R.string.boggart_sequence_add_step)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(StepKind.Response, StepKind.Error).forEach { kind ->
                DropdownMenuItem(text = { Text(stringResource(kind.labelRes())) }, onClick = {
                    open = false
                    onAdd(kind)
                })
            }
        }
    }
}

/**
 * 단계 하나(또는 확률 조작 규칙)의 입력칸. 종류별 입력, 지연, 반복 횟수([showTimes]일 때), 확률.
 */
@Composable
internal fun ColumnScope.StepFields(step: StepForm, showTimes: Boolean, onChange: (StepForm) -> Unit) {
    when (step.kind) {
        StepKind.Response -> {
            NumberField(stringResource(R.string.boggart_status_code), step.status, { onChange(step.copy(status = it)) })
            CodeField(stringResource(R.string.boggart_response_headers_field), step.headers, { onChange(step.copy(headers = it)) })
            step.bodyFile?.let { Text(stringResource(R.string.boggart_preset_body, it), style = MaterialTheme.typography.bodySmall) }
            CodeField(stringResource(R.string.boggart_response_body_field), step.body, { onChange(step.copy(body = it)) })
        }
        StepKind.Error -> FailKindPicker(step.failKind, step.customMessage, { onChange(step.copy(failKind = it)) }, { onChange(step.copy(customMessage = it)) })
        StepKind.Manual -> {
            CodeField("Merge Patch", step.patchText, { onChange(step.copy(patchText = it)) })
            InvalidCheckbox(step.ignoreInvalid) { onChange(step.copy(ignoreInvalid = it)) }
        }
        StepKind.Values -> {
            CodeField("JSON Patch (op · path · value)", step.opsText, { onChange(step.copy(opsText = it)) })
            InvalidCheckbox(step.ignoreInvalid) { onChange(step.copy(ignoreInvalid = it)) }
        }
    }
    DelayPicker(step.delay) { onChange(step.copy(delay = it)) }
    if (showTimes) NumberField(stringResource(R.string.boggart_step_times), step.times, { onChange(step.copy(times = it)) })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = step.chance, onCheckedChange = { onChange(step.copy(chance = it)) })
        Text(stringResource(R.string.boggart_step_chance))
    }
    if (step.chance) NumberField(stringResource(R.string.boggart_step_percent), step.percent, { onChange(step.copy(percent = it)) })
}
