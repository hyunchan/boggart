package io.github.hyunchan.boggart.okhttp

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 릴리스 빌드용 no-op. 요청을 그대로 진행한다.
 *
 * boggart-okhttp의 `engine` 매개변수는 core 타입이라 받지 않는다. 호스트는 `BoggartInterceptor()` 또는
 * `BoggartInterceptor(maxBodyBytes = ...)`처럼 기본 엔진과 이름 붙은 인자만 쓴다는 전제다.
 */
@Suppress("UNUSED_PARAMETER")
public class BoggartInterceptor(maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())

    public companion object {
        public const val DEFAULT_MAX_BODY_BYTES: Long = 1L shl 20
    }
}
