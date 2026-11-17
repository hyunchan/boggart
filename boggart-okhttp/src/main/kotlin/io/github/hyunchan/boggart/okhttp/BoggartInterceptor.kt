package io.github.hyunchan.boggart.okhttp

import io.github.hyunchan.boggart.Boggart
import io.github.hyunchan.boggart.BoggartEngine
import io.github.hyunchan.boggart.BoggartHeaders
import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.BoggartResponse
import io.github.hyunchan.boggart.BoggartUrl
import io.github.hyunchan.boggart.Decision
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.Mode
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * OkHttp 어댑터. [BoggartEngine]의 결정에 따라 응답을 교체·패치하거나 네트워크 에러를 유발한다.
 *
 * **애플리케이션 인터셉터**(`OkHttpClient.Builder.addInterceptor`)로 등록해야 한다. 네트워크 인터셉터로 두면
 * 연결을 맺은 뒤에 실행되어 Replace·Fail도 실제 연결을 만들고, 압축된 바디를 그대로 보게 된다.
 *
 * OkHttp 인터셉터는 동기 API이고 이미 호출 스레드(동기 `execute`) 또는 OkHttp 디스패처 스레드(`enqueue`)에서
 * 실행되므로, suspend인 엔진 호출은 `runBlocking`으로 감싼다. 엔진 호출은 메모리 작업과 bodyFile 해석뿐이라
 * 스레드를 오래 붙잡지 않는다. 지연도 같은 이유로 `Thread.sleep`으로 수행한다.
 *
 * 애플리케이션 인터셉터이므로 OkHttp가 나중에 붙이는 `Content-Type`·`Accept-Encoding`·`Host`·`User-Agent` 등은
 * 매칭 시점에 없다. 앱이 직접 넣은 헤더만 매칭 대상이다.
 *
 * @param maxBodyBytes 캡처할 요청·응답 바디의 최대 크기. 넘으면 바디를 캡처하지 않고, 응답은 패치 없이 원본을 전달한다.
 *   `text/event-stream` 응답과 `Content-Length`가 이 값을 넘는 응답은 바디를 읽지 않고 원본을 전달한다.
 */
public class BoggartInterceptor(
    private val engine: BoggartEngine = Boggart.engine,
    private val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
) : Interceptor {

    init {
        require(maxBodyBytes >= 0) { "maxBodyBytes must be >= 0" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (engine.mode == Mode.Off) return chain.proceed(request)
        val boggartRequest = request.toBoggartRequest()
        return when (val decision = runBlocking { engine.beforeRequest(boggartRequest) }) {
            Decision.Bypass -> chain.proceed(request)
            is Decision.Proceed -> proceed(chain, boggartRequest, decision)
            is Decision.Respond -> {
                delay(chain, decision.delayMs)
                decision.response.toOkHttpResponse(request)
            }
            is Decision.Fail -> {
                delay(chain, decision.delayMs)
                throw decision.kind.toException(request)
            }
        }
    }

    private fun proceed(chain: Interceptor.Chain, boggartRequest: BoggartRequest, decision: Decision.Proceed): Response {
        delay(chain, decision.delayMs)
        val response = try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            engine.onFailure(boggartRequest, decision, e)
            throw e
        }
        val headers = response.headers.toBoggartHeaders()
        val mediaType = response.header("Content-Type") ?: response.body.contentType()?.toString()
        val uncaptured = isStreaming(mediaType) ||
            (response.header(CONTENT_LENGTH)?.toLongOrNull() ?: -1L) > maxBodyBytes ||
            try {
                response.body.source().request(maxBodyBytes + 1)
            } catch (e: IOException) {
                response.close()
                engine.onFailure(boggartRequest, decision, e)
                throw e
            }
        if (uncaptured) {
            // 패치 없이 원본을 넘기되 교환은 바디 없이 기록한다.
            val captured = BoggartResponse(response.code, headers, null, mediaType)
            runBlocking { engine.afterResponse(boggartRequest, captured, Decision.Proceed()) }
            return response
        }
        val body = response.body.use { it.bytes() }
        val original = BoggartResponse(response.code, headers, body, mediaType)
        val delivered = runBlocking { engine.afterResponse(boggartRequest, original, decision) }
        return response.newBuilder()
            .headers(delivered.headers.toOkHttpHeaders())
            .body((delivered.body ?: ByteArray(0)).toResponseBody(delivered.mediaType?.toMediaTypeOrNull()))
            .build()
    }

    private fun isStreaming(mediaType: String?): Boolean =
        mediaType?.substringBefore(';')?.trim().equals(EVENT_STREAM, ignoreCase = true)

    private fun Request.toBoggartRequest(): BoggartRequest =
        BoggartRequest(
            method = method,
            url = BoggartUrl(url.scheme, url.host, url.port, url.encodedPath, url.encodedQuery),
            headers = headers.toBoggartHeaders(),
            body = captureRequestBody(),
        )

    private fun Request.captureRequestBody(): ByteArray? {
        val body = body ?: return null
        if (body.isOneShot() || body.isDuplex()) return null
        if (body.contentLength() > maxBodyBytes) return null
        val buffer = Buffer()
        body.writeTo(buffer)
        return if (buffer.size > maxBodyBytes) null else buffer.readByteArray()
    }

    private fun BoggartResponse.toOkHttpResponse(request: Request): Response {
        val contentType = mediaType
        var okHeaders = headers
        if (contentType != null && CONTENT_TYPE !in okHeaders) okHeaders = okHeaders.with(CONTENT_TYPE, contentType)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(MESSAGE)
            .headers(okHeaders.toOkHttpHeaders())
            .body((body ?: ByteArray(0)).toResponseBody(contentType?.toMediaTypeOrNull()))
            .build()
    }

    private fun FailKind.toException(request: Request): IOException = when (this) {
        FailKind.Timeout -> SocketTimeoutException("Boggart: timeout")
        FailKind.ConnectionReset -> SocketException("Connection reset")
        FailKind.UnknownHost -> UnknownHostException(request.url.host)
        is FailKind.Custom -> IOException(message)
    }

    private fun delay(chain: Interceptor.Chain, delayMs: Long) {
        if (delayMs <= 0) return
        try {
            Thread.sleep(delayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Boggart delay interrupted").apply { initCause(e) }
        }
        if (chain.call().isCanceled()) throw IOException("Canceled")
    }

    private fun Headers.toBoggartHeaders(): BoggartHeaders = BoggartHeaders.of(toList())

    private fun BoggartHeaders.toOkHttpHeaders(): Headers =
        Headers.Builder().apply { toList().forEach { (name, value) -> addUnsafeNonAscii(name, value) } }.build()

    public companion object {
        public const val DEFAULT_MAX_BODY_BYTES: Long = 1L shl 20
        private const val MESSAGE = "Boggart"
        private const val CONTENT_TYPE = "Content-Type"
        private const val CONTENT_LENGTH = "Content-Length"
        private const val EVENT_STREAM = "text/event-stream"
    }
}
