package io.github.hyunchan.boggart

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** JSON Merge Patch (RFC 7386). */
public object JsonMergePatch {
    public fun apply(target: JsonElement, patch: JsonElement): JsonElement {
        if (patch !is JsonObject) return patch
        val result = (target as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        for ((key, value) in patch) {
            if (value is JsonNull) {
                result.remove(key)
            } else {
                result[key] = apply(result[key] ?: JsonNull, value)
            }
        }
        return JsonObject(result)
    }

    /**
     * [original]을 [edited]로 만드는 Merge Patch를 역산한다. `apply(original, diff(original, edited)) == edited`.
     *
     * 객체끼리는 키별로 재귀하고, 사라진 키는 null, 같은 값은 생략한다. 배열·원시값·타입이 바뀐 값은 통째로 교체한다.
     * [edited]가 객체가 아니면 [edited] 자체가 패치다.
     *
     * Merge Patch는 null을 "삭제"로 해석하므로, 객체 안의 값을 명시적 null로 바꾼 편집은 키 삭제로 표현된다.
     */
    public fun diff(original: JsonElement, edited: JsonElement): JsonElement {
        if (original !is JsonObject || edited !is JsonObject) return edited
        val patch = LinkedHashMap<String, JsonElement>()
        for (key in original.keys) {
            if (key !in edited) patch[key] = JsonNull
        }
        for ((key, value) in edited) {
            val before = original[key]
            when {
                before == value -> Unit
                before is JsonObject && value is JsonObject -> patch[key] = diff(before, value)
                else -> patch[key] = value
            }
        }
        return JsonObject(patch)
    }
}
