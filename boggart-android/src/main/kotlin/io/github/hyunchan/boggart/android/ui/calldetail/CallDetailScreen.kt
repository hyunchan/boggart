@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.hyunchan.boggart.android.ui.calldetail
import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.calllist.formatTime
import io.github.hyunchan.boggart.android.ui.common.BackButton
import io.github.hyunchan.boggart.android.ui.common.CallStatus
import io.github.hyunchan.boggart.android.ui.common.CallStatusTheme
import io.github.hyunchan.boggart.android.ui.common.ChoiceRow
import io.github.hyunchan.boggart.android.ui.common.CodeField
import io.github.hyunchan.boggart.android.ui.common.CollectEffects
import io.github.hyunchan.boggart.android.ui.common.JsonTree
import io.github.hyunchan.boggart.android.ui.common.JsonTreeView
import io.github.hyunchan.boggart.android.ui.common.NumberField
import io.github.hyunchan.boggart.android.ui.common.ProvenanceBadge
import io.github.hyunchan.boggart.android.ui.common.RuleDrafts
import io.github.hyunchan.boggart.android.ui.common.SectionTitle
import io.github.hyunchan.boggart.android.ui.common.SectionTitleWithInfo
import io.github.hyunchan.boggart.android.ui.common.color
import io.github.hyunchan.boggart.android.ui.common.expandedSaver
import io.github.hyunchan.boggart.android.ui.common.failKindLabel
import io.github.hyunchan.boggart.android.ui.common.formatBytes
import io.github.hyunchan.boggart.android.ui.common.label
import io.github.hyunchan.boggart.android.ui.common.status
import io.github.hyunchan.boggart.android.ui.common.statusDescription
import io.github.hyunchan.boggart.android.ui.common.storeViewModel
import kotlinx.coroutines.launch

private enum class ResponseTab(@param:StringRes val label: Int) {
    Original(R.string.boggart_response_original),
    Delivered(R.string.boggart_response_delivered),
}

private val STATUS_PRESETS = listOf("200", "401", "403", "404", "500", "503")

