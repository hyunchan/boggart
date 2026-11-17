package io.github.hyunchan.boggart.android.overlay

import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.Provenance
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
 */
internal class OverlayCoordinator(
    private val clock: () -> Long,
    val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    sealed interface Target {
        data class Single(val exchangeId: Long) : Target
        data object List : Target
    }

    sealed interface Bubble {
        data object Hidden : Bubble

        /** @property target 수집 창이 열려 있는 동안은 null. */
        data class Shown(val count: Int, val lastProvenance: Provenance, val target: Target?) : Bubble
    }

    private val lock = Any()
    private val state = MutableStateFlow<Bubble>(Bubble.Hidden)
    private var lastSeenId = 0L
    private var windowEndsAt: Long? = null
    private var windowIds = mutableListOf<Long>()

    val bubble: StateFlow<Bubble> = state.asStateFlow()

    /** 이미 있는 교환을 본 것으로 처리한다(재설치 시 예전 기록으로 버블이 뜨지 않게). */
    fun skipExisting(exchanges: kotlin.collections.List<Exchange>): Unit = synchronized(lock) {
        lastSeenId = maxOf(lastSeenId, exchanges.maxOfOrNull { it.id } ?: 0)
    }

    /** 교환 로그 스냅샷을 받아 새 교환만 반영한다. 새 수집 창을 열었으면 true(호출자가 창 길이 뒤에 [tick]을 예약한다). */
    fun onExchanges(exchanges: kotlin.collections.List<Exchange>): Boolean = synchronized(lock) {
        val fresh = exchanges.filter { it.id > lastSeenId }.sortedBy { it.id }
        if (fresh.isEmpty()) return false
        lastSeenId = fresh.last().id
        val now = clock()
        var opened = false
        for (exchange in fresh) {
            val end = windowEndsAt
            if (end == null || now >= end) {
                windowEndsAt = now + windowMs
                windowIds = mutableListOf()
                opened = true
            }
            windowIds += exchange.id
        }
        state.value = Bubble.Shown(windowIds.size, fresh.last().provenance, target = null)
        opened
    }

    /** 수집 창이 끝났으면 닫고 진입 대상을 정한다. */
    fun tick(): Unit = synchronized(lock) {
        val end = windowEndsAt ?: return
        if (clock() < end) return
        windowEndsAt = null
        val shown = state.value as? Bubble.Shown ?: return
        state.value = shown.copy(target = targetOf(windowIds))
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
        state.value = Bubble.Hidden
    }

    private fun targetOf(ids: kotlin.collections.List<Long>): Target =
        if (ids.size == 1) Target.Single(ids.single()) else Target.List

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 2_000
    }
}
