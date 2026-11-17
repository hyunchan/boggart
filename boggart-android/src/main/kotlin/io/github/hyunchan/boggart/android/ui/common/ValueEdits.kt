package io.github.hyunchan.boggart.android.ui.common

import io.github.hyunchan.boggart.Exchange
import io.github.hyunchan.boggart.JsonPatch
import io.github.hyunchan.boggart.JsonPatchException
import io.github.hyunchan.boggart.JsonPatchOp
import io.github.hyunchan.boggart.OnInvalidPatch
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.Rule
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 값 편집 다이얼로그의 입력 방식. */
internal enum class ValueType {
    Text,
    Number,
    Bool,
    Null,
    Raw,
    ;

    companion object {
        fun of(element: JsonElement?): ValueType = when {
            element == null -> Text
            element is JsonNull -> Null
            element is JsonPrimitive && element.isString -> Text
            element is JsonPrimitive && element.booleanOrNull != null -> Bool
            element is JsonPrimitive -> Number
            else -> Raw
        }
    }
}

/**
 * 트리에서 값을 골라 고치는 편집(JSON Patch)을 위한 순수 함수 모음. op 경로는 [JsonTree]의 경로(RFC 6901)를 그대로 쓴다.
 */
internal object ValueEdits {
    private val pretty = Json { prettyPrint = true }
    private val opsSerializer = ListSerializer(JsonPatchOp.serializer())

    /** 교환에 이미 JsonPatch 규칙이 적용돼 있으면 그 연산에서 시작한다(누적 편집). */
    fun initialOps(applied: Rule?): List<JsonPatchOp> = (applied?.override as? Override.JsonPatch)?.ops.orEmpty()

    /** [original]에 [ops]를 적용한 결과. 적용할 수 없으면 null. */
    fun preview(original: JsonElement, ops: List<JsonPatchOp>): JsonElement? = try {
        JsonPatch.apply(original, ops)
    } catch (_: JsonPatchException) {
        null
    }

    /** [path]가 배열 항목을 거쳐 가면 true. 배열 위치로 지정한 경로는 응답 순서가 바뀌면 다른 항목을 가리킨다. */
    fun crossesArray(doc: JsonElement, path: String): Boolean {
        var node: JsonElement? = doc
        for (token in JsonPatch.parsePointer(path)) {
            node = when (val current = node) {
                is JsonArray -> return true
                is JsonObject -> current[token]
                else -> return false
            }
        }
        return false
    }

    /** [doc]에서 [path]의 값. 없으면 null. */
    fun valueAt(doc: JsonElement, path: String): JsonElement? =
        JsonPatch.parsePointer(path).fold(doc as JsonElement?) { node, token ->
            when (node) {
                is JsonObject -> node[token]
                is JsonArray -> token.toIntOrNull()?.let { node.getOrNull(it) }
                else -> null
            }
        }

    /**
     * [path]의 값을 [value]로 바꾸는 연산을 더한다.
     *
     * 같은 경로의 `replace`가 있고 그 뒤에 구조를 바꾸는 연산(`add`·`remove`)이 없으면 그 자리에서 값만 바꾼다.
     * 바꾼 결과가 [original]의 값과 같으면 연산을 더하지 않고, 같은 경로의 기존 연산도 지운다.
     */
    fun setValue(original: JsonElement, ops: List<JsonPatchOp>, path: String, value: JsonElement): List<JsonPatchOp> {
        val index = replaceableIndex(ops, path)
        if (comparableWithOriginal(ops, path) && valueAt(original, path) == value) {
            return if (index != null) ops.filterIndexed { i, _ -> i != index } else ops
        }
        val op = JsonPatchOp(JsonPatchOp.Op.Replace, path, value)
        return if (index != null) ops.toMutableList().apply { set(index, op) } else ops + op
    }

    /** [path]의 값을 지우는 연산을 더한다. 그 경로나 하위 경로만 바꾸던 `replace`는 의미가 없어지므로 함께 지운다. */
    fun remove(ops: List<JsonPatchOp>, path: String): List<JsonPatchOp> {
        val tailStart = ops.indexOfLast { it.op != JsonPatchOp.Op.Replace } + 1
        val kept = ops.filterIndexed { i, op -> i < tailStart || !(op.path == path || op.path.startsWith("$path/")) }
        return kept + JsonPatchOp(JsonPatchOp.Op.Remove, path)
    }

