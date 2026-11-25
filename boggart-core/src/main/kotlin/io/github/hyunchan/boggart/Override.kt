package io.github.hyunchan.boggart

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

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
     * 단계는 [SequenceStep.times]만큼 이어서 쓴다.
     *
     * 진행 위치(커서)는 규칙이 아니라 [BoggartEngine]이 메모리에 보관한다. 규칙을 고치거나 켜고 끄면 처음부터 다시 시작한다.
     * 지연은 각 단계의 `delayMs`를 쓴다.
     */
    @Serializable
    @SerialName("sequence")
    public data class Sequence(
        val steps: List<SequenceStep>,
        val then: SequenceEnd = SequenceEnd.Last,
    ) : Override {
        /** 단계마다 자기 지연을 쓰므로 0. */
        override val delayMs: Long get() = 0

        /** [then]을 따르기 전까지 쓰는 호출 수. 단계별 [SequenceStep.times]의 합. */
        val calls: Int get() = steps.sumOf { it.times }

        init {
            require(steps.isNotEmpty()) { "A sequence needs at least one step" }
        }

        /** [call]번째(0부터) 호출이 쓰는 단계 번호. [calls] 이상이면 null. */
        public fun stepAt(call: Int): Int? {
            var remaining = call
            steps.forEachIndexed { index, step ->
                if (remaining < step.times) return index
                remaining -= step.times
            }
            return null
        }
    }

    /**
     * 호출마다 [percent]% 확률로 [override]를 적용하고, 나머지는 조작 없이 실제 서버로 보낸다.
     * 적용되지 않은 호출은 원본으로 기록하되 이 규칙의 id를 남긴다. 순차 응답의 단계로도 쓸 수 있다.
     */
    @Serializable
    @SerialName("chance")
    public data class Chance(
        val percent: Int,
        val override: Override,
    ) : Override {
        /** 걸렸을 때 [override]의 지연을 쓰므로 0. */
        override val delayMs: Long get() = 0

        init {
            require(percent in 1..99) { "percent must be 1..99" }
            require(override !is Sequence && override !is Chance) { "A chance can't wrap a sequence or another chance" }
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

/**
 * [Override.Sequence]의 단계 하나. [override]를 [times]번 이어서 쓴다.
 *
 * JSON에서는 조작만 쓰면 times 1이고(`{"type": "replace", …}`), 여러 번이면 `{"times": 3, "override": {…}}`로 감싼다.
 */
@Serializable(with = SequenceStepSerializer::class)
public data class SequenceStep(
    val override: Override,
    val times: Int = 1,
) {
    init {
        require(times in 1..MAX_TIMES) { "times must be 1..$MAX_TIMES" }
        require(override !is Override.Sequence) { "A sequence step can't be another sequence" }
    }

    public companion object {
        public const val MAX_TIMES: Int = 1000
    }
}

/** 조작만 있으면 times 1인 단계로, `times`·`override`가 있으면 묶음으로 읽는다. times 1이면 조작만 쓴다. */
internal object SequenceStepSerializer : KSerializer<SequenceStep> {
    @Serializable
    private class Repeated(val times: Int, val override: Override)

    override val descriptor: SerialDescriptor = Repeated.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SequenceStep) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("SequenceStep needs JSON")
        if (value.times == 1) {
            json.encodeSerializableValue(Override.serializer(), value.override)
        } else {
            json.encodeSerializableValue(Repeated.serializer(), Repeated(value.times, value.override))
        }
    }

    override fun deserialize(decoder: Decoder): SequenceStep {
        val json = decoder as? JsonDecoder ?: throw SerializationException("SequenceStep needs JSON")
        val element = json.decodeJsonElement()
        val isRepeated = element is JsonObject && "type" !in element && "override" in element
        return if (isRepeated) {
            val repeated = json.json.decodeFromJsonElement(Repeated.serializer(), element)
            SequenceStep(repeated.override, repeated.times)
        } else {
            SequenceStep(json.json.decodeFromJsonElement(Override.serializer(), element))
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
