package io.github.hyunchan.boggart.android.ui.rulelist
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.JsonPatchOp
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.android.ui.common.BoggartTheme
import io.github.hyunchan.boggart.android.ui.common.status
import kotlinx.serialization.json.JsonPrimitive

private val PREVIEW_TEMPORARY = listOf(
    Rule("t1", "GET /v1/articles", RequestMatcher("GET", "api.example.com", "/v1/articles"), Override.JsonPatch(listOf(JsonPatchOp(JsonPatchOp.Op.Replace, "/0/title", JsonPrimitive("Boggart")), JsonPatchOp(JsonPatchOp.Op.Remove, "/1")))),
    Rule("t2", "GET /v1/promotions", RequestMatcher("GET", "api.example.com", "/v1/promotions"), Override.Fail(FailKind.Timeout, delayMs = 3000)),
    Rule("t3", "POST /v1/events", RequestMatcher("POST", "api.example.com", "/v1/events"), Override.Fail(FailKind.ConnectionReset), enabled = false),
)

private val PREVIEW_SAVED = listOf(
    Rule("s1", "GET /v1/notices", RequestMatcher("GET", "api.example.com", "/v1/notices"), Override.Replace(status = 404, body = "{}")),
)

@Composable
private fun RuleListPreviewBody(mode: Mode) {
    BoggartTheme {
        RuleListContent(
            temporary = PREVIEW_TEMPORARY,
            saved = PREVIEW_SAVED,
            preset = emptyList(),
            mode = mode,
            onBack = {},
            onOpenRule = {},
            onToggle = { _, _, _ -> },
            onSave = {},
            onRemove = { _, _ -> },
            onClearTemporary = {},
        )
    }
}

@Preview(name = "규칙 목록 · 라이트", widthDp = 380, heightDp = 640)
@Composable
private fun RuleListPreviewLight() {
    RuleListPreviewBody(Mode.Active)
}

@Preview(name = "규칙 목록 · 다크 · 일시정지", widthDp = 380, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RuleListPreviewDarkPaused() {
    RuleListPreviewBody(Mode.Paused)
}
