package io.github.hyunchan.boggart.android.overlay

import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.Provenance
import io.github.hyunchan.boggart.android.overlay.OverlayCoordinator.Bubble
import io.github.hyunchan.boggart.android.overlay.OverlayCoordinator.Target
import io.github.hyunchan.boggart.android.ui.common.CallStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverlayCoordinatorTest {
    private var now = 0L
    private val coordinator = OverlayCoordinator(clock = { now }, windowMs = 2000)
    private val log = mutableListOf<Exchange>()

    private fun call(id: Long, provenance: Provenance = Provenance.Original): List<Long> {
        log += Exchange(id, now, BoggartRequest("GET", "https://a.io/x"), null, null, provenance, null)
        return coordinator.onExchanges(log.toList())
    }

    private fun shown() = coordinator.bubble.value as Bubble.Shown

    private fun at(time: Long): OverlayCoordinatorTest = apply {
        now = time
        coordinator.tick()
    }

    @Test
    fun `single call becomes single target when window closes`() {
        assertEquals(Bubble.Hidden, coordinator.bubble.value)
        assertEquals(listOf(2000L, 3000L), call(1, Provenance.Patched))
        assertEquals(Bubble.Shown(1, CallStatus.Changed, null), coordinator.bubble.value)
        now = 1999
        coordinator.tick()
        assertNull(shown().target)
        now = 2000
        coordinator.tick()
        assertEquals(Target.Single(1), shown().target)
    }

    @Test
    fun `calls inside window are merged into list`() {
        assertEquals(listOf(2000L), call(1))
        now = 1500
        assertEquals(listOf(3000L), call(2, Provenance.Failed))
        assertEquals(2, shown().count)
        assertEquals(CallStatus.Failed, shown().highlight)
        now = 2000
        coordinator.tick()
        assertEquals(Target.List, shown().target)
    }

    @Test
    fun `call after window closes opens new window`() {
        call(1)
        now = 2000
        coordinator.tick()
        now = 3000
        assertEquals(listOf(2000L), call(2))
        assertEquals(Bubble.Shown(1, null, null), coordinator.bubble.value)
        now = 5000
        coordinator.tick()
        assertEquals(Target.Single(2), shown().target)
    }

    @Test
    fun `call at window end without tick still opens new window`() {
        call(1)
        now = 2000
        assertEquals(listOf(2000L), call(2))
        assertEquals(1, shown().count)
    }

    @Test
    fun `current target while collecting and dismiss`() {
        assertNull(coordinator.currentTarget())
        call(1)
        assertEquals(Target.Single(1), coordinator.currentTarget())
        call(2)
        assertEquals(Target.List, coordinator.currentTarget())
        coordinator.dismiss()
        assertEquals(Bubble.Hidden, coordinator.bubble.value)
        assertNull(coordinator.currentTarget())
    }

    @Test
    fun `existing and repeated exchanges are ignored`() {
        log += Exchange(7, 0, BoggartRequest("GET", "https://a.io/x"), null, null, Provenance.Original, null)
        coordinator.skipExisting(log)
        assertEquals(emptyList(), coordinator.onExchanges(log.toList()))
        assertEquals(Bubble.Hidden, coordinator.bubble.value)
        assertEquals(listOf(2000L), call(8))
        assertEquals(emptyList(), coordinator.onExchanges(log.toList()))
        assertEquals(1, shown().count)
    }

    @Test
    fun `changed call is not buried by original calls that follow`() {
        call(1)
        call(2, Provenance.Patched)
        call(3)
        call(4)
        assertEquals(CallStatus.Changed, shown().highlight)
    }

    @Test
    fun `highlight lasts three seconds then returns to original`() {
        now = 1900
        call(1, Provenance.Replaced)
        at(4899)
        assertEquals(CallStatus.Changed, shown().highlight)
        at(4900)
        assertNull(shown().highlight)
    }

    @Test
    fun `same status does not extend highlight`() {
        call(1, Provenance.Patched)
        now = 1000
        assertEquals(emptyList(), call(2, Provenance.Replaced))
        at(3000)
        assertNull(shown().highlight)
    }

    @Test
    fun `original does not cut running highlight`() {
        now = 1900
        call(1, Provenance.Patched)
        now = 4000
        call(2)
        assertEquals(CallStatus.Changed, shown().highlight)
        at(4900)
        assertNull(shown().highlight)
    }

    @Test
    fun `different status replaces highlight and restarts timer`() {
        call(1, Provenance.Patched)
        now = 2500
        assertEquals(listOf(2000L, 3000L), call(2, Provenance.InvalidIgnored))
        assertEquals(CallStatus.NotApplied, shown().highlight)
        at(5499)
        assertEquals(CallStatus.NotApplied, shown().highlight)
        at(5500)
        assertNull(shown().highlight)
    }

    @Test
    fun `higher priority inside lookback wins`() {
        call(1, Provenance.Failed)
        now = 500
        call(2, Provenance.Patched)
        assertEquals(CallStatus.Failed, shown().highlight)
        now = 1000
        call(3, Provenance.InvalidIgnored)
        assertEquals(CallStatus.NotApplied, shown().highlight)
    }

    @Test
    fun `lower priority after lookback replaces`() {
        call(1, Provenance.InvalidIgnored)
        now = 2500
        call(2, Provenance.Patched)
        assertEquals(CallStatus.Changed, shown().highlight)
    }

    @Test
    fun `original call after highlight ends does not restart it`() {
        call(1, Provenance.Patched)
        now = 1500
        call(2, Provenance.Patched)
        at(3000)
        now = 3200
        assertEquals(listOf(2000L), call(3))
        assertNull(shown().highlight)
    }
}
