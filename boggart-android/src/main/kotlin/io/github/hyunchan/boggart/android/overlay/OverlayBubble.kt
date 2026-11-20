package io.github.hyunchan.boggart.android.overlay
import android.graphics.Matrix
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hyunchan.boggart.Boggart
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.android.R
import io.github.hyunchan.boggart.android.ui.common.CallStatus
import io.github.hyunchan.boggart.android.ui.common.color
import io.github.hyunchan.boggart.android.ui.common.label
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

// 디버그 도구라 앱 UI보다 덜 드러나게 한다: 작은 캡슐, 연한 세이지 서리 바탕, 옅은 그림자.
// 세이지는 상태색(회색·파랑·보라·빨강·주황) 어느 것과도 겹치지 않아 테두리 상태색이 묻히지 않는다.
private val PILL_SHAPE = RoundedCornerShape(percent = 50)
private val SAGE_FILL = Brush.verticalGradient(listOf(Color(0xF5E7F2E3), Color(0xF0D3E6CC)))
private val SAGE_TEXT = Color(0xFF24451C)
private val SAGE_DEEP = Color(0xFF3F7A32)

// 테두리 = 강조 중인 상태(조작됨·실패·적용 안 됨). 세이지와 어울리도록 상태색의 채도를 낮췄다.
// 강조 중에는 그 색의 빛줄기가 돌고, 강조가 끝나면 원본 색으로 멈춘다.
private val BORDER_ORIGINAL = Color(0xFF9CBF8F)
private val BORDER_CHANGED = Color(0xFF8E6FAE)
private val BORDER_FAILED = Color(0xFFC0584A)
private val BORDER_NOT_APPLIED = Color(0xFFC9963E)
private val OFFLINE_RED = Color(0xFFB5523E)
private const val BORDER_SPIN_MS = 1800
private val STREAK_HEAD = Color.White.copy(alpha = 0.55f)

private fun CallStatus.bubbleBorder(): Color = when (this) {
    CallStatus.Original -> BORDER_ORIGINAL
    CallStatus.Changed -> BORDER_CHANGED
    CallStatus.Failed -> BORDER_FAILED
    CallStatus.NotApplied -> BORDER_NOT_APPLIED
}
private const val ACTIVE_ALPHA = 1f
private const val IDLE_ALPHA = 0.35f

/**
 * 드래그 가능한 버블. 캡슐에 최근 호출 건수와 활성 규칙 수를, 테두리에 강조 중인 상태를 보여 준다.
 * 규칙 일시정지·오프라인이면 캡슐 아래에 모드 칩(기호 + 이름)을 붙인다.
 * 탭하면 진입 대상으로, 길게 누르면 규칙 목록으로 간다. 최근 호출도 활성 규칙도 없고 기본 모드면 그리지 않는다.
 */
