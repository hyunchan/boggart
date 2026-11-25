package io.github.hyunchan.boggart.ktor

import io.github.hyunchan.boggart.Boggart
import io.github.hyunchan.boggart.BoggartEngine
import io.github.hyunchan.boggart.BoggartHeaders
import io.github.hyunchan.boggart.BoggartRequest
import io.github.hyunchan.boggart.BoggartResponse
import io.github.hyunchan.boggart.BoggartUrl
import io.github.hyunchan.boggart.Decision
import io.github.hyunchan.boggart.Mode
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.copyAndClose
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.io.readByteArray
import kotlin.coroutines.CoroutineContext

/**
 * Ktor 어댑터. 실제 [HttpClientEngine]을 감싸 [BoggartEngine]의 결정에 따라 응답을 교체·패치하거나 네트워크 에러를 유발한다.
 *
 * ```
 * val client = HttpClient(OkHttp.withBoggart()) { install(ContentNegotiation) { json() } }
 * ```
 *
 * 플러그인(`createClientPlugin` + `Send` 훅)이 아니라 엔진 래퍼인 이유: Respond·Fail을 네트워크 없이 처리하려면
 * 플러그인에서는 `HttpClientCall`을 직접 만들어야 하는데, 그 생성자와 `HttpResponse.rawContent`가 `@InternalAPI`다.
 * 엔진 래퍼는 공개 타입인 `HttpResponseData`만 돌려주면 되고, 응답이 클라이언트의 응답 파이프라인
 * (ContentNegotiation, HttpCallValidator 등)을 실제 응답과 똑같이 통과한다. 또 모든 플러그인이 끝난 뒤의 요청을 보므로
 * DefaultRequest·ContentNegotiation이 붙인 헤더도 매칭 대상이다. `execute`의 `@InternalAPI` opt-in은 Ktor의
 * 커스텀 엔진(MockEngine 등)이 모두 따르는 표준 확장 지점이다.
 *
 * 실제 호출이 예외를 던지면 [BoggartEngine.onFailure]로 기록한 뒤 다시 던진다. 코루틴 취소는 기록하지 않는다.
 *
 * @param maxBodyBytes 캡처할 요청·응답 바디의 최대 크기. 넘으면 바디를 캡처하지 않고, 응답은 패치 없이 원본을 전달한다.
 *   `text/event-stream` 응답과 `Content-Length`가 이 값을 넘는 응답은 바디를 읽지 않고 원본을 전달한다.
 */
