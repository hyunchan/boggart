@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.hyunchan.boggart.android.ui.calllist
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.common.BackButton
import io.github.hyunchan.boggart.android.ui.common.BoggartFace
import io.github.hyunchan.boggart.android.ui.common.ModeBanner
import io.github.hyunchan.boggart.android.ui.common.ModeButton
import io.github.hyunchan.boggart.android.ui.common.ProvenanceBadge
import io.github.hyunchan.boggart.android.ui.common.color
import io.github.hyunchan.boggart.android.ui.common.storeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 호출 목록 화면. ViewModel을 만들고 상태 구독만 맡는다. 그리기는 [CallListContent]가 한다. */
@Composable
internal fun CallListScreen(
    onBack: () -> Unit,
    onOpenCall: (Long) -> Unit,
    onOpenRules: () -> Unit,
    viewModel: CallListViewModel = storeViewModel(::CallListViewModel),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CallListContent(
        exchanges = state.exchanges,
        mode = state.mode,
        activeRules = state.activeRules,
        onOpenCall = onOpenCall,
        onBack = onBack,
        onOpenRules = onOpenRules,
        onMode = { viewModel.onIntent(CallListIntent.SetMode(it)) },
        onClear = { viewModel.onIntent(CallListIntent.Clear) },
    )
}

/** 호출 목록의 모양. 상태를 받지 않으므로 미리보기에서 그대로 쓴다. */
@Composable
internal fun CallListContent(
    exchanges: List<Exchange>,
    mode: Mode,
    activeRules: Int,
    onBack: () -> Unit,
    onOpenCall: (Long) -> Unit,
    onOpenRules: () -> Unit,
    onMode: (Mode) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(exchanges) { groupByEndpoint(exchanges) }

    // 맨 위에서는 본문과 같은 색으로 평평하다가, 내용이 밑으로 지나가면 surfaceContainer로 바뀌어 경계를 드러낸다.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(stringResource(R.string.boggart_app_name)) },
                // 첫 화면이지만 버블에서 들어온 사용자가 앱으로 돌아갈 길을 화면에서도 보이게 한다.
                navigationIcon = { BackButton(onBack) },
                actions = {
                    ModeButton(mode, onMode)
                    TextButton(onClick = onOpenRules) { Text(stringResource(R.string.boggart_rules_count, activeRules)) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ModeBanner(mode)
            if (groups.isEmpty()) {
                // 처음 여는 사람이 보는 자리라 보가트 얼굴로 이 화면이 무엇인지 보인다.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
                ) {
                    BoggartFace(Modifier.size(64.dp))
                    Text(
                        stringResource(R.string.boggart_no_calls),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(pluralStringResource(R.plurals.boggart_call_count, exchanges.size, exchanges.size), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = onClear) { Text(stringResource(R.string.boggart_clear_log)) }
                    }
                }
                groups.forEach { group ->
                    item(key = "group:${group.key}") {
                        Text(
                            "${group.key}  (${group.exchanges.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(group.exchanges, key = { "call:${it.id}" }) { exchange ->
                        ExchangeRow(exchange) { onOpenCall(exchange.id) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ExchangeRow(exchange: Exchange, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProvenanceBadge(exchange.provenance)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                exchange.request.url.host + exchange.request.url.path,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (exchange.error != null) {
                Text(
                    exchange.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            exchange.deliveredResponse?.status?.toString() ?: if (exchange.error != null) "ERR" else "—",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(formatTime(exchange.timestampMs), style = MaterialTheme.typography.labelSmall)
    }
}

internal fun formatTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
