package io.github.hyunchan.boggart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonPatchTest {
    private fun j(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun replace(path: String, value: JsonElement) = JsonPatchOp(JsonPatchOp.Op.Replace, path, value)

    private fun add(path: String, value: JsonElement) = JsonPatchOp(JsonPatchOp.Op.Add, path, value)

    private fun remove(path: String) = JsonPatchOp(JsonPatchOp.Op.Remove, path)

    private val feed = j("""[{"id":1,"title":"a"},{"id":2,"title":"b"}]""")

    @Test
    fun `replace inside array changes only that value`() {
        val result = JsonPatch.apply(feed, listOf(replace("/0/title", JsonPrimitive("Boggart"))))
        assertEquals(j("""[{"id":1,"title":"Boggart"},{"id":2,"title":"b"}]"""), result)
    }

    @Test
    fun `replace with explicit null keeps the key`() {
        val result = JsonPatch.apply(j("""{"a":1}"""), listOf(replace("/a", JsonNull)))
        assertEquals(j("""{"a":null}"""), result)
    }

    @Test
    fun `add sets object member and inserts or appends to arrays`() {
        assertEquals(j("""{"a":1,"b":2}"""), JsonPatch.apply(j("""{"a":1}"""), listOf(add("/b", JsonPrimitive(2)))))
        assertEquals(j("[1,9,2]"), JsonPatch.apply(j("[1,2]"), listOf(add("/1", JsonPrimitive(9)))))
        assertEquals(j("[1,2,9]"), JsonPatch.apply(j("[1,2]"), listOf(add("/-", JsonPrimitive(9)))))
        assertEquals(j("[1,2,9]"), JsonPatch.apply(j("[1,2]"), listOf(add("/2", JsonPrimitive(9)))))
    }

    @Test
    fun `remove deletes object member and array element`() {
        assertEquals(j("""{"a":1}"""), JsonPatch.apply(j("""{"a":1,"b":2}"""), listOf(remove("/b"))))
        assertEquals(j("""[{"id":2,"title":"b"}]"""), JsonPatch.apply(feed, listOf(remove("/0"))))
    }

    @Test
    fun `empty path replaces whole document`() {
        assertEquals(j("[]"), JsonPatch.apply(feed, listOf(replace("", j("[]")))))
        assertFailsWith<JsonPatchException> { JsonPatch.apply(feed, listOf(remove(""))) }
    }

    @Test
    fun `ops apply in order`() {
        val ops = listOf(add("/x", JsonPrimitive(1)), replace("/x", JsonPrimitive(2)))
        assertEquals(j("""{"x":2}"""), JsonPatch.apply(j("{}"), ops))
    }

    @Test
    fun `missing paths fail`() {
        listOf(
            replace("/missing", JsonPrimitive(1)),
            remove("/missing"),
            replace("/0/missing/deep", JsonPrimitive(1)),
            replace("/2/title", JsonPrimitive(1)),
            replace("/-", JsonPrimitive(1)),
            replace("/01", JsonPrimitive(1)),
            replace("/x", JsonPrimitive(1)),
            add("/3", JsonPrimitive(1)),
            replace("/0/id/deeper", JsonPrimitive(1)),
        ).forEach { op ->
            assertFailsWith<JsonPatchException>(op.path) { JsonPatch.apply(feed, listOf(op)) }
        }
    }

    @Test
    fun `failure in a later op fails the whole patch`() {
        val ops = listOf(replace("/0/title", JsonPrimitive("x")), remove("/9"))
        assertFailsWith<JsonPatchException> { JsonPatch.apply(feed, ops) }
    }

    @Test
    fun `pointer escapes round trip`() {
        val doc = j("""{"a/b":{"c~d":1}}""")
        val pointer = JsonPatch.pointerOf(listOf("a/b", "c~d"))
        assertEquals("/a~1b/c~0d", pointer)
        assertEquals(listOf("a/b", "c~d"), JsonPatch.parsePointer(pointer))
        assertEquals(j("""{"a/b":{"c~d":2}}"""), JsonPatch.apply(doc, listOf(replace(pointer, JsonPrimitive(2)))))
        assertEquals(listOf("~1"), JsonPatch.parsePointer("/~01"))
    }

    @Test
    fun `op rejects pointer without leading slash`() {
        assertFailsWith<IllegalArgumentException> { replace("title", JsonPrimitive(1)) }
    }
}
