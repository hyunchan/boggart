package io.github.hyunchan.boggart.android.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.hyunchan.boggart.android.OverlayAnchor
import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayPlacementTest {
    private val density = Density(2f)
    private val maxX = 1000f
    private val maxY = 2000f

    private fun at(anchor: OverlayAnchor, h: Int = 0, v: Int = 0, direction: LayoutDirection = LayoutDirection.Ltr) =
        OverlayPlacement(anchor, h, v).offset(maxX, maxY, direction, density)

    @Test
    fun `the default stays where the bubble has always been`() {
        // 오른쪽 끝에서 16dp, 위에서 30%.
        assertEquals(Offset(968f, 600f), at(OverlayAnchor.TopEnd))
    }

    @Test
    fun `each corner mirrors the default`() {
        assertEquals(Offset(32f, 600f), at(OverlayAnchor.TopStart))
        assertEquals(Offset(968f, 1400f), at(OverlayAnchor.BottomEnd))
        assertEquals(Offset(32f, 1400f), at(OverlayAnchor.BottomStart))
    }

    @Test
    fun `extra margins move the bubble inwards from its corner`() {
        assertEquals(Offset(968f - 128f, 600f + 192f), at(OverlayAnchor.TopEnd, h = 64, v = 96))
        assertEquals(Offset(32f + 128f, 1400f - 192f), at(OverlayAnchor.BottomStart, h = 64, v = 96))
    }

    @Test
    fun `start and end follow the layout direction`() {
        assertEquals(Offset(32f, 600f), at(OverlayAnchor.TopEnd, direction = LayoutDirection.Rtl))
        assertEquals(Offset(968f, 600f), at(OverlayAnchor.TopStart, direction = LayoutDirection.Rtl))
    }
}
