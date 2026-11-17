@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.hyunchan.boggart.android.ui.ruleedit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.calldetail.FailKindPicker
import io.github.hyunchan.boggart.android.ui.common.BackButton
import io.github.hyunchan.boggart.android.ui.common.CodeField
import io.github.hyunchan.boggart.android.ui.common.CollectEffects
import io.github.hyunchan.boggart.android.ui.common.DelayPicker
import io.github.hyunchan.boggart.android.ui.common.NumberField
import io.github.hyunchan.boggart.android.ui.common.RuleOrigin
import io.github.hyunchan.boggart.android.ui.common.SectionTitle
import io.github.hyunchan.boggart.android.ui.common.label
import io.github.hyunchan.boggart.android.ui.common.matcherLabel
import io.github.hyunchan.boggart.android.ui.common.overrideLabel
import io.github.hyunchan.boggart.android.ui.common.storeViewModel

/** 규칙 편집 화면. ViewModel을 만들고 상태 구독과 효과 처리만 맡는다. 그리기는 [RuleEditContent]가 한다. */
@Composable
internal fun RuleEditScreen(
    ruleId: String,
    onBack: () -> Unit,
    viewModel: RuleEditViewModel = storeViewModel { RuleEditViewModel(ruleId, it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects, snackbar, onClose = onBack)
    RuleEditContent(state, viewModel::onIntent, onBack, snackbar = snackbar)
}

/** 규칙 편집의 모양. 상태와 의도 전달만 받으므로 미리보기에서 그대로 쓴다. 규칙이 없으면 해제된 규칙이다. */
@Composable
internal fun RuleEditContent(
    state: RuleEditState,
    onIntent: (RuleEditIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val found = state.found
    // 맨 위에서는 본문과 같은 색으로 평평하다가, 내용이 밑으로 지나가면 surfaceContainer로 바뀌어 경계를 드러낸다.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(found?.second?.name ?: stringResource(R.string.boggart_rules_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val form = state.form
        if (found == null || form == null) {
            Text(stringResource(R.string.boggart_rule_lifted), modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val (origin, rule) = found
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(stringResource(R.string.boggart_rule_origin, origin.label(), rule.id), style = MaterialTheme.typography.labelSmall)
            if (origin == RuleOrigin.Preset) {
                PresetRuleView(rule) { onIntent(RuleEditIntent.SetPresetEnabled(it)) }
            } else {
                RuleFormFields(origin, rule, form, state.canSave, onIntent)
            }
        }
    }
}

@Composable
private fun ColumnScope.PresetRuleView(rule: Rule, onEnabledChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.boggart_enabled), modifier = Modifier.weight(1f))
        Switch(checked = rule.enabled, onCheckedChange = onEnabledChange)
    }
    Text("${overrideLabel(rule.override)} · ${matcherLabel(rule)}", style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.boggart_preset_readonly), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ColumnScope.RuleFormFields(origin: RuleOrigin, rule: Rule, form: RuleForm, canSave: Boolean, onIntent: (RuleEditIntent) -> Unit) {
    val override = rule.override
    val edit = { next: RuleForm -> onIntent(RuleEditIntent.Edit(next)) }

    SectionTitle(stringResource(R.string.boggart_conditions))
    OutlinedTextField(
        form.method,
        { edit(form.copy(method = it)) },
        label = { Text(stringResource(R.string.boggart_method)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        form.host,
        { edit(form.copy(host = it)) },
        label = { Text(stringResource(R.string.boggart_host)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        form.path,
        { edit(form.copy(path = it)) },
        label = { Text(stringResource(R.string.boggart_path_glob)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.boggart_header_match_notice), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    CodeField(stringResource(R.string.boggart_header_conditions), form.matchHeaders, { edit(form.copy(matchHeaders = it)) })

    SectionTitle(overrideLabel(override))
    when (override) {
        is Override.Replace -> {
            NumberField(stringResource(R.string.boggart_status_code), form.status, { edit(form.copy(status = it)) })
            CodeField(stringResource(R.string.boggart_response_headers_field), form.responseHeaders, { edit(form.copy(responseHeaders = it)) })
            override.bodyFile?.let { Text(stringResource(R.string.boggart_preset_body, it), style = MaterialTheme.typography.bodySmall) }
            CodeField(stringResource(R.string.boggart_response_body_field), form.body.orEmpty(), { edit(form.copy(body = it)) })
        }
        is Override.Patch -> {
            CodeField("Merge Patch", form.patchText, { edit(form.copy(patchText = it)) })
            InvalidCheckbox(form.ignoreInvalid) { edit(form.copy(ignoreInvalid = it)) }
        }
        is Override.JsonPatch -> {
            CodeField("JSON Patch (op · path · value)", form.opsText, { edit(form.copy(opsText = it)) })
            InvalidCheckbox(form.ignoreInvalid) { edit(form.copy(ignoreInvalid = it)) }
        }
        is Override.Fail -> FailKindPicker(form.failKind, form.customMessage, { edit(form.copy(failKind = it)) }, { edit(form.copy(customMessage = it)) })
    }
    DelayPicker(form.delay) { edit(form.copy(delay = it)) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (origin) {
            RuleOrigin.Temporary -> {
                OutlinedButton(onClick = { onIntent(RuleEditIntent.Apply) }) { Text(stringResource(R.string.boggart_apply)) }
                Button(onClick = { onIntent(RuleEditIntent.Save) }, enabled = canSave) { Text(stringResource(R.string.boggart_save)) }
                OutlinedButton(onClick = { onIntent(RuleEditIntent.Remove) }) { Text(stringResource(R.string.boggart_remove)) }
            }
            RuleOrigin.Saved -> {
                Button(onClick = { onIntent(RuleEditIntent.Save) }) { Text(stringResource(R.string.boggart_save)) }
                OutlinedButton(onClick = { onIntent(RuleEditIntent.Remove) }) { Text(stringResource(R.string.boggart_remove)) }
            }
            RuleOrigin.Preset -> Unit
        }
    }
}

/**
 * 패치 결과가 검증에 실패했을 때 원본을 전달할지. 기본 검사기(JSON 문법)로는 패치 결과가 늘 통과하므로,
 * 앱이 스키마 검사 같은 `BodyValidator`를 지정했을 때만 의미가 있는 고급 설정이다.
 */
@Composable
private fun InvalidCheckbox(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(stringResource(R.string.boggart_ignore_invalid))
    }
}
