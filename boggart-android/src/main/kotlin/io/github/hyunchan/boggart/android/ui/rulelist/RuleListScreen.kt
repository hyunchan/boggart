@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.hyunchan.boggart.android.ui.rulelist
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.common.BackButton
import io.github.hyunchan.boggart.android.ui.common.CollectEffects
import io.github.hyunchan.boggart.android.ui.common.ModeBanner
import io.github.hyunchan.boggart.android.ui.common.RuleOrigin
import io.github.hyunchan.boggart.android.ui.common.color
import io.github.hyunchan.boggart.android.ui.common.label
import io.github.hyunchan.boggart.android.ui.common.overrideSentence
import io.github.hyunchan.boggart.android.ui.common.storeViewModel

/** 규칙 목록 화면. ViewModel을 만들고 상태 구독과 효과 처리만 맡는다. 그리기는 [RuleListContent]가 한다. */
@Composable
internal fun RuleListScreen(
    onBack: () -> Unit,
    onOpenRule: (String) -> Unit,
    viewModel: RuleListViewModel = storeViewModel(::RuleListViewModel),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects, snackbar)
    RuleListContent(
        temporary = state.rules.temporary,
        saved = state.rules.saved,
        preset = state.rules.preset,
        mode = state.mode,
        shadowedBy = state.shadowedBy,
        snackbar = snackbar,
        onBack = onBack,
        onOpenRule = onOpenRule,
        onToggle = { origin, rule, enabled -> viewModel.onIntent(RuleListIntent.Toggle(origin, rule, enabled)) },
        onSave = { viewModel.onIntent(RuleListIntent.Save(it)) },
        onRemove = { origin, rule -> viewModel.onIntent(RuleListIntent.Remove(origin, rule)) },
        onClearTemporary = { viewModel.onIntent(RuleListIntent.ClearTemporary) },
    )
}

/**
 * 규칙 목록의 모양. 상태를 받지 않으므로 아래 미리보기에서 그대로 쓴다.
 *
 * @param saved 저장 규칙을 쓰지 않는 설치면 null. 비어 있으면 섹션을 그리지 않는다.
 * @param preset 프리셋을 쓰지 않는 설치면 null. 비어 있으면 넣는 방법만 한 줄로 안내한다.
 */
@Composable
internal fun RuleListContent(
    temporary: List<Rule>,
    saved: List<Rule>?,
    preset: List<Rule>?,
    mode: Mode,
    onBack: () -> Unit,
    onOpenRule: (String) -> Unit,
    onToggle: (RuleOrigin, Rule, Boolean) -> Unit,
    onSave: (Rule) -> Unit,
    onRemove: (RuleOrigin, Rule) -> Unit,
    onClearTemporary: () -> Unit,
    modifier: Modifier = Modifier,
    shadowedBy: Map<String, String> = emptyMap(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var confirmClear by remember { mutableStateOf(false) }
    // 앱이 여러 서버를 쓸 때만 호스트를 보여 준다. 하나뿐이면 반복되는 잡음이다.
    val showHost = (temporary + saved.orEmpty() + preset.orEmpty()).mapNotNull { it.matcher.host }.toSet().size > 1
    val canSave = saved != null
    val temporaryTitle = stringResource(R.string.boggart_section_temporary)
    val temporaryHint = stringResource(R.string.boggart_section_temporary_hint)
    val temporaryEmpty = stringResource(R.string.boggart_section_temporary_empty)
    val savedTitle = stringResource(R.string.boggart_section_saved)
    val savedHint = stringResource(R.string.boggart_section_saved_hint)
    val presetTitle = stringResource(R.string.boggart_section_preset)
    val presetHint = stringResource(R.string.boggart_section_preset_hint)
    val presetEmpty = stringResource(R.string.boggart_section_preset_empty)
    val clearAll = stringResource(R.string.boggart_clear_all)

    // 맨 위에서는 본문과 같은 색으로 평평하다가, 내용이 밑으로 지나가면 surfaceContainer로 바뀌어 경계를 드러낸다.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(stringResource(R.string.boggart_rules_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 규칙 일시정지·오프라인이면 스위치가 켜져 있어도 규칙이 동작하지 않으므로 여기서도 알린다.
            ModeBanner(mode)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                section(
                    key = "temporary",
                    title = temporaryTitle,
                    hint = temporaryHint,
                    rules = temporary,
                    emptyText = temporaryEmpty,
                    action = if (temporary.isNotEmpty()) clearAll to { confirmClear = true } else null,
                ) { rule -> RuleRow(rule, RuleOrigin.Temporary, showHost, canSave, shadowedBy[rule.id], onOpenRule, onToggle, onSave, onRemove) }
                if (!saved.isNullOrEmpty()) {
                    section(key = "saved", title = savedTitle, hint = savedHint, rules = saved) { rule ->
                        RuleRow(rule, RuleOrigin.Saved, showHost, canSave, shadowedBy[rule.id], onOpenRule, onToggle, onSave, onRemove)
                    }
                }
                if (preset != null) {
                    section(key = "preset", title = presetTitle, hint = presetHint, rules = preset, emptyText = presetEmpty) { rule ->
                        RuleRow(rule, RuleOrigin.Preset, showHost, canSave, shadowedBy[rule.id], onOpenRule, onToggle, onSave, onRemove)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.boggart_riddikulus)) },
            text = { Text(pluralStringResource(R.plurals.boggart_riddikulus_all_message, temporary.size, temporary.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearTemporary()
                }) { Text(stringResource(R.string.boggart_ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.boggart_cancel)) } },
        )
    }
}

