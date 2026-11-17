package io.github.hyunchan.boggart

import io.github.hyunchan.boggart.ktor.withBoggart
import io.github.hyunchan.boggart.okhttp.BoggartInterceptor
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NoopTest {
    @Test
    fun `interceptor passes request through`() {
        var seen: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(BoggartInterceptor())
            .addInterceptor { chain ->
                seen = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("real".toByteArray().toResponseBody())
                    .build()
            }
            .build()
        val request = Request.Builder().url("https://example.com/a").header("X-A", "1").build()
        client.newCall(request).execute().use { assertEquals("real", it.body.string()) }
        assertEquals("1", seen?.header("X-A"))
    }

    @Test
    fun `with boggart returns the same engine and factory`() {
        val engine = MockEngine { respondOk() }
        assertSame(engine, engine.withBoggart())
        assertSame(MockEngine, MockEngine.withBoggart(maxBodyBytes = 10))
        engine.close()
    }
}
