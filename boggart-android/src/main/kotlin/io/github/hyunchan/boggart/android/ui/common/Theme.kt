package io.github.hyunchan.boggart.android.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hyunchan.boggart.Provenance

/**
 * 세이지 그린 팔레트. 오버레이 버블의 세이지 캡슐과 같은 색상 계열이라 버블과 화면이 한 도구로 보인다.
 * 강조색 세이지는 상태색(회색·파랑·보라·빨강·주황)과 겹치지 않는다.
 * 동적 색(배경화면 색)은 쓰지 않는다. 호스트 앱 색과 섞이면 디버그 화면이 구분되지 않는다.
 * tertiaryContainer는 기본이 아닌 모드 안내 띠에 쓰는 옅은 모래색이다(경고로 읽히되 세이지와 겹치지 않게).
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F7A32),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E6CC),
    onPrimaryContainer = Color(0xFF12300C),
    inversePrimary = Color(0xFFA6D196),
    secondary = Color(0xFF56634F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E6CC),
    onSecondaryContainer = Color(0xFF12300C),
    tertiary = Color(0xFF6B5A1E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF1E9C9),
    onTertiaryContainer = Color(0xFF4A3D0A),
    background = Color(0xFFF6F8F4),
    onBackground = Color(0xFF1B2418),
    surface = Color(0xFFF6F8F4),
    onSurface = Color(0xFF1B2418),
    surfaceVariant = Color(0xFFDDE5D7),
    onSurfaceVariant = Color(0xFF56634F),
    surfaceTint = Color(0xFF3F7A32),
    inverseSurface = Color(0xFF2E352B),
    inverseOnSurface = Color(0xFFEEF2EA),
    outline = Color(0xFF7C8B74),
    outlineVariant = Color(0xFFC5CFBF),
    surfaceBright = Color(0xFFF6F8F4),
    surfaceDim = Color(0xFFD7DDD3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5EE),
    surfaceContainer = Color(0xFFEDF2E9),
    surfaceContainerHigh = Color(0xFFE7EDE3),
    surfaceContainerHighest = Color(0xFFE1E8DC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6D196),
    onPrimary = Color(0xFF163A0F),
    primaryContainer = Color(0xFF2C4425),
    onPrimaryContainer = Color(0xFFC9EBBB),
    inversePrimary = Color(0xFF3F7A32),
    secondary = Color(0xFFBCCAB3),
    onSecondary = Color(0xFF273322),
    secondaryContainer = Color(0xFF2C4425),
    onSecondaryContainer = Color(0xFFC9EBBB),
    tertiary = Color(0xFFE2CF8A),
    onTertiary = Color(0xFF3A3005),
    tertiaryContainer = Color(0xFF3B3314),
    onTertiaryContainer = Color(0xFFF1E3A8),
    background = Color(0xFF121611),
    onBackground = Color(0xFFDEE6D9),
    surface = Color(0xFF121611),
    onSurface = Color(0xFFDEE6D9),
    surfaceVariant = Color(0xFF3E483A),
    onSurfaceVariant = Color(0xFFA3B19B),
    surfaceTint = Color(0xFFA6D196),
    inverseSurface = Color(0xFFDEE6D9),
    inverseOnSurface = Color(0xFF2E352B),
    outline = Color(0xFF76866E),
    outlineVariant = Color(0xFF3E483A),
    surfaceBright = Color(0xFF353C32),
    surfaceDim = Color(0xFF121611),
    surfaceContainerLowest = Color(0xFF0C100B),
    surfaceContainerLow = Color(0xFF1A2018),
    surfaceContainer = Color(0xFF1E241C),
    surfaceContainerHigh = Color(0xFF282F26),
    surfaceContainerHighest = Color(0xFF333A30),
)

@Composable
internal fun BoggartTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/**
 * 화면에 보여 주는 호출 상태. core의 [Provenance] 다섯 값을 사용자가 구분할 필요가 있는 넷으로 묶는다.
 * 일부 수정과 교체는 "조작됨" 하나로 보고, 차이(서버를 불렀는지)는 상세 화면 설명 한 줄로만 보여 준다.
 * 깨진 바디를 그대로 보낸 경우(PatchedInvalid)도 조작이 적용됐으므로 조작됨이다.
 */
internal enum class CallStatus { Original, Changed, Failed, NotApplied }

