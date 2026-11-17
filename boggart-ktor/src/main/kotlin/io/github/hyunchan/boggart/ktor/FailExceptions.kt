package io.github.hyunchan.boggart.ktor

import io.github.hyunchan.boggart.FailKind
import io.ktor.client.plugins.SocketTimeoutException
import io.ktor.client.request.HttpRequestData
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException

/**
 * [FailKind]를 Ktor 엔진이 실제로 던지는 예외로 변환한다.
 *
 * 이 모듈에서 JVM 전용 API(`java.net`·`java.io` 예외)를 쓰는 유일한 지점이다.
 *
 * - Timeout: Ktor OkHttp·CIO 엔진은 소켓 타임아웃을 `io.ktor.client.plugins.SocketTimeoutException(request, cause)`로
 *   바꿔 던진다(JVM에서는 `java.net.SocketTimeoutException`). `HttpRequestTimeoutException`은 HttpTimeout 플러그인이
 *   요청 전체 타임아웃에만 던지므로 쓰지 않는다.
 * - UnknownHost: 안드로이드 기본인 OkHttp 엔진은 `java.net.UnknownHostException`을 그대로 전달한다.
 *   CIO의 `UnresolvedAddressException`은 `IOException`이 아니어서 앱의 일반적인 네트워크 에러 처리에 걸리지 않는다.
 */
internal fun FailKind.toException(request: HttpRequestData): Throwable = when (this) {
    FailKind.Timeout -> SocketTimeoutException(request)
    FailKind.ConnectionReset -> SocketException("Connection reset")
    FailKind.UnknownHost -> UnknownHostException(request.url.host)
    is FailKind.Custom -> IOException(message)
}
