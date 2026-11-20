package io.github.hyunchan.boggart.android.overlay

import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.android.ui.common.CallStatus
import io.github.hyunchan.boggart.android.ui.common.status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 오버레이 버블의 진입 규칙. Android와 무관한 순수 Kotlin이며 [clock]으로 시간을 주입한다.
 *
 * - 창이 닫혀 있을 때 호출이 오면 [windowMs] 길이의 수집 창을 연다.
 * - 창이 열린 동안의 호출은 같은 창에 합산한다.
 * - 창이 닫히면([tick]) 1건이면 [Target.Single], 2건 이상이면 [Target.List]로 진입 대상을 정한다.
 * - 창이 닫힌 뒤의 호출은 새 창을 연다(건수는 1부터 다시 센다).
 *
 * 테두리 강조는 수집 창과 따로 정한다. 동시 호출이 많으면 조작된 호출이 원본 호출들에 묻히기 때문이다.
 * - 새 호출이 오면 그 시점부터 [lookbackMs] 전까지의 호출 중 우선순위가 가장 높은 상태를 계산한다
 *   (적용 안 됨 > 실패 > 조작됨 > 원본).
 * - 계산한 상태가 직전 계산과 다르고 원본이 아니면 [highlightMs] 동안 강조한다. 같으면 강조 시간을 늘리지 않는다.
 * - 원본은 진행 중인 강조를 끊지 않는다. 강조가 끝나면 원본 테두리로 돌아간다.
 */
internal class OverlayCoordinator(
    private val clock: () -> Long,
    val windowMs: Long = DEFAULT_WINDOW_MS,
    val lookbackMs: Long = DEFAULT_LOOKBACK_MS,
    val highlightMs: Long = DEFAULT_HIGHLIGHT_MS,
) {
    sealed interface Target {
        data class Single(val exchangeId: Long) : Target
        data object List : Target
    }

    sealed interface Bubble {
        data object Hidden : Bubble

        /**
         * @property highlight 강조 중인 상태. 강조 중이 아니면 null(원본 테두리).
         * @property target 수집 창이 열려 있는 동안은 null.
         */
        data class Shown(val count: Int, val highlight: CallStatus?, val target: Target?) : Bubble
    }

    private val lock = Any()
    private val state = MutableStateFlow<Bubble>(Bubble.Hidden)
    private var lastSeenId = 0L
    private var windowEndsAt: Long? = null
    private var windowIds = mutableListOf<Long>()
    private val recent = ArrayDeque<Pair<Long, CallStatus>>()
    private var lastComputed = CallStatus.Original
    private var highlight: CallStatus? = null
    private var highlightEndsAt = 0L

    val bubble: StateFlow<Bubble> = state.asStateFlow()

    /** 이미 있는 교환을 본 것으로 처리한다(재설치 시 예전 기록으로 버블이 뜨지 않게). */
    fun skipExisting(exchanges: kotlin.collections.List<Exchange>): Unit = synchronized(lock) {
        lastSeenId = maxOf(lastSeenId, exchanges.maxOfOrNull { it.id } ?: 0)
    }

    /**
     * 교환 로그 스냅샷을 받아 새 교환만 반영한다.
     * 지금부터 [tick]을 불러야 할 시간(ms)들을 돌려준다: 새 수집 창을 열었으면 [windowMs], 강조를 시작했으면 [highlightMs].
     */
    fun onExchanges(exchanges: kotlin.collections.List<Exchange>): kotlin.collections.List<Long> = synchronized(lock) {
        val fresh = exchanges.filter { it.id > lastSeenId }.sortedBy { it.id }
        if (fresh.isEmpty()) return emptyList()
        lastSeenId = fresh.last().id
        val now = clock()
        val ticks = mutableListOf<Long>()
        for (exchange in fresh) {
            val end = windowEndsAt
            if (end == null || now >= end) {
                windowEndsAt = now + windowMs
                windowIds = mutableListOf()
                if (windowMs !in ticks) ticks += windowMs
            }
            windowIds += exchange.id
            recent.addLast(now to exchange.provenance.status())
        }
        if (updateHighlight(now)) ticks += highlightMs
        state.value = Bubble.Shown(windowIds.size, highlight, target = null)
        ticks
    }

    /** 최근 호출들의 상태를 계산해 강조를 정한다. 강조를 새로 시작했으면 true. */
    private fun updateHighlight(now: Long): Boolean {
        expireHighlight(now)
        while (recent.isNotEmpty() && recent.first().first < now - lookbackMs) recent.removeFirst()
        val computed = recent.maxByOrNull { it.second.rank }?.second ?: CallStatus.Original
        val changed = computed != lastComputed
        lastComputed = computed
        if (!changed || computed == CallStatus.Original) return false
        highlight = computed
        highlightEndsAt = now + highlightMs
        return true
    }

    private fun expireHighlight(now: Long) {
        if (highlight != null && now >= highlightEndsAt) highlight = null
    }

    /** 수집 창이 끝났으면 닫고 진입 대상을 정한다. 강조 시간이 지났으면 강조를 끈다. */
    fun tick(): Unit = synchronized(lock) {
        val now = clock()
        val shown = state.value as? Bubble.Shown ?: return
        expireHighlight(now)
        val end = windowEndsAt
        val target = if (end != null && now >= end) {
            windowEndsAt = null
            targetOf(windowIds)
        } else {
            shown.target
        }
        state.value = shown.copy(highlight = highlight, target = target)
    }

    /** 지금 버블을 누르면 갈 곳. 창이 열려 있어도 현재까지의 건수로 정한다. 표시 중이 아니면 null. */
    fun currentTarget(): Target? = synchronized(lock) {
        val shown = state.value as? Bubble.Shown ?: return null
        shown.target ?: targetOf(windowIds)
    }

    /** 버블을 눌러 진입한 뒤 숨긴다. */
    fun dismiss(): Unit = synchronized(lock) {
        windowEndsAt = null
        windowIds = mutableListOf()
        highlight = null
        state.value = Bubble.Hidden
    }

    private fun targetOf(ids: kotlin.collections.List<Long>): Target =
        if (ids.size == 1) Target.Single(ids.single()) else Target.List

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 2_000
        const val DEFAULT_LOOKBACK_MS: Long = 2_000
        const val DEFAULT_HIGHLIGHT_MS: Long = 3_000
    }
}

/** 강조 우선순위. 규칙이 조용히 동작하지 않은 것을 가장 먼저 알리고, 의도한 결과 중에서는 앱 영향이 큰 실패를 위에 둔다. */
private val CallStatus.rank: Int
    get() = when (this) {
        CallStatus.Original -> 0
        CallStatus.Changed -> 1
        CallStatus.Failed -> 2
        CallStatus.NotApplied -> 3
    }