/** 섹션 머리(이름·개수·설명, 필요하면 오른쪽 동작)와 규칙 줄. 비어 있으면 [emptyText]가 있을 때만 한 줄 안내한다. */
private fun LazyListScope.section(
    key: String,
    title: String,
    hint: String,
    rules: List<Rule>,
    emptyText: String? = null,
    action: Pair<String, () -> Unit>? = null,
    row: @Composable (Rule) -> Unit,
) {
    item(key = "section:$key") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("$title (${rules.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (rules.isEmpty() && emptyText != null) emptyText else hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
        }
    }
    items(rules, key = { "$key:${it.id}" }) { rule ->
        row(rule)
        HorizontalDivider()
    }
}

/** 규칙 한 줄: 이름과 하는 일, 켜기 스위치, ⋮ 메뉴(저장·지우기). 꺼진 규칙은 흐리게. */
@Composable
private fun RuleRow(
    rule: Rule,
    origin: RuleOrigin,
    showHost: Boolean,
    canSave: Boolean,
    shadowedBy: String?,
    onOpenRule: (String) -> Unit,
    onToggle: (RuleOrigin, Rule, Boolean) -> Unit,
    onSave: (Rule) -> Unit,
    onRemove: (RuleOrigin, Rule) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenRule(rule.id) }
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (rule.enabled) 1f else 0.5f)) {
            Text(rule.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val host = rule.matcher.host?.takeIf { showHost }
            Text(
                listOfNotNull(overrideSentence(rule.override), host).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 켜져 있어도 앞선 규칙에 조건이 덮여 적용될 일이 없으면 알린다. 스위치만 보면 동작하는 줄 알기 때문이다.
            shadowedBy?.let {
                Text(
                    stringResource(R.string.boggart_rule_shadowed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = rule.enabled, onCheckedChange = { onToggle(origin, rule, it) })
        if (origin == RuleOrigin.Preset) {
            // 프리셋은 켜고 끄기만 할 수 있어 메뉴가 없다. 자리만 맞춘다.
            Box(Modifier.padding(start = 48.dp))
        } else {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.boggart_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (origin == RuleOrigin.Temporary && canSave) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.boggart_save)) }, onClick = {
                            menuOpen = false
                            onSave(rule)
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.boggart_remove)) }, onClick = {
                        menuOpen = false
                        onRemove(origin, rule)
                    })
                }
            }
        }
    }
}
