package io.github.hyunchan.boggart.android.ui.calldetail
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hyunchan.boggart.JsonPatchOp
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.common.CODE_FIELD_MAX_HEIGHT
import io.github.hyunchan.boggart.android.ui.common.ChoiceRow
import io.github.hyunchan.boggart.android.ui.common.JsonRow
import io.github.hyunchan.boggart.android.ui.common.JsonTree
import io.github.hyunchan.boggart.android.ui.common.NumberField
import io.github.hyunchan.boggart.android.ui.common.TreeChevron
import io.github.hyunchan.boggart.android.ui.common.ValueEdits
import io.github.hyunchan.boggart.android.ui.common.ValueType
import io.github.hyunchan.boggart.android.ui.common.color
import io.github.hyunchan.boggart.android.ui.common.expandedSaver
import io.github.hyunchan.boggart.android.ui.common.label
import kotlinx.serialization.json.JsonElement

/** 빠른 편집을 쓸 수 없을 때만 그 이유를 보여 준다. 서버 응답이 없는 호출과 JSON이 아닌 응답을 구분한다. */
@Composable
internal fun ColumnScope.ValuesUnavailable(original: JsonElement?, hasServerResponse: Boolean) {
    if (original != null) return
    // 서버를 부르지 않은 호출(교체·실패)은 원본이 없다. JSON이 아닌 것과 구분해 알린다.
    val message = if (hasServerResponse) R.string.boggart_error_values_not_json else R.string.boggart_values_no_server_response
    Text(stringResource(message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * 실제 응답([original])에 지금까지의 연산([ops])을 적용한 미리보기를 고칠 수 있는 트리로 보여 준다.
 * 값은 알약 모양, 객체·배열은 개수 뒤 ⋯ 칩으로 고칠 수 있다는 것이 드러나게 한다.
 */
@Composable
internal fun ColumnScope.ValueTree(stateKey: Long, original: JsonElement?, ops: List<JsonPatchOp>, onOps: (List<JsonPatchOp>) -> Unit) {
    if (original == null) return
    // 첫 항목을 펼친 채 시작해 고칠 수 있는 값이 바로 보이게 한다.
    var expanded by rememberSaveable(stateKey, stateSaver = expandedSaver) { mutableStateOf(JsonTree.initialEditableExpanded(original)) }
    var selected by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var keepFirst by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val preview = remember(original, ops) { ValueEdits.preview(original, ops) } ?: return
    val changed = ValueEdits.changedPaths(ops)
    val rows = JsonTree.rows(preview, expanded)
    val changedInside = ValueEdits.hiddenChangeOwners(changed, rows.map { it.path })

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        rows.forEach { row ->
            EditableRow(
                row = row,
                isChanged = row.path in changed,
                hasHiddenChange = row.path in changedInside,
                isArrayItem = ValueEdits.isArrayItem(preview, row.path),
                onToggle = { expanded = JsonTree.toggle(expanded, it) },
                onSelect = { selected = it },
                onClearArray = { onOps(ValueEdits.clearArray(original, ops, it)) },
                onKeepFirst = { path, size -> keepFirst = path to size },
                onRemove = { onOps(ValueEdits.remove(ops, it)) },
            )
        }
    }

    keepFirst?.let { (path, size) ->
        KeepFirstDialog(size, onApply = { keep ->
            onOps(ValueEdits.keepFirst(ops, path, size, keep))
            keepFirst = null
        }, onDismiss = { keepFirst = null })
    }

    selected?.let { path ->
        ValueDialog(
            path = path,
            current = ValueEdits.valueAt(preview, path),
            originalValue = ValueEdits.valueAt(original, path),
            arrayItem = ValueEdits.crossesArray(preview, path),
            onApply = { value ->
                onOps(ValueEdits.setValue(original, ops, path, value))
                selected = null
            },
            onRemove = if (path.isEmpty()) {
                null
            } else {
                {
                    onOps(ValueEdits.remove(ops, path))
                    selected = null
                }
            },
            onDismiss = { selected = null },
        )
    }
}

/** 트리 한 줄. 원시값은 줄 전체를 눌러 고치고, 객체·배열은 눌러 접고 펼치며 개수 뒤 ⋯ 칩으로 고친다. */
@Composable
private fun EditableRow(
    row: JsonRow,
    isChanged: Boolean,
    hasHiddenChange: Boolean,
    isArrayItem: Boolean,
    onToggle: (String) -> Unit,
    onSelect: (String) -> Unit,
    onClearArray: (String) -> Unit,
    onKeepFirst: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val isLeaf = row.childCount == null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable {
                when {
                    isLeaf -> onSelect(row.path)
                    row.expandable -> onToggle(row.path)
                    else -> onSelect(row.path)
                }
            }.padding(start = (row.depth * 14).dp),
    ) {
        if (isLeaf) {
            TreeChevron(row)
            row.key?.let { Text("$it:", style = mono, color = muted, modifier = Modifier.padding(end = 8.dp)) }
            // 알약 바탕과 타입별 색만으로 누를 수 있는 값임이 드러나 연필 아이콘은 두지 않는다.
            ValuePill(row.value.orEmpty(), isChanged, Modifier.padding(end = 12.dp))
        } else {
            val count = row.childCount
            val (open, close) = if (row.isArray) "[" to "]" else "{" to "}"
            // 메뉴는 줄 끝이 아니라 개수 바로 뒤에 둬 어느 객체·배열의 메뉴인지 붙어 보이게 한다.
            // 루트는 지울 수 없지만 배열이면 비우기·줄이기는 할 수 있다.
            val menu = @Composable {
                if (row.path.isNotEmpty() || row.isArray) {
                    ContainerMenu(row, isChanged || hasHiddenChange, isArrayItem, onSelect, onClearArray, onKeepFirst, onRemove)
                }
            }
            TreeChevron(row)
            row.key?.let { Text("$it: ", style = mono, color = muted) }
            val summary = row.summary
            if (summary != null) {
                // 접힌 객체는 대표 값으로 무슨 항목인지 보여 주고, 개수는 흐리게 덧붙인다. 대표 값이 남는 폭을 모두 쓴다.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        summary,
                        style = mono,
                        color = if (isChanged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isChanged) FontWeight.Bold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(" $open$count$close", style = mono, color = muted)
                    menu()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        if (count == 0) {
                            "$open$close"
                        } else if (row.expanded) {
                            "$open $count"
                        } else {
                            "$open…$close $count"
                        },
                        style = mono,
                        color = if (isChanged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isChanged) FontWeight.Bold else null,
                    )
                    menu()
                }
            }
        }
    }
}