/** 호출 상세 화면. ViewModel을 만들고 상태 구독과 효과 처리만 맡는다. 그리기는 [CallDetailContent]가 한다. */
@Composable
internal fun CallDetailScreen(
    callId: Long,
    onBack: () -> Unit,
    onOpenRule: (String) -> Unit,
    onReturnToApp: () -> Unit,
    viewModel: CallDetailViewModel = storeViewModel { CallDetailViewModel(callId, it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects, snackbar, onReturnToApp = onReturnToApp)
    // 조작됨·실패·적용 안 됨 호출은 화면 강조색을 그 상태색으로 바꾼다.
    CallStatusTheme(state.exchange?.provenance?.status() ?: CallStatus.Original) {
        CallDetailContent(state, viewModel::onIntent, onBack, onOpenRule, snackbar = snackbar)
    }
}

/** 호출 상세의 모양. 상태와 의도 전달만 받으므로 미리보기에서 그대로 쓴다. */
@Composable
internal fun CallDetailContent(
    state: CallDetailState,
    onIntent: (CallDetailIntent) -> Unit,
    onBack: () -> Unit,
    onOpenRule: (String) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val exchange = state.exchange
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    var copyMenu by remember { mutableStateOf(false) }
    // 규칙으로 만들지 않은 수정이 있으면 나가기 전에 묻는다. 묻는 다이얼로그는 화면 안의 일이라 여기서 연다.
    var confirmLeave by remember { mutableStateOf(false) }
    val requestBack = { if (state.dirty) confirmLeave = true else onBack() }
    BackHandler(enabled = state.dirty) { confirmLeave = true }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.boggart_discard_title)) },
            text = { Text(stringResource(R.string.boggart_discard_message)) },
            // 위계: 주 동작인 계속 편집을 오른쪽(confirm) 강조색으로, 나가기는 왼쪽(dismiss) 회색으로 낮춘다.
            confirmButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.boggart_keep_editing)) } },
            dismissButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }) {
                    Text(stringResource(R.string.boggart_discard), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    fun copy(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipData.newPlainText("Boggart", text).toClipEntry())
            snackbar.showSnackbar(resources.getString(R.string.boggart_copied))
        }
    }

    // 맨 위에서는 본문과 같은 색으로 평평하다가, 내용이 밑으로 지나가면 surfaceContainer로 바뀌어 경계를 드러낸다.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                // 번호는 프로세스마다 1부터 다시 매기므로 제목이 아니라 부제로 둔다. 제목은 무슨 호출인지다.
                title = {
                    if (exchange == null) {
                        Text("#${state.callId}")
                    } else {
                        // 상태는 알아보기 용도라 본문이 아니라 상단 바 부제에 둔다. 배지를 누르면 상태 설명이 나온다.
                        Column {
                            Text(
                                RuleDrafts.ruleName(exchange.request),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            CallSubtitle(exchange)
                        }
                    }
                },
                navigationIcon = { BackButton(requestBack) },
                actions = {
                    if (exchange != null) {
                        Box {
                            IconButton(onClick = { copyMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.boggart_more))
                            }
                            DropdownMenu(expanded = copyMenu, onDismissRequest = { copyMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.boggart_copy_url)) }, onClick = {
                                    copyMenu = false
                                    copy(exchange.request.url.toString())
                                })
                                val body = exchange.deliveredResponse?.body ?: exchange.originalResponse?.body
                                if (body != null) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.boggart_copy_body)) }, onClick = {
                                        copyMenu = false
                                        copy(RuleDrafts.prettyBody(body))
                                    })
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.boggart_copy_curl)) }, onClick = {
                                    copyMenu = false
                                    copy(RuleDrafts.curlOf(exchange.request))
                                })
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // 모든 조작이 같은 하단 바(지연 메뉴 + 버튼)를 쓴다. 빠른 편집은 그 위에 변경 목록을 얹는다.
            val serverJson = state.serverJson
            when {
                exchange == null -> Unit
                state.showsValueTree && serverJson != null -> ValuesActionBar(
                    original = serverJson,
                    ops = state.ops,
                    onOps = { onIntent(CallDetailIntent.SetOps(it)) },
                    delayMs = state.editor.delay,
                    onDelay = { onIntent(CallDetailIntent.Edit(state.editor.copy(delay = it))) },
                    buttonText = stringResource(state.submitButton),
                    onSubmit = { onIntent(CallDetailIntent.Submit) },
                )
                // 빠른 편집을 쓸 수 없는 호출(서버 응답 없음·JSON 아님)은 이유만 보여 주고 버튼을 두지 않는다.
                state.action == Action.Values -> Unit
                else -> RuleActionBar(
                    delayMs = state.editor.delay,
                    onDelay = { onIntent(CallDetailIntent.Edit(state.editor.copy(delay = it))) },
                    buttonText = stringResource(state.submitButton),
                    enabled = true,
                    onSubmit = { onIntent(CallDetailIntent.Submit) },
                )
            }
        },
    ) { padding ->
        if (exchange == null) {
            Text(stringResource(R.string.boggart_call_gone), modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // 규칙 만들기를 응답보다 위에 둔다. 응답 바디가 길어도 액션까지 스크롤하지 않게 한다.
            CallNotes(exchange, state.appliedRuleName, state.matchingRuleCount, onOpenRule)
            RuleEditor(exchange, state, onIntent)
            // 주소·요청 헤더·요청 바디는 가끔 확인하는 정보라 응답 위에 접어 둔다.
            RequestSection(exchange)
            // 빠른 편집 트리가 실제로 그려질 때(서버 응답이 JSON일 때)는 같은 바디를 보여 주므로 아래 바디를 접어 둔다.
            ResponseTabs(exchange, collapseBody = state.showsValueTree)
        }
    }
}

/** 상단 바 부제: 상태 배지(누르면 설명) · 상태 코드 · 응답 크기 · 시각 · 번호. */
@Composable
private fun CallSubtitle(exchange: Exchange) {
    var explain by remember { mutableStateOf(false) }
    val delivered = exchange.deliveredResponse
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ProvenanceBadge(exchange.provenance, Modifier.clickable { explain = true })
        Text(
            listOfNotNull(
                delivered?.status?.toString() ?: "ERR",
                delivered?.body?.let { formatBytes(it.size) },
                formatTime(exchange.timestampMs),
                "#${exchange.id}",
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text(exchange.provenance.status().label()) },
            text = {
                Text(listOfNotNull(exchange.statusDescription(), exchange.error).joinToString("\n\n"), style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.boggart_close)) } },
        )
    }
}

