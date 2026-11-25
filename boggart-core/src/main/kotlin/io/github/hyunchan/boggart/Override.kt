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

    /**
     * 매칭될 때마다 [steps]를 차례로 하나씩 적용한다. 다 쓰면 [then]을 따른다.
     *
     * 진행 위치(커서)는 규칙이 아니라 [BoggartEngine]이 메모리에 보관한다. 규칙을 고치거나 켜고 끄면 처음부터 다시 시작한다.
     * 지연은 각 단계의 `delayMs`를 쓴다.
     */
    @Serializable
    @SerialName("sequence")
    public data class Sequence(
        val steps: List<Override>,
        val then: SequenceEnd = SequenceEnd.Last,
    ) : Override {
        /** 단계마다 자기 지연을 쓰므로 0. */
        override val delayMs: Long get() = 0

        init {
            require(steps.isNotEmpty()) { "A sequence needs at least one step" }
            require(steps.none { it is Sequence }) { "A sequence step can't be another sequence" }
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

/** [Override.Sequence]의 단계를 다 쓴 뒤의 동작. */
@Serializable
public enum class SequenceEnd {
    /** 마지막 단계를 계속 쓴다. */
    @SerialName("last")
    Last,

    /** 첫 단계부터 다시 쓴다. */
    @SerialName("loop")
    Loop,

    /** 조작 없이 실제 서버로 보낸다. Offline 모드에서는 규칙 없는 호출처럼 실패한다. */
    @SerialName("original")
    Original,
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