/** 고칠 수 있는 값. 옅은 바탕의 알약에 타입별 글자색을 쓰고, 바꾼 값은 강조색 바탕으로 둔다. */
@Composable
private fun ValuePill(value: String, isChanged: Boolean, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val color = when {
        isChanged -> MaterialTheme.colorScheme.onPrimaryContainer
        value == "null" -> MaterialTheme.colorScheme.onSurfaceVariant
        value.startsWith("\"") -> MaterialTheme.colorScheme.onSurface
        value == "true" || value == "false" -> if (dark) Color(0xFFC3A8E0) else Color(0xFF7A5C9C)
        else -> if (dark) Color(0xFF8EC1D6) else Color(0xFF3F7A94)
    }
    Text(
        value,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        fontWeight = if (isChanged) FontWeight.Bold else null,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(vertical = 4.dp)
            .background(
                if (isChanged) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.shapes.small,
            ).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 빠른 편집 모드의 하단 고정 바. 공통 바([RuleActionBar]) 위에 변경 목록을 얹는다.
 * 변경 목록은 접어 두고 "변경 N개"를 눌러 펼친다.
 */
@Composable
internal fun ValuesActionBar(
    original: JsonElement,
    ops: List<JsonPatchOp>,
    onOps: (List<JsonPatchOp>) -> Unit,
    delayMs: String,
    onDelay: (String) -> Unit,
    buttonText: String,
    onSubmit: () -> Unit,
) {
    val resources = LocalResources.current
    var listOpen by rememberSaveable { mutableStateOf(false) }
    val broken = ValueEdits.preview(original, ops) == null
    // 처음 몇 개만 남기기로 생긴 remove 묶음은 한 줄로 보이고 한 번에 되돌린다.
    val changes = remember(original, ops) { ValueEdits.changes(original, ops) }
    RuleActionBar(
        delayMs = delayMs,
        onDelay = onDelay,
        buttonText = buttonText,
        enabled = ops.isNotEmpty() && !broken,
        onSubmit = onSubmit,
    ) {
        if (ops.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { listOpen = !listOpen }.padding(vertical = 4.dp),
            ) {
                Text(
                    pluralStringResource(R.plurals.boggart_changes_count, changes.size, changes.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (listOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (listOpen) {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    changes.forEach { change ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (change) {
                                    is ValueEdits.Change.Single ->
                                        ValueEdits.describe(change.op) { path -> resources.getString(R.string.boggart_op_removed, path) }
                                    is ValueEdits.Change.KeepFirst ->
                                        resources.getString(R.string.boggart_op_keep_first, change.path.ifEmpty { "/" }, change.keep)
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onOps(ValueEdits.revert(ops, change)) }) { Text(stringResource(R.string.boggart_revert)) }
                        }
                    }
                }
            }
        }
        if (broken) {
            Text(
                stringResource(R.string.boggart_values_broken),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 모든 조작이 함께 쓰는 하단 고정 바: 작은 지연 메뉴 + 만들기/고치기 버튼. 편집 영역이 길어도 버튼이 늘 보인다.
 * 지연은 위계가 낮아 작은 메뉴로 두되, 고른 값("지연 1초")은 늘 보여 켜 둔 것을 잊지 않게 한다.
 * [header]는 버튼 위에 얹는 조작별 내용(빠른 편집의 변경 목록 등)이다.
 */
@Composable
internal fun RuleActionBar(
    delayMs: String,
    onDelay: (String) -> Unit,
    buttonText: String,
    enabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
) {
    // 스크롤된 상단 바와 같은 surfaceContainer로 위아래 무게를 맞춘다. 내용이 늘 밑으로 지나가므로 약한 그림자만 더한다.
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            header()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DelayMenu(delayMs, onDelay)
                Button(onClick = onSubmit, enabled = enabled, modifier = Modifier.weight(1f)) { Text(buttonText) }
            }
        }
    }
}

/** 작은 지연 메뉴. "지연 없음 ▾"처럼 현재 값을 보여 주고 누르면 고른다. */
@Composable
private fun DelayMenu(delayMs: String, onDelay: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(delayLabel(delayMs))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DELAY_PRESETS.forEach { ms ->
                DropdownMenuItem(text = { Text(delayLabel(ms)) }, onClick = {
                    open = false
                    onDelay(ms)
                })
            }
        }
    }
}

@Composable
private fun delayLabel(delayMs: String): String {
    val ms = delayMs.toLongOrNull() ?: 0
    return if (ms == 0L) {
        stringResource(R.string.boggart_delay_none_long)
    } else {
        val seconds = if (ms % 1000 == 0L) (ms / 1000).toString() else "%.1f".format(java.util.Locale.ROOT, ms / 1000.0)
        stringResource(R.string.boggart_delay_seconds, seconds)
    }
}

private val DELAY_PRESETS = listOf("0", "500", "1000", "3000", "5000", "10000")

@Composable
private fun ValueDialog(
    path: String,
    current: JsonElement?,
    originalValue: JsonElement?,
    arrayItem: Boolean,
    onApply: (JsonElement) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var type by rememberSaveable(path) { mutableStateOf(ValueType.of(current)) }
    var text by rememberSaveable(path) { mutableStateOf(ValueEdits.inputText(current, type)) }
    val parsed = ValueEdits.parseInput(type, text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(path.ifEmpty { stringResource(R.string.boggart_whole_document) }, fontFamily = FontFamily.Monospace) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 배열 위치 주의는 배열 항목을 고칠 때만 보여 준다.
                if (arrayItem) {
                    Text(
                        stringResource(R.string.boggart_values_array_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                originalValue?.let {
                    Text(
                        stringResource(R.string.boggart_original_value, it.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ChoiceRow(ValueType.entries, type, { it.label() }) {
                    type = it
                    text = ValueEdits.inputText(current, it)
                }
                when (type) {
                    ValueType.Null -> Unit
                    ValueType.Bool -> ChoiceRow(listOf("true", "false"), text.takeIf { it == "true" || it == "false" } ?: "", { it }) {
                        text = it
                    }
                    else -> OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        isError = parsed == null,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().heightIn(max = CODE_FIELD_MAX_HEIGHT),
                    )
                }
                if (onRemove != null) TextButton(onClick = onRemove) { Text(stringResource(R.string.boggart_remove_value), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null) { Text(stringResource(R.string.boggart_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.boggart_cancel)) } },
    )
}

/**
 * 객체·배열 줄의 ⋯ 메뉴. 개수 바로 뒤 작은 칩으로 연다. 배열은 빈 배열로·처음 N개만 남기기, 배열 항목은 이 항목 지우기,
 * 그 밖에는 지우기와 다른 값으로 바꾸기(값 편집 다이얼로그). 자주 쓰는 배열 편집을 JSON 입력 없이 고르게 한다.
 */
@Composable
private fun ContainerMenu(
    row: JsonRow,
    accent: Boolean,
    isArrayItem: Boolean,
    onSelect: (String) -> Unit,
    onClearArray: (String) -> Unit,
    onKeepFirst: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val count = row.childCount ?: 0
    Box {
        // 보이는 칩은 작게(22dp), 누르는 영역은 40dp로 넉넉하게.
        // 자신이 바뀌었거나 안쪽 변경이 줄로 보이지 않으면(접힘·지운 항목) 강조색 칩으로 표시한다.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { open = true },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(22.dp)
                    .background(
                        if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape,
                    ),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.boggart_more),
                    tint = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).rotate(90f),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun item(label: Int, action: () -> Unit) = @Composable {
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                    open = false
                    action()
                })
            }
            if (row.isArray && count > 0) item(R.string.boggart_array_clear) { onClearArray(row.path) }()
            if (row.isArray && count > 1) item(R.string.boggart_array_keep_first) { onKeepFirst(row.path, count) }()
            if (row.path.isNotEmpty()) {
                item(if (isArrayItem) R.string.boggart_array_remove_item else R.string.boggart_remove_value) { onRemove(row.path) }()
            }
            item(R.string.boggart_replace_with_value) { onSelect(row.path) }()
        }
    }
}

/** 처음 몇 개를 남길지 고른다. 위치 기반이라 응답이 짧아지면 적용되지 않는다는 점을 함께 알린다. */
@Composable
private fun KeepFirstDialog(size: Int, onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("1") }
    val keep = text.toIntOrNull()?.takeIf { it in 0 until size }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.boggart_array_keep_first)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.boggart_array_size_now, size, size), style = MaterialTheme.typography.bodyMedium)
                NumberField(stringResource(R.string.boggart_array_keep_count), text, { text = it }, Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.boggart_array_keep_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { keep?.let(onApply) }, enabled = keep != null) { Text(stringResource(R.string.boggart_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.boggart_cancel)) } },
    )
}
