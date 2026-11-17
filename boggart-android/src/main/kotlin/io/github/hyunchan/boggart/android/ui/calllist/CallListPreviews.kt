package io.github.hyunchan.boggart.android.ui.calllist
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.android.ui.common.BoggartTheme
import io.github.hyunchan.boggart.android.ui.common.PREVIEW_LIST_CALLS

@Composable
private fun CallListPreviewBody(mode: Mode) {
    BoggartTheme {
        CallListContent(PREVIEW_LIST_CALLS, mode, activeRules = 2, onBack = {}, onOpenCall = {}, onOpenRules = {}, onMode = {}, onClear = {})
    }
}

@Preview(name = "호출 목록 · 라이트", widthDp = 380, heightDp = 720)
@Composable
private fun CallListPreviewLight() {
    CallListPreviewBody(Mode.Active)
}

@Preview(name = "호출 목록 · 다크 · 오프라인", widthDp = 380, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CallListPreviewDarkOffline() {
    CallListPreviewBody(Mode.Offline)
}