@Composable
internal fun OverlayBubble(
    coordinator: OverlayCoordinator,
    position: Offset?,
    onPositionChange: (Offset) -> Unit,
    onOpen: (OverlayCoordinator.Target?) -> Unit,
    onOpenRules: () -> Unit,
) {
    val bubble by coordinator.bubble.collectAsStateWithLifecycle()
    val activeRules by remember { Boggart.engine.repository.rules.map { rules -> rules.count { it.enabled } } }
        .collectAsStateWithLifecycle(initialValue = 0)
    val mode by Boggart.engine.modeFlow.collectAsStateWithLifecycle()
    val shown = bubble as? OverlayCoordinator.Bubble.Shown
    // 규칙 일시정지·오프라인은 켜 둔 걸 잊으면 앱이 이상하게 보이므로 호출이 없어도 버블을 진하게 띄운다.
    val modeAlert = mode.alert()
    if (shown == null && activeRules == 0 && !modeAlert) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // 캡슐 폭과 칩 유무에 따라 크기가 바뀌므로 실제 크기로 화면 안 범위를 정한다.
        var faceSize by remember { mutableStateOf(IntSize.Zero) }
        val maxX = (constraints.maxWidth - faceSize.width).toFloat().coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - faceSize.height).toFloat().coerceAtLeast(0f)
        val current = (position ?: Offset(maxX - with(density) { 16.dp.toPx() }, maxY * 0.3f))
            .let { Offset(it.x.coerceIn(0f, maxX), it.y.coerceIn(0f, maxY)) }
        // 드래그 제스처는 maxX·maxY가 바뀔 때만 다시 시작하므로 최신 위치를 따로 읽는다.
        val latest by rememberUpdatedState(current)
        // 최근 호출이 없고 활성 규칙만 남은 대기 상태는 더 옅게 둔다.
        val alpha by animateFloatAsState(if (shown != null || modeAlert) ACTIVE_ALPHA else IDLE_ALPHA, label = "bubbleAlpha")

        BubbleFace(
            count = shown?.count,
            highlight = shown?.highlight,
            activeRules = activeRules,
            mode = mode,
            alpha = alpha,
            modifier = Modifier
                .offset { IntOffset(current.x.roundToInt(), current.y.roundToInt()) }
                .onSizeChanged { faceSize = it }
                .pointerInput(maxX, maxY) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val base = latest
                        onPositionChange(
                            Offset(
                                (base.x + drag.x).coerceIn(0f, maxX),
                                (base.y + drag.y).coerceIn(0f, maxY),
                            ),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onOpen(coordinator.currentTarget()) },
                        onLongPress = { onOpenRules() },
                    )
                },
        )
    }
}

/**
 * 버블의 모양만 그린다. 상태를 받지 않으므로 이 파일 아래의 미리보기에서 그대로 쓴다.
 *
 * @param count 최근 호출 건수. 없으면 null.
 * @param highlight 강조 중인 상태. 그 색의 빛줄기가 테두리를 돈다. null이면 원본 색 테두리.
 * @param mode 규칙 일시정지·오프라인이면 캡슐 아래에 모드 칩을 붙인다.
 */