public class BoggartHttpClientEngine(
    private val delegate: HttpClientEngine,
    private val engine: BoggartEngine = Boggart.engine,
    private val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
) : HttpClientEngine {

    init {
        require(maxBodyBytes >= 0) { "maxBodyBytes must be >= 0" }
    }

    override val dispatcher: CoroutineDispatcher get() = delegate.dispatcher
    override val config: HttpClientEngineConfig get() = delegate.config
    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> get() = delegate.supportedCapabilities
    override val coroutineContext: CoroutineContext get() = delegate.coroutineContext

    @InternalAPI
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        if (engine.mode == Mode.Off) return delegate.execute(data)
        val boggartRequest = data.toBoggartRequest()
        return when (val decision = engine.beforeRequest(boggartRequest)) {
            Decision.Bypass -> delegate.execute(data)
            is Decision.Proceed -> proceed(data, boggartRequest, decision)
            is Decision.Respond -> {
                delay(decision.delayMs)
                engine.onDelivered(boggartRequest, decision)
                decision.response.toResponseData(callContext())
            }
            is Decision.Fail -> {
                delay(decision.delayMs)
                engine.onDelivered(boggartRequest, decision)
                throw decision.kind.toException(data)
            }
        }
    }

    @InternalAPI
    private suspend fun proceed(data: HttpRequestData, boggartRequest: BoggartRequest, decision: Decision.Proceed): HttpResponseData {
        delay(decision.delayMs)
        val response = try {
            delegate.execute(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            engine.onFailure(boggartRequest, decision, e)
            throw e
        }
        val channel = response.body as? ByteReadChannel
        val headers = response.headers.toBoggartHeaders()
        val mediaType = response.headers[HttpHeaders.ContentType]
        val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
        if (channel == null || isStreaming(mediaType) || declaredLength > maxBodyBytes) {
            recordUncaptured(boggartRequest, response, headers, mediaType, decision.startedAtMs)
            return response
        }
        val bytes = try {
            channel.readBuffer(maxBodyBytes + 1).readByteArray()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            engine.onFailure(boggartRequest, decision, e)
            throw e
        }
        if (bytes.size > maxBodyBytes) {
            // 이미 읽은 앞부분과 남은 채널을 이어 붙여 원본 그대로 넘긴다.
            recordUncaptured(boggartRequest, response, headers, mediaType, decision.startedAtMs)
            val rejoined = CoroutineScope(response.callContext).writer {
                this.channel.writeFully(bytes)
                channel.copyAndClose(this.channel)
            }.channel
            return response.copy(body = rejoined)
        }
        val original = BoggartResponse(response.statusCode.value, headers, bytes, mediaType)
        val delivered = engine.afterResponse(boggartRequest, original, decision)
        return response.copy(
            headers = delivered.headers.toKtorHeaders(),
            body = ByteReadChannel(delivered.body ?: ByteArray(0)),
        )
    }

    private suspend fun recordUncaptured(
        request: BoggartRequest,
        response: HttpResponseData,
        headers: BoggartHeaders,
        mediaType: String?,
        startedAtMs: Long?,
    ) {
        val captured = BoggartResponse(response.statusCode.value, headers, null, mediaType)
        engine.afterResponse(request, captured, Decision.Proceed(startedAtMs = startedAtMs))
    }

    override fun close() {
        delegate.close()
    }

    private fun isStreaming(mediaType: String?): Boolean =
        mediaType?.substringBefore(';')?.trim().equals(EVENT_STREAM, ignoreCase = true)

    private fun HttpRequestData.toBoggartRequest(): BoggartRequest {
        var headers = headers.toBoggartHeaders()
        val contentType = body.contentType
        if (contentType != null && HttpHeaders.ContentType !in headers) {
            headers = headers.with(HttpHeaders.ContentType, contentType.toString())
        }
        return BoggartRequest(
            method = method.value,
            url = BoggartUrl(
                scheme = url.protocol.name,
                host = url.host,
                port = url.port,
                path = url.encodedPath.ifEmpty { "/" },
                query = url.encodedQuery.ifEmpty { null },
            ),
            headers = headers,
            body = captureBody(body),
        )
    }

    /** 메모리에 이미 있는 바디(ByteArrayContent, 그 하위인 TextContent)만 캡처한다. */
    private fun captureBody(content: OutgoingContent): ByteArray? {
        if (content !is OutgoingContent.ByteArrayContent) return null
        val bytes = content.bytes()
        return if (bytes.size > maxBodyBytes) null else bytes
    }

    private fun BoggartResponse.toResponseData(callContext: CoroutineContext): HttpResponseData {
        val contentType = mediaType
        var responseHeaders = headers
        if (contentType != null && HttpHeaders.ContentType !in responseHeaders) {
            responseHeaders = responseHeaders.with(HttpHeaders.ContentType, contentType)
        }
        return HttpResponseData(
            statusCode = HttpStatusCode(status, MESSAGE),
            requestTime = GMTDate(),
            headers = responseHeaders.toKtorHeaders(),
            version = HttpProtocolVersion.HTTP_1_1,
            body = ByteReadChannel(body ?: ByteArray(0)),
            callContext = callContext,
        )
    }

    private fun HttpResponseData.copy(
        headers: Headers = this.headers,
        body: Any = this.body,
    ): HttpResponseData = HttpResponseData(statusCode, requestTime, headers, version, body, callContext)

    private fun Headers.toBoggartHeaders(): BoggartHeaders =
        BoggartHeaders.of(entries().flatMap { (name, values) -> values.map { name to it } })

    private fun BoggartHeaders.toKtorHeaders(): Headers =
        Headers.build { toList().forEach { (name, value) -> append(name, value) } }

    public companion object {
        public const val DEFAULT_MAX_BODY_BYTES: Long = 1L shl 20
        private const val MESSAGE = "Boggart"
        private const val EVENT_STREAM = "text/event-stream"
    }
}

/** 이 엔진을 [BoggartHttpClientEngine]으로 감싼다. 클라이언트는 전달된 엔진 인스턴스를 닫지 않는다. */
public fun HttpClientEngine.withBoggart(
    engine: BoggartEngine = Boggart.engine,
    maxBodyBytes: Long = BoggartHttpClientEngine.DEFAULT_MAX_BODY_BYTES,
): HttpClientEngine = BoggartHttpClientEngine(this, engine, maxBodyBytes)

/** 이 팩토리가 만드는 엔진을 [BoggartHttpClientEngine]으로 감싼다. `HttpClient(OkHttp.withBoggart()) { engine { ... } }`. */
public fun <T : HttpClientEngineConfig> HttpClientEngineFactory<T>.withBoggart(
    engine: BoggartEngine = Boggart.engine,
    maxBodyBytes: Long = BoggartHttpClientEngine.DEFAULT_MAX_BODY_BYTES,
): HttpClientEngineFactory<T> {
    val parent = this
    return object : HttpClientEngineFactory<T> {
        override fun create(block: T.() -> Unit): HttpClientEngine =
            BoggartHttpClientEngine(parent.create(block), engine, maxBodyBytes)
    }
}
