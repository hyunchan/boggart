package io.github.hyunchan.boggart.control

/** 요청 대상(`/path?query`)을 디코딩된 경로 세그먼트와 쿼리로 나눈다. java.net 없이 UTF-8 퍼센트 디코딩한다. */
internal object ControlTarget {
    fun parse(target: String): Pair<List<String>, Map<String, String>> {
        val withoutFragment = target.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        val rawQuery = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        val segments = path.split('/').filter { it.isNotEmpty() }.map { decode(it, plusIsSpace = false) }
        val query = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val name = decode(pair.substringBefore('='), plusIsSpace = true)
            val value = decode(pair.substringAfter('=', missingDelimiterValue = ""), plusIsSpace = true)
            if (name in query) throw ControlException(400, "BAD_REQUEST", "Query parameter '$name' is given more than once")
            query[name] = value
        }
        return segments to query
    }

    fun decode(text: String, plusIsSpace: Boolean): String {
        if ('%' !in text && !(plusIsSpace && '+' in text)) return text
        // 인코딩되지 않은 글자는 UTF-8 바이트로 두고, %XX는 바이트 하나로 바꾼 뒤 한 번에 UTF-8로 읽는다.
        val bytes = text.replace("+", if (plusIsSpace) " " else "+").toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        var size = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '%'.code.toByte()) {
                val hex = if (i + 2 < bytes.size) String(bytes, i + 1, 2, Charsets.US_ASCII).toIntOrNull(16) else null
                out[size++] = (hex ?: throw ControlException(400, "BAD_REQUEST", "Malformed percent-encoding in '$text'")).toByte()
                i += 3
            } else {
                out[size++] = b
                i++
            }
        }
        return String(out, 0, size, Charsets.UTF_8)
    }
}