@Composable
internal fun BubbleFace(
    count: Int?,
    highlight: CallStatus?,
    activeRules: Int,
    mode: Mode,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .height(32.dp)
                .shadow(2.dp, PILL_SHAPE)
                .background(SAGE_FILL, PILL_SHAPE)
                .statusBorder(highlight)
                .padding(horizontal = 12.dp),
        ) {
            // 호출도 규칙도 없을 때(모드만 켜짐)는 빈 캡슐 대신 B를 둔다.
            if (count != null || activeRules == 0) {
                Text(count?.toString() ?: "B", color = SAGE_TEXT, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            if (activeRules > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 켜진 규칙 = 불러낸 보가트. 작은 크기용 얼굴을 숫자보다 진한 색으로 칠해 눈·입 구멍이 보이게 한다.
                    Icon(painterResource(R.drawable.boggart_face_small), contentDescription = null, tint = SAGE_TEXT, modifier = Modifier.size(14.dp))
                    Text(
                        activeRules.toString(),
                        color = SAGE_DEEP,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
        if (mode.alert()) ModeLabel(mode)
    }
}

/** 강조 중이 아니면 원본 색 테두리 그대로, 강조 중이면 그 색의 빛줄기가 캡슐 테두리를 따라 돈다. */
@Composable
private fun Modifier.statusBorder(highlight: CallStatus?): Modifier {
    if (highlight == null || highlight == CallStatus.Original) return border(2.dp, BORDER_ORIGINAL, PILL_SHAPE)
    val color = highlight.bubbleBorder()
    val angle by rememberInfiniteTransition(label = "bubbleBorder").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(BORDER_SPIN_MS, easing = LinearEasing)),
        label = "bubbleBorderAngle",
    )
    return drawWithCache {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        val outline = PILL_SHAPE.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
        val dim = color.copy(alpha = 0.35f)
        onDrawWithContent {
            drawContent()
            val brush = object : ShaderBrush() {
                override fun createShader(size: Size): Shader =
                    SweepGradientShader(
                        center = center,
                        // 빛줄기 끝을 반투명 흰색으로 두고 꼬리를 길게 해 날카로운 반짝임 대신 은은하게 흐르게 한다.
                        colors = listOf(dim, STREAK_HEAD, color, dim, dim),
                        colorStops = listOf(0f, 0.05f, 0.25f, 0.42f, 1f),
                    ).apply { setLocalMatrix(Matrix().apply { postRotate(angle, center.x, center.y) }) }
            }
            translate(inset, inset) { drawOutline(outline, brush, style = Stroke(stroke)) }
        }
    }
}

// 모드 표시 글자 둘레의 아주 옅은 흰 번짐. 바탕을 깔지 않아 뒤 화면을 가리지 않는다.
private val LABEL_GLOW = Color(0xCCFFFFFF)

/** 캡슐 아래 모드 표시. 오버레이라 짧은 이름(일시정지·오프라인)을 글자로만 쓴다. */
@Composable
private fun ModeLabel(mode: Mode) {
    Text(
        stringResource(if (mode == Mode.Paused) R.string.boggart_mode_paused_short else R.string.boggart_mode_offline_short),
        style = TextStyle(
            color = if (mode == Mode.Offline) OFFLINE_RED else SAGE_DEEP,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            shadow = Shadow(color = LABEL_GLOW, offset = Offset.Zero, blurRadius = 4f),
        ),
        modifier = Modifier.padding(top = 3.dp),
    )
}

private fun Mode.alert(): Boolean = this == Mode.Paused || this == Mode.Offline

/** 미리보기 한 칸: 버블 상태와 그 설명. */
private data class BubbleSample(
    val caption: String,
    val count: Int?,
    val highlight: CallStatus?,
    val activeRules: Int,
    val mode: Mode,
    val alpha: Float,
)

private val SAMPLES = listOf(
    BubbleSample("호출 3건\n강조 없음", 3, null, 0, Mode.Active, ACTIVE_ALPHA),
    BubbleSample("조작됨\n규칙 1", 12, CallStatus.Changed, 1, Mode.Active, ACTIVE_ALPHA),
    BubbleSample("실패\n규칙 1", 1, CallStatus.Failed, 1, Mode.Active, ACTIVE_ALPHA),
    BubbleSample("적용 안 됨\n규칙 1", 1, CallStatus.NotApplied, 1, Mode.Active, ACTIVE_ALPHA),
    BubbleSample("대기\n규칙만 켜짐", null, null, 1, Mode.Active, IDLE_ALPHA),
    BubbleSample("규칙 일시정지\n규칙 1", null, null, 1, Mode.Paused, ACTIVE_ALPHA),
    BubbleSample("오프라인\n막힌 호출", 1, CallStatus.Failed, 0, Mode.Offline, ACTIVE_ALPHA),
)

/** 버블이 앱 화면 위에 뜨므로 밝은 앱·어두운 앱·색면 바탕에서 본다. */
@Composable
private fun BubbleStates(background: Color, captionColor: Color) {
    FlowRow(
        modifier = Modifier.background(background).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SAMPLES.forEach { sample ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                BubbleFace(
                    count = sample.count,
                    highlight = sample.highlight,
                    activeRules = sample.activeRules,
                    mode = sample.mode,
                    alpha = sample.alpha,
                    modifier = Modifier.height(58.dp),
                )
                Text(
                    sample.caption,
                    color = captionColor,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Preview(name = "버블 · 밝은 앱 위", widthDp = 440)
@Composable
private fun OverlayBubblePreviewLight() {
    BubbleStates(background = Color(0xFFFFFFFF), captionColor = Color(0xFF444444))
}

@Preview(name = "버블 · 어두운 앱 위", widthDp = 440)
@Composable
private fun OverlayBubblePreviewDark() {
    BubbleStates(background = Color(0xFF121212), captionColor = Color(0xFFCCCCCC))
}

@Preview(name = "버블 · 색면 위", widthDp = 440)
@Composable
private fun OverlayBubblePreviewOnColor() {
    BubbleStates(background = Color(0xFF4F46E5), captionColor = Color(0xFFFFFFFF))
}
