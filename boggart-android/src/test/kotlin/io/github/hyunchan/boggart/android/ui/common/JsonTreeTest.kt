package io.github.hyunchan.boggart.android.ui.common

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonTreeTest {
    private val feed = Json.parseToJsonElement(
        """[{"id":1,"title":"a","tags":["x","y"]},{"id":2,"title":"b","tags":[]}]""",
    )

    @Test
    fun `처음에는 루트만 펼치고 최상위 자식은 접는다`() {
        val rows = JsonTree.rows(feed, JsonTree.INITIAL_EXPANDED)

        assertEquals(listOf("", "/0", "/1"), rows.map { it.path })
        assertTrue(rows[0].expanded)
        assertEquals(2, rows[0].childCount)
        assertTrue(rows.drop(1).none { it.expanded })
        assertEquals(listOf(null, "0", "1"), rows.map { it.key })
    }

    @Test
    fun `펼친 노드의 자식만 깊이와 함께 나온다`() {
        val rows = JsonTree.rows(feed, setOf("", "/0", "/0/tags"))

        assertEquals(
            listOf("" to 0, "/0" to 1, "/0/id" to 2, "/0/title" to 2, "/0/tags" to 2, "/0/tags/0" to 3, "/0/tags/1" to 3, "/1" to 1),
            rows.map { it.path to it.depth },
        )
        assertEquals("\"a\"", rows.single { it.path == "/0/title" }.value)
        assertEquals("1", rows.single { it.path == "/0/id" }.value)
    }

    @Test
    fun `빈 컨테이너는 펼칠 수 없다`() {
        val rows = JsonTree.rows(feed, setOf("", "/1", "/1/tags"))
        val empty = rows.single { it.path == "/1/tags" }

        assertFalse(empty.expandable)
        assertFalse(empty.expanded)
    }

    @Test
    fun `원시값 루트는 한 줄이다`() {
        val rows = JsonTree.rows(Json.parseToJsonElement("42"), JsonTree.INITIAL_EXPANDED)

        assertEquals(1, rows.size)
        assertEquals("42", rows.single().value)
    }

    @Test
    fun `경로는 JSON Pointer로 이스케이프해 키 충돌이 없다`() {
        val element = Json.parseToJsonElement("""{"a/b":{"c":1},"a":{"b":{"c":2}},"t~":0}""")
        val rows = JsonTree.rows(element, setOf("", "/a~1b", "/a", "/a/b"))

        assertEquals("1", rows.single { it.path == "/a~1b/c" }.value)
        assertEquals("2", rows.single { it.path == "/a/b/c" }.value)
        assertTrue(rows.any { it.path == "/t~0" })
    }

    @Test
    fun `toggle은 펼침과 접힘을 오간다`() {
        val opened = JsonTree.toggle(JsonTree.INITIAL_EXPANDED, "/0")
        assertEquals(setOf("", "/0"), opened)
        assertEquals(JsonTree.INITIAL_EXPANDED, JsonTree.toggle(opened, "/0"))
    }

    @Test
    fun `줄 라벨은 접힘 여부와 자식 수를 보여 준다`() {
        val rows = JsonTree.rows(feed, setOf("", "/1"))

        assertEquals("[ 2", jsonRowLabel(rows[0]))
        assertEquals("0: {…} 3", jsonRowLabel(rows[1]))
        assertEquals("1: { 3", jsonRowLabel(rows[2]))
        assertEquals("title: \"b\"", jsonRowLabel(rows.single { it.path == "/1/title" }))
        assertEquals("tags: []", jsonRowLabel(rows.single { it.path == "/1/tags" }))
    }

    @Test
    fun `collapsed objects carry a summary and editable trees open the first item`() {
        val rows = JsonTree.rows(feed, JsonTree.INITIAL_EXPANDED)
        assertEquals("\"a\"", rows.single { it.path == "/0" }.summary)
        assertEquals(null, JsonTree.rows(feed, setOf("", "/0")).single { it.path == "/0" }.summary)
        assertEquals(setOf("", "/0"), JsonTree.initialEditableExpanded(feed))
        assertEquals(JsonTree.INITIAL_EXPANDED, JsonTree.initialEditableExpanded(Json.parseToJsonElement("[1,2]")))
        assertEquals(JsonTree.INITIAL_EXPANDED, JsonTree.initialEditableExpanded(Json.parseToJsonElement("{}")))
    }

    @Test
    fun `summary prefers name-like keys then strings then any primitive`() {
        fun obj(text: String) = Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        assertEquals("\"BTC\"", JsonTree.summaryOf(obj("""{"id":1,"price":2,"name":"BTC"}""")))
        assertEquals("\"x\"", JsonTree.summaryOf(obj("""{"count":3,"note":"x"}""")))
        assertEquals("3", JsonTree.summaryOf(obj("""{"count":3,"extra":null}""")))
        assertEquals(null, JsonTree.summaryOf(obj("""{"a":{},"b":null}""")))
    }
}