    /** [path]의 배열을 빈 배열로 바꾼다. 응답 길이와 무관하게 늘 적용된다. */
    fun clearArray(original: JsonElement, ops: List<JsonPatchOp>, path: String): List<JsonPatchOp> =
        setValue(original, ops, path, JsonArray(emptyList()))

    /**
     * [path]의 배열에서 처음 [keep]개만 남긴다. 지금 크기([size])에서 끝 항목부터 지우는 연산을 만든다.
     * 위치 기반이라 실제 응답이 더 짧아지면 연산을 적용할 수 없어 규칙 전체가 원본으로 전달된다.
     */
    fun keepFirst(ops: List<JsonPatchOp>, path: String, size: Int, keep: Int): List<JsonPatchOp> {
        require(keep in 0 until size) { "keep must be in 0 until $size" }
        return (size - 1 downTo keep).fold(ops) { acc, index -> remove(acc, "$path/$index") }
    }

    /** [path]가 배열의 항목이면 true. */
    fun isArrayItem(doc: JsonElement, path: String): Boolean {
        val tokens = JsonPatch.parsePointer(path)
        if (tokens.isEmpty()) return false
        return valueAt(doc, JsonPatch.pointerOf(tokens.dropLast(1))) is JsonArray
    }

    /** 변경 목록에 보이는 한 줄. 연산 하나이거나, "처음 N개만 남기기"로 생긴 연달은 `remove` 묶음이다. */
    sealed interface Change {
        val indices: IntRange

        data class Single(val index: Int, val op: JsonPatchOp) : Change {
            override val indices: IntRange get() = index..index
        }

        data class KeepFirst(override val indices: IntRange, val path: String, val keep: Int) : Change
    }

    /**
     * 연산을 변경 목록 줄로 묶는다. 같은 배열의 마지막 항목부터 한 칸씩 앞으로 지우는 `remove`가 2개 이상 이어지면
     * "처음 N개만 남김" 한 줄로 보인다. 저장되는 연산은 그대로이고 보여 주기만 묶는다.
     */
    fun changes(original: JsonElement, ops: List<JsonPatchOp>): List<Change> {
        val result = mutableListOf<Change>()
        var i = 0
        while (i < ops.size) {
            val group = keepFirstAt(original, ops, i)
            if (group != null) {
                result += group
                i = group.indices.last + 1
            } else {
                result += Change.Single(i, ops[i])
                i++
            }
        }
        return result
    }

    private fun keepFirstAt(original: JsonElement, ops: List<JsonPatchOp>, start: Int): Change.KeepFirst? {
        val (parent, first) = arrayIndexOf(ops[start]) ?: return null
        var end = start
        while (end + 1 < ops.size && arrayIndexOf(ops[end + 1]) == parent to first - (end + 1 - start)) end++
        if (end == start) return null
        // 묶음이 그 시점 배열의 마지막 항목부터 시작해야 "처음 N개만 남김"이 맞는 설명이 된다.
        val before = preview(original, ops.take(start)) ?: return null
        val size = (valueAt(before, parent) as? JsonArray)?.size ?: return null
        if (first != size - 1) return null
        return Change.KeepFirst(start..end, parent, first - (end - start))
    }

    /** `remove` 연산이 배열 항목을 가리키면 (배열 경로, 인덱스). */
    private fun arrayIndexOf(op: JsonPatchOp): Pair<String, Int>? {
        if (op.op != JsonPatchOp.Op.Remove) return null
        val tokens = JsonPatch.parsePointer(op.path)
        val index = tokens.lastOrNull()?.toIntOrNull() ?: return null
        return JsonPatch.pointerOf(tokens.dropLast(1)) to index
    }

    /** 변경 목록 한 줄([change])을 통째로 되돌린다. */
    fun revert(ops: List<JsonPatchOp>, change: Change): List<JsonPatchOp> = ops.filterIndexed { i, _ -> i !in change.indices }

    /** [index]번째 연산을 되돌린다. 뒤의 연산이 그 연산에 기댔다면 미리보기가 실패하므로 화면이 알린다. */
    fun revert(ops: List<JsonPatchOp>, index: Int): List<JsonPatchOp> = ops.filterIndexed { i, _ -> i != index }

