package io.github.hyunchan.boggart.ktor

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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
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

class BoggartHttpClientEngineTest {
    private val temporary = TemporaryRuleSource()
    private val log = ExchangeLog()
    private val engine = BoggartEngine(RuleRepository(listOf(temporary)), log)

    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json("{}") }
    private val mockEngine = MockEngine { handler(it) }

    private fun client(maxBodyBytes: Long = BoggartHttpClientEngine.DEFAULT_MAX_BODY_BYTES) =
        HttpClient(mockEngine.withBoggart(engine, maxBodyBytes))

    private fun MockRequestHandleScope.json(body: String, vararg extra: Pair<String, List<String>>) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), *extra))

    private fun addRule(override: Override) =
        temporary.add(Rule("r1", matcher = RequestMatcher(path = "/users/*"), override = override))

    private val url = "https://api.example.com/users/1"

    @Test
    fun `no rule passes through and records original`() = runBlocking {
        handler = { json("""{"id":1}""") }
        assertEquals("""{"id":1}""", client().get(url).bodyAsText())
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Original, exchange.provenance)
        assertEquals("""{"id":1}""", exchange.deliveredResponse?.bodyAsString())
        assertEquals("application/json", exchange.originalResponse?.mediaType)
        assertEquals(1, mockEngine.requestHistory.size)
    }

    @Test
    fun `replace responds without calling engine`() = runBlocking {
        addRule(Override.Replace(status = 404, headers = mapOf("X-Boggart" to "1"), body = """{"e":1}"""))
        val response = client().get(url)
        assertEquals(404, response.status.value)
        assertEquals("Boggart", response.status.description)
        assertEquals("1", response.headers["X-Boggart"])
        assertEquals("application/json", response.headers[HttpHeaders.ContentType])
        assertEquals("""{"e":1}""", response.bodyAsText())
        assertTrue(mockEngine.requestHistory.isEmpty())
        assertEquals(Provenance.Replaced, log.exchanges.value.single().provenance)
    }

    @Test
    fun `patch rewrites body and content length`() = runBlocking {
        addRule(Override.Patch(Json.parseToJsonElement("""{"name":"Boggart","id":null}""")))
        val body = """{"id":1,"name":"Harry"}"""
        handler = { json(body, HttpHeaders.ContentLength to listOf(body.length.toString())) }
        val response = client().get(url)
        val text = response.bodyAsText()
        assertEquals("""{"name":"Boggart"}""", text)
        assertEquals(text.length.toString(), response.headers[HttpHeaders.ContentLength])
        assertEquals(Provenance.Patched, log.exchanges.value.single().provenance)
    }

    @Test
    fun `invalid patch passes through when configured`() = runBlocking {
        addRule(Override.Patch(Json.parseToJsonElement("""{"id":"x"}""")))
        engine.validator = BodyValidator { _, _ -> false }
        engine.onInvalidPatch = OnInvalidPatch.PassThrough
        handler = { json("""{"id":1}""") }
        assertEquals("""{"id":"x"}""", client().get(url).bodyAsText())
        assertEquals(Provenance.PatchedInvalid, log.exchanges.value.single().provenance)
    }

    @Test
    fun `fail kinds map to engine exceptions`() = runBlocking {
        val expected = listOf(
            Triple(FailKind.Timeout, SocketTimeoutException::class, null),
            Triple(FailKind.ConnectionReset, SocketException::class, "Connection reset"),
            Triple(FailKind.UnknownHost, UnknownHostException::class, "api.example.com"),
            Triple(FailKind.Custom("boom"), IOException::class, "boom"),
        )
        val client = client()
        for ((kind, type, message) in expected) {
            addRule(Override.Fail(kind))
            val error = assertFailsWith<IOException> { client.get(url) }
            assertTrue(type.isInstance(error), "$kind -> $error")
            if (message != null) assertEquals(message, error.message)
        }
        assertTrue(mockEngine.requestHistory.isEmpty())
        assertTrue(log.exchanges.value.all { it.provenance == Provenance.Failed })
    }

    @Test
    fun `delay is applied before responding`() = runBlocking {
        addRule(Override.Replace(body = "{}", delayMs = 300))
        val start = System.nanoTime()
        client().get(url).bodyAsText()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs >= 300, "elapsed $elapsedMs ms")
    }

    @Test
    fun `mode off passes through without recording`() = runBlocking {
        addRule(Override.Fail(FailKind.Timeout))
        engine.mode = Mode.Off
        assertEquals("{}", client().get(url).bodyAsText())
        assertTrue(log.exchanges.value.isEmpty())
        assertEquals(1, mockEngine.requestHistory.size)
    }

    @Test
    fun `offline mode without rule fails as unknown host`() = runBlocking {
        engine.mode = Mode.Offline
        assertFailsWith<java.net.UnknownHostException> { client().get(url) }
        assertTrue(mockEngine.requestHistory.isEmpty())
        assertEquals(Provenance.Failed, log.exchanges.value.single().provenance)
    }

    @Test
    fun `body over limit passes original and records without body`() = runBlocking {
        addRule(Override.Patch(Json.parseToJsonElement("""{"a":1}""")))
        val big = """{"data":"${"x".repeat(100)}"}"""
        handler = { json(big) }
        assertEquals(big, client(maxBodyBytes = 10).get(url).bodyAsText())
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Original, exchange.provenance)
        assertNotNull(exchange.deliveredResponse)
        assertNull(exchange.deliveredResponse?.body)
        assertNull(exchange.appliedRuleId)
    }

    @Test
    fun `content length over limit passes through without reading`() = runBlocking {
        addRule(Override.Patch(Json.parseToJsonElement("""{"a":1}""")))
        val big = """{"data":"${"x".repeat(100)}"}"""
        handler = { json(big, HttpHeaders.ContentLength to listOf(big.length.toString())) }
        assertEquals(big, client(maxBodyBytes = 50).get(url).bodyAsText())
        assertNull(log.exchanges.value.single().deliveredResponse?.body)
    }

    @Test
    fun `event stream passes through`() = runBlocking {
        addRule(Override.Patch(Json.parseToJsonElement("""{"a":1}""")))
        handler = {
            respond("data: {}\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream; charset=utf-8"))
        }
        assertEquals("data: {}\n\n", client().get(url).bodyAsText())
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Original, exchange.provenance)
        assertNull(exchange.deliveredResponse?.body)
    }

    @Test
    fun `engine exception is recorded via onFailure`() = runBlocking {
        handler = { throw IOException("refused") }
        val error = assertFailsWith<IOException> { client().get(url) }
        assertEquals("refused", error.message)
        val exchange = log.exchanges.value.single()
        assertEquals(Provenance.Original, exchange.provenance)
        assertEquals("java.io.IOException: refused", exchange.error)
        assertNull(exchange.deliveredResponse)
    }

    @Test
    fun `request body capture depends on content type`() = runBlocking {
        val client = client()
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody("""{"q":1}""")
        }
        client.post(url) { setBody(byteArrayOf(1, 2, 3)) }
        client.post(url) { setBody(ByteReadChannel("stream")) }
        val (text, bytes, channel) = log.exchanges.value
        assertEquals("""{"q":1}""", text.request.body?.toString(Charsets.UTF_8))
        assertEquals("application/json", text.request.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertContentEquals(byteArrayOf(1, 2, 3), bytes.request.body)
        assertNull(channel.request.body)
        assertEquals(3, mockEngine.requestHistory.size)
    }

    @Test
    fun `multi value headers are preserved`() = runBlocking {
        handler = { json("{}", HttpHeaders.SetCookie to listOf("a=1", "b=2")) }
        val response = client().get(url) {
            header("X-Multi", "1")
            header("X-Multi", "2")
        }
        assertEquals(listOf("a=1", "b=2"), response.headers.getAll(HttpHeaders.SetCookie))
        val exchange = log.exchanges.value.single()
        assertEquals(listOf("1", "2"), exchange.request.headers.values("x-multi"))
        assertEquals(listOf("a=1", "b=2"), exchange.originalResponse?.headers?.values("set-cookie"))
        assertEquals(listOf("1", "2"), mockEngine.requestHistory.single().headers.getAll("X-Multi"))
    }

    @Test
    fun `factory wrapper creates boggart engine`() {
        val factory = MockEngine.withBoggart(engine)
        val created = factory.create { addHandler { respond("{}") } }
        assertTrue(created is BoggartHttpClientEngine)
        created.close()
    }
}
