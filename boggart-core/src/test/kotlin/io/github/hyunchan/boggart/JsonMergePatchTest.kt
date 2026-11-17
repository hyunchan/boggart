package io.github.hyunchan.boggart

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonMergePatchTest {
    private fun merge(target: String, patch: String): String =
        JsonMergePatch.apply(Json.parseToJsonElement(target), Json.parseToJsonElement(patch)).toString()

    @Test
    fun `rfc 7386 examples`() {
        assertEquals("""{"a":"c"}""", merge("""{"a":"b"}""", """{"a":"c"}"""))
        assertEquals("""{"a":"b","b":"c"}""", merge("""{"a":"b"}""", """{"b":"c"}"""))
        assertEquals("{}", merge("""{"a":"b"}""", """{"a":null}"""))
        assertEquals("""{"b":"c"}""", merge("""{"a":"b","b":"c"}""", """{"a":null}"""))
        assertEquals("""{"a":"c"}""", merge("""{"a":["b"]}""", """{"a":"c"}"""))
        assertEquals("""{"a":["c"]}""", merge("""{"a":"c"}""", """{"a":["c"]}"""))
        assertEquals("""{"a":{"b":"d"}}""", merge("""{"a":{"b":"c"}}""", """{"a":{"b":"d","c":null}}"""))
        assertEquals("""{"a":[1]}""", merge("""{"a":[{"b":"c"}]}""", """{"a":[1]}"""))
        assertEquals("""{"a":{"bb":{}}}""", merge("{}", """{"a":{"bb":{"ccc":null}}}"""))
        assertEquals("""["c"]""", merge("""{"a":"foo"}""", """["c"]"""))
        assertEquals("null", merge("""{"a":"foo"}""", "null"))
        assertEquals("""{"a":1}""", merge("[1,2]", """{"a":1}"""))
        assertEquals("""{"e":null,"a":1}""", merge("""{"e":null}""", """{"a":1}"""))
    }

    private fun diff(original: String, edited: String): String =
        JsonMergePatch.diff(Json.parseToJsonElement(original), Json.parseToJsonElement(edited)).toString()

    private fun assertRoundTrip(original: String, edited: String) {
        val o = Json.parseToJsonElement(original)
        val e = Json.parseToJsonElement(edited)
        assertEquals(e, JsonMergePatch.apply(o, JsonMergePatch.diff(o, e)))
    }

    @Test
    fun `diff produces minimal patch`() {
        assertEquals("{}", diff("""{"a":1}""", """{"a":1}"""))
        assertEquals("""{"a":2}""", diff("""{"a":1,"b":1}""", """{"a":2,"b":1}"""))
        assertEquals("""{"b":null}""", diff("""{"a":1,"b":1}""", """{"a":1}"""))
        assertEquals("""{"c":{"x":true}}""", diff("""{"a":1}""", """{"a":1,"c":{"x":true}}"""))
    }

    @Test
    fun `diff recurses into nested objects`() {
        assertEquals(
            Json.parseToJsonElement("""{"user":{"name":"B","tmp":null}}"""),
            Json.parseToJsonElement(diff("""{"user":{"id":1,"name":"A","tmp":0},"n":1}""", """{"user":{"id":1,"name":"B"},"n":1}""")),
        )
        assertRoundTrip("""{"a":{"b":{"c":1,"d":2}}}""", """{"a":{"b":{"c":3},"e":[1]}}""")
    }

    @Test
    fun `diff replaces arrays primitives and type changes whole`() {
        assertEquals("""{"list":[1,3]}""", diff("""{"list":[1,2]}""", """{"list":[1,3]}"""))
        assertEquals("""{"a":{"b":1}}""", diff("""{"a":[1]}""", """{"a":{"b":1}}"""))
        assertEquals("[1]", diff("""{"a":1}""", "[1]"))
        assertEquals("\"s\"", diff("[1]", "\"s\""))
        assertRoundTrip("""{"items":[{"id":1}],"total":1}""", """{"items":[],"total":0}""")
        assertRoundTrip("[1,2]", """{"a":1}""")
    }
}
