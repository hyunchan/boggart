package io.github.hyunchan.boggart

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 규칙이 요청에 가하는 조작. JSON에서는 `type` 필드로 구분한다. */
@Serializable
public sealed interface Override {
    /** 어댑터가 응답 전달 전에 수행할 지연(ms). */
    public val delayMs: Long

    /**
     * 실제 호출 없이 응답 전체를 교체한다.
     *
     * [body](인라인 문자열)와 [bodyFile](에셋 참조) 중 하나만 지정할 수 있다. 둘 다 없으면 빈 바디.
     * [bodyFile]은 core가 읽지 않고 [BodyFileResolver]에 위임한다.
     */
    @Serializable
    @SerialName("replace")
    public data class Replace(
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val bodyFile: String? = null,
        val mediaType: String? = "application/json",
        override val delayMs: Long = 0,
    ) : Override {
        init {
            require(body == null || bodyFile == null) { "Specify either body or bodyFile, not both" }
            require(delayMs >= 0) { "delayMs must be >= 0" }
        }
    }

    /**
     * 실제 응답 바디에 JSON Merge Patch(RFC 7386) 문서 [patch]를 적용한다.
     *
     * @property onInvalid 검증 실패 시 정책. null이면 엔진 전역 [BoggartEngine.onInvalidPatch]를 따른다.
     */
    @Serializable
    @SerialName("patch")
    public data class Patch(
        val patch: JsonElement,
        override val delayMs: Long = 0,
        val onInvalid: OnInvalidPatch? = null,
    ) : Override {
        init {
            require(delayMs >= 0) { "delayMs must be >= 0" }
        }
    }

    /**
     * 실제 응답 바디에 JSON Patch(RFC 6902) 연산 [ops]를 경로별로 적용한다. 지정한 경로의 값만 바뀌고 나머지는 실제 응답을 따른다.
     *
     * 하나라도 적용할 수 없으면(경로 없음, 인덱스 범위 밖) 패치 전체를 버리고 원본을 전달한다.
     *
     * @property onInvalid 검증 실패 시 정책. null이면 엔진 전역 [BoggartEngine.onInvalidPatch]를 따른다.
     */
    @Serializable
    @SerialName("jsonPatch")
    public data class JsonPatch(
        val ops: List<JsonPatchOp>,
        override val delayMs: Long = 0,
        val onInvalid: OnInvalidPatch? = null,
    ) : Override {
        init {
            require(delayMs >= 0) { "delayMs must be >= 0" }
        }
    }

    /** 실제 호출 없이 네트워크 에러를 유발한다. */
    @Serializable
    @SerialName("fail")
    public data class Fail(
        val kind: FailKind,
        override val delayMs: Long = 0,
    ) : Override {
        init {
            require(delayMs >= 0) { "delayMs must be >= 0" }
        }
    }
}

/** 유발할 네트워크 에러 종류. 어댑터가 자기 라이브러리의 예외로 변환한다. */
@Serializable
public sealed interface FailKind {
    @Serializable
    @SerialName("timeout")
    public data object Timeout : FailKind

    @Serializable
    @SerialName("connectionReset")
    public data object ConnectionReset : FailKind

    @Serializable
    @SerialName("unknownHost")
    public data object UnknownHost : FailKind

    @Serializable
    @SerialName("custom")
    public data class Custom(val message: String) : FailKind
}
