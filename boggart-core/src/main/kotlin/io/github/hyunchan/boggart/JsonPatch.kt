package io.github.hyunchan.boggart

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * JSON Patch(RFC 6902)의 연산 하나. `add`·`remove`·`replace`만 지원한다.
 *
 * @property path JSON Pointer(RFC 6901). 빈 문자열은 문서 전체.
 * @property value `add`·`replace`의 새 값. `remove`에서는 쓰지 않는다. 기본값 [JsonNull]이라 null로 바꾸는 연산은 저장할 때 생략돼도 의미가 같다.
 */
@Serializable
public data class JsonPatchOp(
    val op: Op,
    val path: String,
    val value: JsonElement = JsonNull,
) {
    init {
        require(path.isEmpty() || path.startsWith("/")) { "JSON Pointer must be empty or start with '/': $path" }
    }

    @Serializable
    public enum class Op {
        @SerialName("add")
        Add,

        @SerialName("remove")
        Remove,

        @SerialName("replace")
        Replace,
    }
}

/** 경로가 없거나 인덱스가 범위를 벗어나 패치를 적용할 수 없을 때. */
public class JsonPatchException(message: String) : IllegalArgumentException(message)

/** JSON Patch(RFC 6902)의 `add`·`remove`·`replace` 하위 집합. */
public object JsonPatch {
    /**
     * [ops]를 순서대로 적용한다. 문서 전체가 원자적이다. 하나라도 실패하면 [JsonPatchException]을 던지고 [target]은 그대로다.
     */
    public fun apply(target: JsonElement, ops: List<JsonPatchOp>): JsonElement =
        ops.fold(target) { doc, op -> applyOne(doc, op) }

    /** RFC 6901 포인터를 토큰으로 나눈다. `~1`→`/`, `~0`→`~` 순서로 푼다. */
    public fun parsePointer(pointer: String): List<String> {
        if (pointer.isEmpty()) return emptyList()
        if (!pointer.startsWith("/")) throw JsonPatchException("JSON Pointer must start with '/': $pointer")
        return pointer.substring(1).split("/").map { it.replace("~1", "/").replace("~0", "~") }
    }

    /** [tokens]를 RFC 6901 포인터로 만든다. */
    public fun pointerOf(tokens: List<String>): String =
        tokens.joinToString("") { "/" + it.replace("~", "~0").replace("/", "~1") }

    private fun applyOne(doc: JsonElement, op: JsonPatchOp): JsonElement {
        val tokens = parsePointer(op.path)
        if (tokens.isEmpty()) {
            return when (op.op) {
                JsonPatchOp.Op.Add, JsonPatchOp.Op.Replace -> op.value
                JsonPatchOp.Op.Remove -> throw JsonPatchException("Cannot remove the whole document")
            }
        }
        return update(doc, tokens, 0, op)
    }

    private fun update(node: JsonElement, tokens: List<String>, index: Int, op: JsonPatchOp): JsonElement {
        val token = tokens[index]
        val last = index == tokens.lastIndex
        return when (node) {
            is JsonObject -> {
                val map = LinkedHashMap(node)
                if (last) {
                    when (op.op) {
                        JsonPatchOp.Op.Add -> map[token] = op.value
                        JsonPatchOp.Op.Replace -> {
                            if (token !in map) throw missing(op)
                            map[token] = op.value
                        }
                        JsonPatchOp.Op.Remove -> map.remove(token) ?: throw missing(op)
                    }
                } else {
                    val child = map[token] ?: throw missing(op)
                    map[token] = update(child, tokens, index + 1, op)
                }
                JsonObject(map)
            }
            is JsonArray -> {
                val list = node.toMutableList()
                if (last && op.op == JsonPatchOp.Op.Add) {
                    val at = if (token == "-") list.size else arrayIndex(token, list.size, op)
                    list.add(at, op.value)
                } else {
                    val at = arrayIndex(token, list.size - 1, op)
                    when {
                        !last -> list[at] = update(list[at], tokens, index + 1, op)
                        op.op == JsonPatchOp.Op.Replace -> list[at] = op.value
                        else -> list.removeAt(at)
                    }
                }
                JsonArray(list)
            }
            else -> throw missing(op)
        }
    }

    /** 0..[max] 범위의 십진 인덱스. 앞자리 0은 RFC 6901에 따라 거부한다. */
    private fun arrayIndex(token: String, max: Int, op: JsonPatchOp): Int {
        val valid = token == "0" || (token.isNotEmpty() && token[0] in '1'..'9' && token.all(Char::isDigit))
        val value = if (valid) token.toIntOrNull() else null
        if (value == null || value > max) throw missing(op)
        return value
    }

    private fun missing(op: JsonPatchOp) =
        JsonPatchException("Path not found for ${op.op.name.lowercase()}: ${op.path}")
}
