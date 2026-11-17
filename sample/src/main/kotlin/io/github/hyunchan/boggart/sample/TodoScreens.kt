package io.github.hyunchan.boggart.sample

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hyunchan.boggart.android.BoggartAndroid
import kotlinx.coroutines.launch

/** 할 일 목록. 당겨서 새로고침하거나 오류 화면의 다시 시도로 다시 불러온다(규칙은 다음 요청부터 적용된다). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListScreen(onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LoadState<List<Todo>>>(LoadState.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        if (!refreshing) state = LoadState.Loading
        state = load { Network.todos() }
        refreshing = false
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.todos_title)) },
                actions = { TextButton(onClick = { BoggartAndroid.openUi(context) }) { Text(stringResource(R.string.open_boggart)) } },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                attempt++
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val current = state) {
                is LoadState.Loaded -> if (current.value.isEmpty()) {
                    StateMessage(stringResource(R.string.empty_title), stringResource(R.string.empty_message), onRetry = null)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(current.value, key = { it.id }) { todo ->
                            TodoRow(todo, onClick = { onOpen(todo.id) })
                            HorizontalDivider()
                        }
                    }
                }
                else -> LoadStateMessage(current, onRetry = { attempt++ })
            }
        }
    }
}

@Composable
private fun TodoRow(todo: Todo, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        // 두 기호의 글자 폭이 달라 고정 폭 칸에 가운데 맞춰 제목 줄을 맞춘다.
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            Text(if (todo.completed) "✓" else "○", color = MaterialTheme.colorScheme.primary)
        }
        Text(todo.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("#${todo.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 할 일 상세(Ktor). "완료로 표시"는 PATCH 요청이라, 보내는 쪽의 실패도 확인할 수 있다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDetailScreen(id: Int, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val backLabel = stringResource(R.string.back)
    val snackbar = remember { SnackbarHostState() }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LoadState<Todo>>(LoadState.Loading) }
    var completing by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        state = LoadState.Loading
        state = load { Network.todo(id) }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.todo_title, id)) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", modifier = Modifier.semantics { contentDescription = backLabel }) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                is LoadState.Loaded -> Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    val todo = current.value
                    Text(todo.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(if (todo.completed) R.string.status_done else R.string.status_open),
                        color = if (todo.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.user_label, todo.userId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!todo.completed) {
                        Button(
                            enabled = !completing,
                            onClick = {
                                completing = true
                                scope.launch {
                                    when (val result = load { Network.complete(id) }) {
                                        is LoadState.Loaded -> {
                                            // 서버가 돌려준 값이 아니라 화면의 할 일만 완료로 바꾼다(테스트 서버는 실제로 저장하지 않는다).
                                            state = LoadState.Loaded(todo.copy(completed = true))
                                            snackbar.showSnackbar(resources.getString(R.string.marked_done))
                                        }
                                        else -> snackbar.showSnackbar(resources.getString(R.string.mark_done_failed, result.summary(resources)))
                                    }
                                    completing = false
                                }
                            },
                        ) { Text(stringResource(if (completing) R.string.marking_done else R.string.mark_done)) }
                    }
                    OutlinedButton(onClick = { attempt++ }) { Text(stringResource(R.string.reload)) }
                }
                else -> LoadStateMessage(current, onRetry = { attempt++ })
            }
        }
    }
}

/** 로딩·오류 상태를 화면 가운데에 그린다. 정상 상태는 각 화면이 그린다. */
@Composable
private fun LoadStateMessage(state: LoadState<*>, onRetry: () -> Unit) {
    when (state) {
        LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is LoadState.ServerError -> StateMessage(stringResource(R.string.server_error_title, state.code), stringResource(R.string.server_error_message), onRetry)
        is LoadState.ParseError -> StateMessage(stringResource(R.string.parse_error_title), stringResource(R.string.parse_error_message), onRetry, state.detail)
        is LoadState.ConnectionError -> StateMessage(stringResource(R.string.connection_error_title), stringResource(state.reason), onRetry, state.detail)
        is LoadState.Loaded<*> -> Unit
    }
}

/** 제목·설명·다시 시도. [detail]은 개발자용 원인이라 접어 둔다. */
@Composable
private fun StateMessage(title: String, message: String, onRetry: (() -> Unit)?, detail: String? = null) {
    var showDetail by rememberSaveable { mutableStateOf(false) }
    // 당겨서 새로고침이 동작하도록 빈 상태도 스크롤 가능한 영역에 둔다.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 64.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        onRetry?.let { Button(onClick = it, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.retry)) } }
        if (!detail.isNullOrBlank()) {
            TextButton(onClick = { showDetail = !showDetail }) { Text(stringResource(if (showDetail) R.string.hide_cause else R.string.show_cause)) }
            if (showDetail) {
                Text(
                    detail.take(400),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 스낵바 한 줄에 넣을 짧은 원인. 코루틴 안에서 쓰므로 [Resources]로 문구를 만든다. */
private fun LoadState<*>.summary(resources: Resources): String = when (this) {
    is LoadState.ServerError -> resources.getString(R.string.summary_server_error, code)
    is LoadState.ParseError -> resources.getString(R.string.summary_parse_error)
    is LoadState.ConnectionError -> resources.getString(reason)
    else -> ""
}
