package io.github.hyunchan.boggart.android.ui.common
import io.github.hyunchan.boggart.BoggartHeaders
import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.BoggartResponse
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.JsonMergePatch
import io.github.hyunchan.boggart.OnInvalidPatch
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.Provenance
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.android.Screen
import io.github.hyunchan.boggart.android.ui.calllist.groupByEndpoint
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiStateTest {
    private fun exchange(
        id: Long,
        path: String = "/users/1",
        method: String = "GET",
        original: String? = """{"id":1,"name":"Harry","tags":["a"]}""",
        delivered: String? = original,
        appliedRuleId: String? = null,
    ) = Exchange(
        id = id,
        timestampMs = id,
        request = BoggartRequest(method, "https://api.example.com$path"),
        originalResponse = original?.let { BoggartResponse(200, BoggartHeaders.EMPTY, it.toByteArray(), "application/json") },
        deliveredResponse = delivered?.let { BoggartResponse(200, BoggartHeaders.EMPTY, it.toByteArray(), "application/json") },
        provenance = Provenance.Original,
        appliedRuleId = appliedRuleId,
    )

    @Test
    fun `groups by method and path newest first`() {
        val groups = groupByEndpoint(
            listOf(exchange(1), exchange(2, "/posts"), exchange(3), exchange(4, method = "post")),
        )
        assertEquals(listOf("POST /users/1", "GET /users/1", "GET /posts"), groups.map { it.key })
        assertEquals(listOf(3L, 1L), groups[1].exchanges.map { it.id })
        assertEquals(3L, groups[1].latest.id)
    }

    @Test
    fun `target reuses temporary or saved rule and otherwise creates uuid`() {
        assertEquals(RuleTarget.Temporary("l"), RuleDrafts.targetFor("l", setOf("l"), setOf("s")))
        assertEquals(RuleTarget.Saved("s"), RuleDrafts.targetFor("s", setOf("l"), setOf("s")))
        val fresh = RuleDrafts.targetFor("preset-rule", setOf("l"), setOf("s"))
        assertIs<RuleTarget.Temporary>(fresh)
        assertEquals(36, fresh.id.length)
        assertNotEquals(fresh.id, RuleDrafts.targetFor(null, emptySet(), emptySet()).id)
    }

    @Test
    fun `patch rule is diff against original response`() {
        val source = exchange(1, original = """{"id":1,"name":"Harry","tags":["a"]}""")
        val edited = """{"id":1,"name":"Boggart","tags":["a","b"]}"""
        val draft = assertIs<Draft.Ok>(RuleDrafts.patchRule(source, edited, ignoreInvalid = false, delayMs = 50, RuleTarget.Temporary("x")))
        val patch = assertIs<Override.Patch>(draft.rule.override)
        assertEquals(Json.parseToJsonElement("""{"name":"Boggart","tags":["a","b"]}"""), patch.patch)
        assertEquals(OnInvalidPatch.PassThrough, patch.onInvalid)
        assertEquals(50, patch.delayMs)
        assertEquals(RequestMatcher("GET", "api.example.com", "/users/1"), draft.rule.matcher)
        assertEquals("x", draft.rule.id)
        assertEquals(
            Json.parseToJsonElement(edited),
            JsonMergePatch.apply(Json.parseToJsonElement(source.originalResponse!!.bodyAsString()!!), patch.patch),
        )
    }

    @Test
    fun `patch rule errors`() {
        val target = RuleTarget.Temporary("x")
        assertEquals(Draft.Error(DraftError.NoOriginalBody), RuleDrafts.patchRule(exchange(1, original = null), "{}", true, 0, target))
        assertEquals(Draft.Error(DraftError.OriginalNotJson), RuleDrafts.patchRule(exchange(1, original = "<html>"), "{}", true, 0, target))
        assertEquals(Draft.Error(DraftError.EditedNotJson), RuleDrafts.patchRule(exchange(1), """{"id":""", true, 0, target))
        assertEquals(Draft.Error(DraftError.NoChanges), RuleDrafts.patchRule(exchange(1), """{ "id":1, "name":"Harry", "tags":["a"] }""", true, 0, target))
        val ok = assertIs<Draft.Ok>(RuleDrafts.patchRule(exchange(1), """{"id":2,"name":"Harry","tags":["a"]}""", true, 0, target))
        assertEquals(OnInvalidPatch.Ignore, (ok.rule.override as Override.Patch).onInvalid)
        assertEquals("GET /users/1", ok.rule.name)
    }

    @Test
    fun `editor starts from delivered body pretty printed`() {
        val text = RuleDrafts.initialEditorText(exchange(1, original = """{"a":1}""", delivered = """{"a":2}"""))
        assertTrue(text.contains("\n"))
        assertEquals(Json.parseToJsonElement("""{"a":2}"""), Json.parseToJsonElement(text))
        assertEquals("<html>", RuleDrafts.prettyBody("<html>".toByteArray()))
        assertEquals("", RuleDrafts.prettyBody(null))
    }

    @Test
    fun `replace and fail rules`() {
        val replace = RuleDrafts.replaceRule(exchange(1), 503, "Retry-After: 5\nbad line\n X-A : 1 ", "{broken", 10, RuleTarget.Temporary("r"))
        val override = assertIs<Override.Replace>(replace.override)
        assertEquals(503, override.status)
        assertEquals(mapOf("Retry-After" to "5", "X-A" to "1"), override.headers)
        assertEquals("{broken", override.body)
        assertEquals("application/json", override.mediaType)
        val fail = RuleDrafts.failRule(exchange(1), FailKind.Custom("boom"), 0, RuleTarget.Temporary("f"))
        assertEquals(Override.Fail(FailKind.Custom("boom")), fail.override)
    }

    @Test
    fun `editing replace body drops body file only when changed`() {
        val rule = Rule("s", override = Override.Replace(bodyFile = "user.json"))
        val unchanged = RuleDrafts.editReplace(rule, 201, "", "{\"a\":1}", "{\"a\":1}", 0)
        assertEquals(Override.Replace(status = 201, bodyFile = "user.json"), unchanged.override)

        val changed = RuleDrafts.editReplace(rule, 200, "", "{\"a\":2}", "{\"a\":1}", 0)
        val override = assertIs<Override.Replace>(changed.override)
        assertNull(override.bodyFile)
        assertEquals("{\"a\":2}", override.body)
    }

    @Test
    fun `editing matcher and patch`() {
        val rule = Rule("p", matcher = RequestMatcher(method = "GET", path = "/a"), override = Override.Patch(Json.parseToJsonElement("{}")))
        val matcher = RuleDrafts.editMatcher(rule, " post ", "", "/a/**", "Authorization: *").matcher
        assertEquals(RequestMatcher(method = "POST", path = "/a/**", headers = mapOf("Authorization" to "*")), matcher)
        assertEquals(Draft.Error(DraftError.PatchNotJson), RuleDrafts.editPatch(rule, "{", true, 0))
        assertEquals(Draft.Error(DraftError.OpsNotJsonPatch), RuleDrafts.editJsonPatch(rule, "{", true, 0))
        assertEquals(Draft.Error(DraftError.NoOps), RuleDrafts.editJsonPatch(rule, "[]", true, 0))
        val ok = assertIs<Draft.Ok>(RuleDrafts.editPatch(rule, """{"a":null}""", false, 5))
        assertEquals(Override.Patch(Json.parseToJsonElement("""{"a":null}"""), 5, OnInvalidPatch.PassThrough), ok.rule.override)
    }

    @Test
    fun `screen encoding round trip`() {
        listOf(Screen.Calls, Screen.Rules, Screen.CallDetail(42), Screen.RuleEdit("a:b")).forEach {
            assertEquals(it, Screen.decode(it.encode()))
        }
        assertEquals(Screen.Calls, Screen.decode("call:nope"))
    }

    @Test
    fun `curl command replays the request with quoted values`() {
        val request = BoggartRequest(
            "post",
            "https://api.example.com/users?q=a b",
            BoggartHeaders.of("Content-Type" to "application/json", "X-Note" to "it's"),
            """{"name":"O'Brien"}""".toByteArray(),
        )
        assertEquals(
            "curl -X POST 'https://api.example.com/users?q=a b' -H 'Content-Type: application/json' " +
                "-H 'X-Note: it'\\''s' --data-raw '{\"name\":\"O'\\''Brien\"}'",
            RuleDrafts.curlOf(request),
        )
        assertEquals("curl -X GET 'https://api.example.com/a'", RuleDrafts.curlOf(BoggartRequest("GET", "https://api.example.com/a")))
    }

    @Test
    fun `editing the matcher renames only auto named rules`() {
        val matcher = RequestMatcher("GET", "api.example.com", "/feed/v2/news")
        val auto = Rule("a", RuleDrafts.nameFor(matcher), matcher, Override.Fail(FailKind.Timeout))
        assertEquals("GET /feed/v2/news", auto.name)
        assertEquals("GET /feed/**", RuleDrafts.editMatcher(auto, "get", "api.example.com", "/feed/**", "").name)
        val named = auto.copy(name = "뉴스 비우기")
        assertEquals("뉴스 비우기", RuleDrafts.editMatcher(named, "GET", "", "/feed/**", "").name)
    }
}