internal fun Provenance.status(): CallStatus = when (this) {
    Provenance.Original -> CallStatus.Original
    Provenance.Patched, Provenance.Replaced, Provenance.PatchedInvalid -> CallStatus.Changed
    Provenance.Failed -> CallStatus.Failed
    Provenance.InvalidIgnored -> CallStatus.NotApplied
}

/**
 * 상태 배지 색. 버블 테두리와 같은 색상 계열이고 세이지와 어울리게 채도를 낮췄다.
 * 라이트는 흰 글자가 읽히도록 진하게, 다크는 어두운 글자를 올리도록 밝게 한다.
 */
@Composable
internal fun CallStatus.color(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        CallStatus.Original -> if (dark) Color(0xFFA9C79E) else Color(0xFF6E8A64)
        CallStatus.Changed -> if (dark) Color(0xFFC3A8E0) else Color(0xFF7A5C9C)
        CallStatus.Failed -> if (dark) Color(0xFFEE9A8C) else Color(0xFFB04A3C)
        CallStatus.NotApplied -> if (dark) Color(0xFFE8C07A) else Color(0xFFB07E2A)
    }
}

/** 배지 위 글자 색. 라이트는 흰색, 다크는 짙은 세이지. */
@Composable
private fun badgeTextColor(): Color = if (isSystemInDarkTheme()) Color(0xFF15190F) else Color.White

@Composable
internal fun ProvenanceBadge(provenance: Provenance, modifier: Modifier = Modifier) {
    val status = provenance.status()
    Surface(modifier = modifier, color = status.color(), shape = MaterialTheme.shapes.small) {
        Text(
            text = status.label(),
            color = badgeTextColor(),
            fontSize = 11.sp,
            // 기본 글자 스타일의 줄 높이(24sp)를 물려받으면 배지가 불필요하게 높아진다.
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 상태별 강조색 묶음: 강조색, 그 위 글자색, 옅은 강조 바탕, 그 위 글자색. */
private data class Accent(val primary: Color, val onPrimary: Color, val container: Color, val onContainer: Color)

private fun CallStatus.accent(dark: Boolean): Accent? = when (this) {
    CallStatus.Original -> null
    CallStatus.Changed -> if (dark) {
        Accent(Color(0xFFC3A8E0), Color(0xFF3A2356), Color(0xFF4E3A66), Color(0xFFEBDDF7))
    } else {
        Accent(Color(0xFF7A5C9C), Color.White, Color(0xFFEBDDF7), Color(0xFF2E1A45))
    }
    CallStatus.Failed -> if (dark) {
        Accent(Color(0xFFEE9A8C), Color(0xFF5C1A10), Color(0xFF6E2A20), Color(0xFFFFDAD3))
    } else {
        Accent(Color(0xFFB04A3C), Color.White, Color(0xFFF7DCD6), Color(0xFF4A130B))
    }
    // 황토는 흰 바탕 위 글자로 쓰기엔 밝아서 라이트에서는 한 단계 진하게 쓴다.
    CallStatus.NotApplied -> if (dark) {
        Accent(Color(0xFFE8C07A), Color(0xFF3F2C00), Color(0xFF5A4210), Color(0xFFFFE3B0))
    } else {
        Accent(Color(0xFF8A6118), Color.White, Color(0xFFF5E3BF), Color(0xFF3A2800))
    }
}

/**
 * 호출 상세를 그 호출의 상태색으로 감싼다. 원본은 앱 기본(세이지) 그대로, 조작됨은 라벤더, 실패는 벽돌, 적용 안 됨은 황토.
 * 바탕색은 그대로 두고 강조 역할(제목·링크·칩·버튼·강조 표시)만 바꿔, 지금 어떤 호출을 보고 있는지 화면 전체로 느끼게 한다.
 */
@Composable
internal fun CallStatusTheme(status: CallStatus, content: @Composable () -> Unit) {
    val accent = status.accent(isSystemInDarkTheme())
    if (accent == null) {
        content()
        return
    }
    val base = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = base.copy(
            primary = accent.primary,
            onPrimary = accent.onPrimary,
            primaryContainer = accent.container,
            onPrimaryContainer = accent.onContainer,
            secondaryContainer = accent.container,
            onSecondaryContainer = accent.onContainer,
            surfaceTint = accent.primary,
        ),
        content = content,
    )
}