/** 본문 맨 위에 남기는 것: 동작이 있는 적용된 규칙 링크와, 실패 원인처럼 놓치면 안 되는 오류 문구. */
@Composable
private fun ColumnScope.CallNotes(exchange: Exchange, appliedRuleName: String?, matchingRuleCount: Int, onOpenRule: (String) -> Unit) {
    val id = exchange.appliedRuleId
    if (id != null && appliedRuleName != null) {
        val name = appliedRuleName
        AssistChip(onClick = { onOpenRule(id) }, label = { Text(stringResource(R.string.boggart_applied_rule, name), maxLines = 1, overflow = TextOverflow.Ellipsis) })
    }
    // 같은 호출에 맞는 규칙이 여럿이면 하나만 적용되고 나머지는 가려진다. 규칙 목록의 "가려짐" 표시와 짝이다.
    if (matchingRuleCount > 1) {
        Text(
            pluralStringResource(R.plurals.boggart_matching_rules, matchingRuleCount, matchingRuleCount),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    exchange.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** 요청 섹션: 제목을 눌러 펼치면 주소, 요청 헤더, 요청 바디(JSON이면 트리). */
@Composable
private fun ColumnScope.RequestSection(exchange: Exchange) {
    var open by rememberSaveable(exchange.id) { mutableStateOf(false) }
    var headersOpen by rememberSaveable { mutableStateOf(false) }
    var bodyOpen by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(top = 12.dp),
    ) {
        Text(
            stringResource(R.string.boggart_request),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    if (!open) return
    Text(
        exchange.request.url.toString(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val headerCount = exchange.request.headers.size
    Row {
        TextButton(onClick = { headersOpen = !headersOpen }) {
            Text(
                if (headersOpen) {
                    stringResource(R.string.boggart_request_headers_hide)
                } else {
                    pluralStringResource(R.plurals.boggart_request_headers_show, headerCount, headerCount)
                },
            )
        }
        if (exchange.request.body?.isNotEmpty() == true) {
            TextButton(onClick = { bodyOpen = !bodyOpen }) {
                Text(stringResource(if (bodyOpen) R.string.boggart_request_body_hide else R.string.boggart_request_body_show))
            }
        }
    }
    if (headersOpen) {
        Text(
            exchange.request.headers.toList().joinToString("\n") { "${it.first}: ${it.second}" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val requestBody = exchange.request.body
    if (bodyOpen && requestBody != null) {
        val json = remember(requestBody) { RuleDrafts.parseJson(requestBody) }
        if (json == null) {
            CodeField(stringResource(R.string.boggart_body), RuleDrafts.prettyBody(requestBody), {}, readOnly = true)
        } else {
            var expanded by rememberSaveable(exchange.id, stateSaver = expandedSaver) { mutableStateOf(JsonTree.INITIAL_EXPANDED) }
            JsonTreeView(json, expanded, onToggle = { expanded = JsonTree.toggle(expanded, it) })
        }
    }
}

@Composable
private fun ColumnScope.ResponseTabs(exchange: Exchange, collapseBody: Boolean) {
    var tab by rememberSaveable { mutableStateOf(ResponseTab.Delivered) }
    SectionTitle(stringResource(R.string.boggart_response))
    // 서버 응답과 앱이 받은 응답이 다를 때(조작됨)만 탭을 보여 준다. 같으면 하나만.
    val differs = exchange.originalResponse != null &&
        exchange.deliveredResponse != null &&
        exchange.originalResponse != exchange.deliveredResponse
    if (differs) ChoiceRow(ResponseTab.entries, tab, { stringResource(it.label) }) { tab = it }
    val response = if (differs && tab == ResponseTab.Original) {
        exchange.originalResponse
    } else {
        exchange.deliveredResponse ?: exchange.originalResponse
    }
    if (response == null) {
        Text(stringResource(R.string.boggart_none), style = MaterialTheme.typography.bodySmall)
        return
    }
    Text("${response.status}  ${response.mediaType.orEmpty()}", style = MaterialTheme.typography.labelLarge)
    // 요청 헤더처럼 접어 둔다. 주로 보는 것은 바디이고, 펼침 상태는 원본/전달 탭에 공통이다.
    var headersOpen by rememberSaveable(exchange.id) { mutableStateOf(false) }
    val headerCount = response.headers.toList().size
    if (headerCount > 0) {
        TextButton(onClick = { headersOpen = !headersOpen }) {
            Text(
                if (headersOpen) {
                    stringResource(R.string.boggart_response_headers_hide)
                } else {
                    pluralStringResource(R.plurals.boggart_response_headers_show, headerCount, headerCount)
                },
            )
        }
        if (headersOpen) CodeField(stringResource(R.string.boggart_headers), RuleDrafts.formatResponseHeaders(response), {}, readOnly = true)
    }
    SectionTitle(stringResource(R.string.boggart_body))
    var bodyOpen by rememberSaveable(exchange.id) { mutableStateOf(false) }
    if (collapseBody && !bodyOpen) {
        Text(stringResource(R.string.boggart_body_same_as_tree), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { bodyOpen = true }) { Text(stringResource(R.string.boggart_body_show)) }
        return
    }
    if (collapseBody) TextButton(onClick = { bodyOpen = false }) { Text(stringResource(R.string.boggart_body_hide)) }
    val body = response.body
    val json = remember(body) { body?.let(RuleDrafts::parseJson) }
    when {
        body == null -> Text(stringResource(R.string.boggart_body_not_captured), style = MaterialTheme.typography.bodySmall)
        json == null -> CodeField(stringResource(R.string.boggart_body), RuleDrafts.prettyBody(body), {}, readOnly = true)
        else -> {
            // 탭(원본/전달)마다 펼침 상태를 따로 둔다.
            var expanded by rememberSaveable(exchange.id, tab, stateSaver = expandedSaver) { mutableStateOf(JsonTree.INITIAL_EXPANDED) }
            JsonTreeView(json, expanded, onToggle = { expanded = JsonTree.toggle(expanded, it) })
        }
    }
}

@Composable
private fun ColumnScope.RuleEditor(exchange: Exchange, state: CallDetailState, onIntent: (CallDetailIntent) -> Unit) {
    val action = state.action
    val editor = state.editor
    val edit = { fields: EditorFields -> onIntent(CallDetailIntent.Edit(fields)) }
    // 조작 설명은 익숙해지면 읽지 않는 정보라 화면에 펼쳐 두지 않고 제목 옆 ⓘ로 연다.
    // 안내는 고른 조작만이 아니라 세 조작을 나란히 보여 줘 무엇을 고를지 비교하게 한다.
    SectionTitleWithInfo(stringResource(R.string.boggart_create_rule), Action.entries.map { stringResource(it.label) to actionGuide(it) })
    ChoiceRow(Action.entries, action, { stringResource(it.label) }) { onIntent(CallDetailIntent.SelectAction(it)) }
    when (action) {
        Action.Values -> {
            // 빠른 편집은 트리가 주 화면이다. 지연과 만들기 버튼은 모든 조작처럼 화면 아래 고정 바에 있다.
            // 고칠 수 없는 이유(서버 응답 없음, JSON 아님)만 화면에 남긴다.
            ValuesUnavailable(state.serverJson, hasServerResponse = exchange.originalResponse != null)
            ValueTree(exchange.id, state.serverJson, state.ops) { onIntent(CallDetailIntent.SetOps(it)) }
        }
        Action.Manual -> {
            CodeField(stringResource(R.string.boggart_edit_body), editor.bodyText, { edit(editor.copy(bodyText = it)) })
        }
        Action.Respond -> {
            // 서버를 부르지 않는 두 갈래: 정한 응답을 돌려주거나 네트워크 에러를 낸다.
            ChoiceRow(RespondKind.entries, editor.respondKind, { stringResource(it.label) }) { edit(editor.copy(respondKind = it)) }
            when (editor.respondKind) {
                RespondKind.Response -> {
                    // 자주 쓰는 상태 코드는 칩으로 바로 고른다. 그 밖의 코드는 아래 칸에 직접.
                    ChoiceRow(STATUS_PRESETS, editor.status.takeIf { it in STATUS_PRESETS }, { it }) { edit(editor.copy(status = it)) }
                    NumberField(stringResource(R.string.boggart_status_code), editor.status, { edit(editor.copy(status = it)) })
                    CodeField(stringResource(R.string.boggart_response_headers_field), editor.headersText, { edit(editor.copy(headersText = it)) })
                    CodeField(stringResource(R.string.boggart_response_body_field), editor.bodyText, { edit(editor.copy(bodyText = it)) })
                }
                RespondKind.Error -> FailKindPicker(
                    editor.failKind,
                    editor.customMessage,
                    { edit(editor.copy(failKind = it)) },
                    { edit(editor.copy(customMessage = it)) },
                )
            }
        }
    }
}

/** 고른 조작의 안내 문단. ⓘ 다이얼로그에 보여 준다. */
@Composable
private fun actionGuide(action: Action): String = when (action) {
    // 세 조작을 나열할 때 빠른 편집만 두 문단으로 갈라지지 않게 한 문단으로 잇는다.
    Action.Values -> stringResource(R.string.boggart_action_values_desc) + " " + stringResource(R.string.boggart_values_hint)
    Action.Manual -> stringResource(R.string.boggart_manual_hint)
    Action.Respond -> stringResource(R.string.boggart_action_respond_desc)
}

@Composable
internal fun FailKindPicker(
    kind: FailKind,
    customMessage: String,
    onKind: (FailKind) -> Unit,
    onMessage: (String) -> Unit,
) {
    val kinds = listOf(FailKind.Timeout, FailKind.ConnectionReset, FailKind.UnknownHost, FailKind.Custom(customMessage))
    ChoiceRow(kinds, kinds.first { it::class == kind::class }, { failKindLabel(it) }) { onKind(it) }
    if (kind is FailKind.Custom) {
        OutlinedTextField(
            value = customMessage,
            onValueChange = onMessage,
            label = { Text(stringResource(R.string.boggart_exception_message)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
