package io.github.hyunchan.boggart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RuleSerializationTest {
    private val json = Json

    private fun roundTrip(rule: Rule) {
        val encoded = json.encodeToString(Rule.serializer(), rule)
        assertEquals(rule, json.decodeFromString(Rule.serializer(), encoded))
    }

    @Test
    fun `round trip every override`() {
        val matcher = RequestMatcher("GET", "api.example.com", "/users/**", mapOf("q" to "*"), mapOf("X-Env" to "qa"))
        roundTrip(Rule("r1", "replace", matcher, Override.Replace(500, mapOf("X" to "1"), body = "{}", delayMs = 10)))
        roundTrip(Rule("r2", "file", matcher, Override.Replace(bodyFile = "bodies/u.json", mediaType = null)))
        roundTrip(Rule("r3", "patch", matcher, Override.Patch(Json.parseToJsonElement("""{"a":null,"b":[1]}"""), 5)))
        roundTrip(Rule("r3", "patch", matcher, Override.Patch(Json.parseToJsonElement("{}"), onInvalid = OnInvalidPatch.PassThrough)))
        roundTrip(
            Rule(
                "r5",
                "jsonPatch",
                matcher,
                Override.JsonPatch(
                    listOf(
                        JsonPatchOp(JsonPatchOp.Op.Replace, "/0/title", JsonPrimitive("x")),
                        JsonPatchOp(JsonPatchOp.Op.Replace, "/0/link", JsonNull),
                        JsonPatchOp(JsonPatchOp.Op.Add, "/-", Json.parseToJsonElement("""{"id":3}""")),
                        JsonPatchOp(JsonPatchOp.Op.Remove, "/1"),
                    ),
                    delayMs = 3,
                    onInvalid = OnInvalidPatch.Ignore,
                ),
            ),
        )
        listOf(FailKind.Timeout, FailKind.ConnectionReset, FailKind.UnknownHost, FailKind.Custom("boom")).forEach {
            roundTrip(Rule("r4", "fail", matcher, Override.Fail(it), enabled = false))
        }
    }

    @Test
    fun `decode minimal rule file`() {
        val rule = json.decodeFromString(
            Rule.serializer(),
            """{"id":"timeout","matcher":{"path":"/slow"},"override":{"type":"fail","kind":{"type":"timeout"},"delayMs":3000}}""",
        )
        assertEquals("timeout", rule.name)
        assertEquals(Override.Fail(FailKind.Timeout, 3000), rule.override)
        assertEquals(true, rule.enabled)
    }

    @Test
    fun `decode json patch rule from rules document`() {
        val document = RulesDocument.parse(
            """
            {"version":1,"rules":[{"id":"p","matcher":{"path":"/feed"},"override":{"type":"jsonPatch","ops":[
              {"op":"replace","path":"/0/title","value":"Boggart"},
              {"op":"replace","path":"/0/link","value":null},
              {"op":"remove","path":"/1"}
            ]}}]}
            """.trimIndent(),
        )
        val override = assertIs<Override.JsonPatch>(document.rules.single().override)
        assertEquals(
            listOf(
                JsonPatchOp(JsonPatchOp.Op.Replace, "/0/title", JsonPrimitive("Boggart")),
                JsonPatchOp(JsonPatchOp.Op.Replace, "/0/link", JsonNull),
                JsonPatchOp(JsonPatchOp.Op.Remove, "/1"),
            ),
            override.ops,
        )
        assertEquals(document, RulesDocument.parse(document.encode()))
    }

    @Test
    fun `replace rejects both body and bodyFile`() {
        assertFailsWith<IllegalArgumentException> { Override.Replace(body = "{}", bodyFile = "a.json") }
    }
}

class RulesDocumentTest {
    private val rule = Rule("r1", matcher = RequestMatcher(path = "/a"), override = Override.Replace(bodyFile = "a.json"), enabled = false)

    @Test
    fun `round trip`() {
        val document = RulesDocument(rules = listOf(rule))
        assertEquals(document, RulesDocument.parse(document.encode()))
    }

    @Test
    fun `missing version defaults to current and unknown keys are ignored`() {
        val document = RulesDocument.parse("""{"rules":[],"comment":"presets"}""")
        assertEquals(RulesDocument.CURRENT_VERSION, document.version)
    }

    @Test
    fun `unsupported version is rejected before decoding rules`() {
        val error = assertFailsWith<IllegalArgumentException> {
            RulesDocument.parse("""{"version":2,"rules":[{"unknown":"shape"}]}""")
        }
        assertEquals("Unsupported rules document version: 2", error.message)
    }
}
