package io.github.hyunchan.boggart.android

/**
 * 버블이 처음 놓이는 화면 귀퉁이. Start·End는 레이아웃 방향을 따른다(오른쪽에서 왼쪽으로 쓰는 언어면 좌우가 바뀐다).
 *
 * 위쪽 귀퉁이는 위에서, 아래쪽 귀퉁이는 아래에서 버블이 움직일 수 있는 높이의 30% 지점에 놓아 툴바·하단 내비게이션을 비켜 간다.
 * 가로는 가장자리에서 16dp. 버블을 끌어 옮기면 그 위치가 우선한다.
 */
public enum class OverlayAnchor { TopStart, TopEnd, BottomStart, BottomEnd }
