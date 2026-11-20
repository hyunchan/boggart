package io.github.hyunchan.boggart.android.control

import io.github.hyunchan.boggart.control.BoggartControl
import io.github.hyunchan.boggart.control.BoggartMcp
import io.github.hyunchan.boggart.control.ControlRequest
import io.github.hyunchan.boggart.control.ControlResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 연결 하나에 요청 하나를 처리하는 최소 HTTP/1.1. curl이 보내는 형태(Content-Length 바디, `Expect: 100-continue`)만 다룬다.
 * 응답은 항상 JSON이고 `Connection: close`로 끝낸다. 안드로이드 API를 쓰지 않아 JVM 단위 테스트로 검증한다.
 */
internal object ControlHttp {
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_BODY_BYTES = 4 * 1024 * 1024

    class Request(val method: String, val target: String, val headers: Map<String, String>, val body: String?)

    class HttpError(val status: Int, val code: String, message: String) : IOException(message)

    const val MCP_PATH = "/mcp"

    /** [input]에서 요청 하나를 읽어 [control]로 처리하고 [output]에 응답을 쓴다. `/mcp`는 [mcp]가 JSON-RPC로 처리한다. */
    fun serve(input: InputStream, output: OutputStream, control: BoggartControl, mcp: BoggartMcp? = null) {
        val response = try {
            val request = read(input, output)
            if (mcp != null && request.target.substringBefore('?') == MCP_PATH) {
                serveMcp(request, output, mcp)
                return
            }
            runBlocking { control.handle(ControlRequest(request.method, request.target, request.body)) }
        } catch (e: HttpError) {
            error(e.status, e.code, e.message.orEmpty())
        }
        write(output, response)
    }

    /**
     * Streamable HTTP의 POST만 지원한다. 서버가 먼저 보낼 메시지가 없어 GET(SSE 스트림)은 405,
     * 응답이 필요 없는 알림·응답은 202로 받는다.
     */
    private fun serveMcp(request: Request, output: OutputStream, mcp: BoggartMcp) {
        if (request.method != "POST") {
            write(output, error(405, "METHOD_NOT_ALLOWED", "Use POST for $MCP_PATH."), extraHeaders = "Allow: POST\r\n")
            return
        }
        val body = request.body ?: throw HttpError(400, "BAD_REQUEST", "$MCP_PATH needs a JSON-RPC message body")
        val reply = runBlocking { mcp.handle(body) }
        if (reply == null) writeRaw(output, 202, ByteArray(0)) else writeRaw(output, 200, (reply + "\n").toByteArray(Charsets.UTF_8))
    }

    fun read(input: InputStream, output: OutputStream): Request {
        val head = readHead(input)
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "BAD_REQUEST", "Malformed request line")
        val headers = lines.drop(1).filter { it.isNotEmpty() }.associate { line ->
            val colon = line.indexOf(':').takeIf { it > 0 } ?: throw HttpError(400, "BAD_REQUEST", "Malformed header line")
            line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }
        checkNotFromBrowser(headers)
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            throw HttpError(411, "BAD_REQUEST", "Chunked bodies aren't supported; send Content-Length (curl --data-binary @file does).")
        }
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: throw HttpError(400, "BAD_REQUEST", "Bad Content-Length") } ?: 0
        if (length > MAX_BODY_BYTES) throw HttpError(413, "BAD_REQUEST", "Body is larger than $MAX_BODY_BYTES bytes")
        if (length > 0 && headers["expect"].equals("100-continue", ignoreCase = true)) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }
        val body = if (length > 0) readExactly(input, length).toString(Charsets.UTF_8) else null
        return Request(parts[0], parts[1], headers, body)
    }

    fun write(output: OutputStream, response: ControlResponse, extraHeaders: String = "") {
        writeRaw(output, response.status, (response.bodyText() + "\n").toByteArray(Charsets.UTF_8), extraHeaders)
    }

    private fun writeRaw(output: OutputStream, status: Int, body: ByteArray, extraHeaders: String = "") {
        val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
            (if (body.isNotEmpty()) "Content-Type: application/json; charset=utf-8\r\n" else "") +
            "Content-Length: ${body.size}\r\n" +
            extraHeaders +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    fun error(status: Int, code: String, message: String) = ControlResponse(
        status,
        buildJsonObject {
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        },
    )

    /**
     * `adb forward`로 열린 `localhost` 포트에는 개발자 PC 브라우저의 웹 페이지도 요청을 보낼 수 있다.
     * 브라우저는 교차 출처 요청에 늘 `Origin`을 붙이므로 그런 요청을 막고, DNS 리바인딩을 막으려고 `Host`는 루프백 이름만 받는다.
     * curl과 MCP 클라이언트는 `Origin`을 보내지 않는다.
     */
    private fun checkNotFromBrowser(headers: Map<String, String>) {
        if ("origin" in headers) throw HttpError(403, "FORBIDDEN", "Requests from web pages (with an Origin header) are refused")
        val host = headers["host"] ?: return
        val name = if (host.startsWith('[')) host.substringBefore(']') + "]" else host.substringBefore(':')
        if (name.lowercase() !in LOOPBACK_HOSTS) {
            throw HttpError(403, "FORBIDDEN", "Host '$host' is refused; connect to localhost through adb forward")
        }
    }

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

    private fun readHead(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw HttpError(400, "BAD_REQUEST", "Connection closed before the request ended")
            buffer.write(b)
            if (buffer.size() > MAX_HEADER_BYTES) throw HttpError(431, "BAD_REQUEST", "Request headers are too large")
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return buffer.toString(Charsets.US_ASCII.name()).removeSuffix("\r\n\r\n")
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) throw HttpError(400, "BAD_REQUEST", "Body ended after $read of $length bytes")
            read += n
        }
        return bytes
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        else -> if (status >= 500) "Internal Server Error" else "Status"
    }
}
