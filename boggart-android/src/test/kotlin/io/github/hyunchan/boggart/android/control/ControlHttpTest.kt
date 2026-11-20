package io.github.hyunchan.boggart.android.control

import io.github.hyunchan.boggart.BoggartEngine
import io.github.hyunchan.boggart.RuleRepository
import io.github.hyunchan.boggart.TemporaryRuleSource
import io.github.hyunchan.boggart.control.BoggartControl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlHttpTest {
    private val temporary = TemporaryRuleSource()
    private val control = BoggartControl(BoggartEngine(RuleRepository(listOf(temporary))), temporary)

    private fun exchange(raw: String): String {
        val output = ByteArrayOutputStream()
        ControlHttp.serve(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), output, control)
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
    fun `truncated requests are bad requests`() {
        assertEquals(400, status(exchange("GET /mode HTTP/1.1\r\n")))
        assertEquals(400, status(exchange("PUT /mode HTTP/1.1\r\nContent-Length: 10\r\n\r\n{}")))
        assertEquals(400, status(exchange("nonsense\r\n\r\n")))
    }
}
