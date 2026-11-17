package io.github.hyunchan.boggart

/** 네트워크 라이브러리 독립 요청 표현. */
public data class BoggartRequest(
    val method: String,
    val url: BoggartUrl,
    val headers: BoggartHeaders = BoggartHeaders.EMPTY,
    val body: ByteArray? = null,
) {
    public constructor(
        method: String,
        url: String,
        headers: BoggartHeaders = BoggartHeaders.EMPTY,
        body: ByteArray? = null,
    ) : this(method, BoggartUrl.parse(url), headers, body)

    override fun equals(other: Any?): Boolean =
        other is BoggartRequest &&
            method == other.method &&
            url == other.url &&
            headers == other.headers &&
            body.contentEquals(other.body)

    override fun hashCode(): Int =
        ((method.hashCode() * 31 + url.hashCode()) * 31 + headers.hashCode()) * 31 + body.contentHashCode()

    override fun toString(): String = "BoggartRequest($method $url, headers=${headers.size}, body=${body?.size ?: 0}B)"
}

/**
 * 네트워크 라이브러리 독립 응답 표현.
 *
 * @property mediaType `application/json; charset=utf-8` 같은 Content-Type 값. 알 수 없으면 null.
 */
public data class BoggartResponse(
    val status: Int,
    val headers: BoggartHeaders = BoggartHeaders.EMPTY,
    val body: ByteArray? = null,
    val mediaType: String? = null,
) {
    public fun bodyAsString(): String? = body?.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is BoggartResponse &&
            status == other.status &&
            headers == other.headers &&
            mediaType == other.mediaType &&
            body.contentEquals(other.body)

    override fun hashCode(): Int =
        ((status * 31 + headers.hashCode()) * 31 + mediaType.hashCode()) * 31 + body.contentHashCode()

    override fun toString(): String = "BoggartResponse($status, mediaType=$mediaType, headers=${headers.size}, body=${body?.size ?: 0}B)"
}
