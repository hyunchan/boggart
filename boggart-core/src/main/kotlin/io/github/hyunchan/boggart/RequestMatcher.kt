package io.github.hyunchan.boggart

import kotlinx.serialization.Serializable

/**
 * 요청 매칭 조건. 비워 둔 조건은 모든 요청에 매칭된다.
 *
 * @property method HTTP 메서드. 대소문자 무시.
 * @property host 호스트. 대소문자 무시, 정확히 일치.
 * @property path 경로 글롭. `*`는 한 세그먼트 안의 임의 문자열(세그먼트 전체로 쓰면 정확히 한 세그먼트),
 *   `**`는 0개 이상의 세그먼트. 빈 세그먼트는 무시하므로 끝 슬래시 유무는 구분하지 않는다.
 * @property query 쿼리 파라미터 조건. 값이 `*`이면 존재만 검사, 그 외는 같은 이름의 값 중 하나와 일치.
 * @property headers 헤더 조건. 이름은 대소문자 무시, 값 규칙은 [query]와 같다.
 */
@Serializable
public data class RequestMatcher(
    val method: String? = null,
    val host: String? = null,
    val path: String? = null,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
) {
    public fun matches(request: BoggartRequest): Boolean {
        if (method != null && !method.equals(request.method, ignoreCase = true)) return false
        if (host != null && !host.equals(request.url.host, ignoreCase = true)) return false
        if (path != null && !PathGlob.matches(path, request.url.path)) return false
        for ((name, expected) in query) {
            val actual = request.url.queryParameters[name] ?: return false
            if (expected != WILDCARD && expected !in actual) return false
        }
        for ((name, expected) in headers) {
            val actual = request.headers.values(name)
            if (actual.isEmpty()) return false
            if (expected != WILDCARD && expected !in actual) return false
        }
        return true
    }

    /**
     * 이 조건에 맞는 요청이 [other]에도 늘 맞는지(= [other]가 이 조건을 완전히 덮는지) 보수적으로 판단한다.
     * true면 확실히 덮으므로, [other]가 먼저 매칭되는 자리에 있으면 이 조건의 규칙은 적용될 일이 없다.
     * false는 덮지 않거나 일부만 겹쳐 확실하지 않다는 뜻이다.
     */
    public fun isCoveredBy(other: RequestMatcher): Boolean {
        if (other.method != null && !other.method.equals(method, ignoreCase = true)) return false
        if (other.host != null && !other.host.equals(host, ignoreCase = true)) return false
        if (other.path != null && !PathGlob.covers(other.path, path ?: return false)) return false
        return constraintsCovered(other.query, query, ignoreCase = false) &&
            constraintsCovered(other.headers, headers, ignoreCase = true)
    }

    private companion object {
        const val WILDCARD = "*"

        /** [outer]의 조건마다 [inner]에 같은 조건이 있거나, [outer]가 존재만 보면(`*`) [inner]에 그 이름이 있어야 한다. */
        fun constraintsCovered(outer: Map<String, String>, inner: Map<String, String>, ignoreCase: Boolean): Boolean =
            outer.all { (name, expected) ->
                val actual = inner.entries.firstOrNull { it.key.equals(name, ignoreCase) }?.value ?: return@all false
                expected == WILDCARD || expected == actual
            }
    }
}

internal object PathGlob {
    fun matches(pattern: String, path: String): Boolean =
        matchSegments(segments(pattern), 0, segments(path), 0)

    /**
     * [pattern]이 [target] 패턴에 맞는 모든 경로를 덮는지. [target]이 글롭이면 같은 패턴이거나 [pattern]이 `**`일 때만 덮는다고 본다
     * (그 밖의 글롭끼리 포함 관계는 판단하지 않는다).
     */
    fun covers(pattern: String, target: String): Boolean = when {
        segments(pattern) == segments(target) -> true
        '*' in target -> segments(pattern) == listOf("**")
        else -> matches(pattern, target)
    }

    private fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    private fun matchSegments(pattern: List<String>, pi: Int, path: List<String>, si: Int): Boolean {
        if (pi == pattern.size) return si == path.size
        val p = pattern[pi]
        if (p == "**") {
            for (next in si..path.size) {
                if (matchSegments(pattern, pi + 1, path, next)) return true
            }
            return false
        }
        if (si == path.size) return false
        return matchSegment(p, path[si]) && matchSegments(pattern, pi + 1, path, si + 1)
    }

    /** 세그먼트 안의 `*`는 0개 이상의 임의 문자. */
    private fun matchSegment(pattern: String, segment: String): Boolean {
        if ('*' !in pattern) return pattern == segment
        val parts = pattern.split('*')
        if (!segment.startsWith(parts.first())) return false
        var cursor = parts.first().length
        for (i in 1 until parts.size - 1) {
            val found = segment.indexOf(parts[i], cursor)
            if (found < 0) return false
            cursor = found + parts[i].length
        }
        val last = parts.last()
        return segment.length - cursor >= last.length && segment.endsWith(last)
    }
}
