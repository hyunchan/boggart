package io.github.hyunchan.boggart.android.ui.calldetail
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.android.ui.common.BoggartTheme
import io.github.hyunchan.boggart.android.ui.common.CallStatusTheme
import io.github.hyunchan.boggart.android.ui.common.PREVIEW_CHANGED_CALL
import io.github.hyunchan.boggart.android.ui.common.PREVIEW_FAILED_CALL
import io.github.hyunchan.boggart.android.ui.common.PREVIEW_NOT_APPLIED_CALL
import io.github.hyunchan.boggart.android.ui.common.PREVIEW_ORIGINAL_CALL
import io.github.hyunchan.boggart.android.ui.common.RuleSet
import io.github.hyunchan.boggart.android.ui.common.status

@Composable
private fun CallDetailPreviewBody(exchange: Exchange) {
    BoggartTheme {
        CallStatusTheme(exchange.provenance.status()) {
            CallDetailContent(CallDetailState.initial(exchange.id, exchange, RuleSet()), onIntent = {}, onBack = {}, onOpenRule = {})
        }
    }
}

@Preview(name = "호출 상세 · 원본(세이지)", widthDp = 380, heightDp = 900)
@Composable
private fun CallDetailPreviewOriginal() {
    CallDetailPreviewBody(PREVIEW_ORIGINAL_CALL)
}

@Preview(name = "호출 상세 · 조작됨(라벤더)", widthDp = 380, heightDp = 900)
@Composable
private fun CallDetailPreviewChanged() {
    CallDetailPreviewBody(PREVIEW_CHANGED_CALL)
}

@Preview(name = "호출 상세 · 실패(벽돌)", widthDp = 380, heightDp = 900)
@Composable
private fun CallDetailPreviewFailed() {
    CallDetailPreviewBody(PREVIEW_FAILED_CALL)
}

@Preview(name = "호출 상세 · 적용 안 됨(황토)", widthDp = 380, heightDp = 900)
@Composable
private fun CallDetailPreviewNotApplied() {
    CallDetailPreviewBody(PREVIEW_NOT_APPLIED_CALL)
}

@Preview(name = "호출 상세 · 조작됨 · 다크", widthDp = 380, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CallDetailPreviewChangedDark() {
    CallDetailPreviewBody(PREVIEW_CHANGED_CALL)
}
