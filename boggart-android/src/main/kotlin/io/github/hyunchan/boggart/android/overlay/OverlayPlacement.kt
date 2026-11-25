package io.github.hyunchan.boggart.android.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.hyunchan.boggart.android.OverlayAnchor

/**
 * 버블을 처음 놓을 자리. [extraHorizontalDp]·[extraVerticalDp]는 이미 허용 범위로 맞춘 값이다.
 *
 * 기본 자리는 앵커 쪽 가장자리에서 가로 [EDGE_DP], 세로는 움직일 수 있는 높이의 [VERTICAL_FRACTION]이다.
 * 추가 마진은 그 자리에서 화면 안쪽으로 더한다.
 */
internal data class OverlayPlacement(
    val anchor: OverlayAnchor = OverlayAnchor.TopEnd,
    val extraHorizontalDp: Int = 0,
    val extraVerticalDp: Int = 0,
) {
    /** 버블 왼쪽 위 모서리의 위치(px). [maxX]·[maxY]는 버블이 놓일 수 있는 가장 큰 좌표다. */
    fun offset(maxX: Float, maxY: Float, layoutDirection: LayoutDirection, density: Density): Offset = with(density) {
        val fromEdge = (EDGE_DP + extraHorizontalDp).dp.toPx()
        val fromTopOrBottom = maxY * VERTICAL_FRACTION + extraVerticalDp.dp.toPx()
        val end = anchor == OverlayAnchor.TopEnd || anchor == OverlayAnchor.BottomEnd
        val right = end == (layoutDirection == LayoutDirection.Ltr)
        val top = anchor == OverlayAnchor.TopStart || anchor == OverlayAnchor.TopEnd
        Offset(
            x = if (right) maxX - fromEdge else fromEdge,
            y = if (top) fromTopOrBottom else maxY - fromTopOrBottom,
        )
    }

    private companion object {
        const val EDGE_DP = 16
        const val VERTICAL_FRACTION = 0.3f
    }
}
