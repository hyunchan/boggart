package io.github.hyunchan.boggart.okhttp

import io.github.hyunchan.boggart.BodyValidator
import io.github.hyunchan.boggart.BoggartEngine
import io.github.hyunchan.boggart.ExchangeLog
import io.github.hyunchan.boggart.FailKind
import io.github.hyunchan.boggart.Mode
import io.github.hyunchan.boggart.OnInvalidPatch
import io.github.hyunchan.boggart.Override
import io.github.hyunchan.boggart.Provenance
import io.github.hyunchan.boggart.RequestMatcher
import io.github.hyunchan.boggart.Rule
import io.github.hyunchan.boggart.RuleRepository
import io.github.hyunchan.boggart.TemporaryRuleSource
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoggartInterceptorTest {
    @StartStop
    val server = MockWebServer()

    private val temporary = TemporaryRuleSource()
    private val log = ExchangeLog()
    private val engine = BoggartEngine(RuleRepository(listOf(temporary)), log)

    private fun client(maxBodyBytes: Long = BoggartInterceptor.DEFAULT_MAX_BODY_BYTES) =
        OkHttpClient.Builder().addInterceptor(BoggartInterceptor(engine, maxBodyBytes)).build()

    private fun get(path: String = "/users/1", client: OkHttpClient = client()) =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    private fun addRule(override: Override) =
        temporary.add(Rule("r1", matcher = RequestMatcher(path = "/users/*"), override = override))

    private fun json(body: String) = MockResponse(200, headersOf("Content-Type", "application/json"), body)

    @Test
    fun `no rule passes through and records original`() {
        server.enqueue(json("""{"id":1}"""))
        get().use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"id":1}""", response.body.string())
        }
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Original, exchange.provenance)
        assertEquals("""{"id":1}""", exchange.deliveredResponse?.bodyAsString())
        assertEquals("application/json", exchange.originalResponse?.mediaType)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `replace responds without network`() {
        addRule(Override.Replace(status = 404, headers = mapOf("X-Boggart" to "1"), body = """{"e":1}"""))
        get().use { response ->
            assertEquals(404, response.code)
            assertEquals("Boggart", response.message)
            assertEquals("1", response.header("X-Boggart"))
            assertEquals("application/json", response.header("Content-Type"))
            assertEquals("application/json", response.body.contentType()?.toString())
            assertEquals("""{"e":1}""", response.body.string())
        }
        assertEquals(0, server.requestCount)
        assertEquals(Provenance.Replaced, log.exchanges.value.single().provenance)
    }

    @Test
    fun `patch rewrites body and content length`() {
        addRule(Override.Patch(Json.parseToJsonElement("""{"name":"Boggart","id":null}""")))
        server.enqueue(json("""{"id":1,"name":"Harry"}"""))
        get().use { response ->
            val body = response.body.string()
            assertEquals("""{"name":"Boggart"}""", body)
            assertEquals(body.length.toString(), response.header("Content-Length"))
            assertEquals("application/json", response.body.contentType()?.toString())
        }
        assertEquals(Provenance.Patched, log.exchanges.value.single().provenance)
    }

    @Test
    fun `invalid patch passes through when configured`() {
        addRule(Override.Patch(Json.parseToJsonElement("""{"id":"x"}""")))
        engine.validator = BodyValidator { _, _ -> false }
        engine.onInvalidPatch = OnInvalidPatch.PassThrough
        server.enqueue(json("""{"id":1}"""))
        get().use { assertEquals("""{"id":"x"}""", it.body.string()) }
        assertEquals(Provenance.PatchedInvalid, log.exchanges.value.single().provenance)
    }

    @Test
    fun `fail kinds map to io exceptions`() {
        val expected = listOf(
            Triple(FailKind.Timeout, SocketTimeoutException::class, null),
            Triple(FailKind.ConnectionReset, SocketException::class, "Connection reset"),
            Triple(FailKind.UnknownHost, UnknownHostException::class, server.url("/").host),
            Triple(FailKind.Custom("boom"), IOException::class, "boom"),
        )
        for ((kind, type, message) in expected) {
            addRule(Override.Fail(kind))
            val error = assertFailsWith<IOException> { get() }
            assertTrue(type.isInstance(error), "$kind -> $error")
            if (message != null) assertEquals(message, error.message)
        }
        assertEquals(0, server.requestCount)
        assertTrue(log.exchanges.value.all { it.provenance == Provenance.Failed })
    }

    @Test
    fun `delay is applied before responding`() {
        addRule(Override.Replace(body = "{}", delayMs = 300))
        val start = System.nanoTime()
        get().close()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs >= 300, "elapsed $elapsedMs ms")
        // 기록은 지연이 끝나 앱이 응답을 받을 때 생긴다.
        val exchange = log.exchanges.value.single()
        assertTrue(exchange.timestampMs - exchange.startedAtMs >= 300, "recorded ${exchange.timestampMs - exchange.startedAtMs} ms after start")
    }

    @Test
    fun `canceled call during delay fails`() {
        addRule(Override.Replace(body = "{}", delayMs = 300))
        val call = client().newCall(Request.Builder().url(server.url("/users/1")).build())
        Thread {
            Thread.sleep(50)
            call.cancel()
        }.start()
        assertFailsWith<IOException> { call.execute() }
        // 앱이 받지 못한 응답은 기록하지 않는다.
        assertTrue(log.exchanges.value.isEmpty())
    }

    @Test
    fun `mode off passes through without recording`() {
        addRule(Override.Fail(FailKind.Timeout))
        engine.mode = Mode.Off
        server.enqueue(json("{}"))
        get().use { assertEquals("{}", it.body.string()) }
        assertTrue(log.exchanges.value.isEmpty())
    }

    @Test
    fun `mode off skips request conversion`() {
        engine.mode = Mode.Off
        server.enqueue(json("{}"))
        val throwing = object : RequestBody() {
            override fun contentType(): MediaType = "application/json".toMediaType()
            override fun contentLength(): Long = 2
            override fun writeTo(sink: BufferedSink) {
                if (sink is okio.Buffer) error("request body must not be captured in Mode.Off")
                sink.writeUtf8("{}")
            }
        }
        client().newCall(Request.Builder().url(server.url("/users/1")).post(throwing).build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals("{}", server.takeRequest().body?.utf8())
    }

    @Test
    fun `event stream passes through without reading`() {
        addRule(Override.Patch(Json.parseToJsonElement("""{"a":1}""")))
        server.enqueue(MockResponse(200, headersOf("Content-Type", "text/event-stream; charset=utf-8"), "data: {}\n\n"))
        get().use { assertEquals("data: {}\n\n", it.body.string()) }
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Original, exchange.provenance)
        assertNull(exchange.deliveredResponse?.body)
    }

    @Test
    fun `content length over limit passes through without reading`() {
        addRule(Override.Patch(Json.parseToJsonElement("""{"a":1}""")))
        val big = """{"data":"${"x".repeat(100)}"}"""
        server.enqueue(json(big))
        get(client = client(maxBodyBytes = 50)).use { assertEquals(big, it.body.string()) }
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Original, exchange.provenance)
        assertNull(exchange.deliveredResponse?.body)
    }

    @Test
    fun `offline mode without rule fails as unknown host`() {
        engine.mode = Mode.Offline
        assertFailsWith<java.net.UnknownHostException> { get() }
        assertEquals(0, server.requestCount)
        assertEquals(Provenance.Failed, log.exchanges.value.single().provenance)
    }

    @Test
    fun `body over limit passes original and records without body`() {
        addRule(Override.Patch(Json.parseToJsonElement("""{"a":1}""")))
        val big = """{"data":"${"x".repeat(100)}"}"""
        server.enqueue(json(big))
        get(client = client(maxBodyBytes = 10)).use { assertEquals(big, it.body.string()) }
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Original, exchange.provenance)
        assertNull(exchange.deliveredResponse?.body)
        assertNull(exchange.appliedRuleId)
        assertNotNull(exchange.deliveredResponse)
    }

    @Test
    fun `connection failure is recorded via onFailure`() {
        val port = ServerSocket(0).use { it.localPort }
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor(BoggartInterceptor(engine))
            .build()
        assertFailsWith<IOException> {
            client.newCall(Request.Builder().url("http://127.0.0.1:$port/users/1").build()).execute()
        }
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Original, exchange.provenance)
        assertNotNull(exchange.error)
        assertNull(exchange.deliveredResponse)
    }

    @Test
    fun `multi value headers are preserved`() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Set-Cookie", "a=1")
                .addHeader("Set-Cookie", "b=2")
                .body("{}")
                .build(),
        )
        val request = Request.Builder().url(server.url("/users/1"))
            .addHeader("Accept", "application/json")
            .addHeader("Accept", "text/plain")
            .build()
        client().newCall(request).execute().use { response ->
            assertEquals(listOf("a=1", "b=2"), response.headers("Set-Cookie"))
        }
        val exchange = log.exchanges.value.single()
        assertEquals(listOf("application/json", "text/plain"), exchange.request.headers.values("accept"))
        assertEquals(listOf("a=1", "b=2"), exchange.originalResponse?.headers?.values("set-cookie"))
        assertEquals(listOf("application/json", "text/plain"), server.takeRequest().headers.values("Accept"))
    }

    @Test
    fun `request body is captured unless one shot`() {
        server.enqueue(json("{}"))
        server.enqueue(json("{}"))
        val payload = """{"q":1}"""
        val c = client()
        c.newCall(Request.Builder().url(server.url("/users/1")).post(payload.toRequestBody("application/json".toMediaType())).build())
            .execute().close()
        val oneShot = object : RequestBody() {
            override fun contentType(): MediaType = "application/json".toMediaType()
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8(payload)
            }
        }
        c.newCall(Request.Builder().url(server.url("/users/1")).post(oneShot).build()).execute().close()
        val (first, second) = log.exchanges.value
        assertContentEquals(payload.toByteArray(), first.request.body)
        assertEquals("POST", first.request.method)
        assertNull(second.request.body)
        assertEquals(payload, server.takeRequest().body?.utf8())
        assertEquals(payload, server.takeRequest().body?.utf8())
    }

    @Test
    fun `unresolved body file proceeds to network and records invalid`() {
        addRule(Override.Replace(bodyFile = "missing.json"))
        server.enqueue(json("""{"real":true}"""))
        get().use { assertEquals("""{"real":true}""", it.body.string()) }
        assertEquals(1, server.requestCount)
        assertEquals(Provenance.InvalidIgnored, log.exchanges.value.single().provenance)
    }
}
