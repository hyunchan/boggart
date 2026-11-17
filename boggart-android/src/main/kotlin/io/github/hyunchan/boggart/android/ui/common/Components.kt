@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.hyunchan.boggart.android.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.android.R
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

internal val SELECTABLE_MODES: List<Mode> = listOf(Mode.Active, Mode.Paused, Mode.Offline)

/** 기본(규칙 적용)이 아닌 모드에서 목록 위에 띄우는 안내. 켜 둔 걸 잊지 않게 한다. */
@Composable
internal fun ModeBanner(mode: Mode, modifier: Modifier = Modifier) {
    val text = mode.bannerRes() ?: return
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 상단 바의 모드 버튼. 누르면 바텀시트에서 모드를 고른다. 모드는 언제든 되돌릴 수 있어 확인 단계 없이 누르면 바로 적용한다. */
@Composable
internal fun ModeButton(mode: Mode, onMode: (Mode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.boggart_mode, mode.label())) }
    if (open) ModeSheet(mode, onMode) { open = false }
}

@Composable
private fun ModeSheet(mode: Mode, onMode: (Mode) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.boggart_mode_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            // Off는 고르면 기록이 멈추고 버블이 사라져 화면으로 돌아올 길이 없으므로 코드(Config.mode)로만 정한다.
            SELECTABLE_MODES.forEach { option ->
                val selected = option == mode
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton) {
                            onMode(option)
                            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                        }.padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            option.label(),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else null,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            option.description(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun <T> ChoiceRow(options: List<T>, selected: T?, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    // 가로 스크롤 대신 줄바꿈한다. 오른쪽에서 잘린 칩은 있는지조차 알기 어렵다.
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

/** 지연 고르기: 없음 · 1초 · 3초 · 직접 입력. [delayMs]는 밀리초 문자열이다. */
@Composable
internal fun ColumnScope.DelayPicker(delayMs: String, onDelay: (String) -> Unit) {
    val presets = listOf("0", "1000", "3000")
    var custom by rememberSaveable { mutableStateOf(delayMs !in presets) }
    Text(stringResource(R.string.boggart_delay), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    ChoiceRow(
        options = presets + CUSTOM_DELAY,
        selected = if (custom) CUSTOM_DELAY else delayMs,
        label = {
            stringResource(
                when (it) {
                    "0" -> R.string.boggart_delay_none
                    "1000" -> R.string.boggart_delay_1s
                    "3000" -> R.string.boggart_delay_3s
                    else -> R.string.boggart_delay_custom
                },
            )
        },
    ) {
        custom = it == CUSTOM_DELAY
        if (!custom) onDelay(it)
    }
    if (custom) NumberField(stringResource(R.string.boggart_delay_ms), delayMs, onDelay, Modifier.fillMaxWidth())
}

private const val CUSTOM_DELAY = "custom"

@Composable
internal fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/**
 * 헤더·바디·패치처럼 길 수 있는 코드 입력. 화면에는 스크롤하지 않는 미리보기(최대 [PREVIEW_LINES]줄)만 두고,
 * 누르면 전체 화면 편집기(읽기 전용이면 보기)를 연다. 텍스트 필드는 자기 스크롤을 바깥 화면으로 넘기지 않아,
 * 화면 안에 높이를 묶은 입력칸을 두면 그 위에서는 화면이 스크롤되지 않기 때문이다.
 */
@Composable
internal fun CodeField(label: String, value: String, onValueChange: (String) -> Unit, readOnly: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(false) }
    val lineCount = if (value.isEmpty()) 0 else value.count { it == '\n' } + 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
            .clickable { open = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value.ifEmpty { stringResource(R.string.boggart_empty) },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = PREVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Text(
            listOfNotNull(
                pluralStringResource(R.plurals.boggart_lines, lineCount, lineCount).takeIf { lineCount > 0 },
                stringResource(if (readOnly) R.string.boggart_tap_to_view else R.string.boggart_tap_to_edit),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (open) CodeEditorDialog(label, value, onValueChange, readOnly) { open = false }
}

/** 전체 화면 코드 편집기. 고친 내용은 바로 반영되고, 완료·닫기·뒤로는 창만 닫는다. */
@Composable
private fun CodeEditorDialog(label: String, value: String, onValueChange: (String) -> Unit, readOnly: Boolean, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) {
                        Text(stringResource(if (readOnly) R.string.boggart_close else R.string.boggart_done))
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                )
            }
        }
    }
}

private const val PREVIEW_LINES = 8

/**
 * 접히는 JSON 트리. 펼친 노드의 자식만 그린다. 컨테이너 줄을 누르면 접고 펼친다.
 *
 * [onSelect]가 있으면 원시값 줄을 누르거나 컨테이너 줄을 길게 눌러 그 줄을 고른다. [highlighted] 경로는 강조한다.
 */
@Composable
internal fun JsonTreeView(
    root: JsonElement,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    highlighted: Set<String> = emptySet(),
    onSelect: ((JsonRow) -> Unit)? = null,
) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val highlightStyle = style.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        JsonTree.rows(root, expanded).forEach { row ->
            val onClick: (() -> Unit)? = when {
                row.expandable -> ({ onToggle(row.path) })
                row.childCount == null && onSelect != null -> ({ onSelect(row) })
                else -> null
            }
            val onLongClick: (() -> Unit)? = if (row.childCount != null && onSelect != null) ({ onSelect(row) }) else null
            val rowModifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null || onLongClick != null) {
                        Modifier.combinedClickable(onLongClick = onLongClick, onClick = onClick ?: {})
                    } else {
                        Modifier
                    },
                ).padding(start = (row.depth * 12).dp, top = 2.dp, bottom = 2.dp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier) {
                TreeChevron(row)
                Text(jsonRowLabel(row), style = if (row.path in highlighted) highlightStyle else style)
            }
        }
    }
}

