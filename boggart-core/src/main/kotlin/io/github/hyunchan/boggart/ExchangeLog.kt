package io.github.hyunchan.boggart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 캡처된 요청·응답 한 쌍.
 *
 * @property originalResponse 실제 서버 응답. 실제 호출이 없었으면(Replaced, Failed) null.
 * @property deliveredResponse 앱에 전달된 응답. 에러를 유발했거나(Failed) 실제 호출이 실패했으면 null.
 * @property error 실제 호출이 던진 예외의 `toString()`. 성공했으면 null.
 * @property step [Override.Sequence] 규칙이 적용했을 때 쓴 단계(0부터). 순차 응답이 아니면 null.
 */
public data class Exchange(
    val id: Long,
    val timestampMs: Long,
    val request: BoggartRequest,
    val originalResponse: BoggartResponse?,
    val deliveredResponse: BoggartResponse?,
    val provenance: Provenance,
    val appliedRuleId: String?,
    val error: String? = null,
    val step: Int? = null,
)

/** 용량 제한 링 버퍼. [exchanges]는 오래된 것부터 시간순이다. */
public class ExchangeLog(capacity: Int = DEFAULT_CAPACITY) {
    private val lock = Any()
    private val buffer = ArrayDeque<Exchange>()
    private val state = MutableStateFlow<List<Exchange>>(emptyList())

    public val exchanges: StateFlow<List<Exchange>> = state.asStateFlow()

    /** 줄이면 오래된 항목부터 버린다. */
    public var capacity: Int = capacity
        set(value) {
            require(value > 0) { "capacity must be > 0" }
            synchronized(lock) {
                field = value
                trimAndPublish()
            }
        }

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    public fun add(exchange: Exchange) {
        synchronized(lock) {
            buffer.addLast(exchange)
            trimAndPublish()
        }
    }

    public fun clear() {
        synchronized(lock) {
            buffer.clear()
            state.value = emptyList()
        }
    }

    private fun trimAndPublish() {
        while (buffer.size > capacity) buffer.removeFirst()
        state.value = buffer.toList()
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 200
    }
}
