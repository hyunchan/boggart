package io.github.hyunchan.boggart.ktor

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory

/**
 * 릴리스 빌드용 no-op. 엔진을 감싸지 않고 그대로 돌려준다.
 *
 * boggart-ktor의 `engine` 매개변수는 core 타입이라 받지 않는다. `maxBodyBytes`는 이름 붙은 인자로만 쓴다는 전제다.
 */
@Suppress("UNUSED_PARAMETER")
public fun HttpClientEngine.withBoggart(maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES): HttpClientEngine = this

/** 릴리스 빌드용 no-op. 팩토리를 그대로 돌려준다. */
@Suppress("UNUSED_PARAMETER")
public fun <T : HttpClientEngineConfig> HttpClientEngineFactory<T>.withBoggart(
    maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
): HttpClientEngineFactory<T> = this

private const val DEFAULT_MAX_BODY_BYTES: Long = 1L shl 20
