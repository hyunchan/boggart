package io.github.hyunchan.boggart.android.ui.common
import io.github.hyunchan.boggart.BoggartHeaders
import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.BoggartResponse
import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.JsonPatchOp
import io.github.hyunchan.boggart.JsonPatchOp.Op
import io.github.hyunchan.boggart.OnInvalidPatch
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.Provenance
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValueEditsTest {
    private val original = Json.parseToJsonElement("""[{"id":1,"title":"a","tags":["x"]},{"id":2,"title":"b"}]""")

    private fun op(op: Op, path: String, value: String? = null) =
        JsonPatchOp(op, path, value?.let(Json::parseToJsonElement) ?: JsonNull)

    @Test
    fun `setValue adds a replace op`() {
        val ops = ValueEdits.setValue(original, emptyList(), "/0/title", JsonPrimitive("Boggart"))
        assertEquals(listOf(op(Op.Replace, "/0/title", "\"Boggart\"")), ops)
        assertEquals(JsonPrimitive("Boggart"), ValueEdits.valueAt(ValueEdits.preview(original, ops)!!, "/0/title"))
        assertEquals(JsonPrimitive(2), ValueEdits.valueAt(ValueEdits.preview(original, ops)!!, "/1/id"))
    }

    @Test
    fun `setValue on the same path updates in place`() {
        val first = ValueEdits.setValue(original, emptyList(), "/0/title", JsonPrimitive("x"))
        val second = ValueEdits.setValue(original, first + op(Op.Replace, "/1/id", "9"), "/0/title", JsonPrimitive("y"))
        assertEquals(listOf(op(Op.Replace, "/0/title", "\"y\""), op(Op.Replace, "/1/id", "9")), second)
    }

    @Test
    fun `setting the original value back drops the op`() {
        val ops = ValueEdits.setValue(original, emptyList(), "/0/title", JsonPrimitive("x"))
        assertEquals(emptyList(), ValueEdits.setValue(original, ops, "/0/title", JsonPrimitive("a")))
        assertEquals(emptyList(), ValueEdits.setValue(original, emptyList(), "/0/title", JsonPrimitive("a")))
    }

    @Test
    fun `setValue after a structural op appends instead of rewriting`() {
        val ops = listOf(op(Op.Replace, "/0/title", "\"x\""), op(Op.Remove, "/0"))
        val result = ValueEdits.setValue(original, ops, "/0/title", JsonPrimitive("y"))
        assertEquals(ops + op(Op.Replace, "/0/title", "\"y\""), result)
    }

    @Test
    fun `remove drops replaces on the same subtree`() {
        val ops = listOf(op(Op.Replace, "/0/title", "\"x\""), op(Op.Replace, "/1/title", "\"y\""))
        val result = ValueEdits.remove(ops, "/0")
        assertEquals(listOf(op(Op.Replace, "/1/title", "\"y\""), op(Op.Remove, "/0")), result)
        assertEquals(1, (ValueEdits.preview(original, result) as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `revert that breaks a later op makes preview fail`() {
        val ops = listOf(op(Op.Add, "/0/extra", "{}"), op(Op.Replace, "/0/extra", "1"))
        val reverted = ValueEdits.revert(ops, 0)
        assertEquals(listOf(op(Op.Replace, "/0/extra", "1")), reverted)
        assertNull(ValueEdits.preview(original, reverted))
    }

    @Test
    fun `input parsing follows the chosen type`() {
        assertEquals(JsonPrimitive("hello world"), ValueEdits.parseInput(ValueType.Text, "hello world"))
        assertEquals(JsonPrimitive("42"), ValueEdits.parseInput(ValueType.Text, "42"))
        assertEquals(Json.parseToJsonElement("42"), ValueEdits.parseInput(ValueType.Number, " 42 "))
        assertEquals(Json.parseToJsonElement("-1.5e3"), ValueEdits.parseInput(ValueType.Number, "-1.5e3"))
        assertNull(ValueEdits.parseInput(ValueType.Number, "4a"))
        assertEquals(JsonPrimitive(true), ValueEdits.parseInput(ValueType.Bool, "true"))
        assertNull(ValueEdits.parseInput(ValueType.Bool, "yes"))
        assertEquals(JsonNull, ValueEdits.parseInput(ValueType.Null, "ignored"))
        assertEquals(Json.parseToJsonElement("[]"), ValueEdits.parseInput(ValueType.Raw, "[]"))
        assertNull(ValueEdits.parseInput(ValueType.Raw, "<html>"))
    }

    @Test
    fun `input text and type come from the current value`() {
        assertEquals(ValueType.Text, ValueType.of(JsonPrimitive("a")))
        assertEquals(ValueType.Number, ValueType.of(JsonPrimitive(1)))
        assertEquals(ValueType.Bool, ValueType.of(JsonPrimitive(false)))
        assertEquals(ValueType.Null, ValueType.of(JsonNull))
        assertEquals(ValueType.Raw, ValueType.of(Json.parseToJsonElement("{}")))
        assertEquals("a \"q\"", ValueEdits.inputText(JsonPrimitive("a \"q\""), ValueType.Text))
        assertEquals("1", ValueEdits.inputText(JsonPrimitive(1), ValueType.Number))
        assertEquals("1", ValueEdits.inputText(JsonPrimitive(1), ValueType.Text))
    }

    @Test
    fun `initial ops come from an applied json patch rule`() {
        val ops = listOf(op(Op.Replace, "/0/title", "\"x\""))
        val rule = Rule("r", matcher = RequestMatcher(), override = Override.JsonPatch(ops))
        assertEquals(ops, ValueEdits.initialOps(rule))
        assertEquals(emptyList(), ValueEdits.initialOps(rule.copy(override = Override.Patch(JsonNull))))
        assertEquals(emptyList(), ValueEdits.initialOps(null))
    }

    @Test
    fun `jsonPatchRule builds a rule or explains why not`() {
        val body = original.toString().toByteArray()
        val exchange = Exchange(
            id = 1,
            timestampMs = 1,
            request = BoggartRequest("get", "https://api.example.com/feed?x=1"),
            originalResponse = BoggartResponse(200, BoggartHeaders.EMPTY, body, "application/json"),
            deliveredResponse = null,
            provenance = Provenance.Original,
            appliedRuleId = null,
        )
        val target = RuleTarget.Temporary("t")
        assertEquals(Draft.Error(DraftError.NoValueChanges), ValueEdits.jsonPatchRule(exchange, emptyList(), true, 0, target))
        assertEquals(Draft.Error(DraftError.CannotApply), ValueEdits.jsonPatchRule(exchange, listOf(op(Op.Remove, "/9")), true, 0, target))

        val ops = listOf(op(Op.Replace, "/0/title", "\"x\""))
        val rule = assertIs<Draft.Ok>(ValueEdits.jsonPatchRule(exchange, ops, false, 30, target)).rule
        assertEquals("t", rule.id)
        assertEquals("GET /feed", rule.name)
        assertEquals(RequestMatcher("GET", "api.example.com", "/feed"), rule.matcher)
        assertEquals(Override.JsonPatch(ops, 30, OnInvalidPatch.PassThrough), rule.override)

        val html = exchange.copy(originalResponse = exchange.originalResponse!!.copy(body = "<html>".toByteArray()))
        assertEquals(Draft.Error(DraftError.ValuesNotJson), ValueEdits.jsonPatchRule(html, ops, true, 0, target))
    }

    @Test
    fun `ops text round trips and rejects bad input`() {
        val ops = listOf(op(Op.Replace, "/a", "null"), op(Op.Remove, "/b"), op(Op.Add, "/c/-", "1"))
        assertEquals(ops, ValueEdits.parseOps(ValueEdits.formatOps(ops)))
        assertNull(ValueEdits.parseOps("""[{"op":"move","path":"/a"}]"""))
        assertNull(ValueEdits.parseOps("""[{"op":"replace","path":"a"}]"""))
        assertNull(ValueEdits.parseOps("{"))
        assertEquals("/b removed", ValueEdits.describe(ops[1]) { "$it removed" })
        assertEquals("/a = null", ValueEdits.describe(ops[0]) { error("not a remove") })
    }

    @Test
    fun `crossesArray tells whether a path goes through an array item`() {
        val doc = Json.parseToJsonElement("""{"user":{"name":"a"},"items":[{"title":"x"}]}""")
        assertTrue(ValueEdits.crossesArray(doc, "/items/0/title"))
        assertTrue(ValueEdits.crossesArray(doc, "/items/0"))
        assertEquals(false, ValueEdits.crossesArray(doc, "/user/name"))
        assertEquals(false, ValueEdits.crossesArray(doc, "/items"))
        assertTrue(ValueEdits.crossesArray(original, "/0/title"))
    }

    @Test
    fun `clearArray replaces the array with an empty one`() {
        val ops = ValueEdits.clearArray(original, emptyList(), "")
        assertEquals(listOf(op(Op.Replace, "", "[]")), ops)
        assertEquals(Json.parseToJsonElement("[]"), ValueEdits.preview(original, ops))
        val nested = ValueEdits.clearArray(original, emptyList(), "/0/tags")
        assertEquals(Json.parseToJsonElement("[]"), ValueEdits.valueAt(ValueEdits.preview(original, nested)!!, "/0/tags"))
    }

    @Test
    fun `keepFirst removes items from the end`() {
        val doc = Json.parseToJsonElement("[1,2,3,4]")
        val ops = ValueEdits.keepFirst(emptyList(), "", 4, 1)
        assertEquals(listOf(op(Op.Remove, "/3"), op(Op.Remove, "/2"), op(Op.Remove, "/1")), ops)
        assertEquals(Json.parseToJsonElement("[1]"), ValueEdits.preview(doc, ops))
        assertEquals(Json.parseToJsonElement("[]"), ValueEdits.preview(doc, ValueEdits.keepFirst(emptyList(), "", 4, 0)))
        assertNull(ValueEdits.preview(Json.parseToJsonElement("[1,2]"), ops))
    }

    @Test
    fun `isArrayItem tells array items from object members`() {
        assertTrue(ValueEdits.isArrayItem(original, "/0"))
        assertTrue(ValueEdits.isArrayItem(original, "/0/tags/0"))
        assertEquals(false, ValueEdits.isArrayItem(original, "/0/title"))
        assertEquals(false, ValueEdits.isArrayItem(original, ""))
    }

    @Test
    fun `changes groups keepFirst removes into one line`() {
        val list = Json.parseToJsonElement("""{"items":[1,2,3,4,5],"name":"a"}""")
        val edited = ValueEdits.setValue(list, emptyList(), "/name", JsonPrimitive("b"))
        val ops = ValueEdits.keepFirst(edited, "/items", size = 5, keep = 2)
        val changes = ValueEdits.changes(list, ops)
        assertEquals(2, changes.size)
        assertIs<ValueEdits.Change.Single>(changes[0])
        assertEquals(ValueEdits.Change.KeepFirst(1..3, "/items", 2), changes[1])
        assertEquals(edited, ValueEdits.revert(ops, changes[1]))
    }

    @Test
    fun `changes keeps removes that do not start at the end separate`() {
        val list = Json.parseToJsonElement("""[1,2,3,4,5]""")
        val ops = listOf(op(Op.Remove, "/2"), op(Op.Remove, "/1"))
        assertEquals(2, ValueEdits.changes(list, ops).size)
        val single = listOf(op(Op.Remove, "/4"))
        assertIs<ValueEdits.Change.Single>(ValueEdits.changes(list, single).single())
    }

    @Test
    fun `hidden changes belong to the nearest visible container`() {
        val visible = listOf("", "/0", "/0/id", "/1")
        val changed = setOf("/0/id", "/2", "/1/title/text", "/3")
        assertEquals(setOf("", "/1"), ValueEdits.hiddenChangeOwners(changed, visible))
        assertEquals(emptySet(), ValueEdits.hiddenChangeOwners(setOf("/0/id"), visible))
        assertEquals(setOf(""), ValueEdits.hiddenChangeOwners(setOf("/10"), listOf("", "/1")))
    }
}