/** 트리 펼침 상태(JSON Pointer 경로 집합)를 저장한다. */
internal val expandedSaver: Saver<Set<String>, Any> = listSaver(save = { it.toList() }, restore = { it.toSet() })

/** 트리 줄 글자. 펼침 표시는 [TreeChevron]이 따로 그린다. */
internal fun jsonRowLabel(row: JsonRow): String {
    val prefix = row.key?.let { "$it: " }.orEmpty()
    val count = row.childCount ?: return prefix + row.value.orEmpty()
    val (open, close) = if (row.isArray) "[" to "]" else "{" to "}"
    return when {
        count == 0 -> "$prefix$open$close"
        row.expanded -> "$prefix$open $count"
        else -> "$prefix$open…$close $count"
    }
}

/**
 * 트리 줄 앞 펼침 표시. 펼칠 수 있으면 쉐브론(›)이 펼칠 때 90도 돌고, 아니면 같은 폭을 비워 둬
 * 같은 깊이의 줄이 모두 같은 자리에서 시작하게 한다.
 */
@Composable
internal fun TreeChevron(row: JsonRow) {
    val rotation by animateFloatAsState(if (row.expanded) 90f else 0f, label = "treeChevron")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(TREE_CHEVRON_SIZE)) {
        if (row.expandable) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(TREE_CHEVRON_SIZE).rotate(rotation),
            )
        }
    }
}

private val TREE_CHEVRON_SIZE = 18.dp

// 다이얼로그 안처럼 바깥이 스크롤되지 않는 곳에서 쓰는 입력칸 최대 높이.
internal val CODE_FIELD_MAX_HEIGHT: Dp = 240.dp

/** 상단 바의 뒤로 버튼. 머티리얼 기본 아이콘(RTL에서는 좌우 반전)을 쓴다. */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.boggart_back))
    }
}

/** 섹션 제목 오른쪽에 ⓘ를 두고, 누르면 [guides]의 (이름, 설명)을 차례로 나열한 다이얼로그를 보여 준다. */
@Composable
internal fun SectionTitleWithInfo(text: String, guides: List<Pair<String, String>>) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = stringResource(R.string.boggart_guide),
                // 제목과 같은 강조색. 호출 상세에서는 그 호출의 상태색을 따른다.
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(text) },
            text = {
                // 설명이 길어 작은 화면에서 넘치지 않게 본문만 스크롤한다.
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    guides.forEach { (name, guide) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(guide, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.boggart_close)) } },
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** 보가트 얼굴(큰 크기용). 눈·입이 뚫린 한 가지 색 실루엣이라 배경 위에서 [tint] 하나로 칠한다. */
@Composable
internal fun BoggartFace(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.outline) {
    Icon(painterResource(R.drawable.boggart_face), contentDescription = null, tint = tint, modifier = modifier)
}
