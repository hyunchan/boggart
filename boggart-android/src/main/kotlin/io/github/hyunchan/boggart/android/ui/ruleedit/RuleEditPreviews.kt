package io.github.hyunchan.boggart.android.ui.ruleedit
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.JsonPatchOp
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.SequenceEnd
import io.github.hyunchan.boggart.android.ui.common.BoggartTheme
import io.github.hyunchan.boggart.android.ui.common.RuleOrigin
import kotlinx.serialization.json.JsonPrimitive

private val PREVIEW_MATCHER = RequestMatcher("GET", "api.example.com", "/v1/articles")

@Composable
private fun RuleEditPreviewBody(origin: RuleOrigin, rule: Rule, nextStep: Int? = null) {
    BoggartTheme { RuleEditContent(RuleEditState(origin to rule, RuleForm.of(rule), canSave = true, nextStep), onIntent = {}, onBack = {}) }
}

@Preview(name = "규칙 편집 · 임시 · 빠른 편집", widthDp = 380, heightDp = 900)
@Composable
private fun RuleEditPreviewValues() {
    RuleEditPreviewBody(
        RuleOrigin.Temporary,
        Rule(
            "t1",
            "GET /v1/articles",
            PREVIEW_MATCHER,
            Override.JsonPatch(listOf(JsonPatchOp(JsonPatchOp.Op.Replace, "/0/title", JsonPrimitive("BOGGART PATCHED")))),
        ),
    )
}

@Preview(name = "규칙 편집 · 저장됨 · 전체 교체", widthDp = 380, heightDp = 900)
@Composable
private fun RuleEditPreviewReplace() {
    RuleEditPreviewBody(RuleOrigin.Saved, Rule("s1", "GET /v1/notices", PREVIEW_MATCHER.copy(path = "/v1/notices"), Override.Replace(404, body = "{}")))
}

@Preview(name = "규칙 편집 · 임시 · 에러 유발 · 다크", widthDp = 380, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RuleEditPreviewFailDark() {
    RuleEditPreviewBody(RuleOrigin.Temporary, Rule("t2", "GET /v1/promotions", PREVIEW_MATCHER.copy(path = "/v1/promotions"), Override.Fail(FailKind.Timeout, delayMs = 3000)))
}

@Preview(name = "규칙 편집 · 프리셋", widthDp = 380, heightDp = 640)
@Composable
private fun RuleEditPreviewPreset() {
    RuleEditPreviewBody(RuleOrigin.Preset, Rule("a1", "사용자 없음", RequestMatcher("GET", path = "/api/v1/users/*"), Override.Replace(404, bodyFile = "user-404.json", delayMs = 500), enabled = false))
}

@Preview(name = "규칙 편집 · 임시 · 순차 응답", widthDp = 380, heightDp = 900)
@Composable
private fun RuleEditPreviewSequence() {
    val steps = listOf(Override.Fail(FailKind.Timeout, delayMs = 1000), Override.Replace(500), Override.Replace(200, body = "[]"))
    RuleEditPreviewBody(RuleOrigin.Temporary, Rule("t3", "GET /v1/articles 재시도", PREVIEW_MATCHER, Override.Sequence(steps, SequenceEnd.Original)), nextStep = 1)
}
