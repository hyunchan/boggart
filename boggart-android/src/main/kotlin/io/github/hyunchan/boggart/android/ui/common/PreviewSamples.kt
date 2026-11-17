package io.github.hyunchan.boggart.android.ui.common
import io.github.hyunchan.boggart.BoggartHeaders
import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.BoggartResponse
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.Provenance

/** 화면 미리보기에서 함께 쓰는 예시 호출. 가상의 기사 목록 API다. */

internal const val PREVIEW_FEED_URL = "https://api.example.com/v1/articles?limit=20"
private val JSON_HEADERS = BoggartHeaders.of("Content-Type" to "application/json; charset=utf-8")
private val REQUEST_HEADERS = BoggartHeaders.of("Accept" to "application/json", "User-Agent" to "ExampleApp/1.0")

private const val FEED_BODY = """[
  {"id":3021,"title":"City library extends weekend opening hours","summary":"The central library will now open on Sundays.","imageUrl":null,"likes":12,"pinned":true,"publishedAt":"2026-11-16T14:33:38Z","tags":[]},
  {"id":3020,"title":"Ferry timetable changes for the winter season","summary":"Fewer crossings run between December and February.","imageUrl":null,"likes":4,"pinned":false,"publishedAt":"2026-11-16T14:07:11Z","tags":["travel"]},
  {"id":3019,"title":"Local bakery wins regional award","summary":"Judges praised its sourdough.","imageUrl":null,"likes":0,"pinned":false,"publishedAt":"2026-11-16T13:51:02Z","tags":[]}
]"""

private val PATCHED_BODY = FEED_BODY.replace("City library extends weekend opening hours", "BOGGART PATCHED")

private fun response(body: String, status: Int = 200) =
    BoggartResponse(status, JSON_HEADERS, body.toByteArray(), "application/json")

private fun exchange(
    id: Long,
    provenance: Provenance,
    original: BoggartResponse?,
    delivered: BoggartResponse?,
    url: String = PREVIEW_FEED_URL,
    method: String = "GET",
    appliedRuleId: String? = null,
    error: String? = null,
) = Exchange(
    id = id,
    timestampMs = 1_763_360_000_000L + id * 1_000,
    request = BoggartRequest(method, url, REQUEST_HEADERS),
    originalResponse = original,
    deliveredResponse = delivered,
    provenance = provenance,
    appliedRuleId = appliedRuleId,
    error = error,
)

internal val PREVIEW_ORIGINAL_CALL = exchange(1, Provenance.Original, response(FEED_BODY), response(FEED_BODY))
internal val PREVIEW_CHANGED_CALL = exchange(2, Provenance.Patched, response(FEED_BODY), response(PATCHED_BODY), appliedRuleId = "preview-rule")
internal val PREVIEW_FAILED_CALL = exchange(3, Provenance.Failed, null, null, appliedRuleId = "preview-rule", error = "java.net.SocketTimeoutException: timeout")
internal val PREVIEW_NOT_APPLIED_CALL = exchange(4, Provenance.InvalidIgnored, response(FEED_BODY), response(FEED_BODY), appliedRuleId = "preview-rule")

internal val PREVIEW_LIST_CALLS = listOf(
    PREVIEW_CHANGED_CALL.copy(id = 12),
    PREVIEW_ORIGINAL_CALL.copy(id = 11),
    exchange(10, Provenance.Original, response("{}", 204), response("{}", 204), url = "https://api.example.com/v1/events", method = "POST"),
    PREVIEW_FAILED_CALL.copy(id = 9, request = BoggartRequest("GET", "https://api.example.com/v1/notices", REQUEST_HEADERS)),
    exchange(8, Provenance.Replaced, null, response("{}", 404), url = "https://api.example.com/v1/profile", appliedRuleId = "r"),
    PREVIEW_NOT_APPLIED_CALL.copy(id = 7, request = BoggartRequest("GET", "https://api.example.com/v1/config", REQUEST_HEADERS)),
)
