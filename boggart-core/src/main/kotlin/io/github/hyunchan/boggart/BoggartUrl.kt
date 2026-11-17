package io.github.hyunchan.boggart

import java.net.URLDecoder

/**
 * 네트워크 라이브러리 독립 URL 표현.
 *
 * @property path 인코딩된 경로. 비어 있으면 `/`로 본다.
 * @property query 인코딩된 쿼리 문자열(`?` 제외). 없으면 null.
 */
public data class BoggartUrl(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String = "/",
    val query: String? = null,
) {
    /** 디코딩된 쿼리 파라미터. 같은 이름이 여러 번 오면 순서대로 모은다. */
    public val queryParameters: Map<String, List<String>> by lazy(LazyThreadSafetyMode.PUBLICATION) { parseQuery(query) }

    public fun queryParameter(name: String): String? = queryParameters[name]?.firstOrNull()

    override fun toString(): String = buildString {
        append(scheme).append("://").append(host)
        if (port != defaultPort(scheme)) append(':').append(port)
        append(path.ifEmpty { "/" })
        if (query != null) append('?').append(query)
    }

    public companion object {
        /** `scheme://host[:port][/path][?query][#fragment]` 형태를 해석한다. fragment는 버린다. */
        public fun parse(url: String): BoggartUrl {
            val schemeEnd = url.indexOf("://")
            require(schemeEnd > 0) { "Missing scheme: $url" }
            val scheme = url.substring(0, schemeEnd).lowercase()
            val rest = url.substring(schemeEnd + 3).substringBefore('#')
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
            val pathAndQuery = rest.substring(authorityEnd)
            val (host, port) = splitHostPort(authority, scheme)
            require(host.isNotEmpty()) { "Missing host: $url" }
            val path = pathAndQuery.substringBefore('?').ifEmpty { "/" }
            val query = if ('?' in pathAndQuery) pathAndQuery.substringAfter('?') else null
            return BoggartUrl(scheme, host.lowercase(), port, path, query)
        }

        private fun splitHostPort(authority: String, scheme: String): Pair<String, Int> {
            val portSeparator = if (authority.startsWith("[")) {
                authority.indexOf("]:").let { if (it < 0) -1 else it + 1 }
            } else {
                authority.lastIndexOf(':')
            }
            if (portSeparator < 0) return authority to defaultPort(scheme)
            val port = authority.substring(portSeparator + 1).toIntOrNull()
                ?: throw IllegalArgumentException("Invalid port: $authority")
            return authority.substring(0, portSeparator) to port
        }

        private fun defaultPort(scheme: String): Int = when (scheme) {
            "http", "ws" -> 80
            "https", "wss" -> 443
            else -> -1
        }

        private fun parseQuery(query: String?): Map<String, List<String>> {
            if (query.isNullOrEmpty()) return emptyMap()
            val result = LinkedHashMap<String, MutableList<String>>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val name = decode(part.substringBefore('='))
                val value = if ('=' in part) decode(part.substringAfter('=')) else ""
                result.getOrPut(name) { mutableListOf() }.add(value)
            }
            return result
        }

        private fun decode(s: String): String =
            runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)
    }
}