    /** 변경된 경로. 트리에서 강조한다. */
    fun changedPaths(ops: List<JsonPatchOp>): Set<String> = ops.mapTo(HashSet()) { it.path }

    /**
     * 트리에 줄로 보이지 않는 변경(접힌 객체 안의 값, 지운 항목)을 가장 가까운 보이는 조상 경로에 모은다.
     * 그 객체·배열의 ⋯ 칩을 강조해 안쪽이 바뀌었음을 알린다. 줄로 보이는 변경은 값 알약이 이미 드러내므로 넣지 않는다.
     */
    fun hiddenChangeOwners(changed: Set<String>, visible: List<String>): Set<String> {
        val shown = visible.toSet()
        return changed.filterNot { it in shown }.mapNotNullTo(HashSet()) { path ->
            visible.filter { path.startsWith("$it/") }.maxByOrNull { it.length }
        }
    }

    /** 값 편집 다이얼로그의 시작 텍스트. 문자열은 따옴표 없이 보여 준다. */
    fun inputText(element: JsonElement?, type: ValueType): String = when (type) {
        ValueType.Text -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: element?.toString().orEmpty()
        ValueType.Null -> ""
        ValueType.Raw -> element?.let { pretty.encodeToString(JsonElement.serializer(), it) }.orEmpty()
        ValueType.Number, ValueType.Bool -> element?.takeIf { it !is JsonNull }?.toString().orEmpty()
    }

    /** 입력을 JSON 값으로 바꾼다. 형식에 맞지 않으면 null. */
    fun parseInput(type: ValueType, text: String): JsonElement? = when (type) {
        ValueType.Text -> JsonPrimitive(text)
        ValueType.Null -> JsonNull
        ValueType.Bool -> when (text.trim()) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> null
        }
        ValueType.Number -> text.trim().takeIf { NUMBER.matches(it) }?.let { Json.parseToJsonElement(it) }
        ValueType.Raw -> RuleDrafts.parseJson(text.toByteArray())
    }

    fun jsonPatchRule(
        exchange: Exchange,
        ops: List<JsonPatchOp>,
        ignoreInvalid: Boolean,
        delayMs: Long,
        target: RuleTarget,
    ): Draft {
        if (ops.isEmpty()) return Draft.Error(DraftError.NoValueChanges)
        val original = exchange.originalResponse?.body?.let(RuleDrafts::parseJson)
            ?: return Draft.Error(DraftError.ValuesNotJson)
        if (preview(original, ops) == null) return Draft.Error(DraftError.CannotApply)
        val request = exchange.request
        return Draft.Ok(
            Rule(
                id = target.id,
                name = RuleDrafts.ruleName(request),
                matcher = RuleDrafts.matcherFor(request),
                override = Override.JsonPatch(ops, delayMs, if (ignoreInvalid) OnInvalidPatch.Ignore else OnInvalidPatch.PassThrough),
            ),
        )
    }

    fun formatOps(ops: List<JsonPatchOp>): String = pretty.encodeToString(opsSerializer, ops)

    fun parseOps(text: String): List<JsonPatchOp>? = try {
        Json.decodeFromString(opsSerializer, text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** 변경 목록 한 줄. 삭제 문구는 언어별로 [removed]가 만든다. */
    fun describe(op: JsonPatchOp, removed: (String) -> String): String = when (op.op) {
        JsonPatchOp.Op.Replace -> "${op.path} = ${op.value}"
        JsonPatchOp.Op.Add -> "${op.path} + ${op.value}"
        JsonPatchOp.Op.Remove -> removed(op.path)
    }

    private fun replaceableIndex(ops: List<JsonPatchOp>, path: String): Int? {
        val index = ops.indexOfLast { it.path == path && it.op == JsonPatchOp.Op.Replace }
        if (index < 0) return null
        return index.takeIf { ops.drop(index + 1).all { it.op == JsonPatchOp.Op.Replace } }
    }

    /**
     * 모든 연산이 replace이고 [path]의 상위 경로를 바꾸는 연산이 없을 때만, [path]의 원본 값이 지금 문서에서도 같은 자리를 가리킨다.
     */
    private fun comparableWithOriginal(ops: List<JsonPatchOp>, path: String): Boolean =
        ops.all { it.op == JsonPatchOp.Op.Replace } && ops.none { it.path != path && (path.startsWith("${it.path}/") || it.path.isEmpty()) }

    private val NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")
}
