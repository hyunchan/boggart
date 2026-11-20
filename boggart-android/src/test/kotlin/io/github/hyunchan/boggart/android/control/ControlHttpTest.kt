package io.github.hyunchan.boggart.android.control

import io.github.hyunchan.boggart.BoggartEngine
import io.github.hyunchan.boggart.RuleRepository
import io.github.hyunchan.boggart.TemporaryRuleSource
import io.github.hyunchan.boggart.control.BoggartControl
import io.github.hyunchan.boggart.control.BoggartMcp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlHttpTest {
    private val temporary = TemporaryRuleSource()
    private val control = BoggartControl(BoggartEngine(RuleRepository(listOf(temporary))), temporary)
    private val mcp = BoggartMcp(control)

    private fun exchange(raw: String): String {
        val output = ByteArrayOutputStream()
        ControlHttp.serve(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), output, control, mcp)
        return output.toString(Charsets.UTF_8.name())
    }

    private fun status(response: String) = response.substringAfter("HTTP/1.1 ").substringBefore(' ').toInt()

    @Test
    fun `get returns json with content length`() {
        val response = exchange("GET /mode HTTP/1.1\r\nHost: localhost\r\n\r\n")
        assertEquals(200, status(response))
        val body = response.substringAfter("\r\n\r\n")
        assertEquals("{\"mode\":\"active\"}\n", body)
        assertTrue("Content-Length: ${body.toByteArray().size}\r\n" in response)
        assertTrue("Connection: close\r\n" in response)
    }

    @Test
    fun `put reads the body by content length`() {
        val body = """{"override":{"type":"replace","body":"한글"}}"""
        val bytes = body.toByteArray(Charsets.UTF_8).size
        val response = exchange("PUT /rules/a HTTP/1.1\r\nContent-Length: $bytes\r\nContent-Type: application/json\r\n\r\n$body")
        assertEquals(200, status(response), response)
        assertEquals("a", temporary.rules.value.single().id)
    }

    @Test
    fun `expect continue gets an interim response`() {
        val body = """{"mode":"paused"}"""
        val response = exchange("PUT /mode HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertTrue(response.startsWith("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\n"), response)
    }

    @Test
    fun `chunked bodies are refused`() {
        val response = exchange("PUT /mode HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
        assertEquals(411, status(response))
    }

    @Test
    fun `requests from web pages are refused before running`() {
        val body = """{"mode":"paused"}"""
        val fromPage = exchange("PUT /mode HTTP/1.1\r\nHost: localhost:8765\r\nOrigin: https://example.com\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertEquals(403, status(fromPage))
        val rebound = exchange("PUT /mode HTTP/1.1\r\nHost: attacker.example:8765\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertEquals(403, status(rebound))
        assertEquals("{\"mode\":\"active\"}\n", exchange("GET /mode HTTP/1.1\r\n\r\n").substringAfter("\r\n\r\n"))
    }

    @Test
    fun `loopback hosts are accepted`() {
        for (host in listOf("localhost", "localhost:8765", "127.0.0.1:8765", "[::1]:8765", "LOCALHOST")) {
            assertEquals(200, status(exchange("GET /mode HTTP/1.1\r\nHost: $host\r\n\r\n")), host)
        }
    }

    private fun post(path: String, body: String, headers: String = "") =
        exchange("POST $path HTTP/1.1\r\nHost: localhost:8765\r\n${headers}Content-Length: ${body.toByteArray().size}\r\n\r\n$body")

    @Test
    fun `mcp requests get json-rpc replies`() {
        val response = post("/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"boggart_put_rule","arguments":{"id":"a","rule":{"override":{"type":"replace"}}}}}""")
        assertEquals(200, status(response), response)
        assertTrue("\"isError\":false" in response, response)
        assertEquals("a", temporary.rules.value.single().id)
    }

    @Test
    fun `mcp notifications are accepted without a body`() {
        val response = post("/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, status(response))
        assertTrue(response.endsWith("Content-Length: 0\r\nConnection: close\r\n\r\n"), response)
    }

    @Test
    fun `mcp only takes post and refuses web pages`() {
        val get = exchange("GET /mcp HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n")
        assertEquals(405, status(get))
        assertTrue("Allow: POST\r\n" in get)
        assertEquals(403, status(post("/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", "Origin: http://localhost:3000\r\n")))
    }

    @Test
    fun `truncated requests are bad requests`() {
        assertEquals(400, status(exchange("GET /mode HTTP/1.1\r\n")))
        assertEquals(400, status(exchange("PUT /mode HTTP/1.1\r\nContent-Length: 10\r\n\r\n{}")))
        assertEquals(400, status(exchange("nonsense\r\n\r\n")))
    }
}
