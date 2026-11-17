package io.github.hyunchan.boggart

/** 패치 결과 바디 검증기. 호스트 앱이 자기 파서로 구현해 등록할 수 있다. */
public fun interface BodyValidator {
    public fun validate(body: ByteArray, mediaType: String?): Boolean
}

/** UTF-8 JSON 문법(RFC 8259)만 검사하는 기본 검증기. */
public object JsonSyntaxValidator : BodyValidator {
    override fun validate(body: ByteArray, mediaType: String?): Boolean = StrictJson.parse(body) != null
}

/** [Override.Replace.bodyFile] 참조를 바이트로 읽는다. 찾지 못하면 null. */
public fun interface BodyFileResolver {
    public suspend fun resolve(path: String): ByteArray?
}
