package io.github.hyunchan.boggart.android.ui.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 접히는 JSON 트리의 한 줄.
 *
 * @property path JSON Pointer(RFC 6901). 루트는 빈 문자열. 펼침 상태의 키로 쓴다.
 * @property key 객체 멤버 이름 또는 배열 인덱스. 루트는 null.
 * @property childCount 객체·배열이면 자식 수, 원시값이면 null.
 * @property value 원시값의 JSON 표기. 객체·배열이면 null.
 */
internal data class JsonRow(
    val path: String,
    val depth: Int,
    val key: String?,
    val childCount: Int?,
    val isArray: Boolean,
    val expanded: Boolean,
    val value: String?,
    /** 접힌 객체의 대표 값(예: `"City library extends weekend opening hours"`). 무슨 항목인지 펼치지 않고도 알아보게 한다. */
    val summary: String? = null,
) {
    val expandable: Boolean get() = childCount != null && childCount > 0
}

/** 응답 바디를 접히는 트리로 보여 주기 위한 순수 함수 모음. 펼친 노드의 자식만 줄로 만든다. */
internal object JsonTree {
    /** 처음에는 루트만 펼친다. 최상위 객체·배열의 자식은 접힌 채로 보인다. */
    val INITIAL_EXPANDED: Set<String> = setOf("")

    fun rows(root: JsonElement, expanded: Set<String>): List<JsonRow> =
        buildList { append(root, path = "", depth = 0, key = null, expanded = expanded) }

    fun toggle(expanded: Set<String>, path: String): Set<String> =
        if (path in expanded) expanded - path else expanded + path

    private fun MutableList<JsonRow>.append(
        element: JsonElement,
        path: String,
        depth: Int,
        key: String?,
        expanded: Set<String>,
    ) {
        val children: List<Pair<String, JsonElement>>? = when (element) {
            is JsonObject -> element.entries.map { it.key to it.value }
            is JsonArray -> element.mapIndexed { index, child -> index.toString() to child }
            is JsonPrimitive -> null
        }
        if (children == null) {
            add(JsonRow(path, depth, key, childCount = null, isArray = false, expanded = false, value = element.toString()))
            return
        }
        val open = path in expanded && children.isNotEmpty()
        add(
            JsonRow(
                path,
                depth,
                key,
                children.size,
                isArray = element is JsonArray,
                expanded = open,
                value = null,
                summary = if (!open && element is JsonObject) summaryOf(element) else null,
            ),
        )
        if (!open) return
        children.forEach { (childKey, child) ->
            append(child, "$path/${escape(childKey)}", depth + 1, childKey, expanded)
        }
    }

    /**
     * 빠른 편집 트리의 처음 펼침 상태. 루트와, 루트가 객체·배열을 담은 배열이면 첫 항목까지 펼친다.
     * 들어오자마자 고칠 수 있는 값이 보이게 하되, 모든 항목을 펼쳐 목록이 수백 줄이 되지는 않게 한다.
     */
    fun initialEditableExpanded(root: JsonElement): Set<String> {
        val first = (root as? JsonArray)?.firstOrNull()
        return if (first is JsonObject || first is JsonArray) setOf("", "/0") else INITIAL_EXPANDED
    }

    /** 객체를 대표하는 값. 흔히 이름 구실을 하는 키를 먼저 보고, 없으면 첫 문자열, 그다음 첫 원시값. */
    fun summaryOf(obj: JsonObject): String? {
        val primitives = obj.filterValues { it is JsonPrimitive && it !is JsonNull }
        val preferred = SUMMARY_KEYS.firstNotNullOfOrNull { key -> primitives[key] }
        val pick = preferred
            ?: primitives.values.firstOrNull { (it as JsonPrimitive).isString }
            ?: primitives.values.firstOrNull()
        return pick?.toString()
    }

    private val SUMMARY_KEYS = listOf("title", "name", "label", "displayName", "subject", "message", "symbol", "code", "id", "key")

    private fun escape(token: String): String = token.replace("~", "~0").replace("/", "~1")
}
