package io.github.hyunchan.boggart.android.overlay

import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.Provenance
import io.github.hyunchan.boggart.android.overlay.OverlayCoordinator.Bubble
import io.github.hyunchan.boggart.android.overlay.OverlayCoordinator.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayCoordinatorTest {
    private var now = 0L
    private val coordinator = OverlayCoordinator(clock = { now }, windowMs = 2000)
    private val log = mutableListOf<Exchange>()

    private fun call(id: Long, provenance: Provenance = Provenance.Original): Boolean {
        log += Exchange(id, now, BoggartRequest("GET", "https://a.io/x"), null, null, provenance, null)
        return coordinator.onExchanges(log.toList())
    }

    private fun shown() = coordinator.bubble.value as Bubble.Shown

    @Test
    fun `single call becomes single target when window closes`() {
        assertEquals(Bubble.Hidden, coordinator.bubble.value)
        assertTrue(call(1, Provenance.Patched))
        assertEquals(Bubble.Shown(1, Provenance.Patched, null), coordinator.bubble.value)
        now = 1999
        coordinator.tick()
        assertNull(shown().target)
        now = 2000
        coordinator.tick()
        assertEquals(Target.Single(1), shown().target)
    }

    @Test
    fun `calls inside window are merged into list`() {
        assertTrue(call(1))
        now = 1500
        assertFalse(call(2, Provenance.Failed))
        assertEquals(2, shown().count)
        assertEquals(Provenance.Failed, shown().lastProvenance)
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
        assertTrue(call(2))
        assertEquals(Bubble.Shown(1, Provenance.Original, null), coordinator.bubble.value)
        now = 5000
        coordinator.tick()
        assertEquals(Target.Single(2), shown().target)
    }

    @Test
    fun `call at window end without tick still opens new window`() {
        call(1)
        now = 2000
        assertTrue(call(2))
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
        assertFalse(coordinator.onExchanges(log.toList()))
        assertEquals(Bubble.Hidden, coordinator.bubble.value)
        assertTrue(call(8))
        assertFalse(coordinator.onExchanges(log.toList()))
        assertEquals(1, shown().count)
    }
}
