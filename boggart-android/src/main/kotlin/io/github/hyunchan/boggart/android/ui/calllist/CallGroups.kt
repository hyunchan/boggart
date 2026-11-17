package io.github.hyunchan.boggart.android.ui.calllist

import io.github.hyunchan.boggart.Exchange

/** 엔드포인트(method + path)별 교환 묶음. [exchanges]는 최신순이다. */
internal data class EndpointGroup(
    val method: String,
    val path: String,
    val exchanges: List<Exchange>,
) {
    val key: String get() = "$method $path"
    val latest: Exchange get() = exchanges.first()
}

/** 최신 교환이 먼저 오도록 정렬해 엔드포인트별로 묶는다. 묶음 순서는 각 묶음의 최신 교환 순이다. */
internal fun groupByEndpoint(exchanges: List<Exchange>): List<EndpointGroup> =
    exchanges.sortedByDescending { it.id }
        .groupBy { it.request.method.uppercase() to it.request.url.path }
        .map { (key, items) -> EndpointGroup(key.first, key.second, items) }
